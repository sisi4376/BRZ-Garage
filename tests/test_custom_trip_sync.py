"""Exercise production custom baseline persistence with injected flash failures."""
import re
import unittest
from pathlib import Path
import test_firmware_startup as host

ROOT = Path(__file__).resolve().parents[1]


class CustomTripFirmwareTest(unittest.TestCase):
    def test_durable_receipt_retry_and_reboot(self):
        source = (ROOT / 'main/bsp_obd_dsp/nvs_storage.c').read_text(encoding='utf8')
        header = (ROOT / 'main/bsp_obd_dsp/nvs_storage.h').read_text(encoding='utf8')
        store = re.search(r'typedef struct \{\s+uint32_t version;\s+uint64_t baseline_duration_ms;\s+uint64_t baseline_distance_mm;\s+uint64_t baseline_fuel_ul;\s+uint32_t token;[\s\S]*?\} custom_trip_store_t;', source).group()
        receipt = re.search(r'typedef struct \{\s+uint32_t token;[\s\S]*?\} nvs_custom_trip_receipt_t;', header).group()
        functions = '\n'.join(host.function(source, name) for name in (
            'custom_trip_flush_locked', 'nvs_custom_trip_get_receipt',
            'nvs_custom_trip_set_confirmed_baseline', 'nvs_custom_trip_set_baseline'))
        host.StartupTest().run_c(r'''
#include <assert.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>
typedef int esp_err_t;
#define ESP_OK 0
#define ESP_ERR_INVALID_STATE 1
#define CUSTOM_TRIP_STORE_VERSION 2
#define NS_CFG "cfg"
#define KEY_CUSTOM_TRIP_V2 "customtrip2"
#define portMAX_DELAY 0
static int s_mux=1, s_custom_trip_save_mux=2, locks[3];
static void xSemaphoreTake(int lock, int timeout) { (void)timeout; assert(!locks[lock]); locks[lock]=1; }
static void xSemaphoreGive(int lock) { assert(locks[lock]); locks[lock]=0; }
static uint32_t clamp_u32_u64(uint64_t x) { return x > UINT32_MAX ? UINT32_MAX : (uint32_t)x; }
static struct { uint64_t lifetime_distance_mm, lifetime_duration_ms, lifetime_fuel_ul; } s_fuel;
''' + store + '\n' + receipt + r'''
static custom_trip_store_t s_custom_trip, disk;
static bool s_custom_trip_dirty, fail_save;
static unsigned saves;
void nvs_custom_trip_get_receipt(nvs_custom_trip_receipt_t *out);
static esp_err_t save_blob(const char *ns, const char *key, const void *copy, size_t size) {
    assert(locks[2] && !locks[1]); assert(strcmp(ns,"cfg")==0 && strcmp(key,"customtrip2")==0);
    assert(size==sizeof(disk)); saves++;
    nvs_custom_trip_receipt_t during;
    nvs_custom_trip_get_receipt(&during);
    assert(!during.durable); // Never acknowledge RAM while flash is in flight.
    if (fail_save) return 2;
    memcpy(&disk,copy,size); return ESP_OK;
}
''' + functions + r'''
int main(void) {
    s_fuel.lifetime_distance_mm=1000000; s_fuel.lifetime_duration_ms=100000;
    s_fuel.lifetime_fuel_ul=50000;
    nvs_custom_trip_receipt_t receipt;
    fail_save=true;
    assert(nvs_custom_trip_set_confirmed_baseline(1500,150,75,101)!=ESP_OK);
    nvs_custom_trip_get_receipt(&receipt);
    assert(!receipt.durable && receipt.token==101 && receipt.distance_m==1000);
    s_fuel.lifetime_distance_mm=2000000; // Retry cannot move a previously clamped reset point.
    fail_save=false;
    assert(nvs_custom_trip_set_confirmed_baseline(1500,150,75,101)==ESP_OK);
    nvs_custom_trip_get_receipt(&receipt);
    assert(receipt.durable && receipt.distance_m==1000 && receipt.token==101);
    unsigned previous=saves;
    assert(nvs_custom_trip_set_confirmed_baseline(1500,150,75,101)==ESP_OK && saves==previous);
    s_custom_trip=disk; s_custom_trip_dirty=false; // Restart with only the committed blob.
    nvs_custom_trip_get_receipt(&receipt);
    assert(receipt.durable && receipt.token==101 && receipt.distance_m==1000);
    assert(nvs_custom_trip_set_confirmed_baseline(1800,90,40,102)==ESP_OK);
    fail_save=true;
    assert(nvs_custom_trip_set_confirmed_baseline(1900,95,45,103)!=ESP_OK);
    s_custom_trip=disk; s_custom_trip_dirty=false; // A power cut cannot advertise the failed new token.
    nvs_custom_trip_get_receipt(&receipt);
    assert(receipt.token==102 && receipt.distance_m==1800 && receipt.durable);
    fail_save=false;
    assert(nvs_custom_trip_set_confirmed_baseline(1900,95,45,103)==ESP_OK);
    assert(nvs_custom_trip_set_baseline(1700,80,35)==ESP_OK); // Legacy phones still work.
    nvs_custom_trip_get_receipt(&receipt);
    assert(receipt.token==0 && receipt.distance_m==1700 && receipt.durable);
    puts("PASS: flash failure, in-flight reads, idempotent clamping, power cut and legacy commands");
    return 0;
}
''')


if __name__ == '__main__':
    unittest.main()
