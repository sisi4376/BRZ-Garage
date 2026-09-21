#include "lvgl.h"
#include "sim_ui_port.h"
#include "export_path/ui.h"
#include "export_path/ui_theme.h"
#include "export_path/ui_disp_item.h"
#include "bsp_obd_dsp/nvs_storage.h"
#include "bsp_obd_dsp/elm327_ble_client.h"
#include "bsp_obd_dsp/gauge_pair_ble_client.h"
#include "bsp_obd_dsp/espnow_link.h"
#include "app_obd_dsp/vehicle_profiles.h"
#include "freertos/semphr.h"
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

lv_obj_t *ui_ScreenPageLogo;
lv_obj_t *imageLogo;
lv_obj_t *ui_ScreenPageGear;
lv_obj_t *ui_SpinnerGearPage;
lv_obj_t *ui_GearPageArcGearNumBack;
lv_obj_t *ui_GearPageArcLabelGearNumText;
lv_obj_t *ui_GearPageRpmText;
lv_obj_t *ui_GearPageRpmUnit;
lv_obj_t *ui_GearPageGearUnit;
lv_obj_t *ui_ImageGearBlackEar;
lv_obj_t *ui_ScreenPageRpm;
lv_obj_t *ui_SpinnerRpmPage;
lv_obj_t *ui_RpmPageArcRpmBack;
lv_obj_t *ui_RpmPageArcLabelRpmText;
lv_obj_t *ui_RpmPageArcLabelRpmUnit;
lv_obj_t *ui_ImageRpmBlackEar;
lv_obj_t *ui_ScreenPageSpeed;
lv_obj_t *ui_SpinnerSpeedPage;
lv_obj_t *ui_SpeedPageArcSpeedBack;
lv_obj_t *ui_SpeedPageArcLabelSpeedText;
lv_obj_t *ui_SpeedPageArcLabelSpeedUnit;
lv_obj_t *ui_ImageSpeedBlackEar;
lv_obj_t *ui_ScreenPageTemp;
lv_obj_t *ui_ScreenPageTempCustom;
lv_obj_t *ui_ScreenPageInfoCustom;
lv_obj_t *ui_ScreenPageOilPressure;
lv_obj_t *ui_ScreenPageChartConfig;
lv_obj_t *ui_ScreenPageChartAlarm;
lv_obj_t *ui_ScreenPageNeedle;
lv_obj_t *ui_ScreenPageNeedleConfig;
lv_obj_t *ui_NeedleMeter;
lv_meter_scale_t *ui_NeedleScale;
lv_meter_indicator_t *ui_NeedleIndic;
lv_obj_t *ui_NeedleValueLabel;
lv_obj_t *ui_NeedleNameLabel;
lv_obj_t *ui_NeedleUnitLabel;
lv_obj_t *ui_ScreenPageEasterEgg;
lv_obj_t *ui_SpinnerEasterEgg;
lv_obj_t *ArcPageEasterEggBack;
lv_obj_t *imageEasterEgg;
lv_obj_t *ui_ImageEggBlackEar;
lv_obj_t *ui_LabelEasterEggInfo;
lv_obj_t *ui_LabelEasterEggOdometer;
lv_obj_t *ui_ScreenPageBLEScan;
lv_obj_t *ui_ScreenPageSettings;
lv_obj_t *ui_ScreenPageMultiGauge;
lv_obj_t *ui_ScreenPageODBProtocal;
lv_obj_t *ui_SpinnerODBProtocalEgg;
lv_obj_t *ui_ArcPageODBProtocalBack;
lv_obj_t *ui_RollerODBProtocalChoose;
lv_obj_t *ui_ImageODBProtocalBlackEar;
lv_obj_t *ui_LabelOBDIIText;
lv_obj_t *ui_LabelSureTipText;
lv_obj_t *ui_ScreenPageRpmWarn;
lv_obj_t *ui_ScreenPageOilWarn;
lv_obj_t *ui_ScreenPageOTAMode;
lv_obj_t *ui_LabelOTAModeStatus;
lv_obj_t *ui_LabelOTAModeVersion;

SemaphoreHandle_t lvgl_mux = (SemaphoreHandle_t)1;
volatile int s_rpm_flash_test_ticks;

static int s_page = SIM_PAGE_TEMP;
static uint8_t s_intro = 1;
static uint8_t s_position = 1;
static int16_t s_chart_alarm[12] = {
    32767, 32767, 32767, 32767, 32767, 32767,
    32767, 32767, 32767, 32767, 32767, 32767
};
static bool s_ble_connected;
static uint8_t s_bound_master[6];
static nvs_fuel_snapshot_t s_sim_fuel = {
    .data_valid = true,
    .instant_is_lph = false,
    .current_avg_valid = true,
    .lifetime_avg_valid = true,
    .instant_x100 = 720,
    .current_avg_x100 = 810,
    .lifetime_avg_x100 = 842,
    .current_duration_s = 1320,
    .current_distance_m = 23800,
    .current_fuel_ml = 1928,
    .lifetime_duration_s = 368640,
    .lifetime_distance_m = 7342500,
    .lifetime_fuel_ml = 618327,
    .odometer_x10_km = 54886,
    .odometer_calibrated = true,
    .odometer_display_enabled = true,
    .history_count = 8,
    .history = {
        { .id=18, .duration_s=2860, .distance_m=47200, .fuel_ml=3820, .avg_l100_x100=809 },
        { .id=17, .duration_s=1440, .distance_m=18600, .fuel_ml=1660, .avg_l100_x100=892 },
        { .id=16, .duration_s=3980, .distance_m=73100, .fuel_ml=5790, .avg_l100_x100=792 },
        { .id=15, .duration_s=960,  .distance_m=12100, .fuel_ml=1160, .avg_l100_x100=959 },
        { .id=14, .duration_s=2140, .distance_m=35100, .fuel_ml=2890, .avg_l100_x100=823 },
        { .id=13, .duration_s=1780, .distance_m=26400, .fuel_ml=2210, .avg_l100_x100=837 },
        { .id=12, .duration_s=3320, .distance_m=58800, .fuel_ml=4630, .avg_l100_x100=787 },
        { .id=11, .duration_s=740,  .distance_m=8200,  .fuel_ml=810,  .avg_l100_x100=988 },
    },
};

static nvs_user_cfg_t s_sim_cfg = {
    .protocol = 0,
    .theme_cfg = { .theme = 0 },
    .ble_device_name = "ELM327-emulator",
    .default_page = 0,
    .brightness_day = 100,
    .vehicle_profile_idx = 1,
    .oil_pressure_warn_x10 = 70,
    .temp_display_map = {0, 2, 1},
    .info_display_map = {0, 2, 3, 4, 1},
    .needle_source_idx = 2,
    .device_role = ESPNOW_ROLE_STANDALONE,
    .chart_source_idx = 8,
    .rpm_warn_threshold = 5000,
    .rpm_warn_anim_en = 1,
    .trip_merge_timeout_min = 5,
};

static vehicle_profile_t s_vehicles[] = {
    {
        .name = "OBD2 GENERIC", .final_drive_ratio = 4.10f,
        .tire_rolling_radius_m = 0.315f, .gear_count = 6,
        .gear_ratios = {0, 3.500f, 2.000f, 1.400f, 1.100f, 0.900f, 0.750f},
        .has_boost = false,
    },
    {
        .name = "SUBARU BRZ ZD8", .final_drive_ratio = 4.10f,
        .tire_rolling_radius_m = 0.315f, .gear_count = 6,
        .gear_ratios = {0, 3.626f, 2.188f, 1.541f, 1.213f, 1.000f, 0.767f},
        .has_boost = false,
    },
    {
        .name = "SUBARU BRZ ZC6", .final_drive_ratio = 4.10f,
        .tire_rolling_radius_m = 0.314f, .gear_count = 6,
        .gear_ratios = {0, 3.626f, 2.188f, 1.541f, 1.213f, 1.000f, 0.767f},
        .has_boost = false,
    },
};

static const ui_theme_t s_sim_theme = {
    .id = "default",
    .name = "DEFAULT",
    .colors = {
        [UI_COLOR_BG]             = 0x000000,
        [UI_COLOR_RING]           = 0xFFFFFF,
        [UI_COLOR_ARC_TRACK]      = 0x333333,
        [UI_COLOR_ARC_INDICATOR]  = 0xFFFFFF,
        [UI_COLOR_TEXT_PRIMARY]   = 0xFFFFFF,
        [UI_COLOR_TEXT_SECONDARY] = 0x888888,
        [UI_COLOR_NEEDLE]         = 0xFF1010,
        [UI_COLOR_PANEL]          = 0x222222,
    },
    .bezel = { .style_id = 0 },
};

static lv_obj_t *page_object(int page)
{
    switch (page) {
    case SIM_PAGE_GEAR: return ui_ScreenPageGear;
    case SIM_PAGE_RPM: return ui_ScreenPageRpm;
    case SIM_PAGE_SPEED: return ui_ScreenPageSpeed;
    case SIM_PAGE_TEMP: return ui_ScreenPageTemp;
    case SIM_PAGE_INFO: return ui_ScreenPageInfo;
    case SIM_PAGE_NEEDLE: return ui_ScreenPageNeedle;
    case SIM_PAGE_CHART: return ui_ScreenPageOilPressure;
    case SIM_PAGE_DEVICE: return ui_ScreenPageEasterEgg;
    case SIM_PAGE_BLE_SCAN: return ui_ScreenPageBLEScan;
    case SIM_PAGE_SETTINGS: return ui_ScreenPageSettings;
    case SIM_PAGE_MULTI_GAUGE: return ui_ScreenPageMultiGauge;
    case SIM_PAGE_OBD_PROTOCOL: return ui_ScreenPageODBProtocal;
    case SIM_PAGE_LOGO: return ui_ScreenPageLogo;
    case SIM_PAGE_RPM_WARN: return ui_ScreenPageRpmWarn;
    case SIM_PAGE_TEMP_SOURCE: return ui_ScreenPageTempCustom;
    case SIM_PAGE_INFO_SOURCE: return ui_ScreenPageInfoCustom;
    case SIM_PAGE_NEEDLE_SOURCE: return ui_ScreenPageNeedleConfig;
    case SIM_PAGE_CHART_SOURCE: return ui_ScreenPageChartConfig;
    case SIM_PAGE_CHART_ALARM: return ui_ScreenPageChartAlarm;
    case SIM_PAGE_FUEL: return ui_ScreenPageFuel;
    case SIM_PAGE_TRIP_OVERVIEW: return ui_ScreenPageTripOverview;
    case SIM_PAGE_TRIP_HISTORY: return ui_ScreenPageTripHistory;
    default: return NULL;
    }
}

const char *sim_ui_page_name(int page)
{
    static const char *names[SIM_PAGE_COUNT] = {
        "GEAR + RPM", "RPM (LEGACY)", "SPEED", "TEMP", "INFO", "NEEDLE", "CHART",
        "DEVICE", "BLE SCAN", "SETTINGS", "MULTI-GAUGE", "OBD PROTOCOL",
        "LOGO", "YELLOWLINE", "TEMP SOURCE", "INFO SOURCE", "NEEDLE SOURCE",
        "CHART SOURCE", "CHART ALARM", "FUEL", "TRIP OVERVIEW", "TRIP HISTORY"
    };
    return page >= 0 && page < SIM_PAGE_COUNT ? names[page] : "UNKNOWN";
}

int sim_ui_current_page(void) { return s_page; }

void sim_ui_show_page(int page)
{
    // DEVICE text is constructed from live BLE state; rebuild it on entry so
    // the native preview matches the ELM327 connection shown by the host.
    if (page == SIM_PAGE_DEVICE && ui_ScreenPageEasterEgg) {
        lv_obj_del(ui_ScreenPageEasterEgg);
        ui_ScreenPageEasterEgg = NULL;
        ui_ScreenPageEasterEgg_screen_init();
    }
    lv_obj_t *screen = page_object(page);
    if (!screen) return;
    s_page = page;
    lv_scr_load(screen);
}

void sim_ui_set_elm_connected(bool connected) { s_ble_connected = connected; }

void sim_ui_set_rpm_warn_threshold(uint16_t threshold)
{
    if (threshold < 1000) threshold = 1000;
    if (threshold > 6500) threshold = 6500;
    threshold = (uint16_t)(((threshold + 250U) / 500U) * 500U);
    s_sim_cfg.rpm_warn_threshold = threshold;
    ui_rpm_warn_refresh_from_nvs();
}

void _ui_screen_change(lv_obj_t **target, lv_scr_load_anim_t animation, int speed, int delay,
                       void (*target_init)(void))
{
    if (!*target && target_init) target_init();
    if (!*target) return;
    for (int page = 0; page < SIM_PAGE_COUNT; ++page) {
        if (page_object(page) == *target) {
            s_page = page;
            break;
        }
    }
    lv_scr_load_anim(*target, animation, (uint32_t)speed, (uint32_t)delay, false);
}

static bool is_dir(const char *direction, const char *expected)
{
    return direction && strcmp(direction, expected) == 0;
}

void sim_ui_handle_gesture(const char *direction)
{
    bool left = is_dir(direction, "left"), right = is_dir(direction, "right");
    bool up = is_dir(direction, "up"), down = is_dir(direction, "down");
    int next = s_page;
    switch (s_page) {
    case SIM_PAGE_GEAR: next = left ? SIM_PAGE_SPEED : right ? SIM_PAGE_DEVICE : down ? SIM_PAGE_RPM_WARN : s_page; break;
    case SIM_PAGE_RPM: next = left ? SIM_PAGE_SPEED : right ? SIM_PAGE_GEAR : down ? SIM_PAGE_RPM_WARN : s_page; break;
    case SIM_PAGE_SPEED: next = left ? SIM_PAGE_TEMP : right ? SIM_PAGE_GEAR : s_page; break;
    case SIM_PAGE_TEMP: next = left ? SIM_PAGE_INFO : right ? SIM_PAGE_SPEED : down ? SIM_PAGE_TEMP_SOURCE : s_page; break;
    case SIM_PAGE_INFO: next = left ? SIM_PAGE_NEEDLE : right ? SIM_PAGE_TEMP : down ? SIM_PAGE_INFO_SOURCE : s_page; break;
    case SIM_PAGE_NEEDLE: next = left ? SIM_PAGE_CHART : right ? SIM_PAGE_INFO : down ? SIM_PAGE_NEEDLE_SOURCE : s_page; break;
    case SIM_PAGE_CHART: next = left ? SIM_PAGE_FUEL : right ? SIM_PAGE_NEEDLE : down ? SIM_PAGE_CHART_SOURCE : up ? SIM_PAGE_CHART_ALARM : s_page; break;
    case SIM_PAGE_FUEL: next = left ? SIM_PAGE_TRIP_OVERVIEW : right ? SIM_PAGE_CHART : s_page; break;
    case SIM_PAGE_TRIP_OVERVIEW: next = left ? SIM_PAGE_TRIP_HISTORY : right ? SIM_PAGE_FUEL : s_page; break;
    case SIM_PAGE_TRIP_HISTORY:
        if (left) next = SIM_PAGE_DEVICE;
        else if (right) next = SIM_PAGE_TRIP_OVERVIEW;
        else if (down) ui_trip_history_page(+1);
        else if (up) ui_trip_history_page(-1);
        break;
    case SIM_PAGE_DEVICE: next = left ? SIM_PAGE_GEAR : right ? SIM_PAGE_TRIP_HISTORY : up ? SIM_PAGE_BLE_SCAN : down ? SIM_PAGE_SETTINGS : s_page; break;
    case SIM_PAGE_BLE_SCAN: if (left || right) next = SIM_PAGE_DEVICE; break;
    case SIM_PAGE_SETTINGS: next = (left || right) ? SIM_PAGE_DEVICE : down ? SIM_PAGE_MULTI_GAUGE : s_page; break;
    case SIM_PAGE_MULTI_GAUGE: if (up || left || right) next = SIM_PAGE_SETTINGS; break;
    case SIM_PAGE_OBD_PROTOCOL: if (left || right) next = SIM_PAGE_TEMP; break;
    case SIM_PAGE_RPM_WARN: if (up || left || right) next = SIM_PAGE_GEAR; break;
    case SIM_PAGE_TEMP_SOURCE: if (up || left || right) next = SIM_PAGE_TEMP; break;
    case SIM_PAGE_INFO_SOURCE: if (up || left || right) next = SIM_PAGE_INFO; break;
    case SIM_PAGE_NEEDLE_SOURCE: next = SIM_PAGE_NEEDLE; break;
    case SIM_PAGE_CHART_SOURCE:
    case SIM_PAGE_CHART_ALARM: next = SIM_PAGE_CHART; break;
    default: break;
    }
    sim_ui_show_page(next);
}

const nvs_user_cfg_t *nvs_cfg_get(void) { return &s_sim_cfg; }
esp_err_t nvs_cfg_set(const nvs_user_cfg_t *cfg) { s_sim_cfg = *cfg; return 0; }
int16_t nvs_chart_alarm_get(uint8_t item) { return item < 12 ? s_chart_alarm[item] : 32767; }
void nvs_chart_alarm_set(uint8_t item, int16_t raw) { if (item < 12) s_chart_alarm[item] = raw; }
void nvs_fuel_get_snapshot(nvs_fuel_snapshot_t *out) { if (out) *out = s_sim_fuel; }
void nvs_odometer_get_snapshot(nvs_odometer_snapshot_t *out)
{
    if (!out) return;
    out->odometer_x10_km = s_sim_fuel.odometer_x10_km;
    out->calibrated = s_sim_fuel.odometer_calibrated;
    out->display_enabled = s_sim_fuel.odometer_display_enabled;
}
esp_err_t nvs_fuel_save(void) { return 0; }
void nvs_fuel_update(const fuel_sample_t *sample, uint32_t dt_ms)
{
    static fuel_estimator_t estimator;
    static uint64_t duration_ms, distance_mm, fuel_ul;
    fuel_estimate_t result = fuel_estimator_step(&estimator, sample, dt_ms);
    s_sim_fuel.data_valid = result.valid;
    s_sim_fuel.source = result.source;
    s_sim_fuel.instant_is_lph = result.instant_is_lph;
    s_sim_fuel.instant_x100 = result.instant_x100;
    if (!result.integrate) return;
    duration_ms += result.duration_ms;
    distance_mm += result.distance_mm;
    fuel_ul += result.fuel_ul;
    uint32_t seconds = (uint32_t)(duration_ms / 1000ULL);
    uint32_t metres = (uint32_t)(distance_mm / 1000ULL);
    uint32_t ml = (uint32_t)(fuel_ul / 1000ULL);
    duration_ms %= 1000ULL; distance_mm %= 1000ULL; fuel_ul %= 1000ULL;
    s_sim_fuel.current_duration_s += seconds;
    s_sim_fuel.lifetime_duration_s += seconds;
    s_sim_fuel.current_distance_m += metres;
    s_sim_fuel.lifetime_distance_m += metres;
    s_sim_fuel.current_fuel_ml += ml;
    s_sim_fuel.lifetime_fuel_ml += ml;
    s_sim_fuel.current_avg_valid = s_sim_fuel.current_distance_m > 0;
    s_sim_fuel.lifetime_avg_valid = s_sim_fuel.lifetime_distance_m > 0;
    uint64_t current_avg = s_sim_fuel.current_avg_valid
        ? (uint64_t)s_sim_fuel.current_fuel_ml * 10000ULL / s_sim_fuel.current_distance_m : 0;
    uint64_t lifetime_avg = s_sim_fuel.lifetime_avg_valid
        ? s_sim_fuel.lifetime_fuel_ml * 10000ULL / s_sim_fuel.lifetime_distance_m : 0;
    s_sim_fuel.current_avg_x100 = (uint16_t)(current_avg > UINT16_MAX ? UINT16_MAX : current_avg);
    s_sim_fuel.lifetime_avg_x100 = (uint16_t)(lifetime_avg > UINT16_MAX ? UINT16_MAX : lifetime_avg);
}
uint8_t nvs_intro_enable_get(void) { return s_intro; }
void nvs_intro_enable_set(uint8_t en) { s_intro = en; }
uint8_t nvs_device_position_get(void) { return s_position; }
void nvs_device_position_set(uint8_t pos) { s_position = pos; }

const vehicle_profile_t *vehicle_profile_get_all(uint8_t *count)
{
    if (count) *count = (uint8_t)(sizeof(s_vehicles) / sizeof(s_vehicles[0]));
    return s_vehicles;
}
const vehicle_profile_t *vehicle_profile_get_active(void)
{
    unsigned index = s_sim_cfg.vehicle_profile_idx;
    if (index >= sizeof(s_vehicles) / sizeof(s_vehicles[0])) index = 0;
    return &s_vehicles[index];
}
void vehicle_profile_set_active(uint8_t index)
{
    if (index < sizeof(s_vehicles) / sizeof(s_vehicles[0])) s_sim_cfg.vehicle_profile_idx = index;
}

size_t sim_strlcat(char *dst, const char *src, size_t size)
{
    size_t dst_len = strlen(dst), src_len = strlen(src);
    if (dst_len < size && size - dst_len > 1) {
        size_t copy = src_len < size - dst_len - 1 ? src_len : size - dst_len - 1;
        memcpy(dst + dst_len, src, copy);
        dst[dst_len + copy] = '\0';
    }
    return dst_len + src_len;
}

const ui_theme_t *ui_theme_active(void) { return &s_sim_theme; }
uint8_t ui_theme_count(void) { return 1; }
const ui_theme_t *ui_theme_get(uint8_t idx) { (void)idx; return &s_sim_theme; }
void ui_theme_init(void) {}
void ui_theme_set_active(uint8_t idx) { s_sim_cfg.theme_cfg.theme = idx ? 0 : idx; }
const char *ui_theme_names_joined(void) { return "DEFAULT"; }
uint32_t ui_theme_color(ui_color_role_t role)
{
    return (unsigned)role < UI_COLOR__COUNT ? s_sim_theme.colors[role] : 0xFFFFFF;
}
lv_color_t ui_theme_color_lv(ui_color_role_t role) { return lv_color_hex(ui_theme_color(role)); }

lv_obj_t *ui_helpers_create_ring(lv_obj_t *parent, uint8_t border_width)
{
    lv_obj_t *ring = lv_obj_create(parent);
    lv_obj_set_size(ring, 456, 456);
    lv_obj_set_align(ring, LV_ALIGN_CENTER);
    lv_obj_clear_flag(ring, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_radius(ring, LV_RADIUS_CIRCLE, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(ring, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(ring, 0, LV_PART_MAIN);
    lv_obj_set_style_border_color(ring, ui_theme_color_lv(UI_COLOR_RING), LV_PART_MAIN);
    lv_obj_set_style_border_width(ring, border_width, LV_PART_MAIN);
    lv_obj_set_style_border_opa(ring, 255, LV_PART_MAIN);
    return ring;
}
void ui_helpers_style_screen_bg(lv_obj_t *screen)
{
    lv_obj_set_style_bg_color(screen, ui_theme_color_lv(UI_COLOR_BG), LV_PART_MAIN);
    lv_obj_set_style_bg_img_src(screen, NULL, LV_PART_MAIN);
}
void ui_helpers_style_dark_roller(lv_obj_t *roller, const lv_font_t *font)
{
    lv_obj_set_style_text_font(roller, font, LV_PART_MAIN);
    lv_obj_set_style_text_color(roller, lv_color_hex(0xFFFFFF), LV_PART_MAIN);
    lv_obj_set_style_bg_color(roller, lv_color_hex(0x222222), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(roller, 255, LV_PART_MAIN);
    lv_obj_set_style_border_width(roller, 1, LV_PART_MAIN);
    lv_obj_set_style_border_color(roller, lv_color_hex(0x444444), LV_PART_MAIN);
    lv_obj_set_style_radius(roller, 8, LV_PART_MAIN);
    lv_obj_set_style_text_font(roller, font, LV_PART_SELECTED);
    lv_obj_set_style_text_color(roller, lv_color_hex(0x000000), LV_PART_SELECTED);
    lv_obj_set_style_bg_color(roller, lv_color_hex(0xFFFFFF), LV_PART_SELECTED);
    lv_obj_set_style_bg_opa(roller, 255, LV_PART_SELECTED);
}

void ui_chart_apply_source(void)
{
    uint8_t item = s_sim_cfg.chart_source_idx;
    int32_t low, high, div;
    ui_disp_item_range(item, &low, &high, &div);
    lv_label_set_text(ui_LabelChartTitle, ui_disp_item_name(item));
    lv_label_set_text(ui_LabelChartUnit, ui_disp_item_unit(item));
    lv_obj_set_style_text_color(ui_LabelChartTitle, lv_color_hex(ui_disp_item_color(item)), LV_PART_MAIN);
    lv_obj_set_style_bg_color(ui_ChartDot, lv_color_hex(ui_disp_item_color(item)), LV_PART_MAIN);
    lv_chart_set_range(ui_ChartOilPressure, LV_CHART_AXIS_PRIMARY_Y, low, high);
}
void ui_needle_apply_source(void)
{
    if (!ui_NeedleMeter || !ui_NeedleScale) return;
    uint8_t item = s_sim_cfg.needle_source_idx;
    const needle_scale_meta_t *scale = ui_disp_item_scale((disp_item_t)item);
    lv_meter_set_scale_range(ui_NeedleMeter, ui_NeedleScale, scale->nmin, scale->nmax, 270, 135);
    lv_label_set_text(ui_NeedleNameLabel, ui_disp_item_name(item));
    lv_label_set_text(ui_NeedleUnitLabel, ui_disp_item_unit(item));
}

void gauge_display_set_brightness(uint8_t percent) { (void)percent; }
void esp_restart(void) {}
int xSemaphoreTake(SemaphoreHandle_t semaphore, int ticks) { (void)semaphore; (void)ticks; return 1; }
void xSemaphoreGive(SemaphoreHandle_t semaphore) { (void)semaphore; }

bool elm327_ble_is_connected(void) { return s_ble_connected; }
void elm327_ble_disconnect(void) { s_ble_connected = false; }
const char *elm327_ble_get_connected_name(void) { return s_sim_cfg.ble_device_name; }
void elm327_ble_scan_only_stop(void) {}
void elm327_ble_connect_by_addr(const uint8_t mac[6], const char *name)
{
    memcpy(s_sim_cfg.ble_obd_mac, mac, 6);
    strncpy(s_sim_cfg.ble_device_name, name, sizeof(s_sim_cfg.ble_device_name) - 1);
    s_ble_connected = true;
}
void elm327_ble_scan_only_start(int duration_s, ble_scan_found_cb_t callback)
{
    (void)duration_s;
    static const ble_scan_result_t devices[] = {
        { "ELM327-emulator", {0x02,0x00,0x00,0x00,0x00,0x01}, -28 },
        { "OBDII", {0x02,0x00,0x00,0x00,0x00,0x02}, -51 },
    };
    if (callback) { callback(&devices[0], 1); callback(&devices[1], 2); }
}
void gauge_pair_ble_scan_stop(void) {}
void gauge_pair_ble_scan_start(int duration_s, gauge_pair_scan_cb_t callback)
{
    (void)duration_s;
    static const gauge_pair_scan_result_t device = { "SkyGauge-A1B2", {2,3,4,5,6,7}, -42 };
    if (callback) callback(&device, 1);
}
void gauge_pair_ble_connect(const uint8_t addr[6], const char *name, gauge_pair_result_cb_t callback)
{
    if (callback) callback(true, name, addr);
}
bool espnow_link_slave_has_data(void) { return false; }
const char *espnow_link_get_master_name(void) { return ""; }
const uint8_t *espnow_link_get_bound_master_mac(void) { return s_bound_master; }
void espnow_link_bind_master(const uint8_t mac[6]) { memcpy(s_bound_master, mac, 6); }
void espnow_link_unbind_master(void) { memset(s_bound_master, 0, sizeof(s_bound_master)); }
void espnow_link_broadcast_threshold(uint16_t threshold) { (void)threshold; }
void espnow_link_trigger_linked_test(void) {}
void ui_rpm_flash_test_start(void) { s_rpm_flash_test_ticks = 20; }

#define EMPTY_EVENT(name) void name(lv_event_t *event) { LV_UNUSED(event); }
EMPTY_EVENT(ui_event_logo_background)
EMPTY_EVENT(ui_event_main_background)
EMPTY_EVENT(ui_event_gear_background)
EMPTY_EVENT(ui_event_rpm_background)
EMPTY_EVENT(ui_event_speed_background)
EMPTY_EVENT(ui_event_temp_background)
EMPTY_EVENT(ui_event_temp_custom_background)
EMPTY_EVENT(ui_event_info_background)
EMPTY_EVENT(ui_event_info_custom_background)
EMPTY_EVENT(ui_event_oil_pressure_background)
EMPTY_EVENT(ui_event_chart_config_background)
EMPTY_EVENT(ui_event_chart_alarm_background)
EMPTY_EVENT(ui_event_fuel_background)
EMPTY_EVENT(ui_event_trip_overview_background)
EMPTY_EVENT(ui_event_trip_history_background)
EMPTY_EVENT(ui_event_oil_warn_background)
EMPTY_EVENT(ui_event_rpm_warn_background)
EMPTY_EVENT(ui_event_needle_background)
EMPTY_EVENT(ui_event_needle_config_background)
EMPTY_EVENT(ui_event_easter_egg_background)
EMPTY_EVENT(ui_event_easter_egg_ota_button)
EMPTY_EVENT(ui_event_ble_scan_background)
EMPTY_EVENT(ui_event_settings_background)
EMPTY_EVENT(ui_event_multi_gauge_background)
EMPTY_EVENT(ui_event_obd_prot_background)

void sim_ui_create_pages(void)
{
    ui_ScreenPageLogo_screen_init();
    ui_ScreenPageGear_screen_init();
    ui_ScreenPageRpm_screen_init();
    ui_ScreenPageSpeed_screen_init();
    ui_ScreenPageTemp_screen_init();
    ui_ScreenPageTempCustom_screen_init();
    ui_ScreenPageInfo_screen_init();
    ui_ScreenPageInfoCustom_screen_init();
    ui_ScreenPageNeedle_screen_init();
    ui_ScreenPageOilPressure_screen_init();
    ui_ScreenPageNeedleConfig_screen_init();
    ui_ScreenPageChartConfig_screen_init();
    ui_ScreenPageChartAlarm_screen_init();
    ui_ScreenPageFuel_screen_init();
    ui_ScreenPageTripOverview_screen_init();
    ui_ScreenPageTripHistory_screen_init();
    ui_ScreenPageRpmWarn_screen_init();
    ui_ScreenPageEasterEgg_screen_init();
    ui_ScreenPageBLEScan_screen_init();
    ui_ScreenPageSettings_screen_init();
    ui_ScreenPageMultiGauge_screen_init();
    ui_ScreenPageODBProtocal_screen_init();
    lv_arc_set_range(ui_GearPageArcGearNumBack, 0, 100);
    lv_arc_set_range(ui_RpmPageArcRpmBack, 0, 8000);
    lv_arc_set_range(ui_SpeedPageArcSpeedBack, 0, 100);
    sim_ui_show_page(SIM_PAGE_TEMP);
}
