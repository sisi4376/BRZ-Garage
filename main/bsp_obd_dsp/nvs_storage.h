#pragma once
#include "app_obd_dsp/fuel_estimator.h"
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"

/* Temporarily fixed while trip-boundary behavior is being road-tested.
 * Keep the settings row visible so selectable options can be restored later. */
#define TRIP_MERGE_TIMEOUT_EDITABLE 0
#define TRIP_MERGE_TIMEOUT_LOCKED_MIN 15U

/* Keep the MultiGauge page visible, but freeze this BRZ build to the current
 * installed primary-gauge configuration. Values match espnow_link role IDs. */
#define MULTIGAUGE_SETTINGS_EDITABLE 0
#define MULTIGAUGE_LOCKED_ROLE 0U       /* MASTER */
#define MULTIGAUGE_LOCKED_POSITION 1U
#define MULTIGAUGE_LOCKED_INTRO 2U      /* VIDEO */

// Theme config. The index/selectors are real now (see ui_theme.c); the two
// color fields are legacy and unused, kept only to preserve struct layout.
typedef struct {
    uint8_t  theme;         // UI theme index (0=DEFAULT, registry in ui_theme.c)
    uint32_t user_theme_domiant_color;   // legacy, unused (kept for layout)
    uint32_t user_theme_secondary_color;   // legacy, unused (kept for layout)
    uint8_t rsv[5];        // reserved for future use
} theme_cfg_t;

/*------------------ User configuration (written only when changed) ------------------*/
typedef struct {
    uint8_t protocol;      // OBD protocol: 0=auto, 1~9=fixed
    theme_cfg_t theme_cfg;   // theme config
    char    ble_device_name[32]; // last connected BLE device name, empty = not configured
    uint8_t default_page;   // default boot page: 0=Temp, 1=Info, 2=Chart, 3=Needle, 4=Gear+RPM, 5=Speed, 6=Fuel, 7=Trip History, 8=Trip Overview
    uint8_t brightness_day; // brightness 10-100, 0=unset (use 100)
    uint8_t vehicle_profile_idx; // vehicle profile index; new/corrupt configs default to ZD8
    uint16_t brake_temp_warn_c; // brake temp warning threshold, °C (x1)
    uint16_t oil_pressure_warn_x10; // oil pressure warning threshold, 0.1bar
    uint8_t temp_display_map[3]; // TEMP page, 3 rows display-item mapping
    uint8_t info_display_map[5]; // INFO page, 5-cell display-item mapping
    uint8_t needle_source_idx;   // needle page data source (disp_item_t value, default 0=CLT)
    uint8_t device_role;         // multi-gauge role: 0=master (reads ELM327), 1=slave (receives master data)
    uint8_t chart_source_idx;    // chart page data item (disp_item_t value, default 8=OILP)
    uint16_t rpm_warn_threshold; // configurable yellow-line RPM, 1000-6500 (default 5000); redline is fixed at 7000
    uint8_t rpm_warn_anim_en;    // RPM warning flash enable: 0=off, 1=on
    uint8_t espnow_master_mac[6];// master MAC a slave is bound to (all-zero = unbound / accept any)
    uint8_t ble_obd_mac[6];      // paired ELM327 exact MAC (all-zero = unbound, fuzzy-match by ble_device_name).
                                 // NOTE: new fields MUST be appended at the END of this struct;
                                 // see the load_blob grow logic comment in nvs_storage.c.
    uint8_t rpm_warn_linked_en;  // multi-gauge linked flash: 0=off 1=on (gauges turn red in sequence by
                                 // position, then all flash at threshold; logic in ui.c)
    uint8_t rc_enabled;          // RaceChrono BLE service: 0=off (minimal mode), 1=on (full RC+Pair+Info+OTA)
    uint16_t trip_merge_timeout_min; // engine-off finalization / power-cycle merge window; locked to 15 minutes
                                 // NOTE: new fields MUST be appended at the END of this struct;
                                 // see the load_blob grow logic comment in nvs_storage.c.
    uint16_t refuel_detect_threshold_ml; // automatic refuel threshold, 5000-20000 ml (default 10000)
} nvs_user_cfg_t;

/*------------------ Runtime statistics (persisted periodically) ------------------*/
typedef struct {
    uint64_t odometer_m;   // total odometer (m)
    uint64_t trip_m;       // current trip distance (m)
    uint64_t run_time_s;   // total running time (s)
    uint16_t max_speed_kmh; // max speed km/h
    uint16_t avg_speed_kmh;
    uint32_t trip_run_time_s; // current trip running time (s)
    uint8_t  rsv[2];
} nvs_stat_t;

#define NVS_FUEL_TRIP_HISTORY_MAX 20
#define NVS_TRIP_SYNC_QUEUE_MAX   64
#define NVS_REFUEL_HISTORY_MAX    20
#define NVS_REFUEL_THRESHOLD_DEFAULT_ML 10000U
#define NVS_REFUEL_THRESHOLD_MIN_ML      5000U
#define NVS_REFUEL_THRESHOLD_MAX_ML     20000U

typedef struct {
    uint32_t id;
    uint32_t duration_s;
    uint32_t distance_m;
    uint32_t fuel_ml;
    uint16_t avg_l100_x100;
    uint16_t reserved;
} nvs_fuel_trip_record_t;

/* Optional per-trip detail. Acceleration values are signed m/s² x100:
 * acceleration is positive and braking/deceleration is negative. */
typedef struct {
    uint16_t max_speed_kmh;
    uint16_t max_rpm;
    int16_t max_accel_x100;
    int16_t max_decel_x100;
} nvs_trip_detail_t;

/* Full record transferred to the companion phone. Timestamps are Unix UTC
 * seconds; zero means that the phone did not provide valid time during that
 * ignition session (legacy records are intentionally reported this way). */
typedef struct {
    uint32_t id;
    uint64_t start_epoch_s;
    uint64_t end_epoch_s;
    uint32_t duration_s;
    uint32_t distance_m;
    uint32_t fuel_ml;
    uint16_t avg_l100_x100;
    uint16_t flags;
} nvs_trip_sync_record_t;

typedef struct {
    uint8_t protocol_version;
    uint8_t pending_count;
    bool overflowed;
    uint32_t oldest_id;
    uint32_t newest_id;
    uint32_t last_acked_id;
} nvs_trip_sync_meta_t;

/* One interval ending at an automatic (configurable, default >=10 L) or manual refuel reset. The
 * ordinary trip/lifetime accumulators are never reset by this feature. */
typedef struct {
    uint32_t id;
    uint64_t start_epoch_s;
    uint64_t end_epoch_s;
    uint32_t duration_s;
    uint32_t distance_m;
    uint32_t fuel_ml;
    uint32_t odometer_x10_km;
    uint16_t detected_added_ml;
    uint16_t flags; /* bit 0 automatic fuel increase; bit 1 manual reset */
} nvs_refuel_record_t;

typedef struct {
    uint8_t protocol_version;
    uint8_t count;
    uint32_t oldest_id;
    uint32_t newest_id;
    uint64_t current_start_epoch_s;
    uint32_t current_duration_s;
    uint32_t current_distance_m;
    uint32_t current_fuel_ml;
    uint16_t history_revision;
} nvs_refuel_meta_t;

typedef struct {
    uint32_t duration_s;
    uint32_t distance_m;
    uint32_t fuel_ml;
} nvs_custom_trip_meta_t;

typedef struct {
    bool data_valid;
    bool instant_is_lph;
    bool current_avg_valid;
    bool lifetime_avg_valid;
    fuel_source_t source;       // runtime only; persisted trip blobs are unchanged
    uint16_t instant_x100;       // L/h or L/100km x100, selected by instant_is_lph
    uint16_t current_avg_x100;   // current ignition trip, L/100km x100
    uint16_t lifetime_avg_x100;  // all retained trips, L/100km x100
    uint32_t current_duration_s;
    uint32_t current_distance_m;
    uint32_t current_fuel_ml;
    uint64_t lifetime_duration_s;
    uint64_t lifetime_distance_m;
    uint64_t lifetime_fuel_ml;
    uint8_t history_count;
    nvs_fuel_trip_record_t history[NVS_FUEL_TRIP_HISTORY_MAX]; // newest first
    nvs_trip_detail_t current_detail;
    uint64_t current_start_epoch_s;
    uint32_t odometer_x10_km;   // configurable vehicle odometer, 0.1 km
    bool odometer_calibrated;
    bool odometer_display_enabled;
} nvs_fuel_snapshot_t;

typedef struct {
    uint32_t odometer_x10_km;
    bool calibrated;
    bool display_enabled;
} nvs_odometer_snapshot_t;

esp_err_t nvs_storage_init(void);

/* User config accessors */
const nvs_user_cfg_t * nvs_cfg_get(void);
esp_err_t nvs_cfg_set(const nvs_user_cfg_t *cfg);

// Per-item alarm threshold for the chart page (raw units; value>=threshold alarms; 32767=off). item = disp_item_t value.
int16_t nvs_chart_alarm_get(uint8_t item);
void    nvs_chart_alarm_set(uint8_t item, int16_t raw_threshold);

// Multi-gauge boot animation: 0=OFF, 1=RACE AS ONE, 2=VIDEO. Stored as a separate blob, not in the cfg struct.
uint8_t nvs_intro_enable_get(void);           // 0=OFF 1=RACE 2=VIDEO (boot_block flashed via the phone app)
void    nvs_intro_enable_set(uint8_t en);
uint8_t nvs_device_position_get(void);        // 1/2/3
void    nvs_device_position_set(uint8_t pos);

// Boot mode: 0=default Sky Gauge animation, 1=custom boot image, 2=video animation (boot_block)
uint8_t nvs_boot_mode_get(void);
void    nvs_boot_mode_set(uint8_t mode);

/* Runtime statistics accessors */
const nvs_stat_t * nvs_stat_get(void);
void nvs_stat_reset_trip(void);
void nvs_stat_update_speed(uint8_t speed_kmh,uint32_t dt_ms);
nvs_stat_t nvs_stat_get_mileage(void);

/* Fuel and ignition-trip statistics. Prefer SAE PID 01 5E when available;
 * otherwise estimate from MAF/AFR and suppress inferred overrun fuel cut. */
void nvs_fuel_update(const fuel_sample_t *sample, uint32_t dt_ms);
void nvs_fuel_get_snapshot(nvs_fuel_snapshot_t *out);
esp_err_t nvs_fuel_save(void);

/* Observe the already-decoded fuel-level PID. This performs only bounded RAM
 * arithmetic under the existing statistics mutex; flash is saved later by
 * the statistics task, never by the ELM327 receive callback. */
void nvs_refuel_observe_fuel_level(uint16_t percent_x100);
void nvs_refuel_get_meta(nvs_refuel_meta_t *out);
size_t nvs_refuel_read_after(uint32_t after_id, nvs_refuel_record_t *out,
                             size_t max_records);
esp_err_t nvs_refuel_manual_reset(void);
/* Delete one reset node. Its completed interval is merged into the following
 * interval, or into the live interval when deleting the newest node. */
esp_err_t nvs_refuel_delete_node(uint32_t id);
/* Discard only the completed interval before the oldest reset node. Later
 * intervals and the live baseline remain unchanged. */
esp_err_t nvs_refuel_discard_before_first_node(uint32_t oldest_id);

/* The phone owns the custom-trip reset point. Its lifetime baseline is copied
 * to the gauge so the companion page remains correct while the phone is away. */
void nvs_custom_trip_get_meta(nvs_custom_trip_meta_t *out);
esp_err_t nvs_custom_trip_set_baseline(uint32_t distance_m,
                                       uint32_t duration_s,
                                       uint32_t fuel_ml);

/* Phone-synchronized vehicle odometer. Calibration stores one baseline and
 * subsequently adds the already-persisted lifetime trip distance. These
 * setters are user actions only; they do not change OBD polling or trip data. */
void nvs_odometer_get_snapshot(nvs_odometer_snapshot_t *out);
esp_err_t nvs_odometer_set_display(bool enabled);
esp_err_t nvs_odometer_calibrate_x10_km(uint32_t odometer_x10_km);

typedef enum {
    NVS_TRIP_TIME_SYNC_NO_PENDING = 0,
    NVS_TRIP_TIME_SYNC_MERGED,
    NVS_TRIP_TIME_SYNC_NEW_TRIP,
} nvs_trip_time_sync_result_t;

/* Apply Unix time supplied by the companion phone. This also resolves a trip
 * retained by a power loss that occurred before the live engine-off timer. */
esp_err_t nvs_trip_apply_phone_time(uint64_t epoch_s,
                                    nvs_trip_time_sync_result_t *result);
uint64_t nvs_trip_phone_clock_epoch(void); // zero until this boot was phone-synchronized
bool nvs_trip_phone_time_is_valid(void);   // lock-free UI status; false until phone sync this boot

/* Phone history synchronization. Records remain queued until the phone ACKs
 * them. read_after() returns records in ascending trip-id order. */
void nvs_trip_sync_get_meta(nvs_trip_sync_meta_t *out);
size_t nvs_trip_sync_read_after(uint32_t after_id,
                                nvs_trip_sync_record_t *out,
                                nvs_trip_detail_t *detail_out,
                                size_t max_records);
esp_err_t nvs_trip_sync_ack(uint32_t up_to_id);
