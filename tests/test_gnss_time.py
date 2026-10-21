"""Host tests of production NMEA and clock/recorder integration; no board needed."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

def function(source, name):
    match = re.search(r'^(?:static\s+)?[\w *]+\s+' + name + r'\([^;]*?\)\s*\{', source, re.M)
    assert match, name
    return source[match.start():source.index('\n}', match.end()) + 2]

def typedef(source, name):
    return next(m.group() for m in re.finditer(r'typedef (?:struct|enum) \{[^}]*\}\s*\w+;', source)
                if m.group().endswith('} ' + name + ';'))

class GnssTests(unittest.TestCase):
    def run_c(self, sources, includes=()):
        compiler = os.environ.get('HOST_CC') or shutil.which('gcc')
        self.assertIsNotNone(compiler, 'HOST_CC or gcc required')
        env = os.environ.copy()
        env['PATH'] = str(Path(compiler).parent) + os.pathsep + env.get('PATH', '')
        with tempfile.TemporaryDirectory(prefix='gnss-host-') as tmp:
            exe = Path(tmp) / 'test.exe'
            command = [compiler, '-std=c11', '-Wall', '-Wextra', '-Werror', '-Imain']
            command += ['-I' + str(p) for p in includes]
            command += [str(p) for p in sources] + ['-o', str(exe)]
            result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run([str(exe)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_nmea_stream(self):
        self.run_c(['main/app_obd_dsp/gnss_nmea.c', 'tests/gnss_nmea_host_test.c'])

    def test_receiver_lifecycle(self):
        source = (ROOT / 'main/app_obd_dsp/gnss_time.c').read_text(encoding='utf-8')
        body = source.split('#define GNSS_UART', 1)[1].split('#else\nvoid gnss_time_start', 1)[0]
        with tempfile.TemporaryDirectory(prefix='gnss-task-') as tmp:
            path = Path(tmp) / 'gnss_task_body.inc'
            path.write_text('#define GNSS_UART' + body, encoding='utf-8')
            self.run_c(['tests/gnss_task_host_test.c', 'main/app_obd_dsp/gnss_nmea.c'], [tmp])

    def test_disabled_and_other_board_are_noops(self):
        with tempfile.TemporaryDirectory(prefix='gnss-disabled-') as tmp:
            root = Path(tmp)
            entry = root / 'entry.c'
            entry.write_text('#include "app_obd_dsp/gnss_time.h"\nint main(void) { gnss_time_start(); gnss_time_start(); }\n')
            for enabled, board in ((0, 1), (1, 0)):
                (root / 'sdkconfig.h').write_text(f'#define CONFIG_OBD_GNSS_TIME {enabled}\n#define CONFIG_OBD_BOARD_AMOLED_175 {board}\n')
                self.run_c(['main/app_obd_dsp/gnss_time.c', entry], [root])

    def test_production_clock_application_preserves_recording(self):
        storage = (ROOT / 'main/bsp_obd_dsp/nvs_storage.c').read_text(encoding='utf-8')
        header = (ROOT / 'main/bsp_obd_dsp/nvs_storage.h').read_text(encoding='utf-8')
        fixture = r'''
#include <assert.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include "app_obd_dsp/obd_poll_health.h"
#include "app_obd_dsp/trip_time_anchor.h"
#define NVS_FUEL_TRIP_HISTORY_MAX 20
typedef int esp_err_t;
typedef int TickType_t;
#define ESP_OK 0
#define ESP_FAIL -1
#define ESP_ERR_INVALID_ARG 1
#define ESP_ERR_INVALID_STATE 2
#define ESP_ERR_TIMEOUT 3
#define portMAX_DELAY 1000
#define pdTRUE 1
static int s_mux = 1, locked, busy, set_calls, fail_clock;
static int64_t mono_us;
static uint64_t applied_epoch;
#define timeval test_timeval
struct timeval { time_t tv_sec; int tv_usec; };
static int xSemaphoreTake(int mux, int wait) {
    assert(mux && !locked);
    if (busy) { assert(wait == 0); return 0; }
    locked = 1; return 1;
}
static void xSemaphoreGive(int mux) { assert(mux && locked); locked = 0; }
static int settimeofday(const struct timeval *tv, void *zone) {
    (void)zone; assert(locked); ++set_calls;
    if (fail_clock) return -1;
    applied_epoch = tv->tv_sec; return 0;
}
static int64_t esp_timer_get_time(void) { return mono_us; }
'''
        fixture += '\n'.join(typedef(header, n) for n in (
            'nvs_fuel_trip_record_t', 'nvs_trip_detail_t', 'nvs_trip_time_source_t', 'nvs_trip_time_sync_result_t'))
        fixture += '\n' + typedef(storage, 'fuel_store_t')
        fixture += r'''
static fuel_store_t s_fuel;
static struct { uint64_t current_start_epoch_s; } s_refuel;
static struct { uint16_t trip_merge_timeout_min; } s_cfg = {15};
static bool s_refuel_dirty, s_fuel_dirty, s_active_contains_pending;
static atomic_bool s_time_valid;
static atomic_int s_time_source;
static trip_time_anchor_t s_trip_time_anchor;
static void trip_detail_merge(nvs_trip_detail_t *a, const nvs_trip_detail_t *b) { (void)a; (void)b; }
static void fuel_finish_pending_trip(void) { assert(!"unexpected archive"); }
static void fuel_clear_pending(void) { s_fuel.pending_valid = 0; }
'''
        fixture += '\n'.join(function(storage, n) for n in (
            'trip_apply_time', 'nvs_trip_apply_phone_time', 'nvs_trip_apply_gnss_time',
            'nvs_trip_time_is_valid', 'nvs_trip_time_source'))
        fixture += r'''
int main(void) {
    mono_us = 100000000;
    trip_time_anchor_observe(&s_trip_time_anchor, 20200000, 200);
    trip_time_anchor_observe(&s_trip_time_anchor, 100000000, 200);
    s_fuel.active_duration_ms = 80000;
    s_fuel.active_distance_mm = 123456;
    s_fuel.active_fuel_ul = 55555;
    s_fuel.lifetime_distance_mm = 999999;
    fuel_store_t before = s_fuel;
    assert(nvs_trip_apply_gnss_time(123, NULL) == ESP_ERR_INVALID_ARG);
    busy = 1;
    assert(nvs_trip_apply_gnss_time(1791763300, NULL) == ESP_ERR_TIMEOUT);
    assert(!nvs_trip_time_is_valid() && !set_calls && !memcmp(&before, &s_fuel, sizeof(before)));
    busy = 0; fail_clock = 1;
    assert(nvs_trip_apply_gnss_time(1791763300, NULL) == ESP_FAIL);
    assert(!locked && !nvs_trip_time_is_valid());
    fail_clock = 0;
    assert(nvs_trip_apply_gnss_time(1791763300, NULL) == ESP_OK);
    assert(nvs_trip_time_source() == NVS_TRIP_TIME_GNSS && nvs_trip_time_is_valid());
    assert(s_fuel.active_start_epoch_s == 1791763220);
    assert(s_fuel.active_last_epoch_s == 1791763300);
    assert(s_fuel.active_duration_ms == before.active_duration_ms);
    assert(s_fuel.active_distance_mm == before.active_distance_mm);
    assert(s_fuel.active_fuel_ul == before.active_fuel_ul);
    assert(s_fuel.lifetime_distance_mm == before.lifetime_distance_mm);
    assert(nvs_trip_apply_gnss_time(1791763400, NULL) == ESP_ERR_INVALID_STATE);
    assert(applied_epoch == 1791763300);
    assert(nvs_trip_apply_phone_time(1791763301, NULL) == ESP_OK);
    assert(nvs_trip_time_source() == NVS_TRIP_TIME_PHONE);
    assert(nvs_trip_apply_gnss_time(1791763400, NULL) == ESP_ERR_INVALID_STATE);
    assert(applied_epoch == 1791763301 && !locked);
    atomic_store(&s_time_valid, false); atomic_store(&s_time_source, NVS_TRIP_TIME_NONE);
    assert(nvs_trip_apply_phone_time(1791763500, NULL) == ESP_OK);
    int calls = set_calls;
    assert(nvs_trip_apply_gnss_time(1791763600, NULL) == ESP_ERR_INVALID_STATE);
    assert(set_calls == calls && applied_epoch == 1791763500);
    puts("PASS: nonblocking recorder, delayed anchor, totals, once-only and phone race");
}
'''
        with tempfile.TemporaryDirectory(prefix='gnss-clock-') as tmp:
            path = Path(tmp) / 'clock.c'
            path.write_text(fixture, encoding='utf-8')
            self.run_c([path, 'main/app_obd_dsp/trip_time_anchor.c', 'main/app_obd_dsp/obd_poll_health.c'])

if __name__ == '__main__':
    unittest.main()
