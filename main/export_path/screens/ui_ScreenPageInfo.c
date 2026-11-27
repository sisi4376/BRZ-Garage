#include "../ui.h"
#include <stdio.h>
#include <string.h>

lv_obj_t *ui_ScreenPageInfo  = NULL;
lv_obj_t *ui_LabelInfoCLT    = NULL;
lv_obj_t *ui_LabelInfoIAT    = NULL;
lv_obj_t *ui_LabelInfoLoad   = NULL;
lv_obj_t *ui_LabelInfoTPS    = NULL;
lv_obj_t *ui_LabelInfoOil    = NULL;  // oil temp °C (SSM 22 10 17)
lv_obj_t *ui_LabelInfoValue[5] = {NULL, NULL, NULL, NULL, NULL};
lv_obj_t *ui_LabelInfoName[5]  = {NULL, NULL, NULL, NULL, NULL};
lv_obj_t *ui_LabelInfoUnit[5]  = {NULL, NULL, NULL, NULL, NULL};

static lv_obj_t *gps_value;

void ui_format_gps_text(char *text, size_t size, int32_t lat, int32_t lon)
{
    snprintf(text, size, "%.2f %c  %.2f %c",
             (double)(lat < 0 ? -lat : lat) / 100000, lat < 0 ? 'S' : 'N',
             (double)(lon < 0 ? -lon : lon) / 100000, lon < 0 ? 'W' : 'E');
}

void ui_gps_readout(lv_obj_t *gps, lv_obj_t *reading, const char *text)
{
    if (!text) {
        lv_obj_add_flag(gps, LV_OBJ_FLAG_HIDDEN);
        lv_obj_clear_flag(reading, LV_OBJ_FLAG_HIDDEN);
        return;
    }
    if (text[0] == '\0') text = "--";
    lv_obj_add_flag(reading, LV_OBJ_FLAG_HIDDEN);
    lv_obj_clear_flag(gps, LV_OBJ_FLAG_HIDDEN);
    if (strcmp(lv_label_get_text(gps), text)) lv_label_set_text(gps, text);
}

void ui_info_gps_text(const char *text)
{
    ui_gps_readout(gps_value, ui_LabelInfoValue[4], text);
}

// Fit the actual formatted reading to one column, including signed decimals.
static void fit_info_value(lv_event_t *event)
{
    lv_obj_t *label = lv_event_get_target(event);
    const char *text = lv_label_get_text(label);
    uint32_t length = strlen(text);
    lv_coord_t spacing = lv_obj_get_style_text_letter_space(label, LV_PART_MAIN);
    const lv_font_t *font = &ui_font_FontTypoderSize56;
    if (lv_txt_get_width(text, length, font, spacing, LV_TEXT_FLAG_NONE) > 182) {
        font = &ui_font_FontTypoderSize44;
        if (lv_txt_get_width(text, length, font, spacing, LV_TEXT_FLAG_NONE) > 182)
            font = &ui_font_FontTypoderSize36;
    }
    if (lv_obj_get_style_text_font(label, LV_PART_MAIN) != font)
        lv_obj_set_style_text_font(label, font, LV_PART_MAIN);
}

static lv_obj_t *create_info_tile(lv_obj_t *parent,
                                   lv_obj_t **name_out,
                                   lv_obj_t **unit_out,
                                   const char *name, lv_color_t name_color,
                                   const char *unit,
                                   lv_coord_t cx, lv_coord_t cy, lv_coord_t label_offset)
{
    // Name label
    lv_obj_t *lbl_name = lv_label_create(parent);
    lv_label_set_text(lbl_name, name);
    lv_obj_set_style_text_font(lbl_name, &ui_font_FontTypoderSize28, LV_PART_MAIN);
    lv_obj_set_style_text_color(lbl_name, name_color, LV_PART_MAIN);
    lv_obj_align(lbl_name, LV_ALIGN_CENTER, cx, cy - label_offset);
    if (name_out) *name_out = lbl_name;

    // Value label (returned so caller can update it)
    lv_obj_t *lbl_val = lv_label_create(parent);
    lv_label_set_text(lbl_val, "--");
    lv_obj_set_style_text_font(lbl_val, &ui_font_FontTypoderSize56, LV_PART_MAIN);
    lv_obj_set_style_text_color(lbl_val, lv_color_hex(0xFFFFFF), LV_PART_MAIN);
    lv_label_set_long_mode(lbl_val, LV_LABEL_LONG_CLIP);
    lv_obj_add_event_cb(lbl_val, fit_info_value, LV_EVENT_SIZE_CHANGED, NULL);
    lv_obj_set_style_text_align(lbl_val, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_align(lbl_val, LV_ALIGN_CENTER, cx, cy);

    // Unit label
    lv_obj_t *lbl_unit = lv_label_create(parent);
    lv_label_set_text(lbl_unit, unit);
    lv_obj_set_style_text_font(lbl_unit, &ui_font_FontTypoderSize28, LV_PART_MAIN);
    lv_obj_set_style_text_color(lbl_unit, lv_color_hex(0x888888), LV_PART_MAIN);
    lv_obj_align(lbl_unit, LV_ALIGN_CENTER, cx, cy + label_offset);
    if (unit_out) *unit_out = lbl_unit;

    return lbl_val;
}

// Helper: horizontal divider  (y = offset from screen center, places center of 1px line)
static void create_hdiv(lv_obj_t *parent, lv_coord_t y, lv_coord_t w)
{
    lv_obj_t *div = lv_obj_create(parent);
    lv_obj_remove_style_all(div);
    lv_obj_set_width(div, w);
    lv_obj_set_height(div, 1);
    lv_obj_align(div, LV_ALIGN_CENTER, 0, y);   // center of div at (parent_cx, parent_cy+y)
    lv_obj_set_style_bg_color(div, lv_color_hex(0x333333), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(div, 255, LV_PART_MAIN);
    lv_obj_clear_flag(div, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE);
}

// Helper: vertical divider
static void create_vdiv(lv_obj_t *parent, lv_coord_t x, lv_coord_t y, lv_coord_t h)
{
    lv_obj_t *div = lv_obj_create(parent);
    lv_obj_remove_style_all(div);
    lv_obj_set_width(div, 1);
    lv_obj_set_height(div, h);
    lv_obj_align(div, LV_ALIGN_CENTER, x, y);
    lv_obj_set_style_bg_color(div, lv_color_hex(0x333333), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(div, 255, LV_PART_MAIN);
    lv_obj_clear_flag(div, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE);
}

void ui_ScreenPageInfo_screen_init(void)
{
    ui_ScreenPageInfo = lv_obj_create(NULL);
    lv_obj_clear_flag(ui_ScreenPageInfo, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_radius(ui_ScreenPageInfo, 360, LV_PART_MAIN | LV_STATE_DEFAULT);
    ui_helpers_style_screen_bg(ui_ScreenPageInfo);
    lv_obj_set_style_bg_opa(ui_ScreenPageInfo, 255, LV_PART_MAIN | LV_STATE_DEFAULT);

    // Outer spinner ring (decorative, white)
    lv_obj_t *ring = ui_helpers_create_ring(ui_ScreenPageInfo, 8);   // white ring: static circular border, replaces the rotating spinner, removes the arc seam gap

    // Black ear image at top
    lv_obj_t *black_ear = lv_img_create(ui_ScreenPageInfo);
    lv_img_set_src(black_ear, &ui_img_pngblackear_png);
    lv_obj_set_width(black_ear, LV_SIZE_CONTENT);
    lv_obj_set_height(black_ear, LV_SIZE_CONTENT);
    lv_obj_align(black_ear, LV_ALIGN_CENTER, 0, -142);
    lv_obj_add_flag(black_ear, LV_OBJ_FLAG_ADV_HITTEST);
    lv_obj_clear_flag(black_ear, LV_OBJ_FLAG_SCROLLABLE);

    // Page title
    lv_obj_t *title = lv_label_create(ui_ScreenPageInfo);
    lv_label_set_text(title, "INFO");
    lv_obj_set_style_text_font(title, &ui_font_FontTypoderSize20, LV_PART_MAIN);
    lv_obj_set_style_text_color(title, lv_color_hex(0x666666), LV_PART_MAIN);
    lv_obj_align(title, LV_ALIGN_CENTER, 0, -148);

    // Two paired rows and a centered fifth slot on the 466 px round screen.
    create_hdiv(ui_ScreenPageInfo, -27, 316);
    create_hdiv(ui_ScreenPageInfo, 87, 244);
    create_vdiv(ui_ScreenPageInfo, 0, -25, 222);

    ui_LabelInfoCLT  = create_info_tile(ui_ScreenPageInfo,
                                        &ui_LabelInfoName[0], &ui_LabelInfoUnit[0],
                                        "CLT", lv_color_hex(0x44AAFF), "°C",
                                        -96, -84, 38);
    ui_LabelInfoOil  = create_info_tile(ui_ScreenPageInfo,
                                        &ui_LabelInfoName[1], &ui_LabelInfoUnit[1],
                                        "OIL", lv_color_hex(0xFF7722), "°C",
                                        +96, -84, 38);

    ui_LabelInfoLoad = create_info_tile(ui_ScreenPageInfo,
                                        &ui_LabelInfoName[2], &ui_LabelInfoUnit[2],
                                        "LOAD", lv_color_hex(0xFFCC00), "%",
                                        -96, +30, 38);
    ui_LabelInfoTPS  = create_info_tile(ui_ScreenPageInfo,
                                        &ui_LabelInfoName[3], &ui_LabelInfoUnit[3],
                                        "TPS", lv_color_hex(0xFF8844), "%",
                                        +96, +30, 38);

    ui_LabelInfoIAT  = create_info_tile(ui_ScreenPageInfo,
                                        &ui_LabelInfoName[4], &ui_LabelInfoUnit[4],
                                        "IAT", lv_color_hex(0x44FF88), "°C",
                                        0, +144, 38);

    ui_LabelInfoValue[0] = ui_LabelInfoCLT;
    ui_LabelInfoValue[1] = ui_LabelInfoOil;
    ui_LabelInfoValue[2] = ui_LabelInfoLoad;
    ui_LabelInfoValue[3] = ui_LabelInfoTPS;
    ui_LabelInfoValue[4] = ui_LabelInfoIAT;


    gps_value = lv_label_create(ui_ScreenPageInfo);
    lv_label_set_text(gps_value, "");
    lv_obj_set_style_text_font(gps_value, &ui_font_FontTypoderSize24, LV_PART_MAIN);
    lv_obj_set_style_text_align(gps_value, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_set_style_text_color(gps_value, lv_color_white(), LV_PART_MAIN);
    lv_obj_align(gps_value, LV_ALIGN_CENTER, 0, 146);
    lv_obj_add_flag(gps_value, LV_OBJ_FLAG_HIDDEN);

    // Touch events
    lv_obj_move_foreground(ring);   // bring the ring to the front
    lv_obj_add_event_cb(ui_ScreenPageInfo, ui_event_info_background, LV_EVENT_GESTURE, NULL);
}

lv_obj_t *ui_ScreenPageQuad;
lv_obj_t *ui_LabelQuadName[4];
lv_obj_t *ui_LabelQuadValue[4];
lv_obj_t *ui_LabelQuadUnit[4];

void ui_ScreenPageQuad_screen_init(void)
{
    ui_ScreenPageQuad = lv_obj_create(NULL);
    lv_obj_clear_flag(ui_ScreenPageQuad, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_radius(ui_ScreenPageQuad, LV_RADIUS_CIRCLE, LV_PART_MAIN);
    ui_helpers_style_screen_bg(ui_ScreenPageQuad);
    lv_obj_t *ring = ui_helpers_create_ring(ui_ScreenPageQuad, 8);

    // Leave the upper band clear for OBD and TIME.
    create_hdiv(ui_ScreenPageQuad, 0, 400);
    create_vdiv(ui_ScreenPageQuad, 0, 0, 318);
    for (int slot = 0; slot < 4; ++slot) {
        const lv_coord_t x = slot % 2 == 0 ? -96 : 96;
        const lv_coord_t y = slot < 2 ? -69 : 69;
        ui_LabelQuadValue[slot] = create_info_tile(ui_ScreenPageQuad, &ui_LabelQuadName[slot],
                                                   &ui_LabelQuadUnit[slot], "", lv_color_hex(0xFFFFFF),
                                                   "", x, y, 46);
    }
    lv_obj_move_foreground(ring);
    lv_obj_add_event_cb(ui_ScreenPageQuad, ui_event_info_background, LV_EVENT_GESTURE, NULL);
}
