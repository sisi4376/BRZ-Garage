#include "racechrono_ble_diy.h"

#include <string.h>
#include <inttypes.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_log.h"
#include "esp_err.h"
#include "esp_timer.h"
#include "esp_bt_main.h"
#include "esp_bt_defs.h"
#include "esp_gatts_api.h"
#include "esp_gatt_common_api.h"
#include "esp_bt.h"
#include "esp_mac.h"

#include "app_obd_dsp/obd_data_cache.h"
#include "app_obd_dsp/device_identity.h"
#include "app_obd_dsp/ota_update_ble.h"
#include "app_obd_dsp/app_event.h"
#include "app_obd_dsp/vehicle_profiles.h"
#include "bsp_obd_dsp/nvs_storage.h"
#include "bsp_obd_dsp/espnow_link.h"
#include "bsp_obd_dsp/gauge_pair_ble_client.h"   // GAUGE_PAIR_SERVICE_UUID / GAUGE_PAIR_CHAR_MAC (shared with the slave-gauge pairing client)

#define RC_TAG "racechrono_diy"

#define RC_APP_ID 0x52
#define RC_DEVICE_NAME "SkyGarageRC"

#define RC_SERVICE_UUID  0x1FF8
#define RC_CHAR_CAN_MAIN 0x0001
#define RC_CHAR_FILTER   0x0002

#define DEVICE_INFO_SERVICE_UUID 0x1FFA
#define DEVICE_INFO_CHAR_MANIFEST 0x0001
#define DEVICE_INFO_CHAR_TIME_SYNC 0x0002
#define DEVICE_INFO_CHAR_TRIP_META 0x0003
#define DEVICE_INFO_CHAR_TRIP_CONTROL 0x0004
#define DEVICE_INFO_CHAR_TRIP_DATA 0x0005
#define DEVICE_INFO_CHAR_VEHICLE_STATE 0x0006
#define DEVICE_INFO_CHAR_GAUGE_SETTINGS 0x0007
#define DEVICE_INFO_CHAR_BRIGHTNESS_CONTROL 0x0008
#define DEVICE_INFO_CHAR_VEHICLE_PROFILE_CONTROL 0x0009
#define DEVICE_INFO_CHAR_ODOMETER_CONFIG 0x000A
#define DEVICE_INFO_CHAR_REFUEL_META 0x000B
#define DEVICE_INFO_CHAR_REFUEL_CONTROL 0x000C
#define DEVICE_INFO_CHAR_REFUEL_DATA 0x000D
#define DEVICE_INFO_CHAR_CUSTOM_TRIP_CONTROL 0x000E
#define VEHICLE_STATE_WIRE_SIZE 84
#define GAUGE_SETTINGS_WIRE_SIZE 106
#define BRIGHTNESS_CONTROL_WIRE_SIZE 6
#define VEHICLE_PROFILE_CONTROL_WIRE_SIZE 6
#define ODOMETER_CONFIG_WIRE_SIZE 12
#define TRIP_META_WIRE_SIZE 20
#define TRIP_DATA_WIRE_SIZE 48
#define REFUEL_META_WIRE_SIZE 36
#define REFUEL_CONTROL_WIRE_SIZE 10
#define REFUEL_DATA_WIRE_SIZE 48
#define CUSTOM_TRIP_CONTROL_WIRE_SIZE 20
/* The HarmonyOS app intentionally stays on the default ATT MTU (23). An ATT
 * Read/Read Blob response can therefore carry at most MTU-1 = 22 value bytes.
 * Bluedroid does not fragment an app-generated response for us: passing the
 * whole 48/64-byte value merely truncates it and makes the phone reconnect. */
#define ATT_SAFE_READ_CHUNK 22

#define RC_CH_MAX 9
#define RC_PID_RULE_MAX 24

typedef struct {
    uint32_t pid;
    uint16_t interval_ms;
    int64_t last_sent_us;
} rc_pid_rule_t;

typedef struct {
    uint32_t pid;
    int32_t (*read_scaled)(bool *valid);
} rc_chan_t;

static esp_gatt_if_t s_gatts_if = ESP_GATT_IF_NONE;
static uint16_t s_conn_id = 0xFFFF;
static bool s_connected = false;
static bool s_notify_enabled = false;
static bool s_started = false;
static bool s_rc_enabled = true;          // when false, skip RC (0x1FF8) + Pair (0x1FF9), only create Info (0x1FFA) + OTA (0x1FFB)
static bool s_ota_mode = false;           // when true, advertise the OTA advert (Info+OTA UUIDs); entered from the OTA mode screen
static bool s_adv_config_done = false;
static bool s_attr_ready = false;
static bool s_adv_start_pending = false;
static bool s_adv_cfg_pending = false;
static bool s_adv_raw_done = false;
static bool s_scan_rsp_raw_done = false;
static bool s_time_attr_ready = false;

static bool s_allow_all = true;
static uint16_t s_allow_all_interval_ms = 100;
static rc_pid_rule_t s_rules[RC_PID_RULE_MAX];
static uint8_t s_rule_count = 0;

static uint16_t s_handle_can_main = 0;
static uint16_t s_handle_can_cccd = 0;
static uint16_t s_handle_filter = 0;
static int64_t s_last_send_err_log_us = 0;

static TaskHandle_t s_stream_task = NULL;
static int64_t s_last_all_sent_us[RC_CH_MAX] = {0};
static obd_data_snapshot_t s_stream_snapshot;

// Stable virtual CAN IDs: keep backward compatibility for RaceChrono formulas.
#define RC_PID_RPM          0x0000F101u
#define RC_PID_SPEED_KMH    0x0000F102u
#define RC_PID_CLT_C        0x0000F103u
#define RC_PID_IAT_C        0x0000F104u
#define RC_PID_OIL_C        0x0000F105u
#define RC_PID_LOAD_X10     0x0000F106u
#define RC_PID_TPS_X10      0x0000F107u
#define RC_PID_BAT_MV       0x0000F108u
#define RC_PID_BRAKE_X10    0x0000F109u

enum {
    IDX_SVC,
    IDX_CHAR_CAN_MAIN,
    IDX_CHAR_VAL_CAN_MAIN,
    IDX_CHAR_CFG_CAN_MAIN,
    IDX_CHAR_FILTER,
    IDX_CHAR_VAL_FILTER,
    IDX_NB,
};

// The pairing service uses its own attribute table (a separate create_attr_tab call), so it is
// an independently discoverable Primary Service in the peer's GATT database, with boundaries
// not mixed with the RaceChrono service.
enum {
    IDX_PAIR_SVC,
    IDX_PAIR_CHAR_MAC,
    IDX_PAIR_CHAR_VAL_MAC,
    IDX_PAIR_NB,
};

enum {
    IDX_INFO_SVC,
    IDX_INFO_CHAR_MANIFEST,
    IDX_INFO_CHAR_VAL_MANIFEST,
    IDX_INFO_CHAR_TIME_SYNC,
    IDX_INFO_CHAR_VAL_TIME_SYNC,
    IDX_INFO_CHAR_TRIP_META,
    IDX_INFO_CHAR_VAL_TRIP_META,
    IDX_INFO_CHAR_TRIP_CONTROL,
    IDX_INFO_CHAR_VAL_TRIP_CONTROL,
    IDX_INFO_CHAR_TRIP_DATA,
    IDX_INFO_CHAR_VAL_TRIP_DATA,
    IDX_INFO_CHAR_VEHICLE_STATE,
    IDX_INFO_CHAR_VAL_VEHICLE_STATE,
    IDX_INFO_CHAR_GAUGE_SETTINGS,
    IDX_INFO_CHAR_VAL_GAUGE_SETTINGS,
    IDX_INFO_CHAR_BRIGHTNESS_CONTROL,
    IDX_INFO_CHAR_VAL_BRIGHTNESS_CONTROL,
    IDX_INFO_CHAR_VEHICLE_PROFILE_CONTROL,
    IDX_INFO_CHAR_VAL_VEHICLE_PROFILE_CONTROL,
    IDX_INFO_CHAR_ODOMETER_CONFIG,
    IDX_INFO_CHAR_VAL_ODOMETER_CONFIG,
    IDX_INFO_CHAR_REFUEL_META,
    IDX_INFO_CHAR_VAL_REFUEL_META,
    IDX_INFO_CHAR_REFUEL_CONTROL,
    IDX_INFO_CHAR_VAL_REFUEL_CONTROL,
    IDX_INFO_CHAR_REFUEL_DATA,
    IDX_INFO_CHAR_VAL_REFUEL_DATA,
    IDX_INFO_CHAR_CUSTOM_TRIP_CONTROL,
    IDX_INFO_CHAR_VAL_CUSTOM_TRIP_CONTROL,
    IDX_INFO_NB,
};

static const uint16_t s_attr_uuid_primary_service = ESP_GATT_UUID_PRI_SERVICE;
static const uint16_t s_attr_uuid_char_declare = ESP_GATT_UUID_CHAR_DECLARE;
static const uint16_t s_attr_uuid_char_client_cfg = ESP_GATT_UUID_CHAR_CLIENT_CONFIG;

static const uint8_t s_char_prop_read_notify = ESP_GATT_CHAR_PROP_BIT_READ | ESP_GATT_CHAR_PROP_BIT_NOTIFY;
static const uint8_t s_char_prop_write = ESP_GATT_CHAR_PROP_BIT_WRITE;
static const uint8_t s_char_prop_read = ESP_GATT_CHAR_PROP_BIT_READ;
static const uint8_t s_char_prop_time_write = ESP_GATT_CHAR_PROP_BIT_WRITE | ESP_GATT_CHAR_PROP_BIT_READ;

static uint16_t s_service_uuid = RC_SERVICE_UUID;
static uint16_t s_char_uuid_can_main = RC_CHAR_CAN_MAIN;
static uint16_t s_char_uuid_filter = RC_CHAR_FILTER;
static uint16_t s_cccd_init = 0x0000;
static uint8_t s_dummy_val[20] = {0};

static uint16_t s_pair_service_uuid = GAUGE_PAIR_SERVICE_UUID;
static uint16_t s_char_uuid_pair_mac = GAUGE_PAIR_CHAR_MAC;
static uint8_t s_pair_mac[6] = {0};   // this device's ESP-NOW/WiFi MAC; only meaningful in the MASTER role

static uint16_t s_info_service_uuid = DEVICE_INFO_SERVICE_UUID;
static uint16_t s_char_uuid_manifest = DEVICE_INFO_CHAR_MANIFEST;
static uint16_t s_char_uuid_time_sync = DEVICE_INFO_CHAR_TIME_SYNC;
static uint16_t s_char_uuid_trip_meta = DEVICE_INFO_CHAR_TRIP_META;
static uint16_t s_char_uuid_trip_control = DEVICE_INFO_CHAR_TRIP_CONTROL;
static uint16_t s_char_uuid_trip_data = DEVICE_INFO_CHAR_TRIP_DATA;
static uint16_t s_char_uuid_vehicle_state = DEVICE_INFO_CHAR_VEHICLE_STATE;
static uint16_t s_handle_vehicle_state;
static uint8_t s_vehicle_state_value[VEHICLE_STATE_WIRE_SIZE];
static uint16_t s_char_uuid_gauge_settings = DEVICE_INFO_CHAR_GAUGE_SETTINGS;
static uint16_t s_handle_gauge_settings;
static uint8_t s_gauge_settings_value[GAUGE_SETTINGS_WIRE_SIZE];
static uint16_t s_char_uuid_brightness_control = DEVICE_INFO_CHAR_BRIGHTNESS_CONTROL;
static uint16_t s_handle_brightness_control;
static uint8_t s_brightness_control_value[BRIGHTNESS_CONTROL_WIRE_SIZE];
static uint16_t s_char_uuid_vehicle_profile_control = DEVICE_INFO_CHAR_VEHICLE_PROFILE_CONTROL;
static uint16_t s_handle_vehicle_profile_control;
static uint8_t s_vehicle_profile_control_value[VEHICLE_PROFILE_CONTROL_WIRE_SIZE];
static uint16_t s_char_uuid_odometer_config = DEVICE_INFO_CHAR_ODOMETER_CONFIG;
static uint16_t s_handle_odometer_config;
static uint8_t s_odometer_config_value[ODOMETER_CONFIG_WIRE_SIZE];
static uint16_t s_char_uuid_refuel_meta = DEVICE_INFO_CHAR_REFUEL_META;
static uint16_t s_char_uuid_refuel_control = DEVICE_INFO_CHAR_REFUEL_CONTROL;
static uint16_t s_char_uuid_refuel_data = DEVICE_INFO_CHAR_REFUEL_DATA;
static uint16_t s_handle_refuel_meta;
static uint16_t s_handle_refuel_control;
static uint16_t s_handle_refuel_data;
static uint8_t s_refuel_meta_value[REFUEL_META_WIRE_SIZE];
static uint8_t s_refuel_control_value[REFUEL_CONTROL_WIRE_SIZE];
static uint8_t s_refuel_data_value[REFUEL_DATA_WIRE_SIZE];
static uint32_t s_refuel_request_after_id;
static uint16_t s_char_uuid_custom_trip_control = DEVICE_INFO_CHAR_CUSTOM_TRIP_CONTROL;
static uint16_t s_handle_custom_trip_control;
static uint8_t s_custom_trip_control_value[CUSTOM_TRIP_CONTROL_WIRE_SIZE];
static uint8_t s_manifest_blob[512] = {0};
static uint16_t s_manifest_len = 0;
static uint16_t s_handle_manifest = 0;
static uint16_t s_handle_time_sync = 0;
static uint16_t s_handle_trip_meta = 0;
static uint16_t s_handle_trip_control = 0;
static uint16_t s_handle_trip_data = 0;
static uint8_t s_time_sync_value[8] = {0};
static uint8_t s_trip_meta_value[TRIP_META_WIRE_SIZE] = {0};
static uint8_t s_trip_control_value[5] = {0};
static uint8_t s_trip_data_value[TRIP_DATA_WIRE_SIZE] = {0};
static uint32_t s_trip_request_after_id = 0;
static uint32_t s_trip_highest_sent_id = 0;

static void put_le16(uint8_t *dst, uint16_t value)
{
    dst[0] = (uint8_t)value;
    dst[1] = (uint8_t)(value >> 8);
}

static void put_le32(uint8_t *dst, uint32_t value)
{
    for (unsigned i = 0; i < 4; ++i) dst[i] = (uint8_t)(value >> (i * 8U));
}

static void put_le64(uint8_t *dst, uint64_t value)
{
    for (unsigned i = 0; i < 8; ++i) dst[i] = (uint8_t)(value >> (i * 8U));
}

static uint32_t get_le32(const uint8_t *src)
{
    return (uint32_t)src[0] | ((uint32_t)src[1] << 8) |
           ((uint32_t)src[2] << 16) | ((uint32_t)src[3] << 24);
}

static uint16_t get_le16(const uint8_t *src)
{
    return (uint16_t)src[0] | ((uint16_t)src[1] << 8);
}

static uint16_t trip_crc16(const uint8_t *data, size_t len)
{
    uint16_t crc = 0xFFFFU;
    for (size_t i = 0; i < len; ++i) {
        crc ^= (uint16_t)data[i] << 8;
        for (unsigned bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x8000U) ? (uint16_t)((crc << 1) ^ 0x1021U) : (uint16_t)(crc << 1);
        }
    }
    return crc;
}

static uint16_t build_trip_meta_wire(void)
{
    nvs_trip_sync_meta_t meta;
    nvs_trip_sync_get_meta(&meta);
    memset(s_trip_meta_value, 0, sizeof(s_trip_meta_value));
    s_trip_meta_value[0] = meta.protocol_version;
    s_trip_meta_value[1] = meta.pending_count;
    s_trip_meta_value[2] = meta.overflowed ? 1U : 0U;
    put_le32(&s_trip_meta_value[4], meta.oldest_id);
    put_le32(&s_trip_meta_value[8], meta.newest_id);
    put_le32(&s_trip_meta_value[12], meta.last_acked_id);
    put_le16(&s_trip_meta_value[16], NVS_TRIP_SYNC_QUEUE_MAX);
    put_le16(&s_trip_meta_value[18], TRIP_DATA_WIRE_SIZE);
    return sizeof(s_trip_meta_value);
}

static uint16_t build_vehicle_state_wire(void)
{
    nvs_fuel_snapshot_t fuel;
    fuel_sample_t engine;
    nvs_fuel_get_snapshot(&fuel);
    obd_data_get_fuel_sample(&engine);
    int16_t level = obd_data_get_fuel_level_x100();
    uint64_t epoch = nvs_trip_phone_clock_epoch();
    uint8_t *b = s_vehicle_state_value;
    memset(b, 0, VEHICLE_STATE_WIRE_SIZE);
    b[0] = 2;
    b[1] = (level >= 0 ? 1U : 0U) | (engine.rpm_valid && engine.speed_valid ? 2U : 0U) |
           (epoch ? 4U : 0U) | (fuel.lifetime_avg_valid ? 8U : 0U);
    put_le16(b + 2, VEHICLE_STATE_WIRE_SIZE);
    put_le64(b + 4, epoch);
    put_le32(b + 12, (uint32_t)(esp_timer_get_time() / 1000000LL));
    put_le16(b + 16, level >= 0 ? (uint16_t)level : 0xFFFFU);
    put_le16(b + 18, fuel.lifetime_avg_x100);
    put_le32(b + 20, fuel.current_distance_m);
    put_le32(b + 24, fuel.current_duration_s);
    put_le32(b + 28, fuel.current_fuel_ml);
    put_le64(b + 32, fuel.lifetime_distance_m);
    put_le64(b + 40, fuel.lifetime_duration_s);
    put_le64(b + 48, fuel.lifetime_fuel_ml);
    put_le16(b + 56, engine.rpm);
    put_le16(b + 58, engine.speed_kmh);
    put_le16(b + 60, fuel.current_detail.max_speed_kmh);
    put_le16(b + 62, fuel.current_detail.max_rpm);
    put_le16(b + 64, (uint16_t)fuel.current_detail.max_accel_x100);
    put_le16(b + 66, (uint16_t)fuel.current_detail.max_decel_x100);
    put_le64(b + 68, fuel.current_start_epoch_s);
    put_le32(b + 76, obd_data_get_fuel_level_sequence());
    put_le16(b + 82, trip_crc16(b, 82));
    return VEHICLE_STATE_WIRE_SIZE;
}

/* Build a read-only snapshot from the already-loaded RAM configuration. This
 * deliberately performs no NVS reads/writes and does not change any runtime
 * task, page, BLE-to-ELM327, or vehicle acquisition state. */
static uint16_t build_gauge_settings_wire(void)
{
    nvs_user_cfg_t cfg = *nvs_cfg_get();
    uint8_t *b = s_gauge_settings_value;
    memset(b, 0, GAUGE_SETTINGS_WIRE_SIZE);
    b[0] = 2;
    b[1] = 0x01U; /* bit 0: this protocol revision is read-only */
    b[1] |= 0x04U; /* bit 2: discard data before the oldest refuel node */
#if !TRIP_MERGE_TIMEOUT_EDITABLE
    b[1] |= 0x02U; /* bit 1: trip merge timeout is firmware-locked */
#endif
    put_le16(b + 2, GAUGE_SETTINGS_WIRE_SIZE);
    b[4] = cfg.protocol;
    b[5] = cfg.theme_cfg.theme;
    b[6] = cfg.default_page;
    b[7] = cfg.brightness_day;
    b[8] = cfg.vehicle_profile_idx;
    b[9] = cfg.device_role;
    b[10] = nvs_intro_enable_get();
    b[11] = nvs_device_position_get();
    b[12] = cfg.rpm_warn_anim_en;
    b[13] = cfg.rpm_warn_linked_en;
    b[14] = cfg.rc_enabled;
    b[15] = nvs_boot_mode_get();
    put_le16(b + 16, cfg.brake_temp_warn_c);
    put_le16(b + 18, cfg.oil_pressure_warn_x10);
    put_le16(b + 20, cfg.rpm_warn_threshold);
    put_le16(b + 22, cfg.trip_merge_timeout_min);
    memcpy(b + 24, cfg.temp_display_map, sizeof(cfg.temp_display_map));
    memcpy(b + 27, cfg.info_display_map, sizeof(cfg.info_display_map));
    b[32] = cfg.needle_source_idx;
    b[33] = cfg.chart_source_idx;
    memcpy(b + 34, cfg.ble_obd_mac, sizeof(cfg.ble_obd_mac));
    memcpy(b + 40, cfg.espnow_master_mac, sizeof(cfg.espnow_master_mac));
    memcpy(b + 46, cfg.ble_device_name, sizeof(cfg.ble_device_name));
    for (uint8_t item = 0; item < 12U; ++item) {
        put_le16(b + 78 + item * 2U, (uint16_t)nvs_chart_alarm_get(item));
    }
    put_le16(b + 102, cfg.refuel_detect_threshold_ml);
    put_le16(b + 104, trip_crc16(b, 104));
    return GAUGE_SETTINGS_WIRE_SIZE;
}

static uint16_t build_trip_data_wire(void)
{
    nvs_trip_sync_record_t record;
    nvs_trip_detail_t detail;
    if (nvs_trip_sync_read_after(s_trip_request_after_id, &record, &detail, 1) != 1) return 0;
    if (record.id > s_trip_highest_sent_id) s_trip_highest_sent_id = record.id;
    memset(s_trip_data_value, 0, sizeof(s_trip_data_value));
    s_trip_data_value[0] = 2;
    s_trip_data_value[1] = (uint8_t)record.flags;
    put_le16(&s_trip_data_value[2], TRIP_DATA_WIRE_SIZE);
    put_le32(&s_trip_data_value[4], record.id);
    put_le64(&s_trip_data_value[8], record.start_epoch_s);
    put_le64(&s_trip_data_value[16], record.end_epoch_s);
    put_le32(&s_trip_data_value[24], record.duration_s);
    put_le32(&s_trip_data_value[28], record.distance_m);
    put_le32(&s_trip_data_value[32], record.fuel_ml);
    put_le16(&s_trip_data_value[36], record.avg_l100_x100);
    put_le16(&s_trip_data_value[38], detail.max_speed_kmh);
    put_le16(&s_trip_data_value[40], detail.max_rpm);
    put_le16(&s_trip_data_value[42], (uint16_t)detail.max_accel_x100);
    put_le16(&s_trip_data_value[44], (uint16_t)detail.max_decel_x100);
    put_le16(&s_trip_data_value[46], trip_crc16(s_trip_data_value, 46));
    return sizeof(s_trip_data_value);
}

static uint16_t build_odometer_config_wire(void)
{
    nvs_odometer_snapshot_t snapshot;
    nvs_odometer_get_snapshot(&snapshot);
    uint8_t *b = s_odometer_config_value;
    memset(b, 0, sizeof(s_odometer_config_value));
    b[0] = 1;
    b[1] = (snapshot.display_enabled ? 0x01U : 0U) |
           (snapshot.calibrated ? 0x02U : 0U);
    put_le16(b + 2, ODOMETER_CONFIG_WIRE_SIZE);
    put_le32(b + 4, snapshot.odometer_x10_km);
    put_le16(b + 10, trip_crc16(b, 10));
    return sizeof(s_odometer_config_value);
}

static uint16_t build_refuel_meta_wire(void)
{
    nvs_refuel_meta_t meta;
    nvs_refuel_get_meta(&meta);
    uint8_t *b = s_refuel_meta_value;
    memset(b, 0, sizeof(s_refuel_meta_value));
    b[0] = meta.protocol_version;
    b[1] = meta.count;
    put_le16(b + 2, REFUEL_META_WIRE_SIZE);
    put_le32(b + 4, meta.oldest_id);
    put_le32(b + 8, meta.newest_id);
    put_le64(b + 12, meta.current_start_epoch_s);
    put_le32(b + 20, meta.current_duration_s);
    put_le32(b + 24, meta.current_distance_m);
    put_le32(b + 28, meta.current_fuel_ml);
    put_le16(b + 32, meta.history_revision);
    put_le16(b + 34, trip_crc16(b, 34));
    return sizeof(s_refuel_meta_value);
}

static uint16_t build_refuel_data_wire(void)
{
    nvs_refuel_record_t record;
    if (nvs_refuel_read_after(s_refuel_request_after_id, &record, 1) != 1) return 0;
    uint8_t *b = s_refuel_data_value;
    memset(b, 0, sizeof(s_refuel_data_value));
    b[0] = 1;
    b[1] = (uint8_t)record.flags;
    put_le16(b + 2, REFUEL_DATA_WIRE_SIZE);
    put_le32(b + 4, record.id);
    put_le64(b + 8, record.start_epoch_s);
    put_le64(b + 16, record.end_epoch_s);
    put_le32(b + 24, record.duration_s);
    put_le32(b + 28, record.distance_m);
    put_le32(b + 32, record.fuel_ml);
    put_le32(b + 36, record.odometer_x10_km);
    put_le16(b + 40, record.detected_added_ml);
    put_le16(b + 46, trip_crc16(b, 46));
    return sizeof(s_refuel_data_value);
}

// Advertising/GAP device name: the MASTER role advertises "SkyGauge-XXYY" (for slave-gauge pairing discovery), otherwise keeps "SkyGarageRC" (for the RaceChrono phone app).
static char s_adv_name[20] = RC_DEVICE_NAME;

static uint8_t s_adv_raw[] = {
    0x02, 0x01, 0x06,                    // Flags: LE General Discoverable + BR/EDR not supported
    0x07, 0x03, 0xF8, 0x1F, 0xF9, 0x1F, 0xFA, 0x1F // RC, Pair, phone Info
};
// OTA advert is built at runtime (the name varies by role): Flags + Info/OTA UUIDs +
// Complete Local Name. The name lives in the MAIN advert packet (not only in the scan
// response) so every phone OS / Web Bluetooth scanner can match namePrefix filters
// reliably — some stacks never merge scan-response data into the filter.
static uint8_t s_adv_raw_ota[31];
static uint8_t s_adv_raw_ota_len = 0;
static uint8_t s_scan_rsp_raw[31] = {0};

static void prepare_device_info_manifest(void)
{
    const char *json = device_identity_manifest_json();
    size_t len = strnlen(json, sizeof(s_manifest_blob) - 1);
    memcpy(s_manifest_blob, json, len);
    s_manifest_blob[len] = '\0';
    s_manifest_len = (uint16_t)len;
}

// Decide the advertising name and pairing MAC characteristic content based on the current device_role; called once before the startup flow / role change.
static void build_adv_identity(void)
{
    const nvs_user_cfg_t *cfg = nvs_cfg_get();
    if (cfg->device_role == ESPNOW_ROLE_MASTER) {
        uint8_t mac[6] = {0};
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        memcpy(s_pair_mac, mac, sizeof(s_pair_mac));
        snprintf(s_adv_name, sizeof(s_adv_name), "SkyGauge-%02X%02X", mac[4], mac[5]);
    } else {
        memset(s_pair_mac, 0, sizeof(s_pair_mac));
        strncpy(s_adv_name, RC_DEVICE_NAME, sizeof(s_adv_name) - 1);
        s_adv_name[sizeof(s_adv_name) - 1] = '\0';
    }
}

static esp_ble_adv_params_t s_adv_params = {
    .adv_int_min = 0x320, // 500-750 ms: stay discoverable without a boot-only window
    .adv_int_max = 0x4B0,
    .adv_type = ADV_TYPE_IND,
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .channel_map = ADV_CHNL_ALL,
    .adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
};

// Build scan response with complete local name.
// AD format: [len][type=0x09][name bytes...]
static uint32_t build_scan_rsp_with_name(void)
{
    size_t name_len = strlen(s_adv_name);
    if (name_len > 29) {
        name_len = 29;
    }
    memset(s_scan_rsp_raw, 0, sizeof(s_scan_rsp_raw));
    s_scan_rsp_raw[0] = (uint8_t)(1 + name_len);
    s_scan_rsp_raw[1] = 0x09;
    memcpy(&s_scan_rsp_raw[2], s_adv_name, name_len);
    return (uint32_t)(2 + name_len);
}

// Build the OTA advert: Flags + Info/OTA service UUIDs + Complete Local Name.
static void build_ota_adv_data(void)
{
    size_t name_len = strlen(s_adv_name);
    if (name_len > 20) {
        name_len = 20;   // keep total <= 31: 3 (flags) + 6 (uuid list) + 2 (name header) + name
    }
    uint8_t *p = s_adv_raw_ota;
    *p++ = 0x02; *p++ = 0x01; *p++ = 0x06;          // Flags
    *p++ = 0x05; *p++ = 0x03;                        // Complete List of 16-bit Service UUIDs
    *p++ = 0xFA; *p++ = 0x1F;                        // 0x1FFA device info
    *p++ = 0xFB; *p++ = 0x1F;                        // 0x1FFB OTA
    *p++ = (uint8_t)(1 + name_len); *p++ = 0x09;     // Complete Local Name
    memcpy(p, s_adv_name, name_len);
    p += name_len;
    s_adv_raw_ota_len = (uint8_t)(p - s_adv_raw_ota);
}

static void request_adv_config(void)
{
    uint32_t scan_rsp_len = build_scan_rsp_with_name();

    s_adv_raw_done = false;
    s_scan_rsp_raw_done = false;
    s_adv_config_done = false;

    const uint8_t *adv_data;
    uint32_t adv_len;
    if (s_ota_mode || !s_rc_enabled) {
        build_ota_adv_data();
        adv_data = s_adv_raw_ota;
        adv_len = s_adv_raw_ota_len;
    } else {
        adv_data = s_adv_raw;
        adv_len = sizeof(s_adv_raw);
    }

    esp_err_t err_adv = esp_ble_gap_config_adv_data_raw((uint8_t *)adv_data, adv_len);
    esp_err_t err_rsp = esp_ble_gap_config_scan_rsp_data_raw(s_scan_rsp_raw, scan_rsp_len);

    if (err_adv == ESP_OK && err_rsp == ESP_OK) {
        s_adv_cfg_pending = false;
        return;
    }

    s_adv_cfg_pending = true;
    ESP_LOGW(RC_TAG, "Config adv raw deferred: adv=%s scan_rsp=%s",
             esp_err_to_name(err_adv), esp_err_to_name(err_rsp));
}

static const esp_gatts_attr_db_t s_gatt_db[IDX_NB] = {
    [IDX_SVC] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_primary_service, ESP_GATT_PERM_READ,
      sizeof(uint16_t), sizeof(s_service_uuid), (uint8_t *)&s_service_uuid}},

    [IDX_CHAR_CAN_MAIN] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_read_notify}},

    [IDX_CHAR_VAL_CAN_MAIN] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_can_main, ESP_GATT_PERM_READ,
      sizeof(s_dummy_val), sizeof(uint8_t), s_dummy_val}},

    [IDX_CHAR_CFG_CAN_MAIN] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_client_cfg,
      ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE,
      sizeof(uint16_t), sizeof(uint16_t), (uint8_t *)&s_cccd_init}},

    [IDX_CHAR_FILTER] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_write}},

    [IDX_CHAR_VAL_FILTER] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_filter, ESP_GATT_PERM_WRITE,
      sizeof(s_dummy_val), sizeof(uint8_t), s_dummy_val}},
};

// ---- Pairing service (separate attribute table; s_pair_mac is valid only in the MASTER role, read by the slave gauge during BLE pairing) ----
static const esp_gatts_attr_db_t s_gatt_db_pair[IDX_PAIR_NB] = {
    [IDX_PAIR_SVC] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_primary_service, ESP_GATT_PERM_READ,
      sizeof(uint16_t), sizeof(s_pair_service_uuid), (uint8_t *)&s_pair_service_uuid}},

    [IDX_PAIR_CHAR_MAC] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_read}},

    [IDX_PAIR_CHAR_VAL_MAC] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_pair_mac, ESP_GATT_PERM_READ,
      sizeof(s_pair_mac), sizeof(s_pair_mac), s_pair_mac}},
};

// ---- Device identity manifest (read-only; the app uses this to validate hardware / firmware compatibility before updating) ----
static const esp_gatts_attr_db_t s_gatt_db_info[IDX_INFO_NB] = {
    [IDX_INFO_SVC] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_primary_service, ESP_GATT_PERM_READ,
      sizeof(uint16_t), sizeof(s_info_service_uuid), (uint8_t *)&s_info_service_uuid}},

    [IDX_INFO_CHAR_MANIFEST] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_read}},

    [IDX_INFO_CHAR_VAL_MANIFEST] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_manifest, ESP_GATT_PERM_READ,
      sizeof(s_manifest_blob), 0, s_manifest_blob}},

    [IDX_INFO_CHAR_TIME_SYNC] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_time_write}},

    [IDX_INFO_CHAR_VAL_TIME_SYNC] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_time_sync, ESP_GATT_PERM_WRITE | ESP_GATT_PERM_READ,
      sizeof(s_time_sync_value), sizeof(s_time_sync_value), s_time_sync_value}},

    [IDX_INFO_CHAR_TRIP_META] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_read}},

    [IDX_INFO_CHAR_VAL_TRIP_META] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_trip_meta, ESP_GATT_PERM_READ,
      sizeof(s_trip_meta_value), 0, s_trip_meta_value}},

    [IDX_INFO_CHAR_TRIP_CONTROL] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_write}},

    [IDX_INFO_CHAR_VAL_TRIP_CONTROL] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_trip_control, ESP_GATT_PERM_WRITE,
      sizeof(s_trip_control_value), 0, s_trip_control_value}},

    [IDX_INFO_CHAR_TRIP_DATA] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_read}},

    [IDX_INFO_CHAR_VAL_TRIP_DATA] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_trip_data, ESP_GATT_PERM_READ,
      sizeof(s_trip_data_value), 0, s_trip_data_value}},
    [IDX_INFO_CHAR_VEHICLE_STATE] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_read}},
    [IDX_INFO_CHAR_VAL_VEHICLE_STATE] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_vehicle_state, ESP_GATT_PERM_READ,
      sizeof(s_vehicle_state_value), 0, s_vehicle_state_value}},
    [IDX_INFO_CHAR_GAUGE_SETTINGS] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_read}},
    [IDX_INFO_CHAR_VAL_GAUGE_SETTINGS] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_gauge_settings, ESP_GATT_PERM_READ,
      sizeof(s_gauge_settings_value), 0, s_gauge_settings_value}},
    [IDX_INFO_CHAR_BRIGHTNESS_CONTROL] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_write}},
    [IDX_INFO_CHAR_VAL_BRIGHTNESS_CONTROL] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_brightness_control, ESP_GATT_PERM_WRITE,
      sizeof(s_brightness_control_value), 0, s_brightness_control_value}},
    [IDX_INFO_CHAR_VEHICLE_PROFILE_CONTROL] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_write}},
    [IDX_INFO_CHAR_VAL_VEHICLE_PROFILE_CONTROL] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_vehicle_profile_control, ESP_GATT_PERM_WRITE,
      sizeof(s_vehicle_profile_control_value), 0, s_vehicle_profile_control_value}},
    [IDX_INFO_CHAR_ODOMETER_CONFIG] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_time_write}},
    [IDX_INFO_CHAR_VAL_ODOMETER_CONFIG] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_odometer_config,
      ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE,
      sizeof(s_odometer_config_value), 0, s_odometer_config_value}},
    [IDX_INFO_CHAR_REFUEL_META] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_read}},
    [IDX_INFO_CHAR_VAL_REFUEL_META] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_refuel_meta, ESP_GATT_PERM_READ,
      sizeof(s_refuel_meta_value), 0, s_refuel_meta_value}},
    [IDX_INFO_CHAR_REFUEL_CONTROL] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_write}},
    [IDX_INFO_CHAR_VAL_REFUEL_CONTROL] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_refuel_control, ESP_GATT_PERM_WRITE,
      sizeof(s_refuel_control_value), 0, s_refuel_control_value}},
    [IDX_INFO_CHAR_REFUEL_DATA] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_read}},
    [IDX_INFO_CHAR_VAL_REFUEL_DATA] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_refuel_data, ESP_GATT_PERM_READ,
      sizeof(s_refuel_data_value), 0, s_refuel_data_value}},
    [IDX_INFO_CHAR_CUSTOM_TRIP_CONTROL] =
    {{ESP_GATT_AUTO_RSP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_attr_uuid_char_declare, ESP_GATT_PERM_READ,
      sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_char_prop_write}},
    [IDX_INFO_CHAR_VAL_CUSTOM_TRIP_CONTROL] =
    {{ESP_GATT_RSP_BY_APP},
     {ESP_UUID_LEN_16, (uint8_t *)&s_char_uuid_custom_trip_control, ESP_GATT_PERM_WRITE,
      sizeof(s_custom_trip_control_value), 0, s_custom_trip_control_value}},
};

static int32_t read_rpm(bool *valid)
{
    uint16_t v = s_stream_snapshot.rpm;
    *valid = (v > 0);
    return (int32_t)v;
}

static int32_t read_speed(bool *valid)
{
    uint8_t v = s_stream_snapshot.speed;
    *valid = true;
    return (int32_t)v;
}

static int32_t read_clt(bool *valid)
{
    int16_t v = s_stream_snapshot.coolant_temp;
    *valid = (v > -100);
    return (int32_t)v;
}

static int32_t read_iat(bool *valid)
{
    int16_t v = s_stream_snapshot.intake_temp;
    *valid = (v > -100);
    return (int32_t)v;
}

static int32_t read_oil(bool *valid)
{
    int16_t v = s_stream_snapshot.oil_temp;
    *valid = (v > -100);
    return (int32_t)v;
}

static int32_t read_load_x10(bool *valid)
{
    int16_t v = s_stream_snapshot.load_pct;
    *valid = (v >= 0);
    return (int32_t)(v * 10);
}

static int32_t read_tps_x10(bool *valid)
{
    int16_t v = s_stream_snapshot.tps;
    *valid = (v >= 0);
    return (int32_t)(v * 10);
}

static int32_t read_bat_mv(bool *valid)
{
    int32_t v = s_stream_snapshot.bat_mv;
    *valid = (v > 0);
    return v;
}

static int32_t read_brake_x10(bool *valid)
{
    int16_t v = s_stream_snapshot.brake_temp_x10;
    *valid = (v > -1000);
    return (int32_t)v;
}

static const rc_chan_t s_channels[RC_CH_MAX] = {
    {RC_PID_RPM, read_rpm},
    {RC_PID_SPEED_KMH, read_speed},
    {RC_PID_CLT_C, read_clt},
    {RC_PID_IAT_C, read_iat},
    {RC_PID_OIL_C, read_oil},
    {RC_PID_LOAD_X10, read_load_x10},
    {RC_PID_TPS_X10, read_tps_x10},
    {RC_PID_BAT_MV, read_bat_mv},
    {RC_PID_BRAKE_X10, read_brake_x10},
};

static inline void le32_store(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)(v & 0xFFu);
    p[1] = (uint8_t)((v >> 8) & 0xFFu);
    p[2] = (uint8_t)((v >> 16) & 0xFFu);
    p[3] = (uint8_t)((v >> 24) & 0xFFu);
}

static uint32_t be32_to_u32(const uint8_t *p)
{
    return ((uint32_t)p[0] << 24) |
           ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) |
           (uint32_t)p[3];
}

static bool is_known_pid(uint32_t pid)
{
    for (int i = 0; i < RC_CH_MAX; i++) {
        if (s_channels[i].pid == pid) {
            return true;
        }
    }
    return false;
}

static bool map_index_to_pid(uint32_t idx, uint32_t *out_pid)
{
    if (out_pid == NULL) {
        return false;
    }

    // Compatibility: some clients send channel index instead of virtual CAN PID.
    // Accept both 0-based [0..RC_CH_MAX-1] and 1-based [1..RC_CH_MAX].
    if (idx < RC_CH_MAX) {
        *out_pid = s_channels[idx].pid;
        return true;
    }
    if (idx >= 1 && idx <= RC_CH_MAX) {
        *out_pid = s_channels[idx - 1].pid;
        return true;
    }
    return false;
}

static bool normalize_client_pid(uint32_t pid_raw, uint32_t *out_pid)
{
    if (out_pid == NULL) {
        return false;
    }

    uint32_t pid_le = pid_raw;
    uint32_t pid_be = ((pid_raw & 0x000000FFu) << 24) |
                      ((pid_raw & 0x0000FF00u) << 8) |
                      ((pid_raw & 0x00FF0000u) >> 8) |
                      ((pid_raw & 0xFF000000u) >> 24);

    if (is_known_pid(pid_le)) {
        *out_pid = pid_le;
        return true;
    }
    if (is_known_pid(pid_be)) {
        *out_pid = pid_be;
        return true;
    }
    if (map_index_to_pid(pid_le, out_pid)) {
        return true;
    }
    if (map_index_to_pid(pid_be, out_pid)) {
        return true;
    }
    return false;
}

static void send_can_packet(uint32_t pid, int32_t value)
{
    if (!s_connected || !s_notify_enabled || s_handle_can_main == 0 || s_gatts_if == ESP_GATT_IF_NONE) {
        return;
    }

    uint8_t pkt[8];
    le32_store(pkt, pid);
    le32_store(pkt + 4, (uint32_t)value);
    esp_err_t err = esp_ble_gatts_send_indicate(s_gatts_if, s_conn_id, s_handle_can_main, sizeof(pkt), pkt, false);
    if (err != ESP_OK) {
        int64_t now_us = esp_timer_get_time();
        if ((now_us - s_last_send_err_log_us) > 1000000) {
            s_last_send_err_log_us = now_us;
            ESP_LOGW(RC_TAG, "send_indicate failed: %s", esp_err_to_name(err));
        }
    }
}

static bool should_send_by_rule(uint32_t pid, int channel_idx, int64_t now_us)
{
    uint16_t interval_ms = s_allow_all_interval_ms;
    rc_pid_rule_t *rule = NULL;

    if (!s_allow_all) {
        for (uint8_t i = 0; i < s_rule_count; i++) {
            if (s_rules[i].pid == pid) {
                rule = &s_rules[i];
                break;
            }
        }
        if (!rule) {
            return false;
        }
        interval_ms = rule->interval_ms;
    }

    if (interval_ms < 20) {
        interval_ms = 20;
    }

    int64_t *last_us = s_allow_all ? NULL : &rule->last_sent_us;

    if (s_allow_all) {
        if (channel_idx < 0 || channel_idx >= RC_CH_MAX) {
            return false;
        }
        if ((now_us - s_last_all_sent_us[channel_idx]) < ((int64_t)interval_ms * 1000)) {
            return false;
        }
        s_last_all_sent_us[channel_idx] = now_us;
        return true;
    }

    if ((now_us - *last_us) < ((int64_t)interval_ms * 1000)) {
        return false;
    }
    *last_us = now_us;
    return true;
}

static void process_filter_write(const uint8_t *buf, uint16_t len)
{
    if (len < 1 || buf == NULL) {
        return;
    }

    uint8_t cmd = buf[0];
    if (cmd == 0) {
        s_allow_all = false;
        s_rule_count = 0;
        s_trip_request_after_id = 0;
        s_trip_highest_sent_id = 0;
        ESP_LOGD(RC_TAG, "Filter: deny all");
        return;
    }

    if (cmd == 1) {
        if (len >= 3) {
            s_allow_all_interval_ms = (uint16_t)((buf[1] << 8) | buf[2]);
            if (s_allow_all_interval_ms == 0) {
                s_allow_all_interval_ms = 100;
            }
        }
        s_allow_all = true;
        s_rule_count = 0;
        ESP_LOGD(RC_TAG, "Filter: allow all, interval=%u ms", s_allow_all_interval_ms);
        return;
    }

    if (cmd == 2 && len >= 7) {
        uint16_t interval_ms = (uint16_t)((buf[1] << 8) | buf[2]);
        uint32_t pid_le = ((uint32_t)buf[3]) |
                          ((uint32_t)buf[4] << 8) |
                          ((uint32_t)buf[5] << 16) |
                          ((uint32_t)buf[6] << 24);
        uint32_t pid_be = be32_to_u32(&buf[3]);
        uint32_t pid = 0;

        if (!normalize_client_pid(pid_le, &pid)) {
            ESP_LOGW(RC_TAG, "Filter: unknown PID raw_le=0x%08" PRIX32 " raw_be=0x%08" PRIX32 ", ignore", pid_le, pid_be);
            return;
        }
        if (interval_ms == 0) {
            interval_ms = 100;
        }

        for (uint8_t i = 0; i < s_rule_count; i++) {
            if (s_rules[i].pid == pid) {
                s_rules[i].interval_ms = interval_ms;
                ESP_LOGD(RC_TAG, "Filter: update PID=0x%08" PRIX32 " interval=%u", pid, interval_ms);
                return;
            }
        }

        if (s_rule_count < RC_PID_RULE_MAX) {
            s_rules[s_rule_count].pid = pid;
            s_rules[s_rule_count].interval_ms = interval_ms;
            s_rules[s_rule_count].last_sent_us = 0;
            s_rule_count++;
            ESP_LOGD(RC_TAG, "Filter: allow PID=0x%08" PRIX32 " interval=%u", pid, interval_ms);
        }
    }
}

static void stream_task(void *arg)
{
    (void)arg;
    while (1) {
        if (s_connected && s_notify_enabled && s_attr_ready) {
            int64_t now_us = esp_timer_get_time();
            obd_data_get_snapshot(&s_stream_snapshot);
            for (int i = 0; i < RC_CH_MAX; i++) {
                bool valid = false;
                int32_t val = s_channels[i].read_scaled(&valid);
                if (!valid) {
                    continue;
                }
                if (!should_send_by_rule(s_channels[i].pid, i, now_us)) {
                    continue;
                }
                send_can_packet(s_channels[i].pid, val);
            }
        }
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

static void start_advertising_if_ready(void)
{
    if (!s_adv_config_done || !s_time_attr_ready || s_connected) {
        return;
    }

    esp_err_t err = esp_ble_gap_start_advertising(&s_adv_params);
    if (err == ESP_OK) {
        s_adv_start_pending = false;
        return;
    }

    // If controller is busy, just defer without stopping external scans
    s_adv_start_pending = true;
    ESP_LOGW(RC_TAG, "Start adv deferred: %s", esp_err_to_name(err));
}

static void claim_phone_connection(uint16_t conn_id)
{
    if (s_connected && s_conn_id == conn_id) return;
    s_trip_request_after_id = 0;
    s_trip_highest_sent_id = 0;
    s_connected = true;
    s_conn_id = conn_id;
    s_notify_enabled = false;
    s_allow_all = true;
    s_allow_all_interval_ms = 100;
    s_rule_count = 0;
    memset(s_last_all_sent_us, 0, sizeof(s_last_all_sent_us));
    s_last_send_err_log_us = 0;
    ESP_LOGI(RC_TAG, "Phone GATT service active: conn_id=0x%04X", conn_id);
}

static void gatts_cb(esp_gatts_cb_event_t event, esp_gatt_if_t gatts_if, esp_ble_gatts_cb_param_t *param)
{
    ota_update_ble_on_gatts_event(event, gatts_if, param);

    switch (event) {
    case ESP_GATTS_REG_EVT:
        s_gatts_if = gatts_if;
        build_adv_identity();
        {
            esp_err_t err = esp_ble_gap_set_device_name(s_adv_name);
            if (err != ESP_OK) {
                ESP_LOGW(RC_TAG, "Set device name failed: %s", esp_err_to_name(err));
            }
        }
        prepare_device_info_manifest();
        bool is_master = (nvs_cfg_get()->device_role == ESPNOW_ROLE_MASTER);
        if (s_rc_enabled) {
            // Full mode: create all 4 services (RC → Pair → Info → OTA)
            request_adv_config();
            esp_ble_gatts_create_attr_tab(s_gatt_db, gatts_if, IDX_NB, 0);
            ESP_LOGD(RC_TAG, "GATTS registered (adv name=%s), waiting for adv+attr table", s_adv_name);
        } else if (is_master) {
            // Master minimal mode: advertise with Pair service so slaves can discover and pair
            request_adv_config();
            esp_ble_gatts_create_attr_tab(s_gatt_db_pair, gatts_if, IDX_PAIR_NB, 1);
            ESP_LOGI(RC_TAG, "GATTS registered (Master mode: Pair+Info+OTA, adv on, name=%s)", s_adv_name);
        } else {
            // Remain discoverable when the phone arrives after boot.
            request_adv_config();
            esp_ble_gatts_create_attr_tab(s_gatt_db_info, gatts_if, IDX_INFO_NB, 2);
            ESP_LOGI(RC_TAG, "GATTS registered (minimal: Info+Time+OTA, persistent advert, name=%s)", s_adv_name);
        }
        break;

    case ESP_GATTS_CREAT_ATTR_TAB_EVT:
        if (param->add_attr_tab.status == ESP_GATT_OK &&
            param->add_attr_tab.num_handle == IDX_NB &&
            param->add_attr_tab.svc_uuid.uuid.uuid16 == RC_SERVICE_UUID) {
            uint16_t *h = param->add_attr_tab.handles;
            s_handle_can_main = h[IDX_CHAR_VAL_CAN_MAIN];
            s_handle_can_cccd = h[IDX_CHAR_CFG_CAN_MAIN];
            s_handle_filter = h[IDX_CHAR_VAL_FILTER];
            esp_ble_gatts_start_service(h[IDX_SVC]);
            s_attr_ready = true;
            ESP_LOGD(RC_TAG, "RC attr table ready, CAN main handle=0x%04X", s_handle_can_main);
            // After the RC service table is created, create the pairing service attribute table separately
            // (independent create_attr_tab calls, so both services are independently discoverable Primary Services in the peer's GATT database).
            esp_ble_gatts_create_attr_tab(s_gatt_db_pair, gatts_if, IDX_PAIR_NB, 1);
        } else if (param->add_attr_tab.status == ESP_GATT_OK &&
                   param->add_attr_tab.num_handle == IDX_PAIR_NB &&
                   param->add_attr_tab.svc_uuid.uuid.uuid16 == GAUGE_PAIR_SERVICE_UUID) {
            uint16_t *h = param->add_attr_tab.handles;
            esp_ble_gatts_start_service(h[IDX_PAIR_SVC]);
            ESP_LOGD(RC_TAG, "Pair attr table ready, service handle=0x%04X", h[IDX_PAIR_SVC]);
            esp_ble_gatts_create_attr_tab(s_gatt_db_info, gatts_if, IDX_INFO_NB, 2);
        } else if (param->add_attr_tab.status == ESP_GATT_OK &&
                   param->add_attr_tab.num_handle == IDX_INFO_NB &&
                   param->add_attr_tab.svc_uuid.uuid.uuid16 == DEVICE_INFO_SERVICE_UUID) {
            uint16_t *h = param->add_attr_tab.handles;
            s_handle_manifest = h[IDX_INFO_CHAR_VAL_MANIFEST];
            s_handle_time_sync = h[IDX_INFO_CHAR_VAL_TIME_SYNC];
            s_handle_trip_meta = h[IDX_INFO_CHAR_VAL_TRIP_META];
            s_handle_trip_control = h[IDX_INFO_CHAR_VAL_TRIP_CONTROL];
            s_handle_trip_data = h[IDX_INFO_CHAR_VAL_TRIP_DATA];
            s_handle_vehicle_state = h[IDX_INFO_CHAR_VAL_VEHICLE_STATE];
            s_handle_gauge_settings = h[IDX_INFO_CHAR_VAL_GAUGE_SETTINGS];
            s_handle_brightness_control = h[IDX_INFO_CHAR_VAL_BRIGHTNESS_CONTROL];
            s_handle_vehicle_profile_control = h[IDX_INFO_CHAR_VAL_VEHICLE_PROFILE_CONTROL];
            s_handle_odometer_config = h[IDX_INFO_CHAR_VAL_ODOMETER_CONFIG];
            s_handle_refuel_meta = h[IDX_INFO_CHAR_VAL_REFUEL_META];
            s_handle_refuel_control = h[IDX_INFO_CHAR_VAL_REFUEL_CONTROL];
            s_handle_refuel_data = h[IDX_INFO_CHAR_VAL_REFUEL_DATA];
            s_handle_custom_trip_control = h[IDX_INFO_CHAR_VAL_CUSTOM_TRIP_CONTROL];
            esp_ble_gatts_start_service(h[IDX_INFO_SVC]);
            s_time_attr_ready = true;
            ESP_LOGD(RC_TAG, "Info attr table ready, service=0x%04X time=0x%04X trip=%04X/%04X/%04X",
                     h[IDX_INFO_SVC], s_handle_time_sync, s_handle_trip_meta,
                     s_handle_trip_control, s_handle_trip_data);
            ota_update_ble_start(gatts_if);
            start_advertising_if_ready();
        } else if (param->add_attr_tab.svc_uuid.uuid.uuid16 == OBD_OTA_SERVICE_UUID) {
            // The OTA attribute table is owned by ota_update_ble (it already handled
            // this event in ota_update_ble_on_gatts_event); nothing to do here.
        } else {
            ESP_LOGE(RC_TAG, "Create attr table failed, status=%d uuid=0x%04X num_handle=%d",
                     param->add_attr_tab.status, param->add_attr_tab.svc_uuid.uuid.uuid16,
                     param->add_attr_tab.num_handle);
        }
        break;

    case ESP_GATTS_CONNECT_EVT:
        // This callback also fires for our outbound ELM327 GATTC link. Do not
        // treat a bare physical link as the phone or overwrite the phone conn_id.
        ESP_LOGD(RC_TAG, "BLE link established: %02x:%02x:%02x:%02x:%02x:%02x conn_id=0x%04X",
                 param->connect.remote_bda[0], param->connect.remote_bda[1], param->connect.remote_bda[2],
                 param->connect.remote_bda[3], param->connect.remote_bda[4], param->connect.remote_bda[5],
                 param->connect.conn_id);
        break;

    case ESP_GATTS_DISCONNECT_EVT:
        if (s_connected && param->disconnect.conn_id == s_conn_id) {
            s_connected = false;
            s_conn_id = 0xFFFF;
            s_notify_enabled = false;
            ESP_LOGI(RC_TAG, "Phone GATT service disconnected: conn_id=0x%04X",
                     param->disconnect.conn_id);
        }
        start_advertising_if_ready();
        break;

    case ESP_GATTS_WRITE_EVT:
        if (param->write.handle == s_handle_can_cccd ||
            param->write.handle == s_handle_filter ||
            param->write.handle == s_handle_time_sync ||
            param->write.handle == s_handle_trip_control ||
            param->write.handle == s_handle_brightness_control ||
            param->write.handle == s_handle_vehicle_profile_control ||
            param->write.handle == s_handle_odometer_config ||
            param->write.handle == s_handle_refuel_control ||
            param->write.handle == s_handle_custom_trip_control) {
            claim_phone_connection(param->write.conn_id);
        }
        if (param->write.handle == s_handle_can_cccd && param->write.len >= 2) {
            uint16_t cccd = (uint16_t)param->write.value[0] | ((uint16_t)param->write.value[1] << 8);
            s_notify_enabled = ((cccd & 0x0003u) != 0);
            ESP_LOGD(RC_TAG, "CAN main cccd=0x%04X stream %s", cccd, s_notify_enabled ? "EN" : "DIS");
        } else if (s_attr_ready && param->write.handle == s_handle_filter) {
            process_filter_write(param->write.value, param->write.len);
        } else if (param->write.handle == s_handle_time_sync) {
            esp_gatt_status_t status = ESP_GATT_INVALID_ATTR_LEN;
            if (param->write.len == 8 && param->write.offset == 0) {
                uint64_t epoch_s = 0;
                for (unsigned i = 0; i < 8; ++i) {
                    epoch_s |= (uint64_t)param->write.value[i] << (i * 8U);
                }
                nvs_trip_time_sync_result_t resolved;
                esp_err_t err = nvs_trip_apply_phone_time(epoch_s, &resolved);
                if (err == ESP_OK) {
                    status = ESP_GATT_OK;
                    const char *action = resolved == NVS_TRIP_TIME_SYNC_MERGED ? "merged" :
                                         resolved == NVS_TRIP_TIME_SYNC_NEW_TRIP ? "new-trip" :
                                         "no-pending";
                    ESP_LOGI(RC_TAG, "Phone time accepted: epoch=%" PRIu64 " trip=%s",
                             epoch_s, action);
                } else {
                    status = ESP_GATT_INVALID_PDU;
                    ESP_LOGW(RC_TAG, "Rejected phone time packet: epoch=%" PRIu64 " err=%s",
                             epoch_s, esp_err_to_name(err));
                }
            }
            if (param->write.need_rsp) {
                esp_ble_gatts_send_response(gatts_if, param->write.conn_id,
                                            param->write.trans_id, status, NULL);
            }
        } else if (param->write.handle == s_handle_trip_control) {
            esp_gatt_status_t status = ESP_GATT_INVALID_ATTR_LEN;
            if (param->write.len == 5 && param->write.offset == 0) {
                uint8_t command = param->write.value[0];
                uint32_t trip_id = get_le32(&param->write.value[1]);
                if (command == 1U) {
                    s_trip_request_after_id = trip_id;
                    status = ESP_GATT_OK;
                } else if (command == 2U && trip_id != 0U &&
                           trip_id <= s_trip_highest_sent_id) {
                    status = nvs_trip_sync_ack(trip_id) == ESP_OK ?
                             ESP_GATT_OK : ESP_GATT_ERROR;
                } else {
                    status = ESP_GATT_INVALID_PDU;
                }
            }
            if (param->write.need_rsp) {
                esp_ble_gatts_send_response(gatts_if, param->write.conn_id,
                                            param->write.trans_id, status, NULL);
            }
        } else if (param->write.handle == s_handle_brightness_control) {
            /* Packet: version, SET command, percent, total length, CRC16-CCITT.
             * Keep this callback constant-time: it never touches the display,
             * NVS, ELM327 client, fuel estimator, or trip records. */
            esp_gatt_status_t status = ESP_GATT_INVALID_ATTR_LEN;
            if (param->write.len == BRIGHTNESS_CONTROL_WIRE_SIZE &&
                param->write.offset == 0) {
                const uint8_t *value = param->write.value;
                uint16_t expected_crc = get_le16(value + 4);
                bool valid = value[0] == 1U && value[1] == 1U &&
                             value[2] >= 10U && value[2] <= 100U &&
                             value[3] == BRIGHTNESS_CONTROL_WIRE_SIZE &&
                             trip_crc16(value, 4) == expected_crc;
                if (!valid) {
                    status = ESP_GATT_INVALID_PDU;
                } else if (app_event_send(APP_EVT_PHONE_BRIGHTNESS, value[2])) {
                    status = ESP_GATT_OK;
                } else {
                    status = ESP_GATT_ERROR; /* queue full: phone retries later */
                }
            }
            if (param->write.need_rsp) {
                esp_ble_gatts_send_response(gatts_if, param->write.conn_id,
                                            param->write.trans_id, status, NULL);
            }
        } else if (param->write.handle == s_handle_vehicle_profile_control) {
            /* Packet: version, SET command, profile index, total length,
             * CRC16-CCITT. Only the two BRZ 6MT profiles exposed by the phone
             * are accepted. The BLE callback validates/enqueues only. */
            esp_gatt_status_t status = ESP_GATT_INVALID_ATTR_LEN;
            if (param->write.len == VEHICLE_PROFILE_CONTROL_WIRE_SIZE &&
                param->write.offset == 0) {
                const uint8_t *value = param->write.value;
                uint16_t expected_crc = get_le16(value + 4);
                bool supported = value[2] == VEHICLE_PROFILE_ZC6_INDEX ||
                                 value[2] == VEHICLE_PROFILE_ZD8_INDEX;
                bool valid = value[0] == 1U && value[1] == 1U && supported &&
                             value[3] == VEHICLE_PROFILE_CONTROL_WIRE_SIZE &&
                             trip_crc16(value, 4) == expected_crc;
                if (!valid) {
                    status = ESP_GATT_INVALID_PDU;
                } else if (app_event_send(APP_EVT_PHONE_VEHICLE_PROFILE, value[2])) {
                    status = ESP_GATT_OK;
                } else {
                    status = ESP_GATT_ERROR;
                }
            }
            if (param->write.need_rsp) {
                esp_ble_gatts_send_response(gatts_if, param->write.conn_id,
                                            param->write.trans_id, status, NULL);
            }
        } else if (param->write.handle == s_handle_odometer_config) {
            /* Fixed packet: version, command, flags, length, odometer x0.1km,
             * reserved, CRC. Validation and a non-blocking queue copy are the
             * only work done in the BLE host callback. */
            esp_gatt_status_t status = ESP_GATT_INVALID_ATTR_LEN;
            if (param->write.len == ODOMETER_CONFIG_WIRE_SIZE &&
                param->write.offset == 0) {
                const uint8_t *value = param->write.value;
                uint8_t command = value[1];
                uint32_t odometer_x10_km = get_le32(value + 4);
                bool valid = value[0] == 1U && value[3] == ODOMETER_CONFIG_WIRE_SIZE &&
                             value[8] == 0U && value[9] == 0U &&
                             trip_crc16(value, 10) == get_le16(value + 10);
                if (command == 1U) {
                    valid = valid && value[2] <= 1U;
                    if (!valid) status = ESP_GATT_INVALID_PDU;
                    else status = app_event_send(APP_EVT_PHONE_ODOMETER_DISPLAY,
                                                 value[2] != 0U)
                                ? ESP_GATT_OK : ESP_GATT_ERROR;
                } else if (command == 2U) {
                    valid = valid && odometer_x10_km <= 9999999U;
                    if (!valid) status = ESP_GATT_INVALID_PDU;
                    else status = app_event_send(APP_EVT_PHONE_ODOMETER_CALIBRATE,
                                                 odometer_x10_km)
                                ? ESP_GATT_OK : ESP_GATT_ERROR;
                } else {
                    status = ESP_GATT_INVALID_PDU;
                }
            }
            if (param->write.need_rsp) {
                esp_ble_gatts_send_response(gatts_if, param->write.conn_id,
                                            param->write.trans_id, status, NULL);
            }
        } else if (param->write.handle == s_handle_refuel_control) {
            /* Cursor selection and explicit manual reset are constant-time in
             * the BLE callback. The reset itself is applied by the UI/config
             * task through app_event, away from both Bluetooth roles. */
            esp_gatt_status_t status = ESP_GATT_INVALID_ATTR_LEN;
            if (param->write.len == REFUEL_CONTROL_WIRE_SIZE &&
                param->write.offset == 0) {
                const uint8_t *value = param->write.value;
                uint8_t command = value[1];
                uint32_t argument = get_le32(value + 4);
                bool valid = value[0] == 1U &&
                             get_le16(value + 2) == REFUEL_CONTROL_WIRE_SIZE &&
                             trip_crc16(value, 8) == get_le16(value + 8);
                if (!valid || (command < 1U || command > 5U)) {
                    status = ESP_GATT_INVALID_PDU;
                } else if (command == 1U) {
                    s_refuel_request_after_id = argument;
                    status = ESP_GATT_OK;
                } else if (command == 2U && argument != 0U) {
                    status = ESP_GATT_INVALID_PDU;
                } else if (command == 2U) {
                    status = app_event_send(APP_EVT_PHONE_REFUEL_RESET, 0)
                           ? ESP_GATT_OK : ESP_GATT_ERROR;
                } else if (command == 3U && argument == 0U) {
                    status = ESP_GATT_INVALID_PDU;
                } else if (command == 3U) {
                    status = app_event_send(APP_EVT_PHONE_REFUEL_DELETE, argument)
                           ? ESP_GATT_OK : ESP_GATT_ERROR;
                } else if (command == 5U && argument == 0U) {
                    status = ESP_GATT_INVALID_PDU;
                } else if (command == 5U) {
                    status = app_event_send(APP_EVT_PHONE_REFUEL_DISCARD_OLDEST, argument)
                           ? ESP_GATT_OK : ESP_GATT_ERROR;
                } else if (argument < NVS_REFUEL_THRESHOLD_MIN_ML ||
                           argument > NVS_REFUEL_THRESHOLD_MAX_ML ||
                           (argument % 1000U) != 0U) {
                    status = ESP_GATT_INVALID_PDU;
                } else {
                    status = app_event_send(APP_EVT_PHONE_REFUEL_THRESHOLD, argument)
                           ? ESP_GATT_OK : ESP_GATT_ERROR;
                }
            }
            if (param->write.need_rsp) {
                esp_ble_gatts_send_response(gatts_if, param->write.conn_id,
                                            param->write.trans_id, status, NULL);
            }
        } else if (param->write.handle == s_handle_custom_trip_control) {
            /* One default-MTU-safe packet copies the phone's persisted lifetime
             * baseline. Flash I/O is deferred to the UI/config task. */
            esp_gatt_status_t status = ESP_GATT_INVALID_ATTR_LEN;
            if (param->write.len == CUSTOM_TRIP_CONTROL_WIRE_SIZE &&
                param->write.offset == 0) {
                const uint8_t *value = param->write.value;
                bool valid = value[0] == 1U && value[1] == 1U &&
                             get_le16(value + 2) == CUSTOM_TRIP_CONTROL_WIRE_SIZE &&
                             value[16] == 0U && value[17] == 0U &&
                             trip_crc16(value, 18) == get_le16(value + 18);
                if (!valid) {
                    status = ESP_GATT_INVALID_PDU;
                } else if (app_event_send_custom_trip_baseline(
                               get_le32(value + 4), get_le32(value + 8),
                               get_le32(value + 12))) {
                    status = ESP_GATT_OK;
                } else {
                    status = ESP_GATT_ERROR;
                }
            }
            if (param->write.need_rsp) {
                esp_ble_gatts_send_response(gatts_if, param->write.conn_id,
                                            param->write.trans_id, status, NULL);
            }
        }
        break;

    case ESP_GATTS_READ_EVT:
        if (param->read.handle == s_handle_manifest ||
            param->read.handle == s_handle_time_sync ||
            param->read.handle == s_handle_vehicle_state ||
            param->read.handle == s_handle_gauge_settings ||
            param->read.handle == s_handle_odometer_config ||
            param->read.handle == s_handle_refuel_meta ||
            param->read.handle == s_handle_refuel_data ||
            param->read.handle == s_handle_trip_meta ||
            param->read.handle == s_handle_trip_data) {
            claim_phone_connection(param->read.conn_id);
            esp_gatt_rsp_t rsp = {0};
            rsp.attr_value.handle = param->read.handle;
            rsp.attr_value.offset = param->read.offset;

            const uint8_t *source = s_manifest_blob;
            uint16_t source_len = s_manifest_len;
            if (param->read.handle == s_handle_trip_meta) {
                source_len = build_trip_meta_wire();
                source = s_trip_meta_value;
            } else if (param->read.handle == s_handle_trip_data) {
                source_len = build_trip_data_wire();
                source = s_trip_data_value;
            } else if (param->read.handle == s_handle_time_sync) {
                if (param->read.offset == 0) put_le64(s_time_sync_value, nvs_trip_phone_clock_epoch());
                source = s_time_sync_value;
                source_len = sizeof(s_time_sync_value);
            } else if (param->read.handle == s_handle_vehicle_state) {
                // Freeze across ATT Read Blob fragments at the default MTU.
                if (param->read.offset == 0) build_vehicle_state_wire();
                source = s_vehicle_state_value;
                source_len = sizeof(s_vehicle_state_value);
            } else if (param->read.handle == s_handle_gauge_settings) {
                // Freeze one RAM-only snapshot across all ATT Read Blob fragments.
                if (param->read.offset == 0) build_gauge_settings_wire();
                source = s_gauge_settings_value;
                source_len = sizeof(s_gauge_settings_value);
            } else if (param->read.handle == s_handle_odometer_config) {
                if (param->read.offset == 0) build_odometer_config_wire();
                source = s_odometer_config_value;
                source_len = sizeof(s_odometer_config_value);
            } else if (param->read.handle == s_handle_refuel_meta) {
                if (param->read.offset == 0) build_refuel_meta_wire();
                source = s_refuel_meta_value;
                source_len = sizeof(s_refuel_meta_value);
            } else if (param->read.handle == s_handle_refuel_data) {
                source_len = build_refuel_data_wire();
                source = s_refuel_data_value;
            }
            uint16_t available = (source_len > param->read.offset) ?
                                 (uint16_t)(source_len - param->read.offset) : 0;
            uint16_t copy_len = available;
            if (copy_len > ATT_SAFE_READ_CHUNK) copy_len = ATT_SAFE_READ_CHUNK;
            if (copy_len > 0) {
                memcpy(rsp.attr_value.value, source + param->read.offset, copy_len);
            }
            rsp.attr_value.len = copy_len;

            esp_err_t err = esp_ble_gatts_send_response(gatts_if, param->read.conn_id, param->read.trans_id, ESP_GATT_OK, &rsp);
            if (err != ESP_OK) {
                ESP_LOGW(RC_TAG, "Send manifest read response failed: %s", esp_err_to_name(err));
            }
        }
        break;

    default:
        break;
    }
}

void racechrono_ble_diy_handle_gap_event(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param)
{
    (void)param;
    switch (event) {
    case ESP_GAP_BLE_ADV_DATA_RAW_SET_COMPLETE_EVT:
        ESP_LOGI(RC_TAG, "Adv raw data applied");
        s_adv_raw_done = true;
        if (s_adv_raw_done && s_scan_rsp_raw_done) {
            s_adv_config_done = true;
            s_adv_cfg_pending = false;
            start_advertising_if_ready();
        }
        break;
    case ESP_GAP_BLE_SCAN_RSP_DATA_RAW_SET_COMPLETE_EVT:
        ESP_LOGI(RC_TAG, "Scan rsp raw data applied");
        s_scan_rsp_raw_done = true;
        if (s_adv_raw_done && s_scan_rsp_raw_done) {
            s_adv_config_done = true;
            s_adv_cfg_pending = false;
            start_advertising_if_ready();
        }
        break;
    case ESP_GAP_BLE_SCAN_STOP_COMPLETE_EVT:
        if (s_adv_cfg_pending) {
            request_adv_config();
        }
        if (s_adv_start_pending) {
            start_advertising_if_ready();
        }
        break;
    case ESP_GAP_BLE_ADV_START_COMPLETE_EVT:
        if (param->adv_start_cmpl.status == ESP_BT_STATUS_SUCCESS) {
            s_adv_start_pending = false;
            ESP_LOGI(RC_TAG, "Advertising started (name=%s, ota_mode=%d)", s_adv_name, s_ota_mode);
        } else {
            ESP_LOGW(RC_TAG, "Advertising start failed status=%d", param->adv_start_cmpl.status);
        }
        break;
    default:
        break;
    }
}

void racechrono_ble_diy_start(bool enable_racechrono)
{
    if (s_started) {
        return;
    }

    s_rc_enabled = enable_racechrono;

    if (esp_bt_controller_get_status() != ESP_BT_CONTROLLER_STATUS_ENABLED ||
        esp_bluedroid_get_status() != ESP_BLUEDROID_STATUS_ENABLED) {
        ESP_LOGW(RC_TAG, "BT stack is not ready, skip RaceChrono BLE DIY start");
        return;
    }

    esp_err_t err = esp_ble_gatts_register_callback(gatts_cb);
    if (err != ESP_OK) {
        ESP_LOGE(RC_TAG, "gatts register cb failed: %s", esp_err_to_name(err));
        return;
    }

    // app_register triggers REG_EVT, which does common setup + service creation
    err = esp_ble_gatts_app_register(RC_APP_ID);
    if (err != ESP_OK) {
        ESP_LOGE(RC_TAG, "gatts app register failed: %s", esp_err_to_name(err));
        return;
    }

    if (s_rc_enabled && !s_stream_task) {
        xTaskCreate(stream_task, "rc_stream", 4096, NULL, 4, &s_stream_task);
    }

    s_started = true;
    if (s_rc_enabled) {
        ESP_LOGD(RC_TAG, "RaceChrono BLE DIY started");
        ESP_LOGD(RC_TAG, "PID map: RPM=0x%08X SPD=0x%08X CLT=0x%08X IAT=0x%08X OIL=0x%08X LOADx10=0x%08X TPSx10=0x%08X BATmV=0x%08X BRKx10=0x%08X",
                 RC_PID_RPM, RC_PID_SPEED_KMH, RC_PID_CLT_C, RC_PID_IAT_C, RC_PID_OIL_C,
                 RC_PID_LOAD_X10, RC_PID_TPS_X10, RC_PID_BAT_MV, RC_PID_BRAKE_X10);
    } else {
        ESP_LOGI(RC_TAG, "RaceChrono BLE DIY started (minimal: Info+Time+OTA)");
    }
}

bool racechrono_ble_diy_is_connected(void)
{
    return s_connected;
}

bool racechrono_ble_diy_services_ready(void)
{
    return s_time_attr_ready && ota_update_ble_services_ready();
}

// Enter/leave OTA advertising mode (called from the OTA mode screen):
//  - enter: publish the OTA advert (Info+OTA UUIDs + device name) and start advertising,
//    so the phone OTA app can discover this device;
//  - leave: restore the normal advert, retaining phone discoverability.
void racechrono_ble_diy_set_ota_mode(bool enable)
{
    if (!s_started) {
        ESP_LOGW(RC_TAG, "set_ota_mode ignored: GATTS not started yet");
        return;
    }
    if (s_ota_mode == enable) {
        return;
    }
    s_ota_mode = enable;
    ESP_LOGI(RC_TAG, "OTA advertising %s (name=%s)", enable ? "ON" : "OFF", s_adv_name);

    // A closed OTA screen must not disable future phone reconnection.
    esp_ble_gap_stop_advertising();
    request_adv_config();
}

const char *racechrono_ble_diy_get_adv_name(void)
{
    return s_adv_name;
}
