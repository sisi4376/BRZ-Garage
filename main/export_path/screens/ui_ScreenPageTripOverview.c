#include "../ui.h"
#include "../ui_rounding.h"
#include "bsp_obd_dsp/nvs_storage.h"

lv_obj_t *ui_ScreenPageTripOverview = NULL;

static lv_obj_t *s_current_distance;
static lv_obj_t *s_current_time;
static lv_obj_t *s_total_distance;
static lv_obj_t *s_total_time;
static lv_obj_t *s_total_fuel;

static lv_obj_t *overview_label(lv_obj_t *parent, const char *text,
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

void ui_trip_overview_refresh(void)
{
    if (!ui_ScreenPageTripOverview) return;
    nvs_fuel_snapshot_t data;
    nvs_fuel_get_snapshot(&data);
    set_distance(s_current_distance, data.current_distance_m);
    set_duration(s_current_time, data.current_duration_s);
    set_distance(s_total_distance, data.lifetime_distance_m);
    set_duration(s_total_time, data.lifetime_duration_s);
    uint64_t fuel_tenths_l = ui_round_div_u64(data.lifetime_fuel_ml, 100ULL);
    lv_label_set_text_fmt(s_total_fuel, "%llu.%llu",
                          (unsigned long long)(fuel_tenths_l / 10ULL),
                          (unsigned long long)(fuel_tenths_l % 10ULL));
}

void ui_ScreenPageTripOverview_screen_init(void)
{
    ui_ScreenPageTripOverview = lv_obj_create(NULL);
    lv_obj_clear_flag(ui_ScreenPageTripOverview, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_radius(ui_ScreenPageTripOverview, 360, LV_PART_MAIN);
    ui_helpers_style_screen_bg(ui_ScreenPageTripOverview);
    lv_obj_set_style_bg_opa(ui_ScreenPageTripOverview, 255, LV_PART_MAIN);

    lv_obj_t *ring = ui_helpers_create_ring(ui_ScreenPageTripOverview, 8);
    overview_label(ui_ScreenPageTripOverview, "TRIP OVERVIEW",
                   &ui_font_FontTypoderSize20, 0x44FF88, 0, -146, 0);

    overview_label(ui_ScreenPageTripOverview, "CURRENT TRIP",
                   &ui_font_FontTypoderSize16, 0x44AAFF, 0, -112, 0);
    overview_label(ui_ScreenPageTripOverview, "DISTANCE",
                   &ui_font_FontTypoderSize16, 0x777777, -92, -85, 130);
    overview_label(ui_ScreenPageTripOverview, "DRIVE TIME",
                   &ui_font_FontTypoderSize16, 0x777777, 92, -85, 130);
    s_current_distance = overview_label(ui_ScreenPageTripOverview, "0.0",
                                        &ui_font_FontTypoderSize36, 0xFFFFFF, -92, -52, 150);
    s_current_time = overview_label(ui_ScreenPageTripOverview, "0:00",
                                    &ui_font_FontTypoderSize36, 0xFFFFFF, 92, -52, 150);
    overview_label(ui_ScreenPageTripOverview, "KM",
                   &ui_font_FontTypoderSize16, 0x777777, -92, -23, 0);
    overview_label(ui_ScreenPageTripOverview, "H:MM",
                   &ui_font_FontTypoderSize16, 0x777777, 92, -23, 0);

    lv_obj_t *divider = lv_obj_create(ui_ScreenPageTripOverview);
    lv_obj_remove_style_all(divider);
    lv_obj_set_size(divider, 270, 1);
    lv_obj_align(divider, LV_ALIGN_CENTER, 0, 1);
    lv_obj_set_style_bg_color(divider, lv_color_hex(0x333333), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(divider, 255, LV_PART_MAIN);

    overview_label(ui_ScreenPageTripOverview, "LIFETIME",
                   &ui_font_FontTypoderSize16, 0xFFAA44, 0, 24, 0);
    overview_label(ui_ScreenPageTripOverview, "DISTANCE",
                   &ui_font_FontTypoderSize16, 0x777777, -108, 51, 105);
    overview_label(ui_ScreenPageTripOverview, "DRIVE TIME",
                   &ui_font_FontTypoderSize16, 0x777777, 0, 51, 105);
    overview_label(ui_ScreenPageTripOverview, "FUEL USED",
                   &ui_font_FontTypoderSize16, 0x777777, 108, 51, 105);
    s_total_distance = overview_label(ui_ScreenPageTripOverview, "0.0",
                                      &ui_font_FontTypoderSize24, 0xFFFFFF, -108, 80, 112);
    s_total_time = overview_label(ui_ScreenPageTripOverview, "0:00",
                                  &ui_font_FontTypoderSize24, 0xFFFFFF, 0, 80, 112);
    s_total_fuel = overview_label(ui_ScreenPageTripOverview, "0.0",
                                  &ui_font_FontTypoderSize24, 0xFFFFFF, 108, 80, 112);
    overview_label(ui_ScreenPageTripOverview, "KM",
                   &ui_font_FontTypoderSize16, 0x777777, -108, 105, 0);
    overview_label(ui_ScreenPageTripOverview, "H:MM",
                   &ui_font_FontTypoderSize16, 0x777777, 0, 105, 0);
    overview_label(ui_ScreenPageTripOverview, "L",
                   &ui_font_FontTypoderSize16, 0x777777, 108, 105, 0);
    overview_label(ui_ScreenPageTripOverview, "SWIPE UP  TRIP INTERVALS",
                   &ui_font_FontTypoderSize16, 0x555555, 0, 135, 0);

    lv_obj_t *ear = lv_img_create(ui_ScreenPageTripOverview);
    lv_img_set_src(ear, &ui_img_pngblackear_png);
    lv_obj_align(ear, LV_ALIGN_CENTER, 0, -142);
    lv_obj_add_flag(ear, LV_OBJ_FLAG_ADV_HITTEST);
    lv_obj_clear_flag(ear, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_move_foreground(ring);
    lv_obj_add_event_cb(ui_ScreenPageTripOverview, ui_event_trip_overview_background,
                        LV_EVENT_GESTURE, NULL);
    ui_trip_overview_refresh();
}
