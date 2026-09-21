#pragma once
#include "app_obd_dsp/fuel_estimator.h"

#include <stdbool.h>
#include <stdint.h>

typedef int esp_err_t;

#define TRIP_MERGE_TIMEOUT_LOCKED_MIN 15U
#define MULTIGAUGE_LOCKED_ROLE 0U
#define MULTIGAUGE_LOCKED_POSITION 1U
#define MULTIGAUGE_LOCKED_INTRO 2U

typedef struct {
    uint8_t theme;
    uint32_t user_theme_domiant_color;
    uint32_t user_theme_secondary_color;
    uint8_t rsv[5];
} theme_cfg_t;

typedef struct {
    uint8_t protocol;
    theme_cfg_t theme_cfg;
    char ble_device_name[32];
    uint8_t default_page;
    uint8_t brightness_day;
    uint8_t vehicle_profile_idx;
    uint16_t brake_temp_warn_c;
    uint16_t oil_pressure_warn_x10;
    uint8_t temp_display_map[3];
    uint8_t info_display_map[5];
    uint8_t needle_source_idx;
    uint8_t device_role;
    uint8_t chart_source_idx;
    uint16_t rpm_warn_threshold;
    uint8_t rpm_warn_anim_en;
    uint8_t espnow_master_mac[6];
    uint8_t ble_obd_mac[6];
    uint8_t rpm_warn_linked_en;
    uint8_t rc_enabled;
    uint16_t trip_merge_timeout_min;
} nvs_user_cfg_t;

#define NVS_FUEL_TRIP_HISTORY_MAX 20
typedef struct {
    uint32_t id;
    uint32_t duration_s;
    uint32_t distance_m;
    uint32_t fuel_ml;
    uint16_t avg_l100_x100;
    uint16_t reserved;
} nvs_fuel_trip_record_t;

typedef struct {
    bool data_valid;
    bool instant_is_lph;
    bool current_avg_valid;
    bool lifetime_avg_valid;
    fuel_source_t source;
    uint16_t instant_x100;
    uint16_t current_avg_x100;
    uint16_t lifetime_avg_x100;
    uint32_t current_duration_s;
    uint32_t current_distance_m;
    uint32_t current_fuel_ml;
    uint64_t lifetime_duration_s;
    uint64_t lifetime_distance_m;
    uint64_t lifetime_fuel_ml;
    uint32_t odometer_x10_km;
    bool odometer_calibrated;
    bool odometer_display_enabled;
    uint8_t history_count;
    nvs_fuel_trip_record_t history[NVS_FUEL_TRIP_HISTORY_MAX];
} nvs_fuel_snapshot_t;

typedef struct {
    uint32_t odometer_x10_km;
    bool calibrated;
    bool display_enabled;
} nvs_odometer_snapshot_t;

const nvs_user_cfg_t *nvs_cfg_get(void);
esp_err_t nvs_cfg_set(const nvs_user_cfg_t *cfg);
int16_t nvs_chart_alarm_get(uint8_t item);
void nvs_chart_alarm_set(uint8_t item, int16_t raw_threshold);
uint8_t nvs_intro_enable_get(void);
void nvs_intro_enable_set(uint8_t en);
uint8_t nvs_device_position_get(void);
void nvs_device_position_set(uint8_t pos);
void nvs_fuel_update(const fuel_sample_t *sample, uint32_t dt_ms);
void nvs_fuel_get_snapshot(nvs_fuel_snapshot_t *out);
void nvs_odometer_get_snapshot(nvs_odometer_snapshot_t *out);
esp_err_t nvs_fuel_save(void);
