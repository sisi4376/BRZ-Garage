"""Execute production receipt/freshness logic and the disabled connection sweep."""
from pathlib import Path
import unittest
import test_obd_poll_health as host
from test_zd8_release_contract import function

ROOT = Path(__file__).resolve().parents[1]


class ObdSignalStatusTest(unittest.TestCase):
    run_c = host.PollHealthTest.run_c

    def test_only_measurements_turn_green_and_stale_or_new_session_clears_it(self):
        source = (ROOT / 'main/bsp_obd_dsp/elm327_ble_client.c').read_text(encoding='utf-8')
        prelude = r'''
#include <assert.h>
#include <stdint.h>
#include <stdbool.h>
#include "freertos/portmacro.h"
static bool s_connected, s_notify_ready, s_accept_obd_responses, s_profile_transition, s_ota_paused;
static int64_t now_us, s_last_sample_us = -1, s_last_obd_valid_us;
static bool s_got_valid_data;
static portMUX_TYPE s_signal_mux;
static int64_t esp_timer_get_time(void) { return now_us; }
static uint32_t perf_now(void) { return 0; }
#define perf_emit(...) ((void)0)
static void obd_data_set_rpm(uint16_t rpm) { (void)rpm; }
static void obd_data_set_intake_temp(int16_t t) { (void)t; }
'''
        functions = '\n'.join(function(source, n) for n in (
            'reset_obd_signal', 'mark_obd_sample_received', 'elm327_ble_has_valid_data',
            'mark_obd_data_valid', 'default_on_parsed_rpm', 'default_on_parsed_intake_temp'))
        self.run_c(prelude + functions + r'''
int main(void) {
    assert(!elm327_ble_has_valid_data());
    s_connected = s_notify_ready = s_accept_obd_responses = true;
    assert(!elm327_ble_has_valid_data());
    mark_obd_data_valid(); // Old header/self-heal bookkeeping is NOT a sample.
    assert(!elm327_ble_has_valid_data());
    default_on_parsed_intake_temp(999);
    assert(!elm327_ble_has_valid_data());
    default_on_parsed_rpm(0); // An engine-off zero is still a valid ECU reply.
    assert(elm327_ble_has_valid_data());
    now_us = 4999999; assert(elm327_ble_has_valid_data());
    now_us = 5000000; assert(!elm327_ble_has_valid_data());
    default_on_parsed_intake_temp(20); assert(elm327_ble_has_valid_data());
    s_connected = false; assert(!elm327_ble_has_valid_data());
    reset_obd_signal(); s_connected = true;
    assert(!elm327_ble_has_valid_data());
    default_on_parsed_rpm(800); assert(elm327_ble_has_valid_data());
    s_notify_ready = false; assert(!elm327_ble_has_valid_data());
    s_notify_ready = true; s_accept_obd_responses = false;
    assert(!elm327_ble_has_valid_data());
    reset_obd_signal(); s_accept_obd_responses = true;
    assert(!elm327_ble_has_valid_data());
    default_on_parsed_rpm(800);
    s_profile_transition = true; assert(!elm327_ble_has_valid_data());
    s_profile_transition = false; s_ota_paused = true;
    assert(!elm327_ble_has_valid_data());
    return 0;
}
''', ('-Itests/host_stubs',))
        for entry in ('do_elm_init',):
            self.assertIn('reset_obd_signal();', function(source, entry))
        handler = function(source, 'gattc_event_handler')
        self.assertIn('reset_obd_signal();', handler.split('case ESP_GATTC_CONNECT_EVT:')[1].split('case ESP_GATTC_OPEN_EVT:')[0])
        self.assertIn('reset_obd_signal();', handler.split('case ESP_GATTC_DISCONNECT_EVT:')[1])

    def test_connection_and_slave_sweep_cannot_animate_or_flash(self):
        source = (ROOT / 'main/export_path/ui_ext.c').read_text(encoding='utf-8')
        functions = '\n'.join(function(source, n) for n in (
            'ui_ext_sweep_active', 'ui_ext_sweep_trigger', 'ui_ext_sweep_tick'))
        self.run_c(r'''
#include <assert.h>
#include "export_path/ui_ext.h"
static int s_sweep_step, s_sweep_bl_last = -1, brightness_writes;
static bool s_sweep_pending, s_boot_done, s_prev_ble_connected;
static void gauge_display_set_brightness(int brightness) { (void)brightness; brightness_writes++; }
''' + functions + r'''
int main(void) {
    ui_ext_sweep_trigger(true, false); // Connect while logo is playing.
    assert(!s_sweep_pending && !ui_ext_sweep_active());
    s_boot_done = true;
    ui_ext_sweep_trigger(false, false);
    ui_ext_sweep_trigger(true, false);
    assert(!s_sweep_pending && !ui_ext_sweep_active());
    assert(ui_ext_sweep_tick(false, 50) < 0 && brightness_writes == 0);
    s_sweep_step = 1; // Even an old master's sweep must not animate a new slave.
    assert(!ui_ext_sweep_active());
    assert(ui_ext_sweep_tick(true, 50) < 0 && brightness_writes == 0);
    return 0;
}
''')

    def test_slave_requires_master_vehicle_data_not_just_broadcasts(self):
        source = (ROOT / 'main/bsp_obd_dsp/espnow_link.c').read_text(encoding='utf-8')
        functions = '\n'.join(function(source, n) for n in (
            'espnow_link_slave_has_data', 'espnow_link_slave_obd_connected',
            'espnow_link_slave_obd_has_valid_data'))
        self.run_c(r'''
#include <assert.h>
#include "bsp_obd_dsp/espnow_link.h"
static int64_t s_last_rx_us, now_us;
static uint8_t s_rx_obd_flags;
static int64_t esp_timer_get_time(void) { return now_us; }
''' + functions + r'''
int main(void) {
    assert(!espnow_link_slave_obd_connected());
    s_last_rx_us = now_us = 100;
    assert(!espnow_link_slave_obd_connected());
    s_rx_obd_flags = 1; // Connected master, but waiting for vehicle data (also old firmware).
    assert(espnow_link_slave_obd_connected() && !espnow_link_slave_obd_has_valid_data());
    s_rx_obd_flags |= ESPNOW_FLAG_OBD_DATA_VALID;
    assert(espnow_link_slave_obd_has_valid_data());
    now_us += 2000000;
    assert(!espnow_link_slave_obd_connected() && !espnow_link_slave_obd_has_valid_data());
    return 0;
}
''')


if __name__ == '__main__':
    unittest.main()
