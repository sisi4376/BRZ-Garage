#include "../ui.h"
#include "../ui_rounding.h"
#include "bsp_obd_dsp/nvs_storage.h"

lv_obj_t *ui_ScreenPageTripIntervals = NULL;

static lv_obj_t *s_refuel_distance;
static lv_obj_t *s_refuel_time;
static lv_obj_t *s_refuel_fuel;
static lv_obj_t *s_custom_distance;
static lv_obj_t *s_custom_time;
static lv_obj_t *s_custom_fuel;

static lv_obj_t *interval_label(lv_obj_t *parent, const char *text,
                                const lv_font_t *font, uint32_t color,
                                lv_coord_t x, lv_coord_t y, lv_coord_t width)
{
    lv_obj_t *label = lv_label_create(parent);
    lv_label_set_text(label, text);
    if (width > 0) {
        lv_obj_set_width(label, width);
        lv_obj_set_style_text_align(label, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    }
    lv_obj_set_style_text_font(label, font, LV_PART_MAIN);
    lv_obj_set_style_text_color(label, lv_color_hex(color), LV_PART_MAIN);
    lv_obj_align(label, LV_ALIGN_CENTER, x, y);
    return label;
}

static void set_distance(lv_obj_t *label, uint64_t metres)
{
    uint64_t tenths_km = ui_round_div_u64(metres, 100ULL);
    lv_label_set_text_fmt(label, "%llu.%llu",
                          (unsigned long long)(tenths_km / 10ULL),
                          (unsigned long long)(tenths_km % 10ULL));
}

static void set_duration(lv_obj_t *label, uint64_t seconds)
{
    uint64_t minutes = ui_round_div_u64(seconds, 60ULL);
    lv_label_set_text_fmt(label, "%llu:%02llu",
                          (unsigned long long)(minutes / 60ULL),
                          (unsigned long long)(minutes % 60ULL));
}

static void set_fuel(lv_obj_t *label, uint64_t millilitres)
{
    uint64_t tenths_l = ui_round_div_u64(millilitres, 100ULL);
    lv_label_set_text_fmt(label, "%llu.%llu",
                          (unsigned long long)(tenths_l / 10ULL),
                          (unsigned long long)(tenths_l % 10ULL));
}

static void create_trip_block(lv_obj_t *screen, const char *title, uint32_t title_color,
                              lv_coord_t title_y, lv_coord_t heading_y,
                              lv_coord_t value_y, lv_coord_t unit_y,
                              lv_obj_t **distance, lv_obj_t **duration, lv_obj_t **fuel)
{
    interval_label(screen, title, &ui_font_FontTypoderSize16, title_color, 0, title_y, 0);
    interval_label(screen, "DISTANCE", &ui_font_FontTypoderSize16, 0x777777, -108, heading_y, 105);
    interval_label(screen, "DRIVE TIME", &ui_font_FontTypoderSize16, 0x777777, 0, heading_y, 105);
    interval_label(screen, "FUEL USED", &ui_font_FontTypoderSize16, 0x777777, 108, heading_y, 105);
    *distance = interval_label(screen, "0.0", &ui_font_FontTypoderSize24,
                               0xFFFFFF, -108, value_y, 112);
    *duration = interval_label(screen, "0:00", &ui_font_FontTypoderSize24,
                               0xFFFFFF, 0, value_y, 112);
    *fuel = interval_label(screen, "0.0", &ui_font_FontTypoderSize24,
                           0xFFFFFF, 108, value_y, 112);
    interval_label(screen, "KM", &ui_font_FontTypoderSize16, 0x777777, -108, unit_y, 0);
    interval_label(screen, "H:MM", &ui_font_FontTypoderSize16, 0x777777, 0, unit_y, 0);
    interval_label(screen, "L", &ui_font_FontTypoderSize16, 0x777777, 108, unit_y, 0);
}

void ui_trip_intervals_refresh(void)
{
    if (!ui_ScreenPageTripIntervals) return;
    nvs_refuel_meta_t refuel;
    nvs_custom_trip_meta_t custom;
    nvs_refuel_get_meta(&refuel);
    nvs_custom_trip_get_meta(&custom);
    set_distance(s_refuel_distance, refuel.current_distance_m);
    set_duration(s_refuel_time, refuel.current_duration_s);
    set_fuel(s_refuel_fuel, refuel.current_fuel_ml);
    set_distance(s_custom_distance, custom.distance_m);
    set_duration(s_custom_time, custom.duration_s);
    set_fuel(s_custom_fuel, custom.fuel_ml);
}

void ui_ScreenPageTripIntervals_screen_init(void)
{
    ui_ScreenPageTripIntervals = lv_obj_create(NULL);
    lv_obj_clear_flag(ui_ScreenPageTripIntervals, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_radius(ui_ScreenPageTripIntervals, 360, LV_PART_MAIN);
    ui_helpers_style_screen_bg(ui_ScreenPageTripIntervals);
    lv_obj_set_style_bg_opa(ui_ScreenPageTripIntervals, 255, LV_PART_MAIN);

    lv_obj_t *ring = ui_helpers_create_ring(ui_ScreenPageTripIntervals, 8);
    interval_label(ui_ScreenPageTripIntervals, "TRIP INTERVALS",
                   &ui_font_FontTypoderSize20, 0x44FF88, 0, -146, 0);
    create_trip_block(ui_ScreenPageTripIntervals, "SINCE REFUEL", 0x44AAFF,
                      -112, -85, -56, -31,
                      &s_refuel_distance, &s_refuel_time, &s_refuel_fuel);

    lv_obj_t *divider = lv_obj_create(ui_ScreenPageTripIntervals);
    lv_obj_remove_style_all(divider);
    lv_obj_set_size(divider, 270, 1);
    lv_obj_align(divider, LV_ALIGN_CENTER, 0, 1);
    lv_obj_set_style_bg_color(divider, lv_color_hex(0x333333), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(divider, 255, LV_PART_MAIN);

    create_trip_block(ui_ScreenPageTripIntervals, "CUSTOM TRIP", 0xFFAA44,
                      24, 51, 80, 105,
                      &s_custom_distance, &s_custom_time, &s_custom_fuel);
    interval_label(ui_ScreenPageTripIntervals, "SWIPE DOWN  TRIP OVERVIEW",
                   &ui_font_FontTypoderSize16, 0x555555, 0, 135, 0);

    lv_obj_t *ear = lv_img_create(ui_ScreenPageTripIntervals);
    lv_img_set_src(ear, &ui_img_pngblackear_png);
    lv_obj_align(ear, LV_ALIGN_CENTER, 0, -142);
    lv_obj_add_flag(ear, LV_OBJ_FLAG_ADV_HITTEST);
    lv_obj_clear_flag(ear, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_move_foreground(ring);
    lv_obj_add_event_cb(ui_ScreenPageTripIntervals, ui_event_trip_intervals_background,
                        LV_EVENT_GESTURE, NULL);
    ui_trip_intervals_refresh();
}
