// Multi-Gauge (triple-gauge) Settings Page  (entered by swiping down from the settings page)
//  - MODE: MASTER (connects to ELM327 + broadcasts) / SLAVE (receives the master's data) / STANDALONE (standalone, WiFi not started) → NVS device_role
//  - POSITION: this unit's position 1/2/3 (RACE/AS/ONE) in the boot animation     → NVS device_position
//  - INTRO: boot animation: OFF / RACE (LVGL intro) / VIDEO (app-flashed boot_block)  → NVS intro_enable
//  This BRZ build keeps the page for reference but freezes MASTER / 1 / VIDEO.
//  Swipe up/left/right to return to the settings page.

#include "../ui.h"
#include "bsp_obd_dsp/nvs_storage.h"

static const char *mode_names = "MASTER\nSLAVE\nALONE";   // index=device_role: 0=MASTER,1=SLAVE,2=STANDALONE
static const char *pos_names  = "1\n2\n3";          // index 0/1/2 → position 1/2/3
static const char *intro_names = "OFF\nRACE\nVIDEO";  // 0=OFF, 1=RACE, 2=VIDEO (boot_block flashed via the phone app)

static lv_obj_t *s_roller_mode = NULL;
static lv_obj_t *s_roller_pos  = NULL;
static lv_obj_t *s_roller_intro = NULL;

// Shared roller style
static void style_mg_roller(lv_obj_t *r)
{
    lv_obj_clear_flag(r, LV_OBJ_FLAG_GESTURE_BUBBLE);
    lv_obj_set_width(r, 180);
    ui_helpers_style_dark_roller(r, &ui_font_FontTypoderSize20);
    lv_roller_set_visible_row_count(r, 1);   // after the font so the row height uses Size20
    lv_obj_set_height(r, 30);                // explicit, same as the settings page rollers
    lv_obj_add_state(r, LV_STATE_DISABLED);
    lv_obj_clear_flag(r, LV_OBJ_FLAG_CLICKABLE);
    // Make the lock visually unmistakable instead of relying only on the
    // disabled input state. Both the roller body and selected row are grey.
    lv_obj_set_style_bg_color(r, lv_color_hex(0x3A3A3A), LV_PART_MAIN | LV_STATE_DISABLED);
    lv_obj_set_style_bg_opa(r, 255, LV_PART_MAIN | LV_STATE_DISABLED);
    lv_obj_set_style_text_color(r, lv_color_hex(0x8A8A8A), LV_PART_MAIN | LV_STATE_DISABLED);
    lv_obj_set_style_bg_color(r, lv_color_hex(0x555555), LV_PART_SELECTED | LV_STATE_DISABLED);
    lv_obj_set_style_bg_opa(r, 255, LV_PART_SELECTED | LV_STATE_DISABLED);
    lv_obj_set_style_text_color(r, lv_color_hex(0xC8C8C8), LV_PART_SELECTED | LV_STATE_DISABLED);
}

static void make_mg_label(lv_obj_t *parent, const char *txt, int y)
{
    lv_obj_t *l = lv_label_create(parent);
    lv_label_set_text(l, txt);
    lv_obj_set_style_text_font(l, &ui_font_FontTypoderSize16, LV_PART_MAIN);
    lv_obj_set_style_text_color(l, lv_color_hex(0x888888), LV_PART_MAIN);
    lv_obj_align(l, LV_ALIGN_CENTER, 0, y);
}

void ui_ScreenPageMultiGauge_screen_init(void)
{
    ui_ScreenPageMultiGauge = lv_obj_create(NULL);
    lv_obj_clear_flag(ui_ScreenPageMultiGauge, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_radius(ui_ScreenPageMultiGauge, 360, LV_PART_MAIN);
    ui_helpers_style_screen_bg(ui_ScreenPageMultiGauge);
    lv_obj_set_style_bg_opa(ui_ScreenPageMultiGauge, 255, LV_PART_MAIN);

    // White border ring
    lv_obj_t *ring = ui_helpers_create_ring(ui_ScreenPageMultiGauge, 10);

    // Black ear image at top (created before the widgets so they draw on top of the notch)
    lv_obj_t *ear = lv_img_create(ui_ScreenPageMultiGauge);
    lv_img_set_src(ear, &ui_img_pngblackear_png);
    lv_obj_set_width(ear, LV_SIZE_CONTENT);
    lv_obj_set_height(ear, LV_SIZE_CONTENT);
    lv_obj_set_pos(ear, 0, -142);
    lv_obj_set_align(ear, LV_ALIGN_CENTER);
    lv_obj_add_flag(ear, LV_OBJ_FLAG_ADV_HITTEST);
    lv_obj_clear_flag(ear, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *title = lv_label_create(ui_ScreenPageMultiGauge);
    lv_label_set_text(title, "MULTI-GAUGE");
    lv_obj_set_style_text_font(title, &ui_font_FontTypoderSize20, LV_PART_MAIN);
    lv_obj_set_style_text_color(title, lv_color_hex(0xFFFFFF), LV_PART_MAIN);
    lv_obj_align(title, LV_ALIGN_CENTER, 0, -98);

    // Row 1: MODE
    make_mg_label(ui_ScreenPageMultiGauge, "MODE", -68);
    s_roller_mode = lv_roller_create(ui_ScreenPageMultiGauge);
    lv_obj_set_style_clip_corner(s_roller_mode, true, 0);
    style_mg_roller(s_roller_mode);
    lv_roller_set_options(s_roller_mode, mode_names, LV_ROLLER_MODE_NORMAL);
    lv_roller_set_selected(s_roller_mode, MULTIGAUGE_LOCKED_ROLE, LV_ANIM_OFF);
    lv_obj_align(s_roller_mode, LV_ALIGN_CENTER, 0, -38);

    // Row 2: POS (RACE/AS/ONE position)
    lv_obj_t *lbl_pos = lv_label_create(ui_ScreenPageMultiGauge);
    lv_label_set_text(lbl_pos, "POS");
    lv_obj_set_style_text_font(lbl_pos, &ui_font_FontTypoderSize16, LV_PART_MAIN);
    lv_obj_set_style_text_color(lbl_pos, lv_color_hex(0x888888), LV_PART_MAIN);
    lv_obj_align(lbl_pos, LV_ALIGN_CENTER, 0, -6);
    s_roller_pos = lv_roller_create(ui_ScreenPageMultiGauge);
    lv_obj_set_style_clip_corner(s_roller_pos, true, 0);
    style_mg_roller(s_roller_pos);
    lv_roller_set_options(s_roller_pos, pos_names, LV_ROLLER_MODE_NORMAL);
    lv_roller_set_selected(s_roller_pos, MULTIGAUGE_LOCKED_POSITION - 1U, LV_ANIM_OFF);
    lv_obj_align(s_roller_pos, LV_ALIGN_CENTER, 0, 24);

    // Row 3: INTRO (multi-gauge: OFF/RACE/VIDEO)
    lv_obj_t *lbl_intro = lv_label_create(ui_ScreenPageMultiGauge);
    lv_label_set_text(lbl_intro, "INTRO");
    lv_obj_set_style_text_font(lbl_intro, &ui_font_FontTypoderSize16, LV_PART_MAIN);
    lv_obj_set_style_text_color(lbl_intro, lv_color_hex(0x888888), LV_PART_MAIN);
    lv_obj_align(lbl_intro, LV_ALIGN_CENTER, 0, 56);
    s_roller_intro = lv_roller_create(ui_ScreenPageMultiGauge);
    lv_obj_set_style_clip_corner(s_roller_intro, true, 0);
    style_mg_roller(s_roller_intro);
    lv_roller_set_options(s_roller_intro, intro_names, LV_ROLLER_MODE_NORMAL);
    lv_roller_set_selected(s_roller_intro, MULTIGAUGE_LOCKED_INTRO, LV_ANIM_OFF);
    lv_obj_align(s_roller_intro, LV_ALIGN_CENTER, 0, 86);
    make_mg_label(ui_ScreenPageMultiGauge, "LOCKED", 126);

    lv_obj_move_foreground(ring);
    lv_obj_add_event_cb(ui_ScreenPageMultiGauge, ui_event_multi_gauge_background, LV_EVENT_GESTURE, NULL);
}
