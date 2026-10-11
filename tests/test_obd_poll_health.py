"""Run production health tracking, gear scheduling and trip storage on the host."""
import os
import re
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def function(source, name):
    match = re.search(r'^(?:static\s+)?[\w *]+\s+' + name + r'\([^;]*?\)\s*\{', source, re.M)
    assert match, name
    return source[match.start():source.index('\n}', match.end()) + 2]


class PollHealthTest(unittest.TestCase):
    def run_c(self, source, extra=()):
        bundled = ROOT / 'tools/.cache/w64devkit/bin/gcc.exe'
        compiler = str(bundled) if bundled.exists() else shutil.which('gcc')
        self.assertIsNotNone(compiler, 'host C compiler required')
        env = os.environ.copy()
        env['PATH'] = str(Path(compiler).parent) + os.pathsep + env.get('PATH', '')
        with tempfile.TemporaryDirectory(prefix='poll-health-') as tmp:
            path = Path(tmp) / 'test.c'
            path.write_text(source, encoding='utf-8')
            exe = Path(tmp) / 'test.exe'
            subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Imain', str(path),
                            *extra, '-o', str(exe)], cwd=ROOT, env=env, check=True,
                           capture_output=True, text=True)
            result = subprocess.run([str(exe)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_response_length_zero_value_drain_and_merge(self):
        self.run_c(r'''
#include <assert.h>
#include "app_obd_dsp/obd_poll_health.h"
int main(void) {
    uint32_t zero[4] = {0};
    obd_poll_health_request((const uint8_t *)"010D\r", 5);
    obd_poll_health_request((const uint8_t *)"ATZ\r", 4);
    obd_poll_health_response(0x0D, zero, 0); // truncated is not success
    obd_poll_health_t h = obd_poll_health_take();
    assert(h.requested == (OBD_POLL_HEALTH_PRESENT | 2));
    assert(h.received == 0);
    obd_poll_health_response(0x0D, zero, 1); // standstill is a valid response
    obd_poll_health_response(0xA4, zero, 3); // incomplete gear response
    obd_poll_health_t next = obd_poll_health_take();
    assert(next.received == 2 && next.requested == h.requested);
    obd_poll_health_merge(&h, &next);
    assert(h.received == 2);
    assert(obd_poll_health_take().received == 0);
    uint32_t invalid[2] = {256, 1};
    obd_poll_health_response(0x0C, invalid, 2);
    obd_poll_health_response(0xFF, zero, 4);
    assert(obd_poll_health_take().received == 0);
    obd_poll_health_request((const uint8_t *)"2101\r", 5);
    obd_poll_health_toyota_oil_received();
    next = obd_poll_health_take();
    assert(next.received == (1UL << 14));
    assert(next.requested == (OBD_POLL_HEALTH_PRESENT | (1UL << 14)));
    return 0;
}
''', ['main/app_obd_dsp/obd_poll_health.c'])

    def test_disabled_self_check_is_unrecorded_and_preserves_old_history(self):
        self.run_c(r'''
#include <assert.h>
#include "app_obd_dsp/obd_poll_health.h"
int main(void) {
    uint32_t rpm[2] = {0x10, 0x00};
    obd_poll_health_request((const uint8_t *)"010C\r", 5);
    obd_poll_health_response(0x0C, rpm, 2);
    obd_poll_health_toyota_oil_received();
    obd_poll_health_t h = obd_poll_health_take();
    assert(h.requested == 0 && h.received == 0);
    obd_poll_health_t old = {OBD_POLL_HEALTH_PRESENT | 1, 1};
    obd_poll_health_merge(&old, &h);
    assert(old.requested == (OBD_POLL_HEALTH_PRESENT | 1) && old.received == 1);
    return 0;
}
''', ['-DCONFIG_OBD_POLL_SELF_CHECK=0', 'main/app_obd_dsp/obd_poll_health.c'])

    def test_gear_queries_are_bounded_and_never_catch_up(self):
        source = (ROOT / 'main/bsp_obd_dsp/elm327_ble_client.c').read_text(encoding='utf-8')
        self.run_c(r'''
#include <stdint.h>
#include <stdbool.h>
#include <assert.h>
#include <string.h>
#define CONFIG_OBD_EXPERIMENTAL_GEAR_POLL 1
typedef struct { bool obd_standard_gear_pid; } vehicle_profile_t;
static vehicle_profile_t profile = {true};
static const vehicle_profile_t *vehicle_profile_get_active(void) { return &profile; }
static int8_t s_standard_gear_pid_support = -1;
static uint8_t s_gear_support_probes, s_gear_unanswered;
static int64_t s_gear_poll_us;
static unsigned cap_requests, gear_requests;
static void elm327_ble_send_ascii_blocking(const char *cmd) {
    if (!strcmp(cmd,"01 A0\r")) cap_requests++;
    else { assert(!strcmp(cmd,"01 A4\r")); gear_requests++; }
}
''' + function(source, 'poll_interval_due') + '\n' + function(source, 'send_standard_gear_request') + r'''
int main(void) {
    for (int64_t t = 1; t < 30000000; t += 1000) send_standard_gear_request(t);
    assert(cap_requests == 3 && gear_requests == 0 && s_standard_gear_pid_support == 0);
    s_standard_gear_pid_support = 1; s_gear_poll_us = 0;
    for (int64_t t = 1; t < 10000000; t += 1000) send_standard_gear_request(t);
    assert(gear_requests == 3 && s_standard_gear_pid_support == 0);
    s_standard_gear_pid_support = 1; s_gear_unanswered = 0; s_gear_poll_us = 0;
    send_standard_gear_request(1);
    s_gear_unanswered = 0; // successful response callback
    send_standard_gear_request(2000000); assert(gear_requests == 4);
    send_standard_gear_request(2000001); assert(gear_requests == 5);
    s_gear_unanswered = 0;
    send_standard_gear_request(90000001); assert(gear_requests == 6);
    send_standard_gear_request(90000002); assert(gear_requests == 6);
    profile.obd_standard_gear_pid = false;
    send_standard_gear_request(99000001); assert(gear_requests == 6);
    return 0;
}
''')

    def test_production_never_sends_experimental_gear_requests(self):
        source = (ROOT / 'main/bsp_obd_dsp/elm327_ble_client.c').read_text(encoding='utf-8')
        body = function(source, 'send_standard_gear_request')
        # Unknown capability, advertised support and repeated reconnects all
        # keep the released v4.0.0 empty slot. Opt-in behavior is tested above.
        for define in ('', '#define CONFIG_OBD_EXPERIMENTAL_GEAR_POLL 0\n'):
            with self.subTest(define=define):
                self.run_c(r'''
#include <stdint.h>
#include <stdbool.h>
#include <assert.h>
''' + define + r'''
typedef struct { bool obd_standard_gear_pid; } vehicle_profile_t;
static vehicle_profile_t profile = {true};
static const vehicle_profile_t *vehicle_profile_get_active(void) { return &profile; }
static int8_t s_standard_gear_pid_support;
static uint8_t s_gear_support_probes, s_gear_unanswered;
static int64_t s_gear_poll_us;
static unsigned requests;
static bool elm327_ble_send_ascii_blocking(const char *cmd) {
    (void)cmd; ++requests; return true;
}
''' + function(source, 'poll_interval_due') + '\n' + body + r'''
int main(void) {
    for (int reconnect = 0; reconnect < 3; ++reconnect) {
        for (int support = -1; support <= 1; ++support) {
            s_standard_gear_pid_support = support;
            s_gear_support_probes = s_gear_unanswered = 0;
            s_gear_poll_us = 0;
            for (int64_t t = 1; t < 120000000; t += 1000)
                send_standard_gear_request(t);
            assert(requests == 0);
            assert(s_standard_gear_pid_support == support);
            assert(s_gear_support_probes == 0 && s_gear_unanswered == 0 && s_gear_poll_us == 0);
        }
    }
    return 0;
}
''')

    def test_retained_trip_queue_evicts_health_with_its_record(self):
        source = (ROOT / 'main/bsp_obd_dsp/nvs_storage.c').read_text(encoding='utf-8')
        self.run_c(r'''
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <assert.h>
#include "app_obd_dsp/obd_poll_health.h"
#define NVS_TRIP_SYNC_QUEUE_MAX 64
typedef struct { uint32_t id; } nvs_trip_sync_record_t;
typedef struct { uint16_t max_speed_kmh, max_rpm; int16_t max_accel_x100, max_decel_x100; } nvs_trip_detail_t;
static struct {
    uint8_t count, overflowed;
    nvs_trip_sync_record_t records[64];
    nvs_trip_detail_t details[64];
    obd_poll_health_t health[64];
} s_trip_sync;
static bool s_trip_sync_dirty;
''' + function(source, 'trip_sync_enqueue') + r'''
int main(void) {
    nvs_trip_detail_t detail = {0};
    for (unsigned i = 1; i <= 70; ++i) {
        nvs_trip_sync_record_t record = {i};
        obd_poll_health_t h = {OBD_POLL_HEALTH_PRESENT | i, i};
        trip_sync_enqueue(&record, &detail, &h);
    }
    assert(s_trip_sync.count == 64 && s_trip_sync.overflowed && s_trip_sync_dirty);
    for (unsigned i = 0; i < 64; ++i) {
        assert(s_trip_sync.records[i].id == i + 7);
        assert(s_trip_sync.health[i].received == i + 7);
    }
    nvs_trip_sync_record_t legacy = {71};
    trip_sync_enqueue(&legacy, 0, 0);
    assert(s_trip_sync.health[63].requested == 0 && s_trip_sync.health[63].received == 0);
    return 0;
}
''')

    def test_old_nvs_layout_is_an_exact_prefix(self):
        source = (ROOT / 'main/bsp_obd_dsp/nvs_storage.c').read_text(encoding='utf-8')
        header = (ROOT / 'main/bsp_obd_dsp/nvs_storage.h').read_text(encoding='utf-8')
        def typedef(text, name):
            return re.search(r'typedef struct \{[^}]*\} ' + name + ';', text, re.S).group()
        fuel = typedef(source, 'fuel_store_t')
        sync = typedef(source, 'trip_sync_store_t')
        old_fuel = fuel[:fuel.index('    /* Append-only extension:')] + '} old_fuel_t;'
        old_sync = sync[:sync.index('    obd_poll_health_t health[')] + '} old_sync_t;'
        common = '\n'.join(typedef(header, n) for n in
                           ('nvs_fuel_trip_record_t', 'nvs_trip_detail_t', 'nvs_trip_sync_record_t'))
        self.run_c(r'''
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <assert.h>
#include "app_obd_dsp/obd_poll_health.h"
#define NVS_FUEL_TRIP_HISTORY_MAX 20
#define NVS_TRIP_SYNC_QUEUE_MAX 64
''' + common + '\n' + fuel + '\n' + sync + '\n' + old_fuel + '\n' + old_sync + r'''
_Static_assert(sizeof(old_fuel_t) == offsetof(fuel_store_t, active_health), "fuel prefix changed");
_Static_assert(sizeof(old_sync_t) == offsetof(trip_sync_store_t, health), "sync prefix changed");
int main(void) {
    old_fuel_t old = {0}; fuel_store_t current = {0};
    old.active_duration_ms = 123456; old.pending_duration_ms = 654321;
    old.history[19].id = 99; old.history_details[19].max_rpm = 7000;
    memcpy(&current, &old, sizeof(old));
    assert(current.active_duration_ms == 123456 && current.pending_duration_ms == 654321);
    assert(current.history[19].id == 99 && current.history_details[19].max_rpm == 7000);
    assert(current.active_health.requested == 0 && current.history_health[19].received == 0);
    old_sync_t old_queue = {0}; trip_sync_store_t queue = {0};
    old_queue.records[63].id = 999; old_queue.details[63].max_rpm = 7200;
    memcpy(&queue, &old_queue, sizeof(old_queue));
    assert(queue.records[63].id == 999 && queue.details[63].max_rpm == 7200);
    assert(queue.health[63].requested == 0 && queue.health[63].received == 0);
    return 0;
}
''')

    def test_android_v5_migration_keeps_old_record_and_marks_unrecorded(self):
        source = (ROOT / 'android_app/app/src/main/java/com/brz/gauge/trips/TripDatabase.kt').read_text(encoding='utf-8')
        create = re.search(r'CREATE TABLE trips \([\s\S]*?\n\s*\)', source).group()
        old_create = re.sub(r'^\s*poll_(requested|received) INTEGER[^\n]*\n', '', create, flags=re.M)
        with sqlite3.connect(':memory:') as db:
            db.execute(old_create)
            db.execute('INSERT INTO trips (device_id,trip_id,start_epoch_s,end_epoch_s,duration_s,distance_m,fuel_ml,avg_l100_x100,flags,synced_at_s,data_revised) VALUES (?,?,?,?,?,?,?,?,?,?,?)',
                       ('car',42,0,0,3600,10000,800,800,0,0,1))
            for sql in re.findall(r'"(ALTER TABLE trips ADD COLUMN poll_[^"]+)"', source):
                db.execute(sql)
            self.assertEqual(db.execute('SELECT trip_id,distance_m,data_revised,poll_requested,poll_received FROM trips').fetchone(),
                             (42,10000,1,0,0))
            db.execute('UPDATE trips SET poll_requested=?,poll_received=?', (0x80000002,2))
            self.assertEqual(db.execute('SELECT poll_requested,poll_received FROM trips').fetchone(), (0x80000002,2))


if __name__ == '__main__':
    unittest.main()
