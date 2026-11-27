"""Exercise the firmware's layout decoder, navigation and NVS commit semantics."""
from pathlib import Path
import unittest

import test_firmware_startup as host
from test_gauge_boot_settings import typedef

ROOT = Path(__file__).resolve().parents[1]


class GaugeLayoutTest(unittest.TestCase):
    def test_gps_readout_recovers_from_missing_fix(self):
        source = (ROOT / "main/export_path/screens/ui_ScreenPageInfo.c").read_text(encoding="utf8")
        host.StartupTest().run_c(r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef struct { const char *text; bool hidden; } lv_obj_t;
enum { LV_OBJ_FLAG_HIDDEN };
static lv_obj_t gps = {"", true}, numeric = {"34", false};
static lv_obj_t *gps_value = &gps;
static lv_obj_t *ui_LabelInfoValue[5] = {[4] = &numeric};
static void lv_obj_add_flag(lv_obj_t *obj, int flag) { assert(flag == LV_OBJ_FLAG_HIDDEN); obj->hidden = true; }
static void lv_obj_clear_flag(lv_obj_t *obj, int flag) { assert(flag == LV_OBJ_FLAG_HIDDEN); obj->hidden = false; }
static const char *lv_label_get_text(lv_obj_t *obj) { return obj->text; }
static void lv_label_set_text(lv_obj_t *obj, const char *text) { obj->text = text; }
''' + "\n".join(host.function(source, name) for name in (
    "ui_format_gps_text", "ui_gps_readout", "ui_info_gps_text",
)) + r'''
int main(void)
{
    char fix[32];
    ui_format_gps_text(fix, sizeof(fix), 3123040, 12147370);
    assert(!strcmp(fix, "31.23 N  121.47 E"));
    ui_info_gps_text("");
    assert(!gps.hidden && numeric.hidden && !strcmp(gps.text, "--"));
    ui_info_gps_text(fix);
    assert(!gps.hidden && numeric.hidden && !strcmp(gps.text, fix));
    ui_info_gps_text("");
    assert(!gps.hidden && numeric.hidden && !strcmp(gps.text, "--"));
    ui_info_gps_text(NULL);
    assert(gps.hidden && !numeric.hidden);
    ui_format_gps_text(fix, sizeof(fix), -3392490, -11824370);
    assert(!strcmp(fix, "33.92 S  118.24 W"));
    return 0;
}
''')

    def test_trip_gestures_follow_layout_and_keep_vertical_subpages(self):
        source = (ROOT / "main/export_path/ui.c").read_text(encoding="utf8")
        handlers = (
            "gauge_swipe", "ui_event_trip_history_background",
            "ui_event_trip_overview_background", "ui_event_trip_intervals_background",
        )
        host.StartupTest().run_c(r'''
#include <assert.h>
#include "bsp_obd_dsp/gauge_layout.h"

typedef enum { LV_DIR_LEFT, LV_DIR_RIGHT, LV_DIR_TOP, LV_DIR_BOTTOM } lv_dir_t;
typedef enum { LV_EVENT_CLICKED, LV_EVENT_GESTURE } lv_event_t;
enum { LV_SCR_LOAD_ANIM_NONE, TRIP_HISTORY = 7, TRIP_OVERVIEW = 8, TRIP_INTERVALS = 200 };
static lv_dir_t direction;
static int s_active_page, screen, history_offset;
static struct { uint8_t page_order[GAUGE_PAGE_COUNT]; } cfg;
static int ui_ScreenPageTripOverview = TRIP_OVERVIEW;
static int ui_ScreenPageTripIntervals = TRIP_INTERVALS;

#define nvs_cfg_get() (&cfg)
#define lv_indev_get_act() NULL
static lv_event_t lv_event_get_code(lv_event_t *event) { return *event; }
static lv_dir_t lv_indev_get_gesture_dir(void *indev) { (void)indev; return direction; }
static void lv_indev_wait_release(void *indev) { (void)indev; }
static void ui_trip_history_page(int delta) { history_offset += delta; }
static void gauge_show_page(int page) { screen = s_active_page = page; }
static void ui_ScreenPageTripOverview_screen_init(void) {}
static void ui_ScreenPageTripIntervals_screen_init(void) {}
static void _ui_screen_change(int *target, int animation, int speed, int delay, void (*init)(void))
{
    assert(animation == LV_SCR_LOAD_ANIM_NONE && speed == 0 && delay == 0);
    init();
    screen = *target;
}
''' + "\n".join(host.function(source, name) for name in handlers) + r'''
static void swipe(void (*handler)(lv_event_t *), lv_dir_t dir)
{
    lv_event_t event = LV_EVENT_GESTURE;
    direction = dir;
    handler(&event);
}

int main(void)
{
    memset(cfg.page_order, GAUGE_PAGE_UNUSED, sizeof(cfg.page_order));
    cfg.page_order[0] = 5;
    cfg.page_order[1] = TRIP_HISTORY;
    cfg.page_order[2] = 6;
    gauge_show_page(TRIP_HISTORY);
    swipe(ui_event_trip_history_background, LV_DIR_RIGHT);
    assert(screen == 5 && history_offset == 0);  /* Speed replaces the removed overview. */
    gauge_show_page(TRIP_HISTORY);
    swipe(ui_event_trip_history_background, LV_DIR_LEFT);
    assert(screen == 6 && history_offset == 0);
    gauge_show_page(TRIP_HISTORY);
    swipe(ui_event_trip_history_background, LV_DIR_BOTTOM);
    assert(screen == TRIP_HISTORY && history_offset == 1);
    swipe(ui_event_trip_history_background, LV_DIR_TOP);
    assert(screen == TRIP_HISTORY && history_offset == 0);

    cfg.page_order[3] = 6;
    cfg.page_order[2] = TRIP_OVERVIEW;
    swipe(ui_event_trip_history_background, LV_DIR_LEFT);
    assert(screen == TRIP_OVERVIEW);
    swipe(ui_event_trip_overview_background, LV_DIR_TOP);
    assert(screen == TRIP_INTERVALS && s_active_page == TRIP_OVERVIEW);
    swipe(ui_event_trip_intervals_background, LV_DIR_BOTTOM);
    assert(screen == TRIP_OVERVIEW);
    swipe(ui_event_trip_overview_background, LV_DIR_TOP);
    swipe(ui_event_trip_intervals_background, LV_DIR_LEFT);
    assert(screen == 6 && history_offset == 0);
    return 0;
}
''')

    def test_round_trip_navigation_and_flash_failure(self):
        source = (ROOT / "main/bsp_obd_dsp/nvs_storage.c").read_text(encoding="utf8")
        header = (ROOT / "main/bsp_obd_dsp/nvs_storage.h").read_text(encoding="utf8")
        host.StartupTest().run_c(r'''
#include <assert.h>
#include "bsp_obd_dsp/gauge_layout.h"
#define ESP_OK 0
#define ESP_ERR_INVALID_ARG 1
#define NS_CFG "cfg"
#define KEY_CFG "cfg"
#define CHART_ALARM_N 13
typedef int esp_err_t;
''' + typedef(header, "theme_cfg_t") + typedef(header, "nvs_user_cfg_t") + r'''
static nvs_user_cfg_t s_cfg, flash;
static bool fail;
static int writes;
static int save_blob(const char *ns, const char *key, const void *data, size_t n) {
    assert(n == sizeof(flash)); writes++;
    if (fail) return 2;
    memcpy(&flash, data, n);
    return ESP_OK;
}
''' + host.function(source, "nvs_gauge_layout_set") + r'''
int main(void) {
    gauge_layout_t layout = {.pages = GAUGE_DEFAULT_ORDER}, parsed;
    memset(layout.pages, 255, sizeof(layout.pages));
    layout.pages[0] = 10; layout.pages[1] = 5; layout.pages[2] = 9;
    for (int i = 0; i < GAUGE_ITEM_SLOTS; ++i) layout.items[i] = i % 13;
    uint8_t bytes[GAUGE_LAYOUT_WIRE_SIZE];
    gauge_layout_pack(bytes, &layout);
    assert(gauge_layout_unpack(bytes, &parsed));
    assert(!memcmp(&parsed, &layout, sizeof(parsed)));
    assert(gauge_page_step(parsed.pages, 5, true) == 9);
    assert(gauge_page_step(parsed.pages, 5, false) == 10);
    assert(gauge_page_step(parsed.pages, 9, true) == GAUGE_PAGE_SYSTEM);
    assert(gauge_page_step(parsed.pages, GAUGE_PAGE_SYSTEM, true) == 10);
    assert(gauge_startup_page(parsed.pages, 9) == 9);
    assert(gauge_startup_page(parsed.pages, GAUGE_PAGE_SYSTEM) == GAUGE_PAGE_SYSTEM);
    assert(gauge_startup_page(parsed.pages, GAUGE_PAGE_UNUSED) == 10);
    assert(gauge_page_step(parsed.pages, 4, true) == 10);
    s_cfg.default_orientation = 2;
    fail = true;
    assert(nvs_gauge_layout_set(&parsed) != ESP_OK);
    assert(s_cfg.page_order[0] == 0);
    fail = false;
    assert(nvs_gauge_layout_set(&parsed) == ESP_OK);
    assert(nvs_gauge_layout_set(&parsed) == ESP_OK && writes == 2);
    s_cfg = flash;
    assert(s_cfg.page_order[0] == 10 && s_cfg.default_orientation == 2);
    assert(!memcmp(s_cfg.copy_items, parsed.items + 10, sizeof(s_cfg.copy_items)));
    for (int row = 0; row < 3; ++row) {
        layout.items[row] = GAUGE_ITEM_GPS;
        layout.items[10 + row] = GAUGE_ITEM_GPS; /* Page 9 is a TEMP copy. */
        assert(gauge_layout_items_valid(&layout));
    }
    layout.pages[2] = 9 + GAUGE_COPY_SLOTS;
    assert(!gauge_layout_items_valid(&layout));
    layout.pages[2] = 9;
    for (int row = 0; row < 3; ++row) layout.items[10 + row] = row;
    layout.items[7] = GAUGE_ITEM_GPS;
    assert(gauge_layout_items_valid(&layout));
    layout.items[6] = GAUGE_ITEM_GPS;
    assert(!gauge_layout_items_valid(&layout));
    layout.items[6] = 6;
    layout.items[14] = GAUGE_ITEM_GPS;
    assert(!gauge_layout_items_valid(&layout)); /* shared slot belongs to TEMP */
    layout.pages[2] = 9 + GAUGE_COPY_SLOTS;
    assert(gauge_layout_items_valid(&layout));
    layout.items[14] = 0;
    bytes[1] = bytes[2];
    assert(!gauge_layout_unpack(bytes, &parsed));
    gauge_layout_pack(bytes, &layout);
    bytes[1 + GAUGE_PAGE_COUNT] = 0xee;
    assert(!gauge_layout_unpack(bytes, &parsed));
    memset(layout.pages, 255, sizeof(layout.pages));
    assert(nvs_gauge_layout_set(&layout) == ESP_OK);
    s_cfg = flash;
    assert(gauge_page_count(s_cfg.page_order) == 0);
    assert(gauge_page_step(s_cfg.page_order, GAUGE_PAGE_SYSTEM, true) == GAUGE_PAGE_SYSTEM);
    for (int type = 0; type < 4; ++type) {
        layout.pages[0] = type;
        for (int slot = 0; slot < GAUGE_COPY_SLOTS; ++slot) {
            int id = 9 + type * GAUGE_COPY_SLOTS + slot;
            layout.pages[slot + 1] = id;
            assert(gauge_page_type(id) == type && gauge_copy_slot(id) == slot);
        }
        assert(nvs_gauge_layout_set(&layout) == ESP_OK);
        assert(gauge_page_step(layout.pages, GAUGE_PAGE_SYSTEM, true) == type);
        assert(gauge_page_step(layout.pages, GAUGE_PAGE_SYSTEM, false) == layout.pages[GAUGE_PAGE_COUNT - 1]);
    }
    memset(layout.pages, GAUGE_PAGE_UNUSED, sizeof(layout.pages));
    layout.pages[0] = GAUGE_QUAD_FIRST;
    layout.pages[1] = GAUGE_QUAD_FIRST + 1;
    layout.pages[2] = 4;
    assert(gauge_page_type(layout.pages[0]) == GAUGE_TYPE_QUAD);
    assert(gauge_page_step(layout.pages, GAUGE_QUAD_FIRST, true) == GAUGE_QUAD_FIRST + 1);
    assert(nvs_gauge_layout_set(&layout) == ESP_OK);
    layout.pages[1] = 9; /* slot zero is already occupied by the first quad */
    assert(gauge_page_count(layout.pages) < 0);
    layout.pages[1] = GAUGE_PAGE_SYSTEM;
    assert(gauge_page_count(layout.pages) < 0);
    layout.pages[0] = 0;
    layout.pages[1] = 4;
    layout.pages[2] = 4;
    assert(nvs_gauge_layout_set(&layout) == ESP_ERR_INVALID_ARG);
    layout.pages[1] = 9;
    layout.pages[2] = 9 + GAUGE_COPY_SLOTS; /* different templates cannot share configuration storage */
    assert(nvs_gauge_layout_set(&layout) == ESP_ERR_INVALID_ARG);
    return 0;
}
''')
