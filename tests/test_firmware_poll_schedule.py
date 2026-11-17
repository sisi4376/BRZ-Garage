"""Execute the firmware's actual slot switch and compare the capture mirrors."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from obd_gauge_poll import GaugePoll
from test_obd_poll_health import function


class FirmwarePollScheduleTest(unittest.TestCase):
    def test_four_profiles_fast_rounds_rotation_and_reconnect(self):
        source = (ROOT / 'main/bsp_obd_dsp/elm327_ble_client.c').read_text(encoding='utf-8')
        task = function(source, 'obd_poll_task')
        # The compiled switch includes the real oil strategy, slot advancement
        # and interleaved requests, rather than a second handwritten schedule.
        body = task[task.index('        switch(tick_count)'):task.index('        } // end standard OBD poll block')]
        self.assertEqual(task.count('"01 5E\\r"'), 1)
        self.assertEqual(task.count('"01 40\\r"'), 1)
        self.assertIn('s_slow_poll_index = 0;', function(source, 'do_elm_init'))
        compiler = ROOT / 'tools/.cache/w64devkit/bin/gcc.exe'
        compiler = str(compiler) if compiler.exists() else shutil.which('gcc')
        self.assertIsNotNone(compiler)
        harness = r'''
#include <assert.h>
#include <stdlib.h>
#include "app_obd_dsp/vehicle_profiles.h"
static vehicle_profile_t profile;
const vehicle_profile_t *vehicle_profile_get_active(void) { return &profile; }
const vehicle_override_t *vehicle_profile_get_override(void) { return NULL; }
static uint8_t tick_count, s_slow_poll_index, s_oil_override_idx, s_oil_query_mode;
static bool s_oil_use_override;
static uint8_t s_fuel_status_probes;
static int8_t s_fuel_status_pid_support = 1;
static int64_t now_us;
static const vehicle_override_t *s_ov;
static const oil_formula_t *s_oil_formula_pri, *s_oil_formula_sec;
static int64_t esp_timer_get_time(void) { return now_us; }
void obd_data_set_fuel_status(uint8_t status) { (void)status; }
static const char *get_vehicle_fixed_header_cmd(void) { return "ATSH7DF\r"; }
static oil_temp_query_mode_t get_next_oil_query_mode(uint8_t *idx) {
    *idx = 0; return profile.oil_temp_strategy.primary;
}
static bool elm327_ble_send_ascii_blocking(const char *cmd) {
    for (; *cmd; ++cmd) if (*cmd != ' ' && *cmd != '\r') putchar(*cmd);
    putchar('|'); return true;
}
static bool send_rpm_request(const char *tag) {
    (void)tag; return elm327_ble_send_ascii_blocking("01 0C\r");
}
static void send_standard_gear_request(int64_t now) { (void)now; }
''' + function(source, 'send_fuel_status_request') + r'''
static void slot(void) {
    bool can_broadcast=false, can_obd_primary=false;
    bool can_has_coolant=false, can_has_oil=false, can_has_tps=false;
    int64_t poll_now_us=now_us;
''' + body + r'''
}
int main(int argc, char **argv) {
    assert(argc == 2);
    profile.oil_temp_strategy.primary = argv[1][1] == 'c'
        ? OIL_TEMP_MODE_TOYOTA_21_01 : OIL_TEMP_MODE_PID_5C;
    for (int n=0; n<1000; ++n) {
        now_us = n < 80 ? 1000000LL + n*1000LL : 1080000LL + (n-80)*170000LL;
        slot(); putchar('\n');
    }
    // ELM reconnect restarts the rotating cursor at intake temperature.
    tick_count=0; s_slow_poll_index=0;
    for (int n=0; n<6; ++n) { ++now_us; slot(); putchar('\n'); }
    return 0;
}
'''
        env = os.environ.copy()
        env['PATH'] = str(Path(compiler).parent) + os.pathsep + env.get('PATH', '')
        with tempfile.TemporaryDirectory(prefix='firmware-poll-') as tmp:
            c = Path(tmp) / 'poll.c'
            exe = Path(tmp) / 'poll.exe'
            c.write_text(harness, encoding='utf-8')
            result = subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Imain',
                                     str(c), '-o', str(exe)], cwd=ROOT, env=env,
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for mode in ('zd8-mt','zd8-at','zc6-mt','zc6-at'):
                with self.subTest(mode=mode):
                    result = subprocess.run([str(exe), mode], env=env, capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    poll = GaugePoll(mode)
                    poll.observe('0100', b'41 00 BE 3F A8 13\r>')
                    expected = []
                    for n in range(1000):
                        now = 1 + n*.001 if n < 80 else 1.08 + (n-80)*.17
                        expected.append(poll.commands(now))
                    poll = GaugePoll(mode)
                    poll.observe('0100', b'41 00 BE 3F A8 13\r>')
                    expected += [poll.commands(now + (n+1)*.000001) for n in range(6)]
                    actual = [line.rstrip('|').split('|') if line else []
                              for line in result.stdout.splitlines()]
                    self.assertEqual(actual, expected)


if __name__ == '__main__':
    unittest.main()
