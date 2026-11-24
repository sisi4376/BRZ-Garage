#pragma once
#include "ui.h"

/* OBD/TIME indicators occupy the exact former "NO SIGNAL" footprint. */
static inline lv_obj_t *status_indicator_create(lv_obj_t *parent, const char *text, lv_coord_t x)
{
    lv_obj_t *label = lv_label_create(parent);
    lv_obj_clear_flag(label, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_text_font(label, &ui_font_FontTypoderSize16, LV_PART_MAIN);
    lv_obj_set_style_text_color(label, lv_color_hex(0xFF4D4D), LV_PART_MAIN);
    lv_obj_set_style_bg_color(label, lv_color_hex(0x000000), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(label, 160, LV_PART_MAIN);
    lv_obj_set_style_pad_hor(label, 8, LV_PART_MAIN);
    lv_obj_set_style_pad_ver(label, 4, LV_PART_MAIN);
    lv_obj_set_style_radius(label, 6, LV_PART_MAIN);
    lv_label_set_text(label, text);
    lv_obj_align(label, LV_ALIGN_TOP_MID, x, 34);
    lv_obj_add_flag(label, LV_OBJ_FLAG_HIDDEN);
    return label;
}
