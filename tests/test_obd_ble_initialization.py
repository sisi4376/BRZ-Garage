"""Exercise the production BLE initialization entry points with an async SDK mock."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_obd_poll_health import function

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'main/bsp_obd_dsp/elm327_ble_client.c'

PRELUDE = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#define ESP_OK 0
#define ESP_GATT_OK 0
#define ESP_GATT_IF_NONE 255
#define ESP_GATTC_REG_EVT 0
#define ESP_GATTC_CONNECT_EVT 1
#define ESP_GAP_BLE_SCAN_PARAM_SET_COMPLETE_EVT 0
#define ESP_BT_STATUS_SUCCESS 0
#define ESP_BT_CONTROLLER_STATUS_IDLE 0
#define ESP_BT_CONTROLLER_STATUS_ENABLED 2
#define ESP_BLUEDROID_STATUS_ENABLED 2
#define ESP_BT_MODE_CLASSIC_BT 1
#define ESP_BT_MODE_BLE 2
#define BLE_SCAN_TYPE_ACTIVE 1
#define BLE_ADDR_TYPE_PUBLIC 0
#define BLE_SCAN_FILTER_ALLOW_ALL 0
#define BLE_SCAN_DUPLICATE_DISABLE 0
#define ESP_LOGI(...) ((void)0)
#define ESP_LOGE(...) ((void)0)
#define ESP_ERROR_CHECK(x) assert((x) == ESP_OK)
#define BT_CONTROLLER_INIT_CONFIG_DEFAULT() {0}
typedef int esp_gattc_cb_event_t;
typedef int esp_gatt_if_t;
typedef int esp_gap_ble_cb_event_t;
typedef struct { struct { int status; } scan_param_cmpl; } esp_ble_gap_cb_param_t;
typedef struct { int unused; } esp_bt_controller_config_t;
typedef struct { int marker; } elm327_ble_callbacks_t;
typedef struct { struct { int status, app_id; } reg; } esp_ble_gattc_cb_param_t;
typedef struct { int scan_type, own_addr_type, scan_filter_policy;
                 int scan_interval, scan_window, scan_duplicate; } esp_ble_scan_params_t;
static bool s_ble_inited, s_target_bda_valid, s_scan_only_mode, s_connected, s_ota_paused;
static bool s_scan_params_ready;
static int s_gattc_if = ESP_GATT_IF_NONE;
static elm327_ble_callbacks_t s_cbs;
static char s_target_name[32];
static unsigned registrations, scans, scan_configs, callback_registrations, delivered;
static int controller_status, bluedroid_status;
static int esp_bt_controller_mem_release(int mode) { (void)mode; return 0; }
static int esp_bt_controller_get_status(void) { return controller_status; }
static int esp_bt_controller_init(void *cfg) { (void)cfg; controller_status=1; return 0; }
static int esp_bt_controller_enable(int mode) { (void)mode; controller_status=2; return 0; }
static int esp_bluedroid_get_status(void) { return bluedroid_status; }
static int esp_bluedroid_init(void) { bluedroid_status=1; return 0; }
static int esp_bluedroid_enable(void) { bluedroid_status=2; return 0; }
static int esp_ble_gatt_set_local_mtu(int mtu) { assert(mtu==517); return 0; }
static void gap_event_handler(esp_gap_ble_cb_event_t, esp_ble_gap_cb_param_t *);
static void gattc_event_handler(esp_gattc_cb_event_t, esp_gatt_if_t, esp_ble_gattc_cb_param_t *);
static int esp_ble_gap_register_callback(void (*cb)(int,esp_ble_gap_cb_param_t *))
{ (void)cb; callback_registrations++; return 0; }
static int esp_ble_gattc_register_callback(void (*cb)(int,int,esp_ble_gattc_cb_param_t *))
{ (void)cb; callback_registrations++; return 0; }
static int esp_ble_gattc_app_register(int id) { assert(id==0); registrations++; return 0; }
static int esp_ble_gap_set_scan_params(esp_ble_scan_params_t *p)
{ assert(p->scan_interval==0x60); scan_configs++; return 0; }
static void start_scan(void) { scans++; }
'''


class ObdBleInitializationTest(unittest.TestCase):
    def run_case(self, body):
        source = SOURCE.read_text(encoding='utf-8')
        handler = function(source, 'gattc_event_handler')
        prefix = handler[handler.index('{') + 1:handler.index('    switch (event)')]
        reg = handler[handler.index('    case ESP_GATTC_REG_EVT:'):handler.index('    case ESP_GATTC_CONNECT_EVT:')]
        gap = function(source, 'gap_event_handler')
        scan_ready = gap[gap.index('    case ESP_GAP_BLE_SCAN_PARAM_SET_COMPLETE_EVT:'):gap.index('    case ESP_GAP_BLE_SCAN_RESULT_EVT:')]
        harness = PRELUDE + '\n'.join(function(source, name) for name in (
            'elm327_ble_init_and_start', 'ble_ensure_init', 'elm327_ble_ensure_stack_init'))
        harness += '''
static void gattc_event_handler(esp_gattc_cb_event_t event, esp_gatt_if_t gattc_if,
                                esp_ble_gattc_cb_param_t *param) {
''' + prefix + '\n    switch (event) {\n' + reg + '\n    default: delivered++; break;\n    }\n}\n'
        harness += '''
static void gap_event_handler(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param) {
    switch (event) {
''' + scan_ready + '\n    default: break;\n    }\n}\n'
        harness += '\nint main(void) {\n' + body + '\nreturn 0;\n}\n'
        compiler = ROOT / 'tools/.cache/w64devkit/bin/gcc.exe'
        env = os.environ.copy()
        env['PATH'] = str(compiler.parent) + os.pathsep + env.get('PATH', '')
        with tempfile.TemporaryDirectory(prefix='ble-init-') as folder:
            c = Path(folder) / 'test.c'
            exe = Path(folder) / 'test.exe'
            c.write_text(harness, encoding='utf-8')
            result = subprocess.run([str(compiler), '-std=c11', '-Wall', str(c), '-o', str(exe)],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run([str(exe)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_stack_then_obd_start_registers_once_even_before_async_callback(self):
        self.run_case(r'''
    elm327_ble_ensure_stack_init();
    assert(registrations == 1 && scans == 0);
    s_target_bda_valid = true;
    elm327_ble_callbacks_t cb = {42};
    elm327_ble_init_and_start("OBDII", &cb);
    assert(registrations == 1 && callback_registrations == 2);
    assert(s_cbs.marker == 42 && !strcmp(s_target_name, "OBDII"));
    assert(scans == 0); // registration/scan parameter completion is still pending
    esp_ble_gattc_cb_param_t p = {.reg={ESP_GATT_OK,0}};
    gattc_event_handler(ESP_GATTC_REG_EVT, 3, &p);
    assert(s_gattc_if == 3 && scan_configs == 1);
    esp_ble_gap_cb_param_t gap = {.scan_param_cmpl={ESP_BT_STATUS_SUCCESS}};
    gap_event_handler(ESP_GAP_BLE_SCAN_PARAM_SET_COMPLETE_EVT, &gap);
    assert(s_scan_params_ready && scans == 1);
''')

    def test_reuse_ready_stack_starts_scan_without_reregistering(self):
        self.run_case(r'''
    elm327_ble_ensure_stack_init();
    esp_ble_gattc_cb_param_t p = {.reg={ESP_GATT_OK,0}};
    gattc_event_handler(ESP_GATTC_REG_EVT, 3, &p);
    esp_ble_gap_cb_param_t gap = {.scan_param_cmpl={ESP_BT_STATUS_SUCCESS}};
    gap_event_handler(ESP_GAP_BLE_SCAN_PARAM_SET_COMPLETE_EVT, &gap);
    assert(s_scan_params_ready && scans == 0); // no target bound yet
    s_target_bda_valid = true;
    elm327_ble_init_and_start("OBDII", 0);
    assert(registrations == 1 && scans == 1);
    s_connected = true;
    elm327_ble_init_and_start("OBDII", 0);
    assert(registrations == 1 && scans == 1);
    s_connected = false; s_ota_paused = true;
    elm327_ble_init_and_start("OBDII", 0);
    assert(registrations == 1 && scans == 1);
''')

    def test_scan_parameter_failure_and_inactive_modes_do_not_start_scanning(self):
        self.run_case(r'''
    s_target_bda_valid = true;
    esp_ble_gap_cb_param_t gap = {.scan_param_cmpl={1}};
    gap_event_handler(ESP_GAP_BLE_SCAN_PARAM_SET_COMPLETE_EVT, &gap);
    assert(!s_scan_params_ready && scans == 0);
    gap.scan_param_cmpl.status = ESP_BT_STATUS_SUCCESS;
    s_scan_only_mode = true;
    gap_event_handler(ESP_GAP_BLE_SCAN_PARAM_SET_COMPLETE_EVT, &gap);
    assert(s_scan_params_ready && scans == 0);
    s_scan_only_mode = false; s_ota_paused = true;
    gap_event_handler(ESP_GAP_BLE_SCAN_PARAM_SET_COMPLETE_EVT, &gap);
    assert(scans == 0);
    s_ota_paused = false; s_connected = true;
    gap_event_handler(ESP_GAP_BLE_SCAN_PARAM_SET_COMPLETE_EVT, &gap);
    assert(scans == 0);
''')

    def test_failed_or_other_registration_cannot_replace_elm_interface(self):
        self.run_case(r'''
    esp_ble_gattc_cb_param_t p = {.reg={ESP_GATT_OK,0}};
    gattc_event_handler(ESP_GATTC_REG_EVT, 3, &p);
    p.reg.status = 1;
    gattc_event_handler(ESP_GATTC_REG_EVT, 0, &p);
    assert(s_gattc_if == 3 && scan_configs == 1);
    p.reg.status = ESP_GATT_OK; p.reg.app_id = 0x53;
    gattc_event_handler(ESP_GATTC_REG_EVT, 5, &p);
    assert(s_gattc_if == 3 && scan_configs == 1);
    gattc_event_handler(ESP_GATTC_CONNECT_EVT, 5, &p);
    assert(delivered == 0);
    gattc_event_handler(ESP_GATTC_CONNECT_EVT, 3, &p);
    assert(delivered == 1);
''')


if __name__ == '__main__':
    unittest.main()
