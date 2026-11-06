"""Exercise the real storage update, not only the estimator's return value."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_zd8_release_contract import function

ROOT = Path(__file__).resolve().parents[1]


class MileageIntegrationTest(unittest.TestCase):
    def test_storage_retains_distance_when_fuel_is_missing(self):
        bundled = ROOT / 'tools/.cache/w64devkit/bin/gcc.exe'
        compiler = str(bundled) if bundled.exists() else shutil.which('gcc')
        if not compiler:
            self.skipTest('A host C compiler is required')
        env = os.environ.copy()
        env['PATH'] = str(Path(compiler).parent) + os.pathsep + env.get('PATH', '')
        storage = (ROOT / 'main/bsp_obd_dsp/nvs_storage.c').read_text(encoding='utf-8')
        source = r'''
#include <assert.h>
#include <limits.h>
#include <time.h>
#include "app_obd_dsp/fuel_estimator.h"
#include "app_obd_dsp/trip_idle_detector.h"
#include "app_obd_dsp/trip_time_anchor.h"
typedef struct { unsigned requested, received; } obd_poll_health_t;
typedef struct { unsigned max_rpm, max_speed_kmh; int max_accel_x100, max_decel_x100; } detail_t;
static struct {
 uint64_t active_duration_ms, active_distance_mm, active_fuel_ul;
 uint64_t lifetime_duration_ms, lifetime_distance_mm, lifetime_fuel_ul;
 uint64_t active_start_epoch_s, active_last_epoch_s;
 detail_t active_detail; obd_poll_health_t active_health;
} s_fuel;
static fuel_estimator_t s_fuel_estimator;
static trip_idle_detector_t s_trip_idle_detector;
static trip_time_anchor_t s_trip_time_anchor;
static struct { unsigned trip_merge_timeout_min; } s_cfg = {15};
static int s_mux = 1, saves, finalized;
static bool s_fuel_data_valid, s_fuel_instant_is_lph, s_fuel_dirty;
static bool s_accel_speed_valid, s_phone_time_valid;
static uint16_t s_fuel_instant_x100, s_accel_last_speed_kmh;
static uint32_t s_accel_elapsed_ms;
static fuel_source_t s_fuel_source;
static int64_t now_us;
#define portMAX_DELAY 0
#define xSemaphoreTake(a,b) ((void)0)
#define xSemaphoreGive(a) ((void)0)
#define ESP_LOGI(...) ((void)0)
static int64_t esp_timer_get_time(void) { return now_us; }
static obd_poll_health_t obd_poll_health_take(void) { return (obd_poll_health_t){0}; }
static void obd_poll_health_merge(obd_poll_health_t *a, const obd_poll_health_t *b) { (void)a; (void)b; }
static void fuel_finish_active_trip(void) { finalized++; }
static void nvs_fuel_save(void) { saves++; }
''' + function(storage, 'nvs_fuel_update') + r'''
int main(void) {
 fuel_sample_t s = {.rpm=2500, .speed_kmh=60, .rpm_valid=true, .speed_valid=true,
  .maf_valid=true, .maf_x100=1470, .afr_x100=1470, .load_pct=30, .tps_pct=25, .fuel_status=2};
 for (int i=0; i<18000; i++) {
  now_us += 200000; s.maf_valid = (i % 300) >= 15;
  nvs_fuel_update(&s,200);
 }
 assert(s_fuel.active_distance_mm == 60000000);
 assert(s_fuel.lifetime_distance_mm == 60000000);
 assert(s_fuel.active_fuel_ul == 4590400 && s_fuel.lifetime_fuel_ul == 4590400);
 assert(s_fuel.active_duration_ms == 3600000 && !finalized);
 uint64_t before = s_fuel.lifetime_distance_mm;
 s.maf_valid=false; s.rpm_valid=false;
 // Moving with unavailable RPM must not be archived as a parked trip.
 for (int i=0; i<5000; i++) { now_us+=200000; nvs_fuel_update(&s,200); }
 assert(s_fuel.lifetime_distance_mm > before && !finalized && !s_fuel_data_valid);
 assert(s_fuel.active_fuel_ul == 4590400);
 before=s_fuel.lifetime_distance_mm;
 s.speed_valid=false; nvs_fuel_update(&s,200);
 assert(s_fuel.lifetime_distance_mm == before);
 s.speed_valid=true; nvs_fuel_update(&s,2001);
 assert(s_fuel.lifetime_distance_mm == before);
 // Missing fuel at rest must not create mileage. Stop still finalizes/saves.
 s.speed_kmh=0;
 for (int i=0; i<4500; i++) nvs_fuel_update(&s,200);
 assert(s_fuel.lifetime_distance_mm == before && finalized == 1 && saves == 1);
 return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix='mileage-storage-') as tmp:
            src = Path(tmp) / 'test.c'
            exe = Path(tmp) / 'test.exe'
            src.write_text(source, encoding='utf-8')
            result = subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Imain',
                                     str(src), 'main/app_obd_dsp/fuel_estimator.c',
                                     'main/app_obd_dsp/trip_idle_detector.c',
                                     'main/app_obd_dsp/trip_time_anchor.c', '-o', str(exe)],
                                    cwd=ROOT, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run([str(exe)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
