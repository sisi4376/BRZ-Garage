#include "nvs_storage.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include <string.h>
#include "export_path/ui.h"
#include "app_obd_dsp/vehicle_profiles.h"
#include "app_obd_dsp/trip_idle_detector.h"
#include "app_obd_dsp/trip_time_anchor.h"
#include "esp_timer.h"
#include "espnow_link.h"   // ESPNOW_ROLE_* (device_role default / bounds)
#include <time.h>
#include <sys/time.h>

#define TAG                   "nvs_storage"
#define NS_CFG                "cfg"
#define KEY_CFG               "settings"
#define KEY_CHART_ALARM       "chartalarm"
#define CHART_ALARM_N         12   // = DISP_ITEM_COUNT (must stay in sync with disp_item_t in ui.c)
#define CHART_ALARM_OFF       32767 // "off" sentinel for alarm thresholds (unreachable, avoids false alarms)
#define KEY_MG_EXTRA          "mgextra"   // multi-gauge boot animation settings
#define KEY_FUEL_TRIPS        "fueltrips" // lifetime fuel totals + active trip + history
#define KEY_TRIP_SYNC         "tripsync"  // completed records awaiting phone ACK
#define KEY_ODOMETER_CFG      "odometer"  // phone calibration + shared home visibility
#define KEY_REFUEL_HISTORY    "refuelhist" // independent automatic/manual refuel intervals
#define KEY_CUSTOM_TRIP       "customtrip" // phone-selected lifetime baseline for the gauge page
#define FUEL_STORE_VERSION    4
#define TRIP_SYNC_STORE_VERSION 1
#define TRIP_SYNC_PROTOCOL_VERSION 3
#define REFUEL_STORE_VERSION 1U
#define REFUEL_PROTOCOL_VERSION 2U
#define CUSTOM_TRIP_STORE_VERSION 1U
#define KEY_CFG_VERSION       "cfgver"    // config version (missing = v0)
#define CFG_VERSION_CURRENT   12          // current version; bump on field add/semantic change (migration in nvs_storage_init)

static nvs_user_cfg_t s_cfg =   {
                        .protocol = 0, // OBD protocol select: 0=auto, 1~9=fixed, default auto
                        .default_page = 6, // FUEL
                        .theme_cfg.theme = 0,// UI theme index (0=DEFAULT, registry in ui_theme.c)
                        .theme_cfg.user_theme_domiant_color = COLOR_DOMIANT_PINK,// legacy, unused (kept for struct layout)
                        .theme_cfg.user_theme_secondary_color = COLOR_SECONDARY_PINK,// legacy, unused
                        .ble_device_name = "", // empty = use default "OBDII"
                        .vehicle_profile_idx = VEHICLE_PROFILE_DEFAULT_INDEX,
                        .temp_display_map = {0, 1, 2}, // CLT, IAT, OIL
                        .info_display_map = {0, 2, 3, 4, 1}, // CLT, OIL, LOAD, TPS, IAT
                        .brake_temp_warn_c = 600,
                        .oil_pressure_warn_x10 = 80,
                        .device_role = MULTIGAUGE_LOCKED_ROLE,
                        .rpm_warn_threshold = 5000,
                        .rpm_warn_anim_en = 0,
                        .rpm_warn_linked_en = 0,
                        .trip_merge_timeout_min = TRIP_MERGE_TIMEOUT_LOCKED_MIN,
                        .refuel_detect_threshold_ml = NVS_REFUEL_THRESHOLD_DEFAULT_ML,
                    };
static nvs_stat_t     s_stat = {0};   // runtime-only stats, not persisted (reset every boot to save flash)
static uint32_t s_stat_odometer_remainder;
static uint32_t s_stat_trip_remainder;
static uint32_t s_stat_time_remainder_ms;
static SemaphoreHandle_t s_mux;

typedef struct {
    uint32_t version;
    uint32_t next_trip_id;
    uint64_t lifetime_distance_mm;
    uint64_t lifetime_fuel_ul;
    uint64_t lifetime_duration_ms;
    uint64_t active_distance_mm;
    uint64_t active_fuel_ul;
    uint64_t active_duration_ms;
    uint8_t history_count;
    uint8_t reserved[7];
    nvs_fuel_trip_record_t history[NVS_FUEL_TRIP_HISTORY_MAX];
    /* v2 fields are appended so a v1 blob can be migrated without losing
       lifetime totals or history. active_last_epoch_s is refreshed only
       after a phone has supplied valid Unix time during this boot. */
    uint64_t active_last_epoch_s;
    uint64_t pending_distance_mm;
    uint64_t pending_fuel_ul;
    uint64_t pending_duration_ms;
    uint64_t pending_last_epoch_s;
    uint8_t pending_valid;
    uint8_t reserved_v2[7];
    /* v3: explicit trip start timestamps. End timestamps continue to use the
       v2 last-epoch fields, which are updated at every checkpoint. */
    uint64_t active_start_epoch_s;
    uint64_t pending_start_epoch_s;
    /* v4 fields are appended so v1-v3 blobs retain their exact layout. */
    nvs_trip_detail_t active_detail;
    nvs_trip_detail_t pending_detail;
    nvs_trip_detail_t history_details[NVS_FUEL_TRIP_HISTORY_MAX];
} fuel_store_t;

typedef struct {
    uint32_t version;
    uint32_t last_acked_id;
    uint8_t count;
    uint8_t overflowed;
    uint8_t reserved[2];
    nvs_trip_sync_record_t records[NVS_TRIP_SYNC_QUEUE_MAX];
    /* Appended without changing the v1 record array layout. */
    nvs_trip_detail_t details[NVS_TRIP_SYNC_QUEUE_MAX];
} trip_sync_store_t;

static fuel_store_t s_fuel;
static trip_sync_store_t s_trip_sync;

typedef struct {
    uint32_t version;
    uint32_t next_id;
    uint16_t last_level_x100;
    uint16_t candidate_base_x100;
    uint8_t candidate_count;
    uint8_t count;
    uint16_t history_revision; /* increments only when a saved node is deleted */
    uint64_t baseline_duration_ms;
    uint64_t baseline_distance_mm;
    uint64_t baseline_fuel_ul;
    uint64_t current_start_epoch_s;
    nvs_refuel_record_t records[NVS_REFUEL_HISTORY_MAX]; /* oldest first */
} refuel_store_t;

static refuel_store_t s_refuel;
static refuel_store_t s_refuel_save_copy;
static bool s_refuel_dirty;

typedef struct {
    uint32_t version;
    uint64_t baseline_duration_ms;
    uint64_t baseline_distance_mm;
    uint64_t baseline_fuel_ul;
} custom_trip_store_t;

static custom_trip_store_t s_custom_trip;
static custom_trip_store_t s_custom_trip_save_copy;
static bool s_custom_trip_dirty;

typedef struct {
    uint32_t version;
    uint64_t calibration_lifetime_m;
    uint32_t calibration_x10_km;
    uint8_t display_enabled;
    uint8_t calibrated;
    uint8_t reserved[2];
} odometer_store_t;

#define ODOMETER_STORE_VERSION 1U
static odometer_store_t s_odometer = {
    .version = ODOMETER_STORE_VERSION,
    .display_enabled = 1,
};
/* Flash/NVS must not read its source from a small task stack or from PSRAM
 * while cache operations are in progress. A single internal-RAM staging
 * buffer keeps the periodic checkpoint deterministic. Only nvs_fuel_save()
 * uses it, and that function is called by the trip-statistics task. */
static trip_sync_store_t s_trip_sync_save_copy;
static bool s_fuel_dirty;
static bool s_trip_sync_dirty;
static bool s_fuel_data_valid;
static bool s_fuel_instant_is_lph;
static uint16_t s_fuel_instant_x100;
static volatile bool s_phone_time_valid;
static fuel_estimator_t s_fuel_estimator;
static fuel_source_t s_fuel_source;
static trip_time_anchor_t s_trip_time_anchor;
static bool s_active_contains_pending;
static trip_idle_detector_t s_trip_idle_detector;
static bool s_accel_speed_valid;
static uint16_t s_accel_last_speed_kmh;
static uint32_t s_accel_elapsed_ms;
static uint32_t odometer_current_x10_locked(void);

static uint32_t clamp_u32_u64(uint64_t value)
{
    return value > UINT32_MAX ? UINT32_MAX : (uint32_t)value;
}

static uint32_t add_u32_saturated(uint32_t left, uint32_t right)
{
    return clamp_u32_u64((uint64_t)left + (uint64_t)right);
}

static void refuel_reset_locked(uint16_t flags, uint16_t detected_added_ml)
{
    uint64_t now_epoch = s_phone_time_valid ? (uint64_t)time(NULL) : 0;
    uint64_t duration_ms = s_fuel.lifetime_duration_ms >= s_refuel.baseline_duration_ms
                         ? s_fuel.lifetime_duration_ms - s_refuel.baseline_duration_ms : 0;
    uint64_t distance_mm = s_fuel.lifetime_distance_mm >= s_refuel.baseline_distance_mm
                         ? s_fuel.lifetime_distance_mm - s_refuel.baseline_distance_mm : 0;
    uint64_t fuel_ul = s_fuel.lifetime_fuel_ul >= s_refuel.baseline_fuel_ul
                     ? s_fuel.lifetime_fuel_ul - s_refuel.baseline_fuel_ul : 0;
    if (s_refuel.count >= NVS_REFUEL_HISTORY_MAX) {
        memmove(&s_refuel.records[0], &s_refuel.records[1],
                (NVS_REFUEL_HISTORY_MAX - 1U) * sizeof(s_refuel.records[0]));
        s_refuel.count = NVS_REFUEL_HISTORY_MAX - 1U;
    }
    nvs_refuel_record_t *record = &s_refuel.records[s_refuel.count++];
    memset(record, 0, sizeof(*record));
    record->id = ++s_refuel.next_id;
    if (record->id == 0) record->id = ++s_refuel.next_id;
    record->start_epoch_s = s_refuel.current_start_epoch_s;
    record->end_epoch_s = now_epoch;
    record->duration_s = clamp_u32_u64(duration_ms / 1000ULL);
    record->distance_m = clamp_u32_u64(distance_mm / 1000ULL);
    record->fuel_ml = clamp_u32_u64(fuel_ul / 1000ULL);
    record->odometer_x10_km = odometer_current_x10_locked();
    record->detected_added_ml = detected_added_ml;
    record->flags = flags;
    s_refuel.baseline_duration_ms = s_fuel.lifetime_duration_ms;
    s_refuel.baseline_distance_mm = s_fuel.lifetime_distance_mm;
    s_refuel.baseline_fuel_ul = s_fuel.lifetime_fuel_ul;
    s_refuel.current_start_epoch_s = now_epoch;
    s_refuel.candidate_count = 0;
    s_refuel_dirty = true;
}

// Per-item alarm thresholds (raw units), index = disp_item_t: CLT,IAT,OIL,LOD,TPS,RPM,SPD,BAT,OIP,BKT,BST
// By default only oil pressure (8.0bar = x10 80) and brake temp (600°C = x10 6000) keep an alarm; the rest are off.
static int16_t s_chart_alarm[CHART_ALARM_N] = {
    CHART_ALARM_OFF, CHART_ALARM_OFF, CHART_ALARM_OFF, CHART_ALARM_OFF,
    CHART_ALARM_OFF, CHART_ALARM_OFF, CHART_ALARM_OFF, CHART_ALARM_OFF,
    80, 6000, CHART_ALARM_OFF
};

// Multi-gauge boot animation settings (separate blob):
// intro_enable 0=OFF 1=RACE 2=VIDEO (the single boot_block flashed via the phone app; default)
static struct __attribute__((packed)) {
    uint8_t intro_enable;   // 0=OFF 1=RACE 2=VIDEO
    uint8_t device_position; // 1/2/3
    uint8_t boot_mode;      // 0=default animation, 1=custom image, 2=video
} s_mg = { 2, 1, 0 };

/* Forward declarations */
static esp_err_t load_blob(const char *ns,const char *key,void *out,size_t len);
static esp_err_t save_blob(const char *ns,const char *key,const void *data,size_t len);

static uint16_t fuel_average_x100(uint64_t fuel_ul, uint64_t distance_mm)
{
    if (distance_mm < 1000) return 0;
    uint64_t value = (fuel_ul * 10000ULL + distance_mm / 2ULL) / distance_mm;
    return (uint16_t)(value > UINT16_MAX ? UINT16_MAX : value);
}

static uint32_t odometer_current_x10_locked(void)
{
    uint64_t lifetime_m = s_fuel.lifetime_distance_mm / 1000ULL;
    uint64_t value_x10;
    if (s_odometer.calibrated) {
        uint64_t added_m = lifetime_m >= s_odometer.calibration_lifetime_m
                         ? lifetime_m - s_odometer.calibration_lifetime_m : 0;
        value_x10 = (uint64_t)s_odometer.calibration_x10_km + added_m / 100ULL;
    } else {
        value_x10 = lifetime_m / 100ULL;
    }
    return (uint32_t)(value_x10 > UINT32_MAX ? UINT32_MAX : value_x10);
}

static void trip_detail_merge(nvs_trip_detail_t *dst, const nvs_trip_detail_t *src)
{
    if (!dst || !src) return;
    if (src->max_speed_kmh > dst->max_speed_kmh) dst->max_speed_kmh = src->max_speed_kmh;
    if (src->max_rpm > dst->max_rpm) dst->max_rpm = src->max_rpm;
    if (src->max_accel_x100 > dst->max_accel_x100) dst->max_accel_x100 = src->max_accel_x100;
    if (src->max_decel_x100 < dst->max_decel_x100) dst->max_decel_x100 = src->max_decel_x100;
}

static void trip_sync_enqueue(const nvs_trip_sync_record_t *record,
                              const nvs_trip_detail_t *detail)
{
    if (!record || record->id == 0) return;
    if (s_trip_sync.count >= NVS_TRIP_SYNC_QUEUE_MAX) {
        memmove(&s_trip_sync.records[0], &s_trip_sync.records[1],
                (NVS_TRIP_SYNC_QUEUE_MAX - 1U) * sizeof(s_trip_sync.records[0]));
        memmove(&s_trip_sync.details[0], &s_trip_sync.details[1],
                (NVS_TRIP_SYNC_QUEUE_MAX - 1U) * sizeof(s_trip_sync.details[0]));
        s_trip_sync.count = NVS_TRIP_SYNC_QUEUE_MAX - 1U;
        s_trip_sync.overflowed = 1;
    }
    s_trip_sync.records[s_trip_sync.count] = *record;
    if (detail) s_trip_sync.details[s_trip_sync.count] = *detail;
    else memset(&s_trip_sync.details[s_trip_sync.count], 0, sizeof(s_trip_sync.details[0]));
    s_trip_sync.count++;
    s_trip_sync_dirty = true;
}

static void fuel_push_trip(uint64_t duration_ms, uint64_t distance_mm,
                           uint64_t fuel_ul, uint64_t start_epoch_s,
                           uint64_t end_epoch_s,
                           const nvs_trip_detail_t *detail)
{
    if (duration_ms >= 30000 || distance_mm >= 100000) {
        unsigned count = s_fuel.history_count;
        if (count > NVS_FUEL_TRIP_HISTORY_MAX) count = NVS_FUEL_TRIP_HISTORY_MAX;
        unsigned move = count < NVS_FUEL_TRIP_HISTORY_MAX ? count : NVS_FUEL_TRIP_HISTORY_MAX - 1;
        if (move > 0) {
            memmove(&s_fuel.history[1], &s_fuel.history[0],
                    move * sizeof(s_fuel.history[0]));
            memmove(&s_fuel.history_details[1], &s_fuel.history_details[0],
                    move * sizeof(s_fuel.history_details[0]));
        }
        nvs_fuel_trip_record_t *record = &s_fuel.history[0];
        memset(record, 0, sizeof(*record));
        record->id = ++s_fuel.next_trip_id;
        record->duration_s = (uint32_t)(duration_ms / 1000ULL);
        record->distance_m = (uint32_t)(distance_mm / 1000ULL);
        record->fuel_ml = (uint32_t)(fuel_ul / 1000ULL);
        record->avg_l100_x100 = fuel_average_x100(fuel_ul, distance_mm);
        if (detail) s_fuel.history_details[0] = *detail;
        else memset(&s_fuel.history_details[0], 0, sizeof(s_fuel.history_details[0]));
        if (s_fuel.history_count < NVS_FUEL_TRIP_HISTORY_MAX) s_fuel.history_count++;

        nvs_trip_sync_record_t sync_record = {
            .id = record->id,
            .start_epoch_s = start_epoch_s,
            .end_epoch_s = end_epoch_s,
            .duration_s = record->duration_s,
            .distance_m = record->distance_m,
            .fuel_ml = record->fuel_ml,
            .avg_l100_x100 = record->avg_l100_x100,
            .flags = (start_epoch_s >= 1704067200ULL && end_epoch_s >= start_epoch_s) ? 1U : 0U,
        };
        trip_sync_enqueue(&sync_record, detail);
    }
}

static void fuel_clear_active(void)
{
    s_fuel.active_distance_mm = 0;
    s_fuel.active_fuel_ul = 0;
    s_fuel.active_duration_ms = 0;
    s_fuel.active_last_epoch_s = 0;
    s_fuel.active_start_epoch_s = 0;
    memset(&s_fuel.active_detail, 0, sizeof(s_fuel.active_detail));
    s_accel_speed_valid = false;
    s_accel_last_speed_kmh = 0;
    s_accel_elapsed_ms = 0;
    trip_time_anchor_reset(&s_trip_time_anchor);
    trip_idle_detector_reset(&s_trip_idle_detector);
    s_active_contains_pending = false;
}

static void fuel_clear_pending(void)
{
    s_fuel.pending_distance_mm = 0;
    s_fuel.pending_fuel_ul = 0;
    s_fuel.pending_duration_ms = 0;
    s_fuel.pending_last_epoch_s = 0;
    s_fuel.pending_start_epoch_s = 0;
    memset(&s_fuel.pending_detail, 0, sizeof(s_fuel.pending_detail));
    s_fuel.pending_valid = 0;
}

static void fuel_finish_active_trip(void)
{
    /* Re-anchor the boundary immediately before archiving.  The end of a trip
     * is the last engine-running sample, never the time at which the 15-minute
     * finalization timer expires. */
    if (s_phone_time_valid && s_trip_time_anchor.seen) {
        time_t now = time(NULL);
        uint64_t estimated_start = 0;
        uint64_t estimated_end = 0;
        if (now >= (time_t)1704067200 &&
            trip_time_anchor_estimate(&s_trip_time_anchor, (uint64_t)now,
                                      esp_timer_get_time(),
                                      s_fuel.active_duration_ms,
                                      &estimated_start, &estimated_end)) {
            if (!s_active_contains_pending) {
                s_fuel.active_start_epoch_s = estimated_start;
            }
            s_fuel.active_last_epoch_s = estimated_end;
        }
    }
    fuel_push_trip(s_fuel.active_duration_ms, s_fuel.active_distance_mm,
                   s_fuel.active_fuel_ul, s_fuel.active_start_epoch_s,
                   s_fuel.active_last_epoch_s, &s_fuel.active_detail);
    fuel_clear_active();
}

static void fuel_finish_pending_trip(void)
{
    if (s_fuel.pending_valid) {
        fuel_push_trip(s_fuel.pending_duration_ms, s_fuel.pending_distance_mm,
                       s_fuel.pending_fuel_ul, s_fuel.pending_start_epoch_s,
                       s_fuel.pending_last_epoch_s, &s_fuel.pending_detail);
    }
    fuel_clear_pending();
}

/* At boot there is no trustworthy clock yet. Keep the just-ended active
   accumulator in a separate persisted slot, while new OBD samples start a
   fresh active accumulator. Phone time later decides whether to merge them. */
static void fuel_prepare_pending_at_boot(void)
{
    bool active_has_data = s_fuel.active_duration_ms ||
                           s_fuel.active_distance_mm ||
                           s_fuel.active_fuel_ul;
    if (s_fuel.pending_valid) {
        /* Opening the native USB serial port and other harmless resets can
           reboot the ESP32 before the phone has resolved the previous boot's
           pending trip. Never finalize that pending trip merely because a
           second boot occurred. If the intervening boot collected data, fold
           it into the retained accumulator; phone time will still make the
           actual merge/split decision. This is boot-only arithmetic and does
           not add tasks, allocations or flash writes. */
        if (active_has_data) {
            s_fuel.pending_duration_ms += s_fuel.active_duration_ms;
            s_fuel.pending_distance_mm += s_fuel.active_distance_mm;
            s_fuel.pending_fuel_ul += s_fuel.active_fuel_ul;
            trip_detail_merge(&s_fuel.pending_detail, &s_fuel.active_detail);
            if (s_fuel.pending_start_epoch_s < 1704067200ULL &&
                s_fuel.active_start_epoch_s >= 1704067200ULL) {
                s_fuel.pending_start_epoch_s = s_fuel.active_start_epoch_s;
            }
            if (s_fuel.active_last_epoch_s >= 1704067200ULL) {
                s_fuel.pending_last_epoch_s = s_fuel.active_last_epoch_s;
            }
            fuel_clear_active();
        }
        return;
    }
    if (active_has_data) {
        s_fuel.pending_duration_ms = s_fuel.active_duration_ms;
        s_fuel.pending_distance_mm = s_fuel.active_distance_mm;
        s_fuel.pending_fuel_ul = s_fuel.active_fuel_ul;
        s_fuel.pending_last_epoch_s = s_fuel.active_last_epoch_s;
        s_fuel.pending_start_epoch_s = s_fuel.active_start_epoch_s;
        s_fuel.pending_detail = s_fuel.active_detail;
        s_fuel.pending_valid = 1;
        fuel_clear_active();
    }
}

esp_err_t nvs_storage_init(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND)
    {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    load_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
    // Mileage/trip stats are no longer persisted (see s_stat declaration); stay {0} and start fresh each boot.
    {   // Chart alarm thresholds: load if present in NVS; otherwise keep static defaults (don't overwrite to 0).
        nvs_handle_t h; size_t sz = sizeof(s_chart_alarm);
        if (nvs_open(NS_CFG, NVS_READONLY, &h) == ESP_OK) {
            nvs_get_blob(h, KEY_CHART_ALARM, s_chart_alarm, &sz);
            nvs_close(h);
        }
    }
    {   // Multi-gauge boot animation settings: same as above, load if present else keep defaults.
        nvs_handle_t h; size_t sz = sizeof(s_mg);
        if (nvs_open(NS_CFG, NVS_READONLY, &h) == ESP_OK) {
            nvs_get_blob(h, KEY_MG_EXTRA, &s_mg, &sz);
            nvs_close(h);
        }
        if (s_mg.device_position < 1 || s_mg.device_position > 3) s_mg.device_position = 1;
        if (s_mg.intro_enable > 2) s_mg.intro_enable = 2;   // legacy REI/SHINJI/ASUKA (3/4) map to VIDEO (2)
        if (s_mg.boot_mode > 2) s_mg.boot_mode = 0;
        ESP_LOGD("nvs", "mg loaded: intro=%u pos=%u boot=%u (blob_sz=%u)", s_mg.intro_enable, s_mg.device_position, s_mg.boot_mode, (unsigned)sz);
    }

    /* ---- Config version migration ----
       nvs_user_cfg_t (s_cfg) also uses the generic grow logic in load_blob():
       if the stored blob is smaller than the current struct, copy the old bytes, keep compile-time
       defaults for the new trailing fields, then rewrite the blob at the new size. That logic REQUIRES
       new fields to be appended at the END of nvs_user_cfg_t, otherwise old data would be reinterpreted
       into the wrong fields. Respect this constraint when adding fields to s_cfg; never insert in the middle. */
    {
        nvs_handle_t h;
        uint8_t stored_ver = 0;
        bool has_ver = false;
        if (nvs_open(NS_CFG, NVS_READONLY, &h) == ESP_OK) {
            if (nvs_get_u8(h, KEY_CFG_VERSION, &stored_ver) == ESP_OK) has_ver = true;
            nvs_close(h);
        }
        if (!has_ver || stored_ver < CFG_VERSION_CURRENT) {
            ESP_LOGW("nvs", "Config migration v%u → v%u", stored_ver, CFG_VERSION_CURRENT);
            // v0 → v1: boot_mode field added, default 0 (SKY GAUGE)
            if (stored_ver < 1) {
                s_mg.boot_mode = 0;
                save_blob(NS_CFG, KEY_MG_EXTRA, &s_mg, sizeof(s_mg));
            }
            // v1 → v2: theme_cfg.theme becomes a real theme selector (see ui_theme.c).
            // The old default value 1 was never used; reset it to 0 (DEFAULT) so existing
            // devices keep their original look. Must persist, otherwise the next boot would
            // read 1 back from NVS. The version bump means a later deliberate AMBER (index 1)
            // selection won't be reset again.
            if (stored_ver < 2) {
                if (s_cfg.theme_cfg.theme == 1) {
                    s_cfg.theme_cfg.theme = 0;
                    save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
                }
            }
            // v2 → v3: the three built-in boot videos (REI/SHINJI/ASUKA = intro 2/3/4) were
            // replaced by a single app-flashed animation (VIDEO = 2). Old 3/4 map to 2; old 2
            // keeps its value but now plays /bootmedia/boot_block.*. Persist so the roller
            // never shows a stale selection again.
            if (stored_ver < 3) {
                if (s_mg.intro_enable > 2) {
                    s_mg.intro_enable = 2;
                }
                save_blob(NS_CFG, KEY_MG_EXTRA, &s_mg, sizeof(s_mg));
            }
            // v3 → v4: merge the GEAR and RPM boot pages and compact later page IDs.
            if (stored_ver < 4) {
                uint8_t old_page = s_cfg.default_page;
                if (old_page == 5) s_cfg.default_page = 4;       // RPM → GEAR+RPM
                else if (old_page >= 6 && old_page <= 8) s_cfg.default_page--;
                if (s_cfg.default_page != old_page) {
                    save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
                }
            }
            // v4 → v5: the configurable RPM threshold becomes a yellow line;
            // reserve 7000 for the fixed redline and 9000 for dial full-scale.
            if (stored_ver < 5 && s_cfg.rpm_warn_threshold > 6500) {
                s_cfg.rpm_warn_threshold = 6500;
                save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
            }
            // v5 → v6: trip_merge_timeout_min was appended to nvs_user_cfg_t.
            // v6 → v7: widen the field to uint16_t for 480 min and change the
            // default/options. This pre-release setting is reset to the new default.
            if (stored_ver < 7) {
                s_cfg.trip_merge_timeout_min = 240;
                save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
            }
            // v7 → v8: initialize the short merge window. The current build
            // subsequently normalizes every stored version to the locked value.
            if (stored_ver < 8) {
                s_cfg.trip_merge_timeout_min = TRIP_MERGE_TIMEOUT_LOCKED_MIN;
                save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
            }
            // v8 → v9: this BRZ-specific build now defaults to the ZD8 6MT
            // profile. Migrate the previous generic default once; a later manual
            // selection of OBD2 Generic remains respected because cfgver is then 9.
            if (stored_ver < 9 && s_cfg.vehicle_profile_idx == 0) {
                s_cfg.vehicle_profile_idx = VEHICLE_PROFILE_DEFAULT_INDEX;
                save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
            }
            // v9 → v10: make FUEL the startup default for this BRZ build.
            // The boot-page roller remains editable, so a later deliberate
            // user selection is respected after this one-time migration.
            if (stored_ver < 10) {
                s_cfg.default_page = 6;
                save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
            }
            // v10 → v11: this product build exposes only ZC6 and ZD8. Any
            // previously selected internal/legacy profile becomes ZD8.
            if (stored_ver < 11 &&
                !vehicle_profile_is_selectable(s_cfg.vehicle_profile_idx)) {
                s_cfg.vehicle_profile_idx = VEHICLE_PROFILE_DEFAULT_INDEX;
                save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
            }
            // v11 → v12: automatic refuel detection becomes configurable and
            // changes from the former fixed 5 L threshold to a 10 L default.
            if (stored_ver < 12) {
                s_cfg.refuel_detect_threshold_ml = NVS_REFUEL_THRESHOLD_DEFAULT_ML;
                save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
            }
            // Write the new version number
            if (nvs_open(NS_CFG, NVS_READWRITE, &h) == ESP_OK) {
                nvs_set_u8(h, KEY_CFG_VERSION, CFG_VERSION_CURRENT);
                nvs_commit(h);
                nvs_close(h);
            }
            ESP_LOGI("nvs", "Config migration done, now v%u", CFG_VERSION_CURRENT);
        }
    }

    /* Default-value repair for new fields (old NVS data has rsv[x] all zero) */
    if(s_cfg.brightness_day < 10) s_cfg.brightness_day = 100; // valid range 10-100; 0/unset/out-of-range all become 100
    if(s_cfg.default_page > 8) s_cfg.default_page = 6; // 0=Temp..4=Gear+RPM,5=Speed,6=Fuel,7=Trip History,8=Trip Overview
    if(s_cfg.needle_source_idx >= 11) s_cfg.needle_source_idx = 0; // DISP_ITEM_COUNT=11 (CLT..BOOST)
    if(s_cfg.device_role > 2) s_cfg.device_role = ESPNOW_ROLE_STANDALONE; // role: 0=master 1=slave 2=standalone; out-of-range -> standalone
    if(s_cfg.chart_source_idx >= 11) s_cfg.chart_source_idx = 8; // chart item out-of-range -> default OILP (old NVS byte 0=CLT is also fine, unify to OILP)
    // Keep internal/legacy profiles frozen even if an old NVS blob selected one.
    if(!vehicle_profile_is_selectable(s_cfg.vehicle_profile_idx)) {
        s_cfg.vehicle_profile_idx = VEHICLE_PROFILE_DEFAULT_INDEX;
        save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
    }
    if(s_cfg.brake_temp_warn_c < 10 || s_cfg.brake_temp_warn_c > 1200) s_cfg.brake_temp_warn_c = 600;
    if(s_cfg.oil_pressure_warn_x10 > 100) s_cfg.oil_pressure_warn_x10 = 80;
    // 0=unset/legacy out-of-range -> default 6000; clamped here centrally so callers (ui.c / ui_ScreenPageRpmWarn.c) don't repeat the check
    if(s_cfg.rpm_warn_threshold < 1000 || s_cfg.rpm_warn_threshold > 6500) s_cfg.rpm_warn_threshold = 5000;
    if (s_cfg.refuel_detect_threshold_ml < NVS_REFUEL_THRESHOLD_MIN_ML ||
        s_cfg.refuel_detect_threshold_ml > NVS_REFUEL_THRESHOLD_MAX_ML ||
        (s_cfg.refuel_detect_threshold_ml % 1000U) != 0U) {
        s_cfg.refuel_detect_threshold_ml = NVS_REFUEL_THRESHOLD_DEFAULT_ML;
        save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
    }
    /* Keep the existing field and UI row for a future unlock, but normalize
       old selections once so runtime and persisted behavior are both 15 min. */
#if TRIP_MERGE_TIMEOUT_EDITABLE
    if(s_cfg.trip_merge_timeout_min != 5 && s_cfg.trip_merge_timeout_min != 15 && s_cfg.trip_merge_timeout_min != 30 &&
       s_cfg.trip_merge_timeout_min != 60 && s_cfg.trip_merge_timeout_min != 120 &&
       s_cfg.trip_merge_timeout_min != 240 && s_cfg.trip_merge_timeout_min != 480)
        s_cfg.trip_merge_timeout_min = TRIP_MERGE_TIMEOUT_LOCKED_MIN;
#else
    if (s_cfg.trip_merge_timeout_min != TRIP_MERGE_TIMEOUT_LOCKED_MIN) {
        s_cfg.trip_merge_timeout_min = TRIP_MERGE_TIMEOUT_LOCKED_MIN;
        save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
    }
#endif

    // Validate TEMP/INFO custom display-item maps: 0..(DISP_ITEM_COUNT-1)
    for (int i = 0; i < 3; ++i) {
        if (s_cfg.temp_display_map[i] > 11) s_cfg.temp_display_map[i] = (uint8_t)i;
    }
    for (int i = 0; i < 5; ++i) {
        if (s_cfg.info_display_map[i] > 11) {
            static const uint8_t def_map[5] = {0, 2, 3, 4, 1};
            s_cfg.info_display_map[i] = def_map[i];
        }
    }

#if !MULTIGAUGE_SETTINGS_EDITABLE
    /* Normalize both runtime and persisted values. This prevents an older NVS
     * selection or any remaining internal setter from changing the frozen
     * primary-gauge role after a reboot. */
    if (s_cfg.device_role != MULTIGAUGE_LOCKED_ROLE) {
        s_cfg.device_role = MULTIGAUGE_LOCKED_ROLE;
        save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
    }
    if (s_mg.device_position != MULTIGAUGE_LOCKED_POSITION ||
        s_mg.intro_enable != MULTIGAUGE_LOCKED_INTRO) {
        s_mg.device_position = MULTIGAUGE_LOCKED_POSITION;
        s_mg.intro_enable = MULTIGAUGE_LOCKED_INTRO;
        save_blob(NS_CFG, KEY_MG_EXTRA, &s_mg, sizeof(s_mg));
    }
#endif

    s_mux = xSemaphoreCreateMutex();

    load_blob(NS_CFG, KEY_FUEL_TRIPS, &s_fuel, sizeof(s_fuel));
    load_blob(NS_CFG, KEY_TRIP_SYNC, &s_trip_sync, sizeof(s_trip_sync));
    load_blob(NS_CFG, KEY_REFUEL_HISTORY, &s_refuel, sizeof(s_refuel));
    load_blob(NS_CFG, KEY_CUSTOM_TRIP, &s_custom_trip, sizeof(s_custom_trip));
    if (s_trip_sync.version != TRIP_SYNC_STORE_VERSION ||
        s_trip_sync.count > NVS_TRIP_SYNC_QUEUE_MAX) {
        memset(&s_trip_sync, 0, sizeof(s_trip_sync));
        s_trip_sync.version = TRIP_SYNC_STORE_VERSION;
        /* Import the retained on-device history once. Legacy entries have no
           reliable wall-clock timestamps, so flags remains zero. */
        unsigned retained = 0;
        if (s_fuel.version >= 1 && s_fuel.version <= FUEL_STORE_VERSION &&
            s_fuel.history_count <= NVS_FUEL_TRIP_HISTORY_MAX) {
            retained = s_fuel.history_count;
        }
        for (int i = (int)retained - 1; i >= 0; --i) {
            const nvs_fuel_trip_record_t *old = &s_fuel.history[i];
            nvs_trip_sync_record_t imported = {
                .id = old->id,
                .duration_s = old->duration_s,
                .distance_m = old->distance_m,
                .fuel_ml = old->fuel_ml,
                .avg_l100_x100 = old->avg_l100_x100,
            };
            trip_sync_enqueue(&imported, &s_fuel.history_details[i]);
        }
    }
    if ((s_fuel.version < 1 || s_fuel.version > FUEL_STORE_VERSION) ||
        s_fuel.history_count > NVS_FUEL_TRIP_HISTORY_MAX) {
        memset(&s_fuel, 0, sizeof(s_fuel));
        s_fuel.version = FUEL_STORE_VERSION;
    } else if (s_fuel.version == 1) {
        /* v1 has no cross-power timestamp, so its retained active trip cannot
           be compared safely. Finalize it once, then start v2 tracking. */
        fuel_finish_active_trip();
        s_fuel.version = FUEL_STORE_VERSION;
    } else {
        fuel_prepare_pending_at_boot();
        s_fuel.version = FUEL_STORE_VERSION;
    }
    save_blob(NS_CFG, KEY_FUEL_TRIPS, &s_fuel, sizeof(s_fuel));
    save_blob(NS_CFG, KEY_TRIP_SYNC, &s_trip_sync, sizeof(s_trip_sync));
    if (s_refuel.version != REFUEL_STORE_VERSION ||
        s_refuel.count > NVS_REFUEL_HISTORY_MAX) {
        memset(&s_refuel, 0, sizeof(s_refuel));
        s_refuel.version = REFUEL_STORE_VERSION;
        s_refuel.last_level_x100 = UINT16_MAX;
        s_refuel.candidate_base_x100 = UINT16_MAX;
        s_refuel.baseline_duration_ms = s_fuel.lifetime_duration_ms;
        s_refuel.baseline_distance_mm = s_fuel.lifetime_distance_mm;
        s_refuel.baseline_fuel_ul = s_fuel.lifetime_fuel_ul;
        save_blob(NS_CFG, KEY_REFUEL_HISTORY, &s_refuel, sizeof(s_refuel));
    }
    s_custom_trip_dirty = false;
    if (s_custom_trip.version != CUSTOM_TRIP_STORE_VERSION) {
        memset(&s_custom_trip, 0, sizeof(s_custom_trip));
        s_custom_trip.version = CUSTOM_TRIP_STORE_VERSION;
        s_custom_trip.baseline_duration_ms = s_fuel.lifetime_duration_ms;
        s_custom_trip.baseline_distance_mm = s_fuel.lifetime_distance_mm;
        s_custom_trip.baseline_fuel_ul = s_fuel.lifetime_fuel_ul;
        if (save_blob(NS_CFG, KEY_CUSTOM_TRIP, &s_custom_trip,
                      sizeof(s_custom_trip)) != ESP_OK) {
            s_custom_trip_dirty = true;
        }
    }
    {
        odometer_store_t loaded = s_odometer;
        load_blob(NS_CFG, KEY_ODOMETER_CFG, &loaded, sizeof(loaded));
        if (loaded.version != ODOMETER_STORE_VERSION || loaded.display_enabled > 1U ||
            loaded.calibrated > 1U) {
            memset(&loaded, 0, sizeof(loaded));
            loaded.version = ODOMETER_STORE_VERSION;
            loaded.display_enabled = 1U;
            save_blob(NS_CFG, KEY_ODOMETER_CFG, &loaded, sizeof(loaded));
        }
        s_odometer = loaded;
    }
    s_trip_sync_dirty = false;
    s_refuel_dirty = false;
    return ESP_OK;
}

/* User config */
const nvs_user_cfg_t * nvs_cfg_get(void){ return &s_cfg; }

esp_err_t nvs_cfg_set(const nvs_user_cfg_t *cfg)
{
    if(!cfg) return ESP_ERR_INVALID_ARG;
    nvs_user_cfg_t normalized = *cfg;
    normalized.vehicle_profile_idx =
        vehicle_profile_normalize_index(normalized.vehicle_profile_idx);
    if (normalized.refuel_detect_threshold_ml < NVS_REFUEL_THRESHOLD_MIN_ML ||
        normalized.refuel_detect_threshold_ml > NVS_REFUEL_THRESHOLD_MAX_ML ||
        (normalized.refuel_detect_threshold_ml % 1000U) != 0U) {
        normalized.refuel_detect_threshold_ml = NVS_REFUEL_THRESHOLD_DEFAULT_ML;
    }
#if !TRIP_MERGE_TIMEOUT_EDITABLE
    normalized.trip_merge_timeout_min = TRIP_MERGE_TIMEOUT_LOCKED_MIN;
#endif
#if !MULTIGAUGE_SETTINGS_EDITABLE
    normalized.device_role = MULTIGAUGE_LOCKED_ROLE;
#endif
    if(memcmp(&normalized,&s_cfg,sizeof(s_cfg))==0) return ESP_OK;
    s_cfg=normalized;
    return save_blob(NS_CFG, KEY_CFG, &s_cfg, sizeof(s_cfg));
}

/* Chart alarm thresholds */
int16_t nvs_chart_alarm_get(uint8_t item){
    return (item < CHART_ALARM_N) ? s_chart_alarm[item] : CHART_ALARM_OFF;
}
void nvs_chart_alarm_set(uint8_t item, int16_t raw_threshold){
    if(item >= CHART_ALARM_N) return;
    if(s_chart_alarm[item] == raw_threshold) return;
    s_chart_alarm[item] = raw_threshold;
    save_blob(NS_CFG, KEY_CHART_ALARM, s_chart_alarm, sizeof(s_chart_alarm));
}

/* Multi-gauge boot animation settings */
uint8_t nvs_intro_enable_get(void){
#if !MULTIGAUGE_SETTINGS_EDITABLE
    return MULTIGAUGE_LOCKED_INTRO;
#else
    return s_mg.intro_enable;
#endif
}
void nvs_intro_enable_set(uint8_t en){
#if !MULTIGAUGE_SETTINGS_EDITABLE
    en = MULTIGAUGE_LOCKED_INTRO;
#endif
    if(en > 4) return;
    if(s_mg.intro_enable == en) return;
    s_mg.intro_enable = en;
    save_blob(NS_CFG, KEY_MG_EXTRA, &s_mg, sizeof(s_mg));
}
uint8_t nvs_device_position_get(void){
#if !MULTIGAUGE_SETTINGS_EDITABLE
    return MULTIGAUGE_LOCKED_POSITION;
#else
    return s_mg.device_position;
#endif
}
void nvs_device_position_set(uint8_t pos){
#if !MULTIGAUGE_SETTINGS_EDITABLE
    pos = MULTIGAUGE_LOCKED_POSITION;
#endif
    if(pos < 1 || pos > 3) return;
    if(s_mg.device_position == pos) return;
    s_mg.device_position = pos;
    save_blob(NS_CFG, KEY_MG_EXTRA, &s_mg, sizeof(s_mg));
}
uint8_t nvs_boot_mode_get(void){ return s_mg.boot_mode; }
void nvs_boot_mode_set(uint8_t mode){
    if(mode > 2) return;
    if(s_mg.boot_mode == mode) return;
    s_mg.boot_mode = mode;
    save_blob(NS_CFG, KEY_MG_EXTRA, &s_mg, sizeof(s_mg));
}

/* Statistics */
const nvs_stat_t * nvs_stat_get(void){return &s_stat;}
/*
 * Update driving statistics.
 * @param speed_kmh speed in km/h
 * @param dt_ms elapsed time in ms
 * @note moving-only legacy statistics; retain fractions for sub-second updates
 */
void nvs_stat_update_speed(uint8_t speed_kmh, uint32_t dt_ms)
{
    if(dt_ms == 0) return;
    if(speed_kmh == 0) return;

    xSemaphoreTake(s_mux,portMAX_DELAY);
    uint64_t numerator = (uint64_t)speed_kmh * dt_ms;
    uint64_t odometer = numerator + s_stat_odometer_remainder;
    uint64_t trip = numerator + s_stat_trip_remainder;
    s_stat.odometer_m += odometer / 3600ULL;
    s_stat.trip_m += trip / 3600ULL;
    s_stat_odometer_remainder = (uint32_t)(odometer % 3600ULL);
    s_stat_trip_remainder = (uint32_t)(trip % 3600ULL);

    /* 2. time */
    uint64_t time_ms = (uint64_t)dt_ms + s_stat_time_remainder_ms;
    s_stat.run_time_s += time_ms / 1000ULL;
    s_stat.trip_run_time_s += time_ms / 1000ULL;
    s_stat_time_remainder_ms = (uint32_t)(time_ms % 1000ULL);

    /* 3. max speed */
    if(speed_kmh > s_stat.max_speed_kmh) s_stat.max_speed_kmh = speed_kmh;

    /* 4. avg speed = trip distance / trip time (m/s) -> km/h */
    if(s_stat.trip_run_time_s){
        double avg_ms = (double)s_stat.trip_m / (double)s_stat.trip_run_time_s; // m/s
        s_stat.avg_speed_kmh = (uint16_t)(avg_ms * 3.6 + 0.5);
        if(s_stat.avg_speed_kmh > s_stat.max_speed_kmh) s_stat.avg_speed_kmh = s_stat.max_speed_kmh;
    }

    xSemaphoreGive(s_mux);
}

/*
 * Reset current-trip statistics (trip distance, max speed, avg speed, running time).
*/
void nvs_stat_reset_trip(void){
    xSemaphoreTake(s_mux,portMAX_DELAY);
    s_stat.trip_m=0;
    s_stat.max_speed_kmh=0;
    s_stat.avg_speed_kmh=0;
    s_stat.run_time_s=0;
    s_stat.trip_run_time_s=0;
    s_stat_trip_remainder=0;
    s_stat_time_remainder_ms=0;
    xSemaphoreGive(s_mux);
}

/*
 * Get current-trip statistics (distance, max speed, avg speed, running time).
 * @return a snapshot of the statistics struct
*/
nvs_stat_t nvs_stat_get_mileage(void){
    xSemaphoreTake(s_mux,portMAX_DELAY);
    nvs_stat_t stat = s_stat;
    xSemaphoreGive(s_mux);
    return stat;
}

void nvs_fuel_update(const fuel_sample_t *sample, uint32_t dt_ms)
{
    if (!s_mux || dt_ms == 0) return;

    xSemaphoreTake(s_mux, portMAX_DELAY);
    fuel_estimate_t estimate = fuel_estimator_step(&s_fuel_estimator, sample, dt_ms);
    s_fuel_data_valid = estimate.valid;
    s_fuel_instant_x100 = estimate.instant_x100;
    s_fuel_instant_is_lph = estimate.instant_is_lph;
    s_fuel_source = estimate.source;

    /* Driving time must not depend on MAF / PID 5E availability. RPM is the
       engine-running clock; the monotonic anchors later reconstruct real
       start/end timestamps from a delayed phone synchronization. */
    bool engine_running = sample && sample->rpm_valid && sample->rpm > 0;
    bool interval_valid = dt_ms <= 2000;
    if (engine_running && interval_valid) {
        if (sample->rpm > s_fuel.active_detail.max_rpm)
            s_fuel.active_detail.max_rpm = sample->rpm;
        if (sample->speed_valid) {
            if (sample->speed_kmh > s_fuel.active_detail.max_speed_kmh)
                s_fuel.active_detail.max_speed_kmh = sample->speed_kmh;
            if (!s_accel_speed_valid) {
                s_accel_speed_valid = true;
                s_accel_last_speed_kmh = sample->speed_kmh;
                s_accel_elapsed_ms = 0;
            } else {
                s_accel_elapsed_ms = UINT32_MAX - s_accel_elapsed_ms < dt_ms
                                   ? UINT32_MAX : s_accel_elapsed_ms + dt_ms;
                if (sample->speed_kmh != s_accel_last_speed_kmh && s_accel_elapsed_ms > 0) {
                    int32_t delta_kmh = (int32_t)sample->speed_kmh - s_accel_last_speed_kmh;
                    int32_t accel_x100 = (int32_t)(((int64_t)delta_kmh * 250000LL) /
                                                   (9LL * s_accel_elapsed_ms));
                    /* Ignore impossible PID glitches above roughly 3 g. */
                    if (accel_x100 >= -3000 && accel_x100 <= 3000) {
                        if (accel_x100 > s_fuel.active_detail.max_accel_x100)
                            s_fuel.active_detail.max_accel_x100 = (int16_t)accel_x100;
                        if (accel_x100 < s_fuel.active_detail.max_decel_x100)
                            s_fuel.active_detail.max_decel_x100 = (int16_t)accel_x100;
                    }
                    s_accel_last_speed_kmh = sample->speed_kmh;
                    s_accel_elapsed_ms = 0;
                }
            }
        } else {
            s_accel_speed_valid = false;
            s_accel_elapsed_ms = 0;
        }
        int64_t now_us = esp_timer_get_time();
        trip_time_anchor_observe(&s_trip_time_anchor, now_us, dt_ms);
        s_fuel.active_duration_ms += dt_ms;
        s_fuel.lifetime_duration_ms += dt_ms;
        if (s_phone_time_valid) {
            time_t now = time(NULL);
            if (now >= (time_t)1704067200) {
                uint64_t now_s = (uint64_t)now;
                if (s_fuel.active_start_epoch_s < 1704067200ULL) {
                    uint64_t duration_s = s_fuel.active_duration_ms / 1000ULL;
                    s_fuel.active_start_epoch_s = now_s > duration_s ? now_s - duration_s : now_s;
                }
                s_fuel.active_last_epoch_s = now_s;
            }
        }
        s_fuel_dirty = true;
    } else {
        s_accel_speed_valid = false;
        s_accel_elapsed_ms = 0;
    }

    bool active_has_data = s_fuel.active_duration_ms ||
                           s_fuel.active_distance_mm ||
                           s_fuel.active_fuel_ul;
    bool trip_finalized = trip_idle_detector_step(
        &s_trip_idle_detector, engine_running, active_has_data, dt_ms,
        s_cfg.trip_merge_timeout_min);
    if (trip_finalized) {
        ESP_LOGI(TAG, "engine stopped for %u min; finalizing trip",
                 (unsigned)s_cfg.trip_merge_timeout_min);
        fuel_finish_active_trip();
        s_fuel_dirty = true;
    }
    if (!estimate.integrate) {
        xSemaphoreGive(s_mux);
        /* Persist the completed history entry and phone-sync queue immediately.
         * This runs in the statistics task, never a BLE callback. */
        if (trip_finalized) nvs_fuel_save();
        return;
    }

    uint64_t fuel_ul = estimate.fuel_ul;
    uint64_t distance_mm = estimate.distance_mm;

    s_fuel.active_distance_mm += distance_mm;
    s_fuel.active_fuel_ul += fuel_ul;
    s_fuel.lifetime_distance_mm += distance_mm;
    s_fuel.lifetime_fuel_ul += fuel_ul;
    s_fuel_dirty = true;
    xSemaphoreGive(s_mux);
    if (trip_finalized) nvs_fuel_save();
}

void nvs_fuel_get_snapshot(nvs_fuel_snapshot_t *out)
{
    if (!out) return;
    memset(out, 0, sizeof(*out));
    if (!s_mux) return;

    xSemaphoreTake(s_mux, portMAX_DELAY);
    out->data_valid = s_fuel_data_valid;
    out->instant_is_lph = s_fuel_instant_is_lph;
    out->instant_x100 = s_fuel_instant_x100;
    out->source = s_fuel_source;
    out->current_avg_x100 = fuel_average_x100(s_fuel.active_fuel_ul,
                                               s_fuel.active_distance_mm);
    out->lifetime_avg_x100 = fuel_average_x100(s_fuel.lifetime_fuel_ul,
                                                s_fuel.lifetime_distance_mm);
    out->current_avg_valid = s_fuel.active_distance_mm >= 1000;
    out->lifetime_avg_valid = s_fuel.lifetime_distance_mm >= 1000;
    out->current_duration_s = (uint32_t)(s_fuel.active_duration_ms / 1000ULL);
    out->current_distance_m = (uint32_t)(s_fuel.active_distance_mm / 1000ULL);
    out->current_fuel_ml = (uint32_t)(s_fuel.active_fuel_ul / 1000ULL);
    out->lifetime_duration_s = s_fuel.lifetime_duration_ms / 1000ULL;
    out->lifetime_distance_m = s_fuel.lifetime_distance_mm / 1000ULL;
    out->lifetime_fuel_ml = s_fuel.lifetime_fuel_ul / 1000ULL;
    out->history_count = s_fuel.history_count;
    memcpy(out->history, s_fuel.history, sizeof(out->history));
    out->current_detail = s_fuel.active_detail;
    out->current_start_epoch_s = s_fuel.active_start_epoch_s;
    out->odometer_x10_km = odometer_current_x10_locked();
    out->odometer_calibrated = s_odometer.calibrated != 0;
    out->odometer_display_enabled = s_odometer.display_enabled != 0;
    xSemaphoreGive(s_mux);
}

void nvs_refuel_observe_fuel_level(uint16_t percent_x100)
{
    if (!s_mux || percent_x100 > 10000U) return;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    if (s_refuel.last_level_x100 == UINT16_MAX) {
        s_refuel.last_level_x100 = percent_x100;
        s_refuel_dirty = true;
        xSemaphoreGive(s_mux);
        return;
    }
    if (percent_x100 <= s_refuel.last_level_x100) {
        /* Consumption moves the comparison floor downward. Small upward tank
         * slosh never moves it upward and therefore cannot accumulate into a
         * false refuel event. */
        s_refuel.last_level_x100 = percent_x100;
        s_refuel.candidate_count = 0;
        s_refuel.candidate_base_x100 = UINT16_MAX;
        s_refuel_dirty = true;
    } else {
        uint16_t increase = percent_x100 - s_refuel.last_level_x100;
        /* With the nominal 50 L tank, 0.01% equals 5 ml. Keep the comparison
         * in fuel-level units so the existing two-sample debounce is unchanged. */
        uint16_t threshold_x100 = s_cfg.refuel_detect_threshold_ml / 5U;
        if (increase >= threshold_x100) {
            if (s_refuel.candidate_base_x100 != s_refuel.last_level_x100) {
                s_refuel.candidate_base_x100 = s_refuel.last_level_x100;
                s_refuel.candidate_count = 1;
            } else if (s_refuel.candidate_count < UINT8_MAX) {
                ++s_refuel.candidate_count;
            }
            /* Require two independent PID samples (normally 30 s apart). */
            if (s_refuel.candidate_count >= 2U) {
                uint32_t added_ml = (uint32_t)increase * 5U; /* nominal 50 L tank */
                if (added_ml > UINT16_MAX) added_ml = UINT16_MAX;
                refuel_reset_locked(0x01U, (uint16_t)added_ml);
                s_refuel.last_level_x100 = percent_x100;
                s_refuel.candidate_base_x100 = UINT16_MAX;
            }
            s_refuel_dirty = true;
        } else {
            s_refuel.candidate_count = 0;
            s_refuel.candidate_base_x100 = UINT16_MAX;
        }
    }
    xSemaphoreGive(s_mux);
}

void nvs_refuel_get_meta(nvs_refuel_meta_t *out)
{
    if (!out) return;
    memset(out, 0, sizeof(*out));
    out->protocol_version = REFUEL_PROTOCOL_VERSION;
    if (!s_mux) return;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    out->count = s_refuel.count;
    if (s_refuel.count) {
        out->oldest_id = s_refuel.records[0].id;
        out->newest_id = s_refuel.records[s_refuel.count - 1U].id;
    }
    out->current_start_epoch_s = s_refuel.current_start_epoch_s;
    uint64_t duration_ms = s_fuel.lifetime_duration_ms >= s_refuel.baseline_duration_ms
                         ? s_fuel.lifetime_duration_ms - s_refuel.baseline_duration_ms : 0;
    uint64_t distance_mm = s_fuel.lifetime_distance_mm >= s_refuel.baseline_distance_mm
                         ? s_fuel.lifetime_distance_mm - s_refuel.baseline_distance_mm : 0;
    uint64_t fuel_ul = s_fuel.lifetime_fuel_ul >= s_refuel.baseline_fuel_ul
                     ? s_fuel.lifetime_fuel_ul - s_refuel.baseline_fuel_ul : 0;
    out->current_duration_s = clamp_u32_u64(duration_ms / 1000ULL);
    out->current_distance_m = clamp_u32_u64(distance_mm / 1000ULL);
    out->current_fuel_ml = clamp_u32_u64(fuel_ul / 1000ULL);
    out->history_revision = s_refuel.history_revision;
    xSemaphoreGive(s_mux);
}

size_t nvs_refuel_read_after(uint32_t after_id, nvs_refuel_record_t *out,
                             size_t max_records)
{
    if (!out || max_records == 0 || !s_mux) return 0;
    size_t copied = 0;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    for (uint8_t i = 0; i < s_refuel.count && copied < max_records; ++i) {
        if (s_refuel.records[i].id > after_id) out[copied++] = s_refuel.records[i];
    }
    xSemaphoreGive(s_mux);
    return copied;
}

esp_err_t nvs_refuel_manual_reset(void)
{
    if (!s_mux) return ESP_ERR_INVALID_STATE;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    refuel_reset_locked(0x02U, 0);
    xSemaphoreGive(s_mux);
    return ESP_OK;
}

void nvs_custom_trip_get_meta(nvs_custom_trip_meta_t *out)
{
    if (!out) return;
    memset(out, 0, sizeof(*out));
    if (!s_mux) return;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    uint64_t duration_ms = s_fuel.lifetime_duration_ms >= s_custom_trip.baseline_duration_ms
                         ? s_fuel.lifetime_duration_ms - s_custom_trip.baseline_duration_ms : 0;
    uint64_t distance_mm = s_fuel.lifetime_distance_mm >= s_custom_trip.baseline_distance_mm
                         ? s_fuel.lifetime_distance_mm - s_custom_trip.baseline_distance_mm : 0;
    uint64_t fuel_ul = s_fuel.lifetime_fuel_ul >= s_custom_trip.baseline_fuel_ul
                     ? s_fuel.lifetime_fuel_ul - s_custom_trip.baseline_fuel_ul : 0;
    out->duration_s = clamp_u32_u64(duration_ms / 1000ULL);
    out->distance_m = clamp_u32_u64(distance_mm / 1000ULL);
    out->fuel_ml = clamp_u32_u64(fuel_ul / 1000ULL);
    xSemaphoreGive(s_mux);
}

esp_err_t nvs_custom_trip_set_baseline(uint32_t distance_m,
                                       uint32_t duration_s,
                                       uint32_t fuel_ml)
{
    if (!s_mux) return ESP_ERR_INVALID_STATE;
    custom_trip_store_t copy;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    uint64_t distance_mm = (uint64_t)distance_m * 1000ULL;
    uint64_t duration_ms = (uint64_t)duration_s * 1000ULL;
    uint64_t fuel_ul = (uint64_t)fuel_ml * 1000ULL;
    /* A phone snapshot can be one checkpoint ahead after an abrupt power cut.
     * Clamp instead of allowing an underflow to hide the custom trip forever. */
    if (distance_mm > s_fuel.lifetime_distance_mm) distance_mm = s_fuel.lifetime_distance_mm;
    if (duration_ms > s_fuel.lifetime_duration_ms) duration_ms = s_fuel.lifetime_duration_ms;
    if (fuel_ul > s_fuel.lifetime_fuel_ul) fuel_ul = s_fuel.lifetime_fuel_ul;
    bool changed = s_custom_trip.version != CUSTOM_TRIP_STORE_VERSION ||
                   s_custom_trip.baseline_distance_mm != distance_mm ||
                   s_custom_trip.baseline_duration_ms != duration_ms ||
                   s_custom_trip.baseline_fuel_ul != fuel_ul;
    s_custom_trip.version = CUSTOM_TRIP_STORE_VERSION;
    s_custom_trip.baseline_distance_mm = distance_mm;
    s_custom_trip.baseline_duration_ms = duration_ms;
    s_custom_trip.baseline_fuel_ul = fuel_ul;
    copy = s_custom_trip;
    bool needs_save = changed || s_custom_trip_dirty;
    if (needs_save) s_custom_trip_dirty = false;
    xSemaphoreGive(s_mux);
    if (!needs_save) return ESP_OK;
    esp_err_t err = save_blob(NS_CFG, KEY_CUSTOM_TRIP, &copy, sizeof(copy));
    if (err != ESP_OK) {
        /* Keep the RAM baseline active and let the regular checkpoint retry
         * persistence. A repeated identical phone packet must not be mistaken
         * for an already durable value after a transient NVS failure. */
        xSemaphoreTake(s_mux, portMAX_DELAY);
        s_custom_trip_dirty = true;
        xSemaphoreGive(s_mux);
    }
    return err;
}

esp_err_t nvs_refuel_delete_node(uint32_t id)
{
    if (!s_mux || id == 0U) return ESP_ERR_INVALID_ARG;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    uint8_t index = 0;
    while (index < s_refuel.count && s_refuel.records[index].id != id) ++index;
    if (index >= s_refuel.count) {
        xSemaphoreGive(s_mux);
        return ESP_ERR_NOT_FOUND;
    }

    const nvs_refuel_record_t removed = s_refuel.records[index];
    if ((uint8_t)(index + 1U) < s_refuel.count) {
        /* The following node remains the end of the merged interval. */
        nvs_refuel_record_t *following = &s_refuel.records[index + 1U];
        following->start_epoch_s = removed.start_epoch_s;
        following->duration_s = add_u32_saturated(removed.duration_s, following->duration_s);
        following->distance_m = add_u32_saturated(removed.distance_m, following->distance_m);
        following->fuel_ml = add_u32_saturated(removed.fuel_ml, following->fuel_ml);
    } else {
        /* The newest saved node separates the last completed interval from the
         * live one. Move the baselines back so both become one live interval. */
        uint64_t duration_ms = (uint64_t)removed.duration_s * 1000ULL;
        uint64_t distance_mm = (uint64_t)removed.distance_m * 1000ULL;
        uint64_t fuel_ul = (uint64_t)removed.fuel_ml * 1000ULL;
        s_refuel.baseline_duration_ms = s_refuel.baseline_duration_ms >= duration_ms
                                      ? s_refuel.baseline_duration_ms - duration_ms : 0;
        s_refuel.baseline_distance_mm = s_refuel.baseline_distance_mm >= distance_mm
                                      ? s_refuel.baseline_distance_mm - distance_mm : 0;
        s_refuel.baseline_fuel_ul = s_refuel.baseline_fuel_ul >= fuel_ul
                                  ? s_refuel.baseline_fuel_ul - fuel_ul : 0;
        s_refuel.current_start_epoch_s = removed.start_epoch_s;
    }

    if ((uint8_t)(index + 1U) < s_refuel.count) {
        memmove(&s_refuel.records[index], &s_refuel.records[index + 1U],
                (size_t)(s_refuel.count - index - 1U) * sizeof(s_refuel.records[0]));
    }
    --s_refuel.count;
    memset(&s_refuel.records[s_refuel.count], 0, sizeof(s_refuel.records[0]));
    if (++s_refuel.history_revision == 0U) ++s_refuel.history_revision;
    s_refuel_dirty = true;
    xSemaphoreGive(s_mux);
    return ESP_OK;
}

esp_err_t nvs_refuel_discard_before_first_node(uint32_t oldest_id)
{
    if (!s_mux || oldest_id == 0U) return ESP_ERR_INVALID_ARG;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    if (s_refuel.count == 0U || s_refuel.records[0].id != oldest_id) {
        xSemaphoreGive(s_mux);
        return ESP_ERR_NOT_FOUND;
    }
    if (s_refuel.count > 1U) {
        memmove(&s_refuel.records[0], &s_refuel.records[1],
                (size_t)(s_refuel.count - 1U) * sizeof(s_refuel.records[0]));
    }
    --s_refuel.count;
    memset(&s_refuel.records[s_refuel.count], 0, sizeof(s_refuel.records[0]));
    if (++s_refuel.history_revision == 0U) ++s_refuel.history_revision;
    s_refuel_dirty = true;
    xSemaphoreGive(s_mux);
    return ESP_OK;
}

void nvs_odometer_get_snapshot(nvs_odometer_snapshot_t *out)
{
    if (!out) return;
    memset(out, 0, sizeof(*out));
    if (!s_mux) return;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    out->odometer_x10_km = odometer_current_x10_locked();
    out->calibrated = s_odometer.calibrated != 0;
    out->display_enabled = s_odometer.display_enabled != 0;
    xSemaphoreGive(s_mux);
}

esp_err_t nvs_odometer_set_display(bool enabled)
{
    if (!s_mux) return ESP_ERR_INVALID_STATE;
    odometer_store_t copy;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    s_odometer.display_enabled = enabled ? 1U : 0U;
    copy = s_odometer;
    xSemaphoreGive(s_mux);
    return save_blob(NS_CFG, KEY_ODOMETER_CFG, &copy, sizeof(copy));
}

esp_err_t nvs_odometer_calibrate_x10_km(uint32_t odometer_x10_km)
{
    if (!s_mux) return ESP_ERR_INVALID_STATE;
    odometer_store_t copy;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    s_odometer.calibration_x10_km = odometer_x10_km;
    s_odometer.calibration_lifetime_m = s_fuel.lifetime_distance_mm / 1000ULL;
    s_odometer.calibrated = 1U;
    copy = s_odometer;
    xSemaphoreGive(s_mux);
    return save_blob(NS_CFG, KEY_ODOMETER_CFG, &copy, sizeof(copy));
}

esp_err_t nvs_fuel_save(void)
{
    if (!s_mux) return ESP_ERR_INVALID_STATE;
    fuel_store_t copy;
    bool save_fuel = false;
    bool save_sync = false;
    bool save_refuel = false;
    bool save_custom_trip = false;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    if (s_fuel_dirty) {
        copy = s_fuel;
        // Clear before writing so an update racing with flash I/O marks it dirty again.
        s_fuel_dirty = false;
        save_fuel = true;
    }
    if (s_trip_sync_dirty) {
        s_trip_sync_save_copy = s_trip_sync;
        s_trip_sync_dirty = false;
        save_sync = true;
    }
    if (s_refuel_dirty) {
        s_refuel_save_copy = s_refuel;
        s_refuel_dirty = false;
        save_refuel = true;
    }
    if (s_custom_trip_dirty) {
        s_custom_trip_save_copy = s_custom_trip;
        s_custom_trip_dirty = false;
        save_custom_trip = true;
    }
    xSemaphoreGive(s_mux);

    esp_err_t fuel_err = ESP_OK;
    esp_err_t sync_err = ESP_OK;
    esp_err_t refuel_err = ESP_OK;
    esp_err_t custom_trip_err = ESP_OK;
    if (save_fuel) {
        fuel_err = save_blob(NS_CFG, KEY_FUEL_TRIPS, &copy, sizeof(copy));
    }
    if (save_sync) {
        sync_err = save_blob(NS_CFG, KEY_TRIP_SYNC, &s_trip_sync_save_copy,
                             sizeof(s_trip_sync_save_copy));
    }
    if (save_refuel) {
        refuel_err = save_blob(NS_CFG, KEY_REFUEL_HISTORY, &s_refuel_save_copy,
                               sizeof(s_refuel_save_copy));
    }
    if (save_custom_trip) {
        custom_trip_err = save_blob(NS_CFG, KEY_CUSTOM_TRIP,
                                    &s_custom_trip_save_copy,
                                    sizeof(s_custom_trip_save_copy));
    }
    if (fuel_err != ESP_OK || sync_err != ESP_OK || refuel_err != ESP_OK ||
        custom_trip_err != ESP_OK) {
        xSemaphoreTake(s_mux, portMAX_DELAY);
        if (fuel_err != ESP_OK) s_fuel_dirty = true;
        if (sync_err != ESP_OK) s_trip_sync_dirty = true;
        if (refuel_err != ESP_OK) s_refuel_dirty = true;
        if (custom_trip_err != ESP_OK) s_custom_trip_dirty = true;
        xSemaphoreGive(s_mux);
    }
    if (fuel_err != ESP_OK) return fuel_err;
    if (sync_err != ESP_OK) return sync_err;
    if (refuel_err != ESP_OK) return refuel_err;
    return custom_trip_err;
}

uint64_t nvs_trip_phone_clock_epoch(void)
{
    if (!s_mux) return 0;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    uint64_t epoch = s_phone_time_valid ? (uint64_t)time(NULL) : 0;
    xSemaphoreGive(s_mux);
    return epoch;
}

bool nvs_trip_phone_time_is_valid(void)
{
    /* Single-byte, write-once-per-boot status for UI indication. Deliberately
     * lock-free so the LVGL refresh path can never delay OBD/trip storage. */
    return s_phone_time_valid;
}

esp_err_t nvs_trip_apply_phone_time(uint64_t epoch_s,
                                    nvs_trip_time_sync_result_t *result)
{
    /* Reject corrupted packets and millisecond timestamps accidentally sent
       as seconds. Accepted range is 2024-01-01 through 2100-01-01 UTC. */
    if (epoch_s < 1704067200ULL || epoch_s > 4102444800ULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (!s_mux) return ESP_ERR_INVALID_STATE;

    struct timeval tv = {
        .tv_sec = (time_t)epoch_s,
        .tv_usec = 0,
    };
    if (settimeofday(&tv, NULL) != 0) return ESP_FAIL;

    nvs_trip_time_sync_result_t resolved = NVS_TRIP_TIME_SYNC_NO_PENDING;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    s_phone_time_valid = true;
    if (s_refuel.current_start_epoch_s < 1704067200ULL) {
        s_refuel.current_start_epoch_s = epoch_s;
        s_refuel_dirty = true;
    }

    uint64_t estimated_start = 0;
    uint64_t estimated_end = 0;
    bool estimated = trip_time_anchor_estimate(&s_trip_time_anchor, epoch_s,
                                                esp_timer_get_time(),
                                                s_fuel.active_duration_ms,
                                                &estimated_start, &estimated_end);
    if (estimated && !s_active_contains_pending) {
        /* Recompute on every later phone sync, so a delayed first sync and a
           corrected phone clock both repair this boot's estimated boundary. */
        s_fuel.active_start_epoch_s = estimated_start;
        s_fuel.active_last_epoch_s = estimated_end;
    } else if (s_fuel.active_start_epoch_s < 1704067200ULL &&
               s_fuel.active_duration_ms > 0) {
        uint64_t duration_s = s_fuel.active_duration_ms / 1000ULL;
        s_fuel.active_start_epoch_s = epoch_s > duration_s ? epoch_s - duration_s : epoch_s;
    }

    if (s_fuel.pending_valid) {
        /* A delayed phone connection may arrive many minutes after driving
           resumed. Compare the reconstructed session start, not epoch_s, or
           the connection delay is incorrectly counted as a power-off gap. */
        uint64_t current_start = s_fuel.active_start_epoch_s;
        if (current_start < 1704067200ULL) {
            uint64_t uptime_s = (uint64_t)esp_timer_get_time() / 1000000ULL;
            current_start = epoch_s > uptime_s ? epoch_s - uptime_s : epoch_s;
        }
        bool current_active_has_data = s_fuel.active_duration_ms ||
                                       s_fuel.active_distance_mm ||
                                       s_fuel.active_fuel_ul;
        bool can_merge = trip_time_gap_within(s_fuel.pending_last_epoch_s,
                                              current_start,
                                              s_cfg.trip_merge_timeout_min);
        if (can_merge) {
            s_fuel.active_duration_ms += s_fuel.pending_duration_ms;
            s_fuel.active_distance_mm += s_fuel.pending_distance_mm;
            s_fuel.active_fuel_ul += s_fuel.pending_fuel_ul;
            trip_detail_merge(&s_fuel.active_detail, &s_fuel.pending_detail);
            if (s_fuel.pending_start_epoch_s >= 1704067200ULL) {
                s_fuel.active_start_epoch_s = s_fuel.pending_start_epoch_s;
            }
            /* A quick gauge reboot with no subsequent engine start has no new
             * monotonic anchor.  Keep the persisted pre-reboot ignition-off
             * boundary instead of replacing it with phone-sync time. */
            if (!current_active_has_data &&
                s_fuel.pending_last_epoch_s >= 1704067200ULL) {
                s_fuel.active_last_epoch_s = s_fuel.pending_last_epoch_s;
            }
            s_active_contains_pending = true;
            resolved = NVS_TRIP_TIME_SYNC_MERGED;
        } else {
            fuel_finish_pending_trip();
            resolved = NVS_TRIP_TIME_SYNC_NEW_TRIP;
        }
        fuel_clear_pending();
    }
    if ((!estimated && !s_active_contains_pending) ||
        s_fuel.active_last_epoch_s < 1704067200ULL) {
        s_fuel.active_last_epoch_s = epoch_s;
    }
    /* Never emit the inconsistency observed in legacy record #4: the wall
       interval must be at least the accumulated engine-running duration. */
    if (s_fuel.active_start_epoch_s >= 1704067200ULL &&
        s_fuel.active_last_epoch_s >= s_fuel.active_start_epoch_s) {
        uint64_t duration_s = s_fuel.active_duration_ms / 1000ULL;
        if (s_fuel.active_last_epoch_s - s_fuel.active_start_epoch_s < duration_s) {
            s_fuel.active_start_epoch_s = s_fuel.active_last_epoch_s > duration_s
                                        ? s_fuel.active_last_epoch_s - duration_s
                                        : s_fuel.active_last_epoch_s;
        }
    }
    s_fuel_dirty = true;
    xSemaphoreGive(s_mux);

    if (result) *result = resolved;
    /* Do not write flash from the Bluedroid GATTS callback. The regular
       one-minute/stop-edge checkpoint persists both fuel and sync state.
       This keeps the phone link from starving the simultaneous ELM327 link. */
    return ESP_OK;
}

void nvs_trip_sync_get_meta(nvs_trip_sync_meta_t *out)
{
    if (!out) return;
    memset(out, 0, sizeof(*out));
    out->protocol_version = TRIP_SYNC_PROTOCOL_VERSION;
    if (!s_mux) return;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    /* Protocol 3 retains a rolling history for phones with independent local
       cursors.  pending_count keeps its legacy meaning so protocol 1/2 clients
       still stop after their gauge-wide ACK cursor reaches the newest record. */
    for (uint8_t i = 0; i < s_trip_sync.count; ++i) {
        if (s_trip_sync.records[i].id > s_trip_sync.last_acked_id &&
            out->pending_count < UINT8_MAX) {
            out->pending_count++;
        }
    }
    out->overflowed = s_trip_sync.overflowed != 0;
    out->last_acked_id = s_trip_sync.last_acked_id;
    if (s_trip_sync.count) {
        out->oldest_id = s_trip_sync.records[0].id;
        out->newest_id = s_trip_sync.records[s_trip_sync.count - 1U].id;
    }
    xSemaphoreGive(s_mux);
}

size_t nvs_trip_sync_read_after(uint32_t after_id,
                                nvs_trip_sync_record_t *out,
                                nvs_trip_detail_t *detail_out,
                                size_t max_records)
{
    if (!out || max_records == 0 || !s_mux) return 0;
    size_t copied = 0;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    for (uint8_t i = 0; i < s_trip_sync.count && copied < max_records; ++i) {
        if (s_trip_sync.records[i].id > after_id) {
            out[copied] = s_trip_sync.records[i];
            if (detail_out) detail_out[copied] = s_trip_sync.details[i];
            copied++;
        }
    }
    xSemaphoreGive(s_mux);
    return copied;
}

esp_err_t nvs_trip_sync_ack(uint32_t up_to_id)
{
    if (!s_mux) return ESP_ERR_INVALID_STATE;
    xSemaphoreTake(s_mux, portMAX_DELAY);
    /* Do not let a malformed client ACK a future ID: that would make all
       subsequently-created trips appear older than the persisted cursor. */
    uint32_t newest_id = s_trip_sync.count
                         ? s_trip_sync.records[s_trip_sync.count - 1U].id
                         : s_trip_sync.last_acked_id;
    if (up_to_id < s_trip_sync.last_acked_id || up_to_id > newest_id) {
        xSemaphoreGive(s_mux);
        return ESP_ERR_INVALID_ARG;
    }
    /* Do not delete acknowledged records.  The queue is now a retained ring:
       trip_sync_enqueue() evicts only the oldest record when capacity is
       reached, allowing another phone to resume from its own local cursor. */
    if (up_to_id > s_trip_sync.last_acked_id) s_trip_sync.last_acked_id = up_to_id;
    s_trip_sync_dirty = true;
    xSemaphoreGive(s_mux);
    /* ACK is idempotent: defer its flash write to the normal checkpoint so
       a phone connection never blocks the Bluetooth host task. If power is
       lost first, the record is simply sent to the phone again and upserted. */
    return ESP_OK;
}

/* Helpers */
static esp_err_t load_blob(const char *ns,const char *key,void *out,size_t len)
{
    nvs_handle_t h; esp_err_t err;
    if(nvs_open(ns,NVS_READONLY,&h)==ESP_OK){
        // Query the actual stored length first
        size_t stored_len = 0;
        err = nvs_get_blob(h, key, NULL, &stored_len);
        if(err == ESP_OK && stored_len > 0){
            size_t copy_len = (stored_len < len) ? stored_len : len;
            // Partial load: copy what exists; new trailing fields keep their static defaults
            err = nvs_get_blob(h, key, out, &copy_len);
            nvs_close(h);
            if(err == ESP_OK){
                if(stored_len < len){
                    // struct grew; rewrite NVS at the new size (loaded old fields + defaulted new fields)
                    save_blob(ns, key, out, len);
                }
                return ESP_OK;
            }
            return err;
        }
        nvs_close(h);
    }
    memset(out,0,len);
    return save_blob(ns,key,out,len);
}

static esp_err_t save_blob(const char *ns,const char *key,const void *data,size_t len)
{
    nvs_handle_t h; esp_err_t err=nvs_open(ns,NVS_READWRITE,&h);
    if(err!=ESP_OK) return err;
    err=nvs_set_blob(h,key,data,len);
    if(err==ESP_OK) err=nvs_commit(h);
    nvs_close(h);
    return err;
}
