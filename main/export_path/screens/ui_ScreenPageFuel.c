#include "../ui.h"
#include "../ui_rounding.h"
#include "bsp_obd_dsp/nvs_storage.h"

lv_obj_t *ui_ScreenPageFuel = NULL;
lv_obj_t *ui_LabelFuelInstant = NULL;
lv_obj_t *ui_LabelFuelInstantUnit = NULL;
lv_obj_t *ui_LabelFuelTripAverage = NULL;
lv_obj_t *ui_LabelFuelLifetimeAverage = NULL;
static lv_obj_t *s_fuel_title;
static bool s_fuel_rendered;
static nvs_fuel_snapshot_t s_last_fuel_render;

static lv_obj_t *fuel_label(lv_obj_t *parent, const char *text,
                            const lv_font_t *font, uint32_t color,
                            lv_coord_t x, lv_coord_t y)
{
    lv_obj_t *label = lv_label_create(parent);
    lv_label_set_text(label, text);
    lv_obj_set_style_text_font(label, font, LV_PART_MAIN);
    lv_obj_set_style_text_color(label, lv_color_hex(color), LV_PART_MAIN);
    lv_obj_align(label, LV_ALIGN_CENTER, x, y);
    return label;
}

static void fuel_set_x100(lv_obj_t *label, bool valid, uint16_t value)
{
    if (!label) return;
    if (!valid) lv_label_set_text(label, "--.-");
    else {
        uint32_t tenths = (uint32_t)ui_round_div_u64(value, 10U);
        lv_label_set_text_fmt(label, "%lu.%lu",
                              (unsigned long)(tenths / 10U),
                              (unsigned long)(tenths % 10U));
    }
}

void ui_fuel_page_refresh(void)
{
    if (!ui_ScreenPageFuel) return;
    nvs_fuel_snapshot_t data;
    nvs_fuel_get_snapshot(&data);
    if (s_fuel_title && (!s_fuel_rendered || data.source != s_last_fuel_render.source)) {
        const char *title = "FUEL / NO DATA";
        if (data.source == FUEL_SOURCE_ECU) title = "FUEL / ECU";
        else if (data.source == FUEL_SOURCE_MAF) title = "FUEL / MAF";
        else if (data.source == FUEL_SOURCE_CUT_EST) title = "FUEL / CUT EST";
        else if (data.source == FUEL_SOURCE_ENGINE_OFF) title = "FUEL / OFF";
        lv_label_set_text(s_fuel_title, title);
    }
    if (!s_fuel_rendered || data.data_valid != s_last_fuel_render.data_valid ||
        data.instant_x100 != s_last_fuel_render.instant_x100) {
        fuel_set_x100(ui_LabelFuelInstant, data.data_valid, data.instant_x100);
    }
    if (!s_fuel_rendered || data.current_avg_valid != s_last_fuel_render.current_avg_valid ||
        data.current_avg_x100 != s_last_fuel_render.current_avg_x100) {
        fuel_set_x100(ui_LabelFuelTripAverage, data.current_avg_valid, data.current_avg_x100);
    }
    if (!s_fuel_rendered || data.lifetime_avg_valid != s_last_fuel_render.lifetime_avg_valid ||
        data.lifetime_avg_x100 != s_last_fuel_render.lifetime_avg_x100) {
        fuel_set_x100(ui_LabelFuelLifetimeAverage, data.lifetime_avg_valid, data.lifetime_avg_x100);
    }
    if (ui_LabelFuelInstantUnit &&
        (!s_fuel_rendered || data.instant_is_lph != s_last_fuel_render.instant_is_lph)) {
        lv_label_set_text(ui_LabelFuelInstantUnit,
                          data.instant_is_lph ? "L/h" : "L/100km");
    }
    s_last_fuel_render = data;
    s_fuel_rendered = true;
}

void ui_ScreenPageFuel_screen_init(void)
{
    s_fuel_rendered = false;
    ui_ScreenPageFuel = lv_obj_create(NULL);
    lv_obj_clear_flag(ui_ScreenPageFuel, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_radius(ui_ScreenPageFuel, 360, LV_PART_MAIN);
    ui_helpers_style_screen_bg(ui_ScreenPageFuel);
    lv_obj_set_style_bg_opa(ui_ScreenPageFuel, 255, LV_PART_MAIN);

    lv_obj_t *ring = ui_helpers_create_ring(ui_ScreenPageFuel, 8);
    s_fuel_title = fuel_label(ui_ScreenPageFuel, "FUEL", &ui_font_FontTypoderSize20,
               0x666666, 0, -148);

    fuel_label(ui_ScreenPageFuel, "INSTANT", &ui_font_FontTypoderSize20,
               0x44FF88, 0, -105);
    ui_LabelFuelInstant = fuel_label(ui_ScreenPageFuel, "--.-",
                                    &ui_font_FontTypoderSize56, 0xFFFFFF, -18, -62);
    ui_LabelFuelInstantUnit = fuel_label(ui_ScreenPageFuel, "L/100km",
                                        &ui_font_FontTypoderSize16, 0x888888, 92, -55);

    lv_obj_t *divider = lv_obj_create(ui_ScreenPageFuel);
    lv_obj_remove_style_all(divider);
    lv_obj_set_size(divider, 240, 1);
    lv_obj_align(divider, LV_ALIGN_CENTER, 0, -12);
    lv_obj_set_style_bg_color(divider, lv_color_hex(0x333333), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(divider, 255, LV_PART_MAIN);

    fuel_label(ui_ScreenPageFuel, "CURRENT TRIP", &ui_font_FontTypoderSize16,
               0x44AAFF, -82, 25);
    ui_LabelFuelTripAverage = fuel_label(ui_ScreenPageFuel, "--.-",
                                        &ui_font_FontTypoderSize40, 0xFFFFFF, -82, 61);
    fuel_label(ui_ScreenPageFuel, "L/100km", &ui_font_FontTypoderSize16,
               0x888888, -82, 92);

    fuel_label(ui_ScreenPageFuel, "LIFETIME", &ui_font_FontTypoderSize16,
               0xFFAA44, 82, 25);
    ui_LabelFuelLifetimeAverage = fuel_label(ui_ScreenPageFuel, "--.-",
                                            &ui_font_FontTypoderSize40, 0xFFFFFF, 82, 61);
    fuel_label(ui_ScreenPageFuel, "L/100km", &ui_font_FontTypoderSize16,
               0x888888, 82, 92);

    lv_obj_t *ear = lv_img_create(ui_ScreenPageFuel);
    lv_img_set_src(ear, &ui_img_pngblackear_png);
    lv_obj_align(ear, LV_ALIGN_CENTER, 0, -142);
    lv_obj_add_flag(ear, LV_OBJ_FLAG_ADV_HITTEST);
    lv_obj_clear_flag(ear, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_move_foreground(ring);
    lv_obj_add_event_cb(ui_ScreenPageFuel, ui_event_fuel_background,
                        LV_EVENT_GESTURE, NULL);
    ui_fuel_page_refresh();
}
