"""Exercise the firmware's layout decoder, navigation and NVS commit semantics."""
from pathlib import Path
import unittest

import test_firmware_startup as host
from test_gauge_boot_settings import typedef

ROOT = Path(__file__).resolve().parents[1]


class GaugeLayoutTest(unittest.TestCase):
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
    layout.pages[1] = 4;
    layout.pages[2] = 4;
    assert(nvs_gauge_layout_set(&layout) == ESP_ERR_INVALID_ARG);
    layout.pages[1] = 9;
    layout.pages[2] = 9 + GAUGE_COPY_SLOTS; /* different templates cannot share configuration storage */
    assert(nvs_gauge_layout_set(&layout) == ESP_ERR_INVALID_ARG);
    return 0;
}
''')
