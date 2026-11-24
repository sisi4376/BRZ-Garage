// Three-slot information page. Each row can display any configured signal.

#include "../ui.h"
#include <string.h>

// Value labels (externally accessible from timer callback)
lv_obj_t *ui_LabelCoolantTempText = NULL;
lv_obj_t *ui_LabelOilTempText     = NULL;  // real oil temp °C (SSM 22 10 17, A-40)
lv_obj_t *ui_LabelIntakeTempText  = NULL;
lv_obj_t *ui_LabelTempValue[3]    = {NULL, NULL, NULL};
lv_obj_t *ui_LabelTempName[3]     = {NULL, NULL, NULL};
lv_obj_t *ui_LabelTempUnit[3]     = {NULL, NULL, NULL};
lv_obj_t *ui_LabelTempDot[3]      = {NULL, NULL, NULL};  // colored dot at row start (color refreshed per data item)

// Helper: colored circle dot
static lv_obj_t *create_color_dot(lv_obj_t *parent, lv_color_t color, lv_coord_t x, lv_coord_t y)
{
    lv_obj_t *dot = lv_obj_create(parent);
    lv_obj_remove_style_all(dot);
    lv_obj_set_size(dot, 10, 10);
    lv_obj_set_style_radius(dot, LV_RADIUS_CIRCLE, LV_PART_MAIN);
    lv_obj_set_style_bg_color(dot, color, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(dot, 255, LV_PART_MAIN);
    lv_obj_align(dot, LV_ALIGN_LEFT_MID, x, y);
    lv_obj_clear_flag(dot, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE);
    return dot;
}

// Use the large face for normal readings, including four-digit RPM. The
// content-sized label stays centered when a wider signed or five-digit value arrives.
static void fit_value(lv_event_t *event)
{
    lv_obj_t *value = lv_event_get_target(event);
    const char *text = lv_label_get_text(value);
    const lv_font_t *font = &ui_font_FontTypoderSize56;
    lv_coord_t spacing = lv_obj_get_style_text_letter_space(value, LV_PART_MAIN);
    if (lv_txt_get_width(text, strlen(text), font, spacing, LV_TEXT_FLAG_NONE) > 202)
        font = &ui_font_FontTypoderSize40;
    if (lv_obj_get_style_text_font(value, LV_PART_MAIN) != font)
        lv_obj_set_style_text_font(value, font, LV_PART_MAIN);
}

static void make_row(unsigned slot, lv_coord_t y, lv_coord_t inset, lv_color_t color, const char *name)
{
    lv_obj_t *parent = ui_ScreenPageTemp;
    ui_LabelTempDot[slot] = create_color_dot(parent, color, inset, y);

    lv_obj_t *label = ui_LabelTempName[slot] = lv_label_create(parent);
    lv_label_set_text(label, name);
    lv_obj_set_style_text_font(label, &lv_font_montserrat_32, LV_PART_MAIN);
    lv_obj_set_style_text_color(label, color, LV_PART_MAIN);
    lv_obj_align(label, LV_ALIGN_LEFT_MID, inset + 14, y);

    lv_obj_t *value = ui_LabelTempValue[slot] = lv_label_create(parent);
    lv_label_set_text(value, "--");
    lv_obj_set_style_text_font(value, &ui_font_FontTypoderSize56, LV_PART_MAIN);
    lv_obj_set_style_text_color(value, lv_color_hex(0xFFFFFF), LV_PART_MAIN);
    lv_label_set_long_mode(value, LV_LABEL_LONG_CLIP);
    lv_obj_set_style_text_letter_space(value, -2, LV_PART_MAIN);
    lv_obj_align(value, LV_ALIGN_CENTER, 5, y);
    lv_obj_add_event_cb(value, fit_value, LV_EVENT_SIZE_CHANGED, NULL);

    lv_obj_t *unit = ui_LabelTempUnit[slot] = lv_label_create(parent);
    lv_label_set_text(unit, "°C");
    lv_obj_set_style_text_font(unit, &lv_font_montserrat_32, LV_PART_MAIN);
    lv_obj_set_style_text_color(unit, lv_color_hex(0xD6D6D6), LV_PART_MAIN);
    lv_obj_align(unit, LV_ALIGN_LEFT_MID, 341, y);
}

// Helper: horizontal divider line
static void make_hdiv(lv_obj_t *parent, lv_coord_t y, lv_coord_t w)
{
    lv_obj_t *div = lv_obj_create(parent);
    lv_obj_remove_style_all(div);
    lv_obj_set_size(div, w, 1);
    lv_obj_align(div, LV_ALIGN_CENTER, 0, y);
    lv_obj_set_style_bg_color(div, lv_color_hex(0xFFFFFF), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(div, 50, LV_PART_MAIN);
    lv_obj_clear_flag(div, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE);
}

void ui_ScreenPageTemp_screen_init(void)
{
    ui_ScreenPageTemp = lv_obj_create(NULL);
    lv_obj_clear_flag(ui_ScreenPageTemp, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_radius(ui_ScreenPageTemp, 360, LV_PART_MAIN | LV_STATE_DEFAULT);
    ui_helpers_style_screen_bg(ui_ScreenPageTemp);
    lv_obj_set_style_bg_opa(ui_ScreenPageTemp, 255, LV_PART_MAIN | LV_STATE_DEFAULT);

    lv_obj_t *spinner_ring = ui_helpers_create_ring(ui_ScreenPageTemp, 10);

    // OBD and TIME occupy the top band. The first row starts below the badge.
    // Row insets follow the inner circle at each row height.
    make_row(0, -84, 43, lv_color_hex(0x44AAFF), "CLT");
    make_hdiv(ui_ScreenPageTemp, -41, 346);
    make_row(1, 2, 25, lv_color_hex(0x44FF88), "IAT");
    make_hdiv(ui_ScreenPageTemp, 45, 346);
    make_row(2, 88, 45, lv_color_hex(0xFF7722), "OIL");

    // Backward compatibility for existing update code
    ui_LabelCoolantTempText = ui_LabelTempValue[0];
    ui_LabelIntakeTempText = ui_LabelTempValue[1];
    ui_LabelOilTempText = ui_LabelTempValue[2];

    // Black ear image at top
    lv_obj_t *black_ear = lv_img_create(ui_ScreenPageTemp);
    lv_img_set_src(black_ear, &ui_img_pngblackear_png);
    lv_obj_set_width(black_ear, LV_SIZE_CONTENT);
    lv_obj_set_height(black_ear, LV_SIZE_CONTENT);
    lv_obj_set_x(black_ear, 0);
    lv_obj_set_y(black_ear, -142);
    lv_obj_set_align(black_ear, LV_ALIGN_CENTER);
    lv_obj_add_flag(black_ear, LV_OBJ_FLAG_ADV_HITTEST);
    lv_obj_clear_flag(black_ear, LV_OBJ_FLAG_SCROLLABLE);

    // Events
    lv_obj_move_foreground(spinner_ring);   // bring the ring to the front
    lv_obj_add_event_cb(ui_ScreenPageTemp, ui_event_temp_background, LV_EVENT_GESTURE, NULL);
}
