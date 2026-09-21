#include "../ui.h"
#include "../ui_rounding.h"
#include "bsp_obd_dsp/nvs_storage.h"

lv_obj_t *ui_ScreenPageTripHistory = NULL;
static lv_obj_t *s_trip_rows[4];
static lv_obj_t *s_trip_empty;
static lv_obj_t *s_trip_title;
static unsigned s_trip_page;

void ui_trip_history_page(int delta)
{
    nvs_fuel_snapshot_t data;
    nvs_fuel_get_snapshot(&data);
    unsigned pages = data.history_count ? (data.history_count + 3U) / 4U : 1U;
    int next = (int)s_trip_page + delta;
    if (next < 0) next = 0;
    if ((unsigned)next >= pages) next = (int)pages - 1;
    s_trip_page = (unsigned)next;
    ui_trip_history_refresh();
}

void ui_trip_history_refresh(void)
{
    if (!ui_ScreenPageTripHistory) return;
    nvs_fuel_snapshot_t data;
    nvs_fuel_get_snapshot(&data);
    unsigned pages = data.history_count ? (data.history_count + 3U) / 4U : 1U;
    if (s_trip_page >= pages) s_trip_page = pages - 1U;
    if (s_trip_title) {
        lv_label_set_text_fmt(s_trip_title, "TRIP HISTORY  %u/%u",
                              s_trip_page + 1U, pages);
    }
    if (data.history_count) lv_obj_add_flag(s_trip_empty, LV_OBJ_FLAG_HIDDEN);
    else lv_obj_clear_flag(s_trip_empty, LV_OBJ_FLAG_HIDDEN);
    for (unsigned i = 0; i < 4; ++i) {
        unsigned index = s_trip_page * 4U + i;
        if (index >= data.history_count) {
            lv_obj_add_flag(s_trip_rows[i], LV_OBJ_FLAG_HIDDEN);
            continue;
        }
        lv_obj_clear_flag(s_trip_rows[i], LV_OBJ_FLAG_HIDDEN);
        const nvs_fuel_trip_record_t *trip = &data.history[index];
        uint32_t total_minutes = (uint32_t)ui_round_div_u64(trip->duration_s, 60U);
        uint32_t hours = total_minutes / 60U;
        uint32_t minutes = total_minutes % 60U;
        uint32_t distance_tenths_km = (uint32_t)ui_round_div_u64(trip->distance_m, 100U);
        uint32_t average_tenths = (uint32_t)ui_round_div_u64(trip->avg_l100_x100, 10U);
        lv_label_set_text_fmt(s_trip_rows[i],
                              "#%lu   %02lu:%02lu   %lu.%lukm   %u.%u",
                              (unsigned long)trip->id,
                              (unsigned long)hours, (unsigned long)minutes,
                              (unsigned long)(distance_tenths_km / 10U),
                              (unsigned long)(distance_tenths_km % 10U),
                              average_tenths / 10U,
                              average_tenths % 10U);
    }
}

void ui_ScreenPageTripHistory_screen_init(void)
{
    ui_ScreenPageTripHistory = lv_obj_create(NULL);
    lv_obj_clear_flag(ui_ScreenPageTripHistory, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_radius(ui_ScreenPageTripHistory, 360, LV_PART_MAIN);
    ui_helpers_style_screen_bg(ui_ScreenPageTripHistory);
    lv_obj_set_style_bg_opa(ui_ScreenPageTripHistory, 255, LV_PART_MAIN);
    lv_obj_t *ring = ui_helpers_create_ring(ui_ScreenPageTripHistory, 8);

    s_trip_title = lv_label_create(ui_ScreenPageTripHistory);
    lv_label_set_text(s_trip_title, "TRIP HISTORY");
    lv_obj_set_style_text_font(s_trip_title, &ui_font_FontTypoderSize20, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_trip_title, lv_color_hex(0x44FF88), LV_PART_MAIN);
    lv_obj_align(s_trip_title, LV_ALIGN_CENTER, 0, -142);

    lv_obj_t *header = lv_label_create(ui_ScreenPageTripHistory);
    lv_label_set_text(header, "TRIP    TIME      DIST      AVG L/100");
    lv_obj_set_style_text_font(header, &ui_font_FontTypoderSize16, LV_PART_MAIN);
    lv_obj_set_style_text_color(header, lv_color_hex(0x666666), LV_PART_MAIN);
    lv_obj_align(header, LV_ALIGN_CENTER, 0, -102);

    for (unsigned i = 0; i < 4; ++i) {
        s_trip_rows[i] = lv_label_create(ui_ScreenPageTripHistory);
        lv_label_set_text(s_trip_rows[i], "");
        lv_obj_set_width(s_trip_rows[i], 320);
        lv_obj_set_style_text_align(s_trip_rows[i], LV_TEXT_ALIGN_LEFT, LV_PART_MAIN);
        lv_obj_set_style_text_font(s_trip_rows[i], &ui_font_FontTypoderSize16, LV_PART_MAIN);
        lv_obj_set_style_text_color(s_trip_rows[i], lv_color_hex(i ? 0xBBBBBB : 0xFFFFFF), LV_PART_MAIN);
        lv_obj_align(s_trip_rows[i], LV_ALIGN_CENTER, 0, -58 + (lv_coord_t)i * 44);
    }

    s_trip_empty = lv_label_create(ui_ScreenPageTripHistory);
    lv_label_set_text(s_trip_empty, "NO COMPLETED TRIPS YET\nSAVED 15 MIN AFTER ENGINE OFF");
    lv_obj_set_width(s_trip_empty, 300);
    lv_obj_set_style_text_align(s_trip_empty, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_set_style_text_font(s_trip_empty, &ui_font_FontTypoderSize16, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_trip_empty, lv_color_hex(0x888888), LV_PART_MAIN);
    lv_obj_align(s_trip_empty, LV_ALIGN_CENTER, 0, 0);

    lv_obj_t *hint = lv_label_create(ui_ScreenPageTripHistory);
    lv_label_set_text(hint, "UP/DOWN PAGES");
    lv_obj_set_style_text_font(hint, &ui_font_FontTypoderSize16, LV_PART_MAIN);
    lv_obj_set_style_text_color(hint, lv_color_hex(0x555555), LV_PART_MAIN);
    lv_obj_align(hint, LV_ALIGN_CENTER, 0, 132);

    lv_obj_move_foreground(ring);
    lv_obj_add_event_cb(ui_ScreenPageTripHistory, ui_event_trip_history_background,
                        LV_EVENT_GESTURE, NULL);
    ui_trip_history_refresh();
}
