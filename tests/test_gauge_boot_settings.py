"""Run production boot-default persistence and framebuffer rotation on the host."""
import re
import unittest
from pathlib import Path
import test_firmware_startup as host

ROOT = Path(__file__).resolve().parents[1]


def typedef(source, name):
    return next(m.group() for m in re.finditer(r'typedef struct \{[^}]*\}\s*\w+;', source)
                if m.group().endswith('} ' + name + ';'))


class GaugeBootSettingsTest(unittest.TestCase):
    def test_firmware_settings_snapshot_v3(self):
        source = (ROOT / 'main/bsp_obd_dsp/racechrono_ble_diy.c').read_text(encoding='utf8')
        header = (ROOT / 'main/bsp_obd_dsp/nvs_storage.h').read_text(encoding='utf8')
        wire_size = re.search(r'#define GAUGE_SETTINGS_WIRE_SIZE \d+', source).group()
        host.StartupTest().run_c(r'''
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <stdbool.h>
#include <stddef.h>
#define TRIP_MERGE_TIMEOUT_EDITABLE 0
''' + wire_size + '\n' + typedef(header, 'theme_cfg_t') + typedef(header, 'nvs_user_cfg_t') + r'''
static nvs_user_cfg_t cfg;
static uint8_t s_gauge_settings_value[GAUGE_SETTINGS_WIRE_SIZE];
static const nvs_user_cfg_t *nvs_cfg_get(void) { return &cfg; }
static uint8_t nvs_intro_enable_get(void) { return 2; }
static uint8_t nvs_device_position_get(void) { return 1; }
static uint8_t nvs_boot_mode_get(void) { return 0; }
static int16_t nvs_chart_alarm_get(uint8_t item) { return item * 10; }
''' + '\n'.join(host.function(source, name) for name in ('put_le16', 'trip_crc16', 'build_gauge_settings_wire')) + r'''
int main(void) {
    cfg.refuel_detect_threshold_ml = 10000; cfg.brightness_day = 75;
    for (unsigned page = 0; page <= 8; page++) for (unsigned rotation = 0; rotation <= 3; rotation++) {
        cfg.default_page = page; cfg.default_orientation = rotation;
        assert(build_gauge_settings_wire() == 108);
        const uint8_t *b = s_gauge_settings_value;
        assert(b[0] == 3 && b[1] == 7 && b[2] == 108 && b[3] == 0);
        assert(b[6] == page && b[7] == 75 && b[104] == rotation && b[105] == 0);
        assert((b[102] | b[103] << 8) == 10000);
        assert((b[106] | b[107] << 8) == trip_crc16(b, 106));
    }
    return 0;
}
''')

    def test_flash_failure_retry_reboot_and_legacy_padding(self):
        source = (ROOT / 'main/bsp_obd_dsp/nvs_storage.c').read_text(encoding='utf8')
        header = (ROOT / 'main/bsp_obd_dsp/nvs_storage.h').read_text(encoding='utf8')
        migration = re.search(r'if \(stored_ver < 13\) \{[^}]+\}', source).group()
        host.StartupTest().run_c(r'''
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <stdbool.h>
#include <stddef.h>
typedef int esp_err_t;
#define ESP_OK 0
#define ESP_ERR_INVALID_ARG 1
#define NS_CFG "cfg"
#define KEY_CFG "user"
''' + typedef(header, 'theme_cfg_t') + typedef(header, 'nvs_user_cfg_t') + r'''
static nvs_user_cfg_t s_cfg, flash;
static bool fail;
static unsigned writes;
static esp_err_t save_blob(const char *ns, const char *key, const void *data, size_t len) {
    (void)ns; (void)key; assert(len == sizeof(flash)); writes++;
    if (fail) return 2;
    memcpy(&flash, data, len); return ESP_OK;
}
''' + host.function(source, 'nvs_boot_setting_set') + r'''
int main(void) {
    memset(&s_cfg, 0xa5, sizeof(s_cfg));
    s_cfg.default_page = 8; s_cfg.brightness_day = 75;
    unsigned stored_ver = 12;
''' + migration + r'''
    assert(s_cfg.default_orientation == 0 && s_cfg.default_page == 8);
    assert(flash.default_orientation == 0 && flash.brightness_day == 75);
    nvs_user_cfg_t original = s_cfg;
    fail = true;
    assert(nvs_boot_setting_set(2, 0) != ESP_OK);
    assert(memcmp(&s_cfg, &original, sizeof(s_cfg)) == 0);
    assert(nvs_boot_setting_set(3, 1) != ESP_OK);
    assert(memcmp(&s_cfg, &flash, sizeof(s_cfg)) == 0);
    fail = false;
    for (unsigned page = 0; page <= 8; page++) {
        for (unsigned orientation = 0; orientation <= 3; orientation++) {
            assert(nvs_boot_setting_set(2, page) == ESP_OK);
            assert(nvs_boot_setting_set(3, orientation) == ESP_OK);
            s_cfg = flash; // reboot reload
            assert(s_cfg.default_page == page && s_cfg.default_orientation == orientation);
            original.default_page = page; original.default_orientation = orientation;
            assert(memcmp(&s_cfg, &original, sizeof(s_cfg)) == 0);
            unsigned previous = writes;
            assert(nvs_boot_setting_set(2, page) == ESP_OK);
            assert(nvs_boot_setting_set(3, orientation) == ESP_OK);
            assert(writes == previous); // retries do not wear flash
        }
    }
    unsigned previous = writes;
    assert(nvs_boot_setting_set(0, 0) == ESP_ERR_INVALID_ARG);
    assert(nvs_boot_setting_set(2, 9) == ESP_ERR_INVALID_ARG);
    assert(nvs_boot_setting_set(3, 4) == ESP_ERR_INVALID_ARG);
    assert(nvs_boot_setting_set(3, 255) == ESP_ERR_INVALID_ARG);
    assert(writes == previous);
    return 0;
}
''')

    def test_partial_frame_alignment_and_touch_inverse_all_four_directions(self):
        host.StartupTest().run_c(r'''
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "bsp_obd_dsp/gauge_rotation.h"
int main(void) {
    uint16_t input[466 * 20], output[466 * 20 + 2];
    const int sizes[] = {360, 466};
    for (unsigned size = 0; size < 2; size++) {
        int edge = sizes[size];
        for (int width = 2; width <= edge; width += 2) {
            const int height = 10, left = edge - width, top = edge - height;
            for (int i = 0; i < width * height; i++) input[i] = (uint16_t)i;
            for (uint8_t turns = 0; turns < 4; turns++) {
                memset(output, 0xff, sizeof(output));
                gauge_rotate_rgb565(input, output, width, height, turns);
                assert(output[width * height] == 0xffff);
                int x = left, y = top, w = width, h = height;
                gauge_rotate_area(&x, &y, &w, &h, edge, edge, turns);
                assert(!(x % 2) && !(y % 2) && !(w % 2) && !(h % 2));
                assert(x >= 0 && y >= 0 && x + w <= edge && y + h <= edge);
                for (int dy = 0; dy < h; dy++) for (int dx = 0; dx < w; dx++) {
                    int tx = x + dx, ty = y + dy;
                    /* LVGL 8's actual pointer inverse, using our driver enum. */
                    unsigned rotation = (4 - turns) % 4;
                    if (rotation == 2 || rotation == 3) { tx = edge - tx - 1; ty = edge - ty - 1; }
                    if (rotation == 1 || rotation == 3) { int temp = ty; ty = tx; tx = edge - temp - 1; }
                    assert(tx >= left && tx < left + width && ty >= top && ty < top + height);
                    assert(output[dy * w + dx] == input[(ty - top) * width + tx - left]);
                }
            }
        }
    }
    return 0;
}
''')


if __name__ == '__main__':
    unittest.main()
