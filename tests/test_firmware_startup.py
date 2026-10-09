"""Run production startup/telemetry paths with deterministic allocation faults."""
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def function(source, name):
    match = re.search(r'^(?:static\s+)?[\w *]+\s+' + name + r'\([^;]*?\)\s*\{', source, re.M)
    assert match, name
    return source[match.start():source.index('\n}', match.end()) + 2]


class StartupTest(unittest.TestCase):
    def run_c(self, source, headers=None, extras=()):
        compiler = ROOT / 'tools/.cache/w64devkit/bin/gcc.exe'
        self.assertTrue(compiler.exists(), 'host C compiler required')
        env = dict(os.environ, PATH=str(compiler.parent) + os.pathsep + os.environ['PATH'])
        with tempfile.TemporaryDirectory(prefix='startup-host-') as tmp:
            folder = Path(tmp)
            for name, content in (headers or {}).items():
                path = folder / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding='utf-8')
            path = folder / 'test.c'
            path.write_text(source, encoding='utf-8')
            exe = folder / 'test.exe'
            result = subprocess.run([str(compiler), '-std=c11', '-Wall', '-Wextra',
                                     '-I' + str(folder), '-Itests/host_stubs', '-Imain',
                                     str(path), *extras, '-o', str(exe)], cwd=ROOT, env=env,
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run([str(exe)], env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_statistics_allocation_retry_and_real_loop_heartbeat(self):
        self.run_c(r'''
#include <assert.h>
#include <setjmp.h>
#include "app_obd_dsp/vehicle_profiles.h"
#include "freertos/FreeRTOS.h"
static jmp_buf escape;
static unsigned creates, delays, iterations;
static bool allocation_ok;
TickType_t test_now;
static const vehicle_profile_t profile={.name="ZD8",.gear_count=0};
const vehicle_profile_t *vehicle_profile_get_active(void) {return &profile;}
float vehicle_profile_calc_constant(const vehicle_profile_t *p) {(void)p;return 1;}
void nvs_stat_update_speed(uint8_t s,uint32_t dt) {(void)s;(void)dt;}
void nvs_fuel_update(const fuel_sample_t *s,uint32_t dt) {(void)s;assert(dt==200);iterations++;}
int nvs_fuel_save(void) {return 0;}
static void delay(unsigned ticks) {
    if (delays++==1) longjmp(escape,1);
    test_now+=ticks;
}
static int create(void (*fn)(void *), const char *name, unsigned stack, void *arg,
                  unsigned priority, TaskHandle_t *out) {
    (void)fn;(void)name;(void)arg;assert(stack==6144 && priority==2);
    creates++;if (!allocation_ok) return 0;*out=(void *)1;return 1;
}
#include "app_obd_dsp/obd_data_cache.c"
int main(void) {
    assert(!obd_statistics_is_healthy());
    assert(!vMileageDataStatisticTask() && creates==1);
    allocation_ok=true;
    assert(vMileageDataStatisticTask() && creates==2);
    assert(vMileageDataStatisticTask() && creates==2);
    // No vehicle samples: a complete real task iteration is still healthy.
    if (!setjmp(escape)) mileage_statistics_task(0);
    assert(iterations==1 && obd_statistics_update_count()==1);
    assert(obd_statistics_is_healthy());
    test_now+=2001;assert(!obd_statistics_is_healthy());
    s_statistics_last_tick=UINT32_MAX-100;test_now=100;
    assert(obd_statistics_is_healthy());
    // The update counter must never wrap to the unstarted sentinel (zero).
    s_statistics_updates=UINT32_MAX;delays=0;
    if (!setjmp(escape)) mileage_statistics_task(0);
    assert(obd_statistics_update_count()==1);
    return 0;
}
''', {'freertos/task.h': r'''
#pragma once
#include "freertos/FreeRTOS.h"
extern TickType_t test_now;
static inline TickType_t xTaskGetTickCount(void) {return test_now;}
static inline UBaseType_t uxTaskGetStackHighWaterMark(TaskHandle_t t) {(void)t;return 1024;}
#define vTaskDelay delay
#define xTaskCreate create
'''})

    def test_ota_confirmation_requires_progress_and_fresh_heartbeat(self):
        source = (ROOT / 'main/app_main.c').read_text(encoding='utf-8')
        self.run_c(r'''
#include <stdint.h>
#include <stdbool.h>
#include <assert.h>
#include <inttypes.h>
#include "esp_log.h"
#define ESP_ERR_OTA_ROLLBACK_INVALID_STATE 1
#define pdMS_TO_TICKS(x) (x)
static uint32_t updates, after_wait;
static bool healthy;
static unsigned confirmations;
static uint32_t obd_statistics_update_count(void) {return updates;}
static bool obd_statistics_is_healthy(void) {return healthy;}
static void vTaskDelay(unsigned ms) {assert(ms==15000);updates=after_wait;}
static int esp_ota_mark_app_valid_cancel_rollback(void) {confirmations++;return ESP_OK;}
''' + function(source, 'validate_startup') + r'''
int main(void) {
    assert(!validate_startup(true) && confirmations==0); // never started
    updates=10;after_wait=10;healthy=true;
    assert(!validate_startup(true) && confirmations==0); // stalled, cached healthy
    updates=10;after_wait=20;healthy=false;
    assert(!validate_startup(true) && confirmations==0); // progressed then stalled
    updates=10;after_wait=85;healthy=true;
    assert(validate_startup(true) && confirmations==1); // no ECU required
    updates=0;after_wait=0;healthy=false;
    assert(validate_startup(false) && confirmations==2); // receive-only slave
    return 0;
}
''')

    def test_optional_monitor_allocation_failures_release_everything(self):
        self.run_c(r'''
#include <stdint.h>
#include <stdlib.h>
#include <assert.h>
#include <stdbool.h>
static size_t available, largest;
static bool fail_alloc, fail_task;
static unsigned allocs, frees, tasks;
static void *live;
static size_t heap_caps_get_free_size(uint32_t c) {assert(c==3);return available;}
static size_t heap_caps_get_largest_free_block(uint32_t c) {assert(c==3);return largest;}
static void *heap_caps_calloc(size_t n,size_t size,uint32_t c) {
    assert(c==3);allocs++;if(fail_alloc)return 0;live=calloc(n,size);return live;
}
static void heap_caps_free(void *p) {assert(p==live);frees++;free(p);live=0;}
static int xTaskCreate(void (*fn)(void *),const char *name,unsigned stack,void *arg,unsigned pri,void *out) {
    (void)fn;(void)name;(void)out;assert(arg==live && stack==4096 && pri==1);tasks++;return !fail_task;
}
#define CONFIG_OBD_PERF_MONITOR 1
#include "app_obd_dsp/perf_monitor.c"
int main(void) {
    perf_emit(PERF_RPM,0,0,0); // no allocation before optional startup
    available=largest=8192;perf_monitor_start();assert(allocs==0 && tasks==0);
    available=65536;largest=100;perf_monitor_start();assert(allocs==0);
    largest=65536;fail_alloc=true;perf_monitor_start();assert(allocs==1 && tasks==0);
    fail_alloc=false;fail_task=true;perf_monitor_start();
    assert(allocs==2 && tasks==1 && frees==1 && !live && !enabled && !s_context);
    perf_emit(PERF_RPM,0,0,0);
    fail_task=false;perf_monitor_start();assert(enabled && live && tasks==2);
    perf_emit(PERF_RPM,0,123,1);
    perf_event_t e;assert(perf_queue_pop(&s_context->queue,&e) && e.time_us==123);
    perf_monitor_start();assert(tasks==2 && allocs==3); // idempotent
    heap_caps_free(live);
    return 0;
}
''', {'esp_heap_caps.h': '#pragma once\n#define MALLOC_CAP_INTERNAL 1\n#define MALLOC_CAP_8BIT 2\n',
      'esp_timer.h': '#pragma once\nstatic inline int64_t esp_timer_get_time(void) {return 123;}\n',
      'freertos/task.h': '#pragma once\n#define tskIDLE_PRIORITY 0\nstatic inline void vTaskDelay(unsigned t) {(void)t;}\n'},
      ('main/app_obd_dsp/perf_monitor_core.c',))

    def test_core_allocation_precedes_display_and_radio(self):
        source = (ROOT / 'main/app_main.c').read_text(encoding='utf-8')
        main = function(source, 'app_main')
        self.assertEqual(main.count('vMileageDataStatisticTask()'), 1)
        start = main.index('ESP_ERROR_CHECK(vMileageDataStatisticTask()')
        for allocation in ('bsp_display_new(', 'heap_caps_malloc(', 'elm327_ble_ensure_stack_init()',
                           'espnow_link_start_master()'):
            self.assertLess(start, main.index(allocation))
        self.assertNotIn('xTaskCreate(mark_app_valid', source)
        self.assertLess(main.index('validate_startup('), main.index('perf_monitor_start()'))


if __name__ == '__main__':
    unittest.main()
