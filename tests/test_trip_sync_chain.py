"""Exercise production trip archiving, retained cursors and BLE serialization."""
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def function(source, name):
    match = re.search(r'^(?:static\s+)?[\w *]+\s+' + name + r'\([^;]*?\)\s*\{', source, re.M)
    assert match, name
    return source[match.start():source.index('\n}', match.end()) + 2]


def typedef(source, name):
    return next(m.group() for m in re.finditer(r'typedef struct \{[^}]*\}\s*\w+;', source)
                if m.group().endswith('} ' + name + ';'))


def build_fixture(output):
    storage = (ROOT / 'main/bsp_obd_dsp/nvs_storage.c').read_text(encoding='utf-8')
    header = (ROOT / 'main/bsp_obd_dsp/nvs_storage.h').read_text(encoding='utf-8')
    ble = (ROOT / 'main/bsp_obd_dsp/racechrono_ble_diy.c').read_text(encoding='utf-8')
    source = r'''
#include <assert.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>
#include "app_obd_dsp/obd_poll_health.h"
#define NVS_FUEL_TRIP_HISTORY_MAX 20
#define NVS_TRIP_SYNC_QUEUE_MAX 64
#define TRIP_SYNC_PROTOCOL_VERSION 4
#define TRIP_META_WIRE_SIZE 20
#define TRIP_DATA_WIRE_SIZE 56
typedef int esp_err_t;
#define ESP_OK 0
#define ESP_ERR_INVALID_STATE 1
#define ESP_ERR_INVALID_ARG 2
#define portMAX_DELAY 0
static int s_mux = 1;
#define xSemaphoreTake(a,b) ((void)0)
#define xSemaphoreGive(a) ((void)0)
'''
    source += '\n'.join(typedef(header, n) for n in (
        'nvs_fuel_trip_record_t', 'nvs_trip_detail_t', 'nvs_trip_sync_record_t', 'nvs_trip_sync_meta_t'))
    source += '\n' + typedef(storage, 'fuel_store_t') + '\n' + typedef(storage, 'trip_sync_store_t')
    source += r'''
static fuel_store_t s_fuel;
static trip_sync_store_t s_trip_sync;
static bool s_trip_sync_dirty;
static uint8_t s_trip_meta_value[TRIP_META_WIRE_SIZE];
static uint8_t s_trip_data_value[TRIP_DATA_WIRE_SIZE];
static uint32_t s_trip_request_after_id, s_trip_highest_sent_id;
'''
    source += '\n'.join(function(storage, n) for n in (
        'fuel_average_x100', 'trip_sync_enqueue', 'fuel_push_trip',
        'nvs_trip_sync_get_meta', 'nvs_trip_sync_read_after', 'nvs_trip_sync_ack'))
    source += '\n' + '\n'.join(function(ble, n) for n in (
        'put_le16', 'put_le32', 'put_le64', 'trip_crc16', 'build_trip_meta_wire', 'build_trip_data_wire'))
    source += r'''
static bool s_fuel_dirty, s_refuel_dirty, s_custom_trip_dirty;
static trip_sync_store_t s_trip_sync_save_copy, persisted_sync;
// Unrelated stores are inert: this test faults the trip-queue checkpoint only.
static int s_refuel, s_refuel_save_copy, s_custom_trip, s_custom_trip_save_copy;
#define NS_CFG "cfg"
#define KEY_FUEL_TRIPS "fueltrips"
#define KEY_TRIP_SYNC "tripsync"
#define KEY_REFUEL_HISTORY "refuelhist"
#define KEY_CUSTOM_TRIP "customtrip"
static bool fail_sync, update_during_save;
static esp_err_t save_blob(const char *ns, const char *key, const void *data, size_t len) {
    (void)ns;
    if (!strcmp(key, KEY_TRIP_SYNC)) {
        assert(len == sizeof(persisted_sync));
        if (fail_sync) return ESP_ERR_INVALID_STATE;
        memcpy(&persisted_sync, data, len);
        if (update_during_save) {
            update_during_save = false;
            fuel_push_trip(60000, 1000000, 80000, 1787895000ULL, 1787895060ULL, NULL, NULL);
        }
    }
    return ESP_OK;
}
'''
    source += '\n' + function(storage, 'nvs_fuel_save')
    source += r'''
int main(int argc, char **argv) {
    assert(argc == 2);
    nvs_trip_detail_t detail = {138, 6840, 326, -714};
    obd_poll_health_t health = {0x80002003U, 2};
    // Short accidental samples do not become trips.
    fuel_push_trip(1000, 0, 0, 0, 0, &detail, &health);
    assert(s_trip_sync.count == 0);
    // Two newly finalized trips, with their real unit conversion and IDs.
    for (unsigned i = 0; i < 2; i++)
        fuel_push_trip(3600000, 48210000, 4210000, 1787882400ULL+i*4000,
                       1787886000ULL+i*4000, &detail, &health);
    assert(s_trip_sync_dirty && s_trip_sync.count == 2);
    assert(nvs_trip_sync_ack(3) == ESP_ERR_INVALID_ARG);
    assert(s_trip_sync.last_acked_id == 0);
    assert(build_trip_meta_wire() == 20);
    FILE *out = fopen(argv[1], "wb"); assert(out);
    assert(fwrite(s_trip_meta_value, 1, 20, out) == 20);
    assert(build_trip_data_wire() == 56);
    assert(fwrite(s_trip_data_value, 1, 56, out) == 56);
    // A retry after interruption returns exactly the same record.
    uint8_t first[56]; memcpy(first, s_trip_data_value, 56);
    assert(build_trip_data_wire() == 56 && !memcmp(first, s_trip_data_value, 56));
    s_trip_request_after_id = 1;
    assert(build_trip_data_wire() == 56 && s_trip_highest_sent_id == 2);
    assert(fwrite(s_trip_data_value, 1, 56, out) == 56);
    assert(nvs_trip_sync_ack(2) == ESP_OK && nvs_trip_sync_ack(2) == ESP_OK);
    assert(s_trip_sync.count == 2); // ACK must not remove another phone's history.
    assert(build_trip_meta_wire() == 20 && s_trip_meta_value[1] == 0);
    s_trip_request_after_id = 0;
    assert(build_trip_data_wire() == 56 && !memcmp(first, s_trip_data_value, 56));
    // Data generated after the latest ACK is discoverable immediately.
    fuel_push_trip(3600000, 48210000, 4210000, 1787890400ULL,
                   1787894000ULL, &detail, &health);
    assert(build_trip_meta_wire() == 20 && s_trip_meta_value[1] == 1);
    assert(fwrite(s_trip_meta_value, 1, 20, out) == 20);
    s_trip_request_after_id = 2;
    assert(build_trip_data_wire() == 56);
    assert(fwrite(s_trip_data_value, 1, 56, out) == 56);
    s_trip_request_after_id = 3;
    assert(build_trip_data_wire() == 0);
    assert(fclose(out) == 0);
    // A failed flash write must preserve the queue for the next checkpoint.
    fail_sync = true;
    assert(nvs_fuel_save() != ESP_OK && s_trip_sync_dirty);
    fail_sync = false;
    assert(nvs_fuel_save() == ESP_OK && !s_trip_sync_dirty && persisted_sync.count == 3);
    // A record arriving during I/O must remain dirty after that older snapshot.
    s_trip_sync_dirty = true;
    update_during_save = true;
    assert(nvs_fuel_save() == ESP_OK && s_trip_sync_dirty && persisted_sync.count == 3);
    assert(s_trip_sync.count == 4);
    assert(nvs_fuel_save() == ESP_OK && !s_trip_sync_dirty && persisted_sync.count == 4);
    // Reload the committed queue and resume from the phone's durable cursor.
    memset(&s_trip_sync, 0, sizeof(s_trip_sync));
    s_trip_sync = persisted_sync;
    s_trip_request_after_id = 3;
    assert(build_trip_data_wire() == 56 && s_trip_data_value[4] == 4);
    return 0;
}
'''
    compiler = ROOT / 'tools/.cache/w64devkit/bin/gcc.exe'
    compiler = str(compiler) if compiler.exists() else shutil.which('gcc')
    assert compiler, 'host C compiler required'
    env = os.environ.copy()
    env['PATH'] = str(Path(compiler).parent) + os.pathsep + env.get('PATH', '')
    with tempfile.TemporaryDirectory(prefix='trip-chain-') as tmp:
        c = Path(tmp) / 'chain.c'
        exe = Path(tmp) / 'chain.exe'
        c.write_text(source, encoding='utf-8')
        subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror', '-Imain',
                        str(c), '-o', str(exe)], cwd=ROOT, env=env, check=True)
        subprocess.run([str(exe), str(Path(output).resolve())], env=env, check=True)


class TripSyncChainTest(unittest.TestCase):
    def test_new_archives_retry_ack_and_subsequent_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            packet = Path(tmp) / 'packets.bin'
            build_fixture(packet)
            data = packet.read_bytes()
            self.assertEqual(len(data), 208)
            self.assertEqual(data[0], 4)  # metadata version != record version
            self.assertEqual(struct.unpack_from('<I', data, 24)[0], 1)
            self.assertEqual(struct.unpack_from('<I', data, 80)[0], 2)
            self.assertEqual(struct.unpack_from('<I', data, 156)[0], 3)


if __name__ == '__main__':
    import sys
    if len(sys.argv) == 3 and sys.argv[1] == '--fixture':
        build_fixture(sys.argv[2])
    else:
        unittest.main()
