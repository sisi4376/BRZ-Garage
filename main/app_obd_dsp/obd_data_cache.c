#include "obd_data_cache.h"
#include "vehicle_profiles.h"
#include "freertos/FreeRTOS.h"
#include "freertos/portmacro.h"
#include "freertos/task.h"
#include "bsp_obd_dsp/nvs_storage.h"
#include "esp_log.h"
#include "esp_timer.h"


// Simple globals protected by a critical section
static int16_t  s_coolant_temp = -40;
static int16_t  s_oil_temp = -100;
static int16_t  s_intake_temp = -40;
static int16_t  s_load_pct = -1;
static int16_t  s_tps = -1;
static int32_t  s_bat_mv = -1;
static int16_t  s_oil_pressure_x10 = -1;
static int16_t  s_brake_temp_x10 = -1000;
static int16_t  s_boost_x10 = -32768;
static int8_t   s_gear = 127;
static obd_gear_source_t s_gear_source = OBD_GEAR_SOURCE_NONE;
static TickType_t s_gear_last_tick = 0;
static int16_t  s_afr_x100 = -1;
static uint16_t s_maf_x100 = 0;
static uint32_t s_fuel_rate_ml_h = 0;
static TickType_t s_fuel_rate_last_tick = 0;
enum { FI_RPM, FI_SPEED, FI_MAF, FI_AFR, FI_LOAD, FI_TPS, FI_RATE, FI_STATUS, FI_COUNT };
static TickType_t s_fuel_ticks[FI_COUNT];
static bool s_fuel_seen[FI_COUNT];
static uint8_t s_fuel_status;
static int16_t s_fuel_level_x100 = -1;
static TickType_t s_fuel_level_tick;
static uint32_t s_fuel_level_sequence;
static uint32_t s_factory_odometer_x10_km;
static bool s_factory_odometer_valid;

static void fuel_input_received_locked(unsigned index)
{
    s_fuel_ticks[index] = xTaskGetTickCount();
    s_fuel_seen[index] = true;
}

static bool fuel_input_fresh_locked(unsigned index, TickType_t now, uint32_t age_ms)
{
    return s_fuel_seen[index] &&
        (TickType_t)(now - s_fuel_ticks[index]) <= pdMS_TO_TICKS(age_ms);
}
static brake_rs485_status_t s_brake_rs485_status = BRAKE_RS485_IDLE;
static portMUX_TYPE s_mux = portMUX_INITIALIZER_UNLOCKED;

#define DIRECT_GEAR_STALE_MS 2500  // direct signal timeout before RPM/speed inference resumes
#define FUEL_RATE_STALE_MS   5000  // 01 5E must be refreshed each OBD polling round
#define GEAR_PAIR_MAX_AGE_MS   750  // bounded allowance for serial ELM/BLE response latency
#define GEAR_INFER_STALE_MS   3000  // tolerate slow polling, never hold a disconnected gear forever
#define GEAR_CONFIRM_SAMPLES    3  // three independent RPM/speed pairs confirm a new gear

// Smoothing state (advanced on the setter side, getters only read; unaffected by the number of callers)
static uint16_t s_rpm_smooth = 0;
static uint8_t  s_speed_smooth = 0;
static TickType_t s_rpm_last_tick = 0;
static TickType_t s_speed_last_tick = 0;
static bool s_rpm_pair_pending = false;
static bool s_speed_pair_pending = false;
static bool s_inferred_gear_seen = false;
static int8_t s_inferred_gear = 127;
static int8_t s_infer_candidate = 127;
static uint8_t s_infer_candidate_count = 0;
static TickType_t s_inferred_gear_last_tick = 0;
static TickType_t s_infer_candidate_last_tick = 0;

static void update_inferred_gear_locked(TickType_t now_tick);

// RPM override layer: during multi-gauge linkage tests, the master gauge injects simulated RPM here.
// When enabled, obd_data_get_rpm() returns the override value (used for both local display and ESP-NOW broadcast).
static bool     s_rpm_override_en = false;
static uint16_t s_rpm_override_val = 0;

void obd_data_rpm_override_set(bool en, uint16_t val)
{
    portENTER_CRITICAL(&s_mux);
    s_rpm_override_en = en;
    s_rpm_override_val = val;
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_set_rpm(uint16_t rpm)
{
    TickType_t now = xTaskGetTickCount();
    portENTER_CRITICAL(&s_mux);
    s_rpm_smooth = rpm;  // CAN 100Hz data is already clean, no smoothing needed
    s_rpm_last_tick = now;
    fuel_input_received_locked(FI_RPM);
    s_rpm_pair_pending = true;
    update_inferred_gear_locked(now);
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_reset_temp_cache(void)
{
    portENTER_CRITICAL(&s_mux);
    s_coolant_temp = -40;
    s_oil_temp = -100;
    s_intake_temp = -40;
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_set_oil_temp_invalid(void)
{
    portENTER_CRITICAL(&s_mux);
    s_oil_temp = -100;
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_set_speed(uint8_t kmh)
{
    TickType_t now_tick = xTaskGetTickCount();
    portENTER_CRITICAL(&s_mux);
    s_speed_smooth = kmh;  // PID 0D is already integer-valued; smoothing caused visible lag
    s_speed_last_tick = now_tick;
    fuel_input_received_locked(FI_SPEED);
    s_speed_pair_pending = true;
    update_inferred_gear_locked(now_tick);
    portEXIT_CRITICAL(&s_mux);
}

// Called on BOTH response paths. Each response participates in at most one
// pair: repeated RPM packets must not turn one old speed into two confirmations.
static void update_inferred_gear_locked(TickType_t now_tick)
{
    if (s_inferred_gear_seen &&
        (TickType_t)(now_tick - s_inferred_gear_last_tick) > pdMS_TO_TICKS(GEAR_INFER_STALE_MS)) {
        s_inferred_gear = 127;
        s_inferred_gear_seen = false;
    }
    if (s_infer_candidate_count &&
        (TickType_t)(now_tick - s_infer_candidate_last_tick) > pdMS_TO_TICKS(GEAR_INFER_STALE_MS)) {
        s_infer_candidate = 127;
        s_infer_candidate_count = 0;
    }

    uint8_t kmh = s_speed_smooth;
    uint16_t rpm = s_rpm_smooth;
    // Retain the existing standstill display convention; it is not a measured
    // neutral switch. RPM alone must not keep a stale zero-speed sample alive.
    bool stopped = s_speed_pair_pending && kmh < 3;
    if (!stopped) {
        if (!s_rpm_pair_pending || !s_speed_pair_pending) return;
        if ((TickType_t)(now_tick - s_rpm_last_tick) > pdMS_TO_TICKS(GEAR_PAIR_MAX_AGE_MS))
            s_rpm_pair_pending = false;
        if ((TickType_t)(now_tick - s_speed_last_tick) > pdMS_TO_TICKS(GEAR_PAIR_MAX_AGE_MS))
            s_speed_pair_pending = false;
        // Do not erase a valid estimate/candidate just because the next packet
        // has not arrived yet. Freshness limits above and in getters still apply.
        if (!s_rpm_pair_pending || !s_speed_pair_pending) return;
    }
    s_rpm_pair_pending = false;
    s_speed_pair_pending = false;

    int8_t candidate = 127;
    if (stopped) {
        candidate = GEAR_NEUTRAL;
    } else if (rpm > 0) {
        const vehicle_profile_t *profile = vehicle_profile_get_active();
        float calc_const = vehicle_profile_calc_constant(profile);
        if (profile && calc_const > 0.0f && profile->gear_count > 0) {
            float total_ratio = (float)rpm / ((float)kmh * calc_const);
            float best_error = 1000.0f;
            uint8_t best_gear = 0;
            for (uint8_t gear = 1; gear <= profile->gear_count && gear < VEHICLE_MAX_GEARS; ++gear) {
                float expected = profile->gear_ratios[gear] * profile->final_drive_ratio;
                if (expected <= 0.0f) continue;
                float diff = total_ratio >= expected ? total_ratio - expected : expected - total_ratio;
                float relative_error = diff / expected;
                if (relative_error < best_error) {
                    best_error = relative_error;
                    best_gear = gear;
                }
            }
            // The old +/-15% windows overlap in the upper gears. Reject clutch
            // and shift transients instead: allow 10% below 20 km/h for PID 0D
            // quantization, and 7% once vehicle speed is more precise.
            float tolerance = kmh < 20 ? 0.10f : 0.07f;
            if (best_gear > 0 && best_error <= tolerance) candidate = (int8_t)best_gear;
        }
    }

    if (candidate == GEAR_NEUTRAL) {
        s_inferred_gear = GEAR_NEUTRAL;
        s_inferred_gear_seen = true;
        s_inferred_gear_last_tick = now_tick;
        s_infer_candidate = 127;
        s_infer_candidate_count = 0;
    } else if (candidate >= GEAR_1 && candidate <= GEAR_8) {
        if (candidate == s_inferred_gear) {
            s_inferred_gear_last_tick = now_tick;
            s_infer_candidate = 127;
            s_infer_candidate_count = 0;
        } else {
            if (candidate == s_infer_candidate) {
                if (s_infer_candidate_count < GEAR_CONFIRM_SAMPLES) s_infer_candidate_count++;
            } else {
                s_infer_candidate = candidate;
                s_infer_candidate_count = 1;
            }
            s_infer_candidate_last_tick = now_tick;
            // While the clutch is in or a shift is in progress, display unknown
            // instead of holding a gear that is known to be stale.
            s_inferred_gear = 127;
            if (s_infer_candidate_count >= GEAR_CONFIRM_SAMPLES) {
                s_inferred_gear = candidate;
                s_inferred_gear_seen = true;
                s_inferred_gear_last_tick = now_tick;
                s_infer_candidate = 127;
                s_infer_candidate_count = 0;
            }
        }
    } else {
        s_infer_candidate = 127;
        s_infer_candidate_count = 0;
        // A fresh pair outside every ratio window is genuinely untrustworthy.
        s_inferred_gear = 127;
        s_inferred_gear_seen = false;
    }
}

void obd_data_set_coolant_temp(int16_t temp)
{
    portENTER_CRITICAL(&s_mux);
    s_coolant_temp = temp;
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_set_oil_temp(int16_t temp)
{
    if (temp < -20 || temp > 150) return;
    portENTER_CRITICAL(&s_mux);
    s_oil_temp = temp;
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_set_intake_temp(int16_t temp)
{
    portENTER_CRITICAL(&s_mux);
    s_intake_temp = temp;
    portEXIT_CRITICAL(&s_mux);
}

uint16_t obd_data_get_rpm(void)
{
    uint16_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_rpm_override_en ? s_rpm_override_val : s_rpm_smooth;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

uint8_t obd_data_get_speed(void)
{
    uint8_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_speed_smooth;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

int16_t obd_data_get_coolant_temp(void)
{
    int16_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_coolant_temp;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

int16_t obd_data_get_oil_temp(void)
{
    int16_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_oil_temp;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

int16_t obd_data_get_intake_temp(void)
{
    int16_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_intake_temp;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

void obd_data_set_load_pct(int16_t pct)
{
    portENTER_CRITICAL(&s_mux);
    s_load_pct = pct;
    fuel_input_received_locked(FI_LOAD);
    portEXIT_CRITICAL(&s_mux);
}

int16_t obd_data_get_load_pct(void)
{
    int16_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_load_pct;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

void obd_data_set_tps(int16_t pct)
{
    portENTER_CRITICAL(&s_mux);
    s_tps = pct;
    fuel_input_received_locked(FI_TPS);
    portEXIT_CRITICAL(&s_mux);
}

int16_t obd_data_get_tps(void)
{
    int16_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_tps;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

void obd_data_set_bat_mv(int32_t mv)
{
    portENTER_CRITICAL(&s_mux);
    s_bat_mv = mv;
    portEXIT_CRITICAL(&s_mux);
}

int32_t obd_data_get_bat_mv(void)
{
    int32_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_bat_mv;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

void obd_data_set_oil_pressure_x10(int16_t pressure_x10)
{
    if (pressure_x10 < 0 || pressure_x10 > 200) return;
    portENTER_CRITICAL(&s_mux);
    s_oil_pressure_x10 = pressure_x10;
    portEXIT_CRITICAL(&s_mux);
}

int16_t obd_data_get_oil_pressure_x10(void)
{
    int16_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_oil_pressure_x10;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

void obd_data_set_boost_x10(int16_t boost_x10)
{
    if (boost_x10 < -15 || boost_x10 > 300) return;
    portENTER_CRITICAL(&s_mux);
    s_boost_x10 = boost_x10;
    portEXIT_CRITICAL(&s_mux);
}

int16_t obd_data_get_boost_x10(void)
{
    int16_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_boost_x10;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

void obd_data_set_brake_temp_x10(int16_t temp_x10)
{
    if (temp_x10 < -500 || temp_x10 > 12000) return;
    portENTER_CRITICAL(&s_mux);
    s_brake_temp_x10 = temp_x10;
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_set_brake_rs485_status(brake_rs485_status_t status)
{
    portENTER_CRITICAL(&s_mux);
    s_brake_rs485_status = status;
    portEXIT_CRITICAL(&s_mux);
}

int16_t obd_data_get_brake_temp_x10(void)
{
    int16_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_brake_temp_x10;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

static bool direct_gear_is_fresh_locked(TickType_t now)
{
    if (s_gear_source == OBD_GEAR_SOURCE_NONE || s_gear == 127) {
        return false;
    }
    return (TickType_t)(now - s_gear_last_tick) <= pdMS_TO_TICKS(DIRECT_GEAR_STALE_MS);
}

void obd_data_set_gear_source(int8_t gear, obd_gear_source_t source)
{
    TickType_t now = xTaskGetTickCount();
    if (source <= OBD_GEAR_SOURCE_NONE || source > OBD_GEAR_SOURCE_CAN) return;

    portENTER_CRITICAL(&s_mux);
    bool current_fresh = direct_gear_is_fresh_locked(now);
    if (gear >= -1 && gear <= GEAR_8) {
        // CAN > OEM DID > standard PID A4 while each source remains fresh.
        if (!current_fresh || source >= s_gear_source) {
            s_gear = gear;
            s_gear_source = source;
            s_gear_last_tick = now;
        }
    } else if (!current_fresh || source >= s_gear_source) {
        s_gear = 127;
        s_gear_source = OBD_GEAR_SOURCE_NONE;
        s_gear_last_tick = 0;
    }
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_set_gear(int8_t gear)
{
    obd_data_set_gear_source(gear, OBD_GEAR_SOURCE_OEM_DID);
}

void obd_data_reset_gear(void)
{
    portENTER_CRITICAL(&s_mux);
    s_gear = 127;
    s_gear_source = OBD_GEAR_SOURCE_NONE;
    s_gear_last_tick = 0;
    s_inferred_gear = 127;
    s_infer_candidate = 127;
    s_infer_candidate_count = 0;
    s_inferred_gear_last_tick = 0;
    s_infer_candidate_last_tick = 0;
    s_inferred_gear_seen = false;
    s_rpm_pair_pending = false;
    s_speed_pair_pending = false;
    portEXIT_CRITICAL(&s_mux);
}

int8_t obd_data_get_gear(void)
{
    TickType_t now = xTaskGetTickCount();
    int8_t val;
    portENTER_CRITICAL(&s_mux);
    bool inferred_fresh = s_inferred_gear != 127 && s_inferred_gear_seen &&
        (TickType_t)(now - s_inferred_gear_last_tick) <= pdMS_TO_TICKS(GEAR_INFER_STALE_MS);
    val = direct_gear_is_fresh_locked(now) ? s_gear : (inferred_fresh ? s_inferred_gear : 127);
    portEXIT_CRITICAL(&s_mux);
    return val;
}

void obd_data_set_afr_x100(int16_t afr_x100)
{
    portENTER_CRITICAL(&s_mux);
    s_afr_x100 = (afr_x100 >= 800 && afr_x100 <= 2200) ? afr_x100 : -1;
    fuel_input_received_locked(FI_AFR);
    portEXIT_CRITICAL(&s_mux);
}

int16_t obd_data_get_afr_x100(void)
{
    int16_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_afr_x100;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

void obd_data_set_maf_x100(uint16_t maf_x100)
{
    portENTER_CRITICAL(&s_mux);
    s_maf_x100 = maf_x100;
    fuel_input_received_locked(FI_MAF);
    portEXIT_CRITICAL(&s_mux);
}

uint16_t obd_data_get_maf_x100(void)
{
    uint16_t val;
    portENTER_CRITICAL(&s_mux);
    val = s_maf_x100;
    portEXIT_CRITICAL(&s_mux);
    return val;
}

void obd_data_set_fuel_rate_ml_h(uint32_t fuel_rate_ml_h)
{
    TickType_t now = xTaskGetTickCount();
    portENTER_CRITICAL(&s_mux);
    s_fuel_rate_ml_h = fuel_rate_ml_h;
    s_fuel_rate_last_tick = now;
    fuel_input_received_locked(FI_RATE);
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_set_fuel_rate_unavailable(void)
{
    portENTER_CRITICAL(&s_mux);
    s_fuel_rate_ml_h = 0;
    s_fuel_rate_last_tick = 0;
    s_fuel_seen[FI_RATE] = false;
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_set_fuel_status(uint8_t status)
{
    portENTER_CRITICAL(&s_mux);
    s_fuel_status = (status == 1 || status == 2 || status == 4 || status == 8 || status == 16)
                  ? status : 0;
    fuel_input_received_locked(FI_STATUS);
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_reset_fuel_inputs(void)
{
    portENTER_CRITICAL(&s_mux);
    for (unsigned i = 0; i < FI_COUNT; ++i) s_fuel_seen[i] = false;
    s_fuel_status = 0;
    s_fuel_level_x100 = -1;
    s_fuel_rate_last_tick = 0;
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_set_fuel_level_x100(uint16_t percent_x100)
{
    bool valid;
    portENTER_CRITICAL(&s_mux);
    s_fuel_level_x100 = percent_x100 <= 10000 ? (int16_t)percent_x100 : -1;
    s_fuel_level_tick = xTaskGetTickCount();
    valid = s_fuel_level_x100 >= 0;
    if (valid) {
        ++s_fuel_level_sequence;
        if (s_fuel_level_sequence == 0) ++s_fuel_level_sequence;
    }
    portEXIT_CRITICAL(&s_mux);
    if (valid) nvs_refuel_observe_fuel_level(percent_x100);
}

uint32_t obd_data_get_fuel_level_sequence(void)
{
    uint32_t sequence;
    portENTER_CRITICAL(&s_mux);
    sequence = s_fuel_level_sequence;
    portEXIT_CRITICAL(&s_mux);
    return sequence;
}

int16_t obd_data_get_fuel_level_x100(void)
{
    portENTER_CRITICAL(&s_mux);
    int16_t level = (TickType_t)(xTaskGetTickCount() - s_fuel_level_tick) <= pdMS_TO_TICKS(90000)
        ? s_fuel_level_x100 : -1;
    portEXIT_CRITICAL(&s_mux);
    return level;
}

void obd_data_set_factory_odometer_x10_km(uint32_t odometer_x10_km)
{
    portENTER_CRITICAL(&s_mux);
    s_factory_odometer_x10_km = odometer_x10_km;
    s_factory_odometer_valid = true;
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_reset_factory_odometer(void)
{
    portENTER_CRITICAL(&s_mux);
    s_factory_odometer_x10_km = 0;
    s_factory_odometer_valid = false;
    portEXIT_CRITICAL(&s_mux);
}

bool obd_data_get_factory_odometer_x10_km(uint32_t *odometer_x10_km)
{
    bool valid;
    portENTER_CRITICAL(&s_mux);
    valid = s_factory_odometer_valid;
    if (valid && odometer_x10_km) *odometer_x10_km = s_factory_odometer_x10_km;
    portEXIT_CRITICAL(&s_mux);
    return valid;
}

void obd_data_get_fuel_sample(fuel_sample_t *out)
{
    if (!out) return;
    TickType_t now = xTaskGetTickCount();
    portENTER_CRITICAL(&s_mux);
    *out = (fuel_sample_t) {
        .rpm = s_rpm_smooth, .speed_kmh = s_speed_smooth,
        .maf_x100 = s_maf_x100, .fuel_rate_ml_h = s_fuel_rate_ml_h,
        .rpm_valid = fuel_input_fresh_locked(FI_RPM, now, 3000),
        .speed_valid = fuel_input_fresh_locked(FI_SPEED, now, 3000),
        .maf_valid = fuel_input_fresh_locked(FI_MAF, now, 5000),
        .fuel_rate_valid = fuel_input_fresh_locked(FI_RATE, now, FUEL_RATE_STALE_MS),
        .afr_x100 = fuel_input_fresh_locked(FI_AFR, now, 5000) ? s_afr_x100 : -1,
        .load_pct = fuel_input_fresh_locked(FI_LOAD, now, 2500) ? s_load_pct : -1,
        .tps_pct = fuel_input_fresh_locked(FI_TPS, now, 2500) ? s_tps : -1,
        .fuel_status = fuel_input_fresh_locked(FI_STATUS, now, 2500) ? s_fuel_status : 0,
    };
    portEXIT_CRITICAL(&s_mux);
}

void obd_data_get_snapshot(obd_data_snapshot_t *out)
{
    if (!out) return;
    TickType_t now = xTaskGetTickCount();

    portENTER_CRITICAL(&s_mux);
    out->rpm = s_rpm_override_en ? s_rpm_override_val : s_rpm_smooth;
    out->speed = s_speed_smooth;
    out->coolant_temp = s_coolant_temp;
    out->oil_temp = s_oil_temp;
    out->intake_temp = s_intake_temp;
    out->load_pct = s_load_pct;
    out->tps = s_tps;
    out->bat_mv = s_bat_mv;
    out->oil_pressure_x10 = s_oil_pressure_x10;
    out->boost_x10 = s_boost_x10;
    out->brake_temp_x10 = s_brake_temp_x10;
    bool inferred_fresh = s_inferred_gear != 127 && s_inferred_gear_seen &&
        (TickType_t)(now - s_inferred_gear_last_tick) <= pdMS_TO_TICKS(GEAR_INFER_STALE_MS);
    out->gear = direct_gear_is_fresh_locked(now) ? s_gear : (inferred_fresh ? s_inferred_gear : 127);
    out->afr_x100 = s_afr_x100;
    out->maf_x100 = s_maf_x100;
    out->fuel_rate_ml_h = s_fuel_rate_ml_h;
    out->fuel_rate_valid = fuel_input_fresh_locked(FI_RATE, now, FUEL_RATE_STALE_MS);
    out->brake_rs485_status = s_brake_rs485_status;
    portEXIT_CRITICAL(&s_mux);
}

/**
 * @brief Compute and determine the gear from RPM and vehicle speed
 * @param rpm engine speed (RPM)
 * @param speed vehicle speed (km/h)
 * @return the computed gear
 */
enGear calculate_gear(float rpm, float speed) {
    static enGear s_last_gear = GEAR_NEUTRAL;
    // 1. Check input data validity
    if (rpm <= 0 || speed < 3) {
        s_last_gear = GEAR_NEUTRAL;
        return GEAR_NEUTRAL;
    }

    // 2. Compute the total gear ratio using the active vehicle profile
    const vehicle_profile_t *profile = vehicle_profile_get_active();
    float calc_const = vehicle_profile_calc_constant(profile);
    if (!profile || calc_const <= 0.0f || profile->gear_count == 0) {
        return s_last_gear;
    }
    float total_ratio = rpm / (speed * calc_const);

    // 3. Pick the closest expected total ratio. This resolves the small overlap
    // between adjacent tolerance windows instead of returning the first match.
    float best_error = 1000.0f;
    enGear best_gear = GEAR_NEUTRAL;
    for (uint8_t gear = 1; gear <= profile->gear_count && gear < VEHICLE_MAX_GEARS; ++gear) {
        float expected = profile->gear_ratios[gear] * profile->final_drive_ratio;
        if (expected <= 0.0f) continue;
        float diff = total_ratio >= expected ? total_ratio - expected : expected - total_ratio;
        float relative_error = diff / expected;
        if (relative_error < best_error) {
            best_error = relative_error;
            best_gear = (enGear)gear;
        }
    }
    if (best_gear != GEAR_NEUTRAL && best_error <= profile->gear_tolerance) {
        s_last_gear = best_gear;
        return best_gear;
    }
    
    // 4. Outside all ranges: check if it could be neutral (high RPM, near-zero speed)
    if (rpm > 800 && speed < 5) { // Above idle and nearly stationary
        s_last_gear = GEAR_NEUTRAL;
        return GEAR_NEUTRAL;
    }
    
    // 5. Unrecognized ratio: return the last gear
    return s_last_gear;
}

/**
 * @brief Mileage statistics timer callback
 * @param pvParameter argument
 * @return none
 * @note
 * @note Mileage statistics task
 */
static void mileage_statistics_task(void* arg)
{
    (void)arg;
    // Checkpoint every minute regardless of speed. A transition to zero speed or
    // a valid RPM transition from running to zero adds one immediate checkpoint
    // and, when that save succeeds, restarts the one-minute interval. Coincident
    // triggers are coalesced into one write.
    // nvs_fuel_save() also skips clean data.
    int64_t last_ms = esp_timer_get_time() / 1000;
    int64_t periodic_last_ms = last_ms;
    bool state_initialized = false;
    bool was_stopped = false;
    bool rpm_state_initialized = false;
    bool was_engine_running = false;
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(200));
        int64_t now_ms = esp_timer_get_time() / 1000;
        int64_t elapsed_ms = now_ms - last_ms;
        last_ms = now_ms;
        /* Preserve real monotonic time for the engine-off finalizer.  Fuel and
         * mileage integration independently reject intervals above 2 s, but
         * collapsing a long task/OTA delay to 2001 ms also collapsed the
         * 15-minute engine-off window and could leave a trip active long after
         * the car had stopped. */
        uint32_t dt_ms = elapsed_ms <= 0 ? 0U :
                         elapsed_ms > UINT32_MAX ? UINT32_MAX : (uint32_t)elapsed_ms;
        fuel_sample_t sample;
        obd_data_get_fuel_sample(&sample);
        if (sample.speed_valid && sample.rpm_valid && sample.rpm > 0 && dt_ms <= 2000)
            nvs_stat_update_speed(sample.speed_kmh, dt_ms);
        nvs_fuel_update(&sample, dt_ms);

        bool stopped = sample.speed_valid && sample.speed_kmh == 0;
        bool stop_edge = stopped && (!state_initialized || !was_stopped);
        bool engine_running = sample.rpm_valid && sample.rpm > 0;
        bool rpm_zero_edge = sample.rpm_valid && rpm_state_initialized &&
                             was_engine_running && sample.rpm == 0;
        bool checkpoint_edge = stop_edge || rpm_zero_edge;
        bool periodic_due = now_ms - periodic_last_ms >= 60000;

        if (periodic_due || checkpoint_edge) {
            esp_err_t save_err = nvs_fuel_save();
            /* A successful edge checkpoint becomes the new periodic anchor,
             * avoiding another NVS write shortly after the vehicle stops. Preserve
             * the old anchor on failure so the regular checkpoint is not deferred. */
            if (periodic_due || (checkpoint_edge && save_err == ESP_OK))
                periodic_last_ms = now_ms;
            UBaseType_t stack_free = uxTaskGetStackHighWaterMark(NULL);
            if (save_err != ESP_OK || stack_free < 512) {
                ESP_LOGW("trip_stat", "checkpoint err=%s stack_free=%u",
                         esp_err_to_name(save_err), (unsigned)stack_free);
            } else if (periodic_due) {
                ESP_LOGI("trip_stat", "checkpoint ok stack_free=%u",
                         (unsigned)stack_free);
            }
        }

        state_initialized = sample.speed_valid;
        was_stopped = stopped;
        rpm_state_initialized = sample.rpm_valid;
        was_engine_running = engine_running;
    }
}

/**
 * @brief Initialize the mileage statistics task
 * @return none
 * @note
 * @note Initialize the mileage statistics task
 */
void vMileageDataStatisticTask(void)
{
    static TaskHandle_t s_task = NULL;
    if (!s_task) {
        /* Real NVS checkpoints take substantially more stack than clean-idle
         * calls. Keep enough margin for a full history/sync checkpoint. */
        if (xTaskCreate(mileage_statistics_task, "trip_stat", 6144, NULL, 2, &s_task) != pdPASS) {
            s_task = NULL;
            ESP_LOGE("trip_stat", "failed to create mileage task");
        }
    }
}
