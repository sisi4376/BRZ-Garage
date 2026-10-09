"""Execute production C with synthetic ELM replies (not vehicle captures)."""
import ctypes
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def function(source, name):
    match = re.search(r'^(?:static\s+)?(?:inline\s+)?[\w *]+\s+' + name +
                      r'\([^;]*?\)\s*\{', source, re.M)
    assert match, name
    end = source.index('\n}', match.end()) + 2
    return source[match.start():end]


def packet(count=38, raw=140, boundary=0x55, compact=False):
    data = [boundary] * (count + 2)
    data[:2] = [0x61, 0x01]
    data[-5] = raw
    sep = '' if compact else ' '
    rows = [f'{len(data):03X}', '0: ' + sep.join(f'{x:02X}' for x in data[:6])]
    for pos in range(6, len(data), 7):
        rows.append(f'{1 + (pos-6)//7:X}: ' + sep.join(f'{x:02X}' for x in data[pos:pos+7]))
    return '\r'.join(rows) + '\r>'


class Zc6PidTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        bundled = ROOT / 'tools/.cache/w64devkit/bin/gcc.exe'
        cls.compiler = str(bundled) if bundled.exists() else shutil.which('gcc')
        if not cls.compiler:
            raise unittest.SkipTest('A host C compiler is required')
        cls.env = os.environ.copy()
        cls.env['PATH'] = str(Path(cls.compiler).parent) + os.pathsep + cls.env.get('PATH', '')
        cls.tmp = tempfile.TemporaryDirectory(prefix='zc6-host-')
        cls.addClassCleanup(cls.tmp.cleanup)
        dll = Path(cls.tmp.name) / 'zc6.dll'
        cls.compile(['-shared', 'main/app_obd_dsp/zc6_pid.c', '-o', str(dll)])
        cls.lib = ctypes.CDLL(str(dll))
        cls.addClassCleanup(cls.unload_library)
        cls.parse = cls.lib.zc6_pid_parse_oil_temp
        cls.parse.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_int16)]
        cls.parse.restype = ctypes.c_bool

    @classmethod
    def unload_library(cls):
        import _ctypes
        handle = cls.lib._handle
        del cls.parse, cls.lib
        if os.name == 'nt':
            _ctypes.FreeLibrary(handle)
        else:
            _ctypes.dlclose(handle)

    @classmethod
    def compile(cls, args):
        result = subprocess.run([cls.compiler, '-std=c11', '-Wall', '-Wextra',
                                 '-Itests/host_stubs', '-Imain', *args], cwd=ROOT,
                                env=cls.env, capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    def check(self, response, expected):
        output = ctypes.c_int16(-999)
        ok = self.parse(response.encode('ascii'), ctypes.byref(output))
        self.assertEqual(ok, expected is not None, response)
        self.assertEqual(output.value, -999 if expected is None else expected, response)

    def test_both_upstream_layouts_all_raw_values_and_frame_boundary_bytes(self):
        for count in (38, 39):
            for raw in range(256):
                self.check(packet(count, raw), raw - 40)
            # ELM has already removed PCI: these are payload bytes, not sequence bytes.
            for boundary in range(0x20, 0x30):
                self.check(packet(count, boundary=boundary), 100)

    def test_whitespace_echo_compact_and_assembled_formats(self):
        for count in (38, 39):
            self.check('2101\r\n' + packet(count).replace('\r', '\r\n'), 100)
            self.check('21 01\r' + packet(count, compact=True).lower() + '\r\n', 100)
            flat = re.sub(r'\r[0-9A-F]: ?', '', packet(count).split('\r', 1)[1])
            flat = flat.removeprefix('0: ')
            self.check(flat, 100)
            self.check(flat.replace(' ', ''), 100)

    def test_every_incomplete_prefix_is_rejected(self):
        for count in (38, 39):
            full = packet(count)
            for end in range(len(full) - 2):
                self.check(full[:end] + '>', None)
            self.check(full[:-1], None)
        for count in (5, 33, 37, 40):
            self.check(packet(count), None)

    def test_wrong_length_order_pid_and_error_responses(self):
        full = packet()
        variants = [
            full.replace('028', '029'), full.replace('028', '027'),
            full.replace('2:', '3:'), full.replace('2:', '1:'),
            full.replace('61 01', '61 02'), full.replace('61 01', '41 01'),
            full.replace('55 55', '5 55', 1), full.replace('55 55', 'XX 55', 1),
            full.replace('\r1:', '\r1: 21'), full + 'NO DATA',
            full + full, full.replace('\r>', '\rNO DATA\r>'),
            full.split('\r', 1)[1], '7E8 10 28 ' + full,
            'NO DATA\r>', '7F 21 12\r>', 'STOPPED\r>', 'SEARCHING...\r>', '',
        ]
        for response in variants:
            self.check(response, None)
        self.assertFalse(self.parse(None, ctypes.pointer(ctypes.c_int16())))
        self.assertFalse(self.parse(full.encode(), None))

    def test_transport_callbacks_cache_and_profile_switch(self):
        elm = (ROOT / 'main/bsp_obd_dsp/elm327_ble_client.c').read_text(encoding='utf-8')
        profiles = (ROOT / 'main/app_obd_dsp/vehicle_profiles.c').read_text(encoding='utf-8')
        table = profiles[profiles.index('static const vehicle_profile_t s_profiles[]'):]
        table = table[:table.index('\n};') + 3]
        globals_start = elm.index('static const vehicle_override_t *s_ov =')
        globals_end = elm.index('// Oil-temp diagnostic stats', globals_start)
        # Execute the actual receive accumulation/gating and Mode21 dispatch blocks.
        start = elm.index('        // ---- Accumulate multi-packet data')
        end = elm.index('        do {', start)
        accumulate = elm[start:end].replace('break;', 'return;')
        start = elm.index('        if (s_expect_mode21) {', end)
        end = elm.index(' else if (p41', start)
        dispatch = elm[start:end]
        functions = '\n'.join(function(elm, name) for name in (
            'can_rules_have_channel', 'init_oil_temp_strategy',
            'obd_data_set_oil_temp_with_offset', 'default_on_parsed_oil_temp',
            'default_on_raw_notify', 'finish_elm_response',
            'elm327_ble_ascii_cmd_to_bytes', 'elm327_ble_send_ascii_blocking'))
        harness = (ROOT / 'tests/zc6_pid_host_test.c').read_text(encoding='utf-8')
        harness = '#include "app_obd_dsp/obd_poll_health.h"\n' + harness
        harness = harness.replace('/* PROFILE_TABLE */', table)
        harness = harness.replace('/* PROFILE_SETTER */', '\n'.join(function(profiles, name) for name in (
            'vehicle_profile_is_selectable', 'vehicle_profile_normalize_index', 'vehicle_profile_set_active')))
        harness = harness.replace('/* ELM_GLOBALS */', elm[globals_start:globals_end])
        harness = harness.replace('/* ELM_FUNCTIONS */', functions)
        harness = harness.replace('/* ELM_ACCUMULATE */', accumulate)
        harness = harness.replace('/* ELM_DISPATCH */', dispatch)
        espnow = (ROOT / 'main/bsp_obd_dsp/espnow_link.c').read_text(encoding='utf-8')
        start = espnow.index('typedef struct', espnow.index('// Broadcast packet:'))
        end = espnow.index('} espnow_obd_packet_t;', start) + len('} espnow_obd_packet_t;')
        harness = harness.replace('/* ESPNOW_PACKET */', espnow[start:end])
        harness = harness.replace('/* ESPNOW_APPLY */', function(espnow, 'apply_packet'))
        harness = harness.replace('/* ESPNOW_MASTER */', function(espnow, 'master_pack'))
        source = Path(self.tmp.name) / 'integration.c'
        source.write_text(harness, encoding='utf-8')
        exe = Path(self.tmp.name) / 'integration.exe'
        self.compile([str(source), 'main/app_obd_dsp/zc6_pid.c',
                      'main/app_obd_dsp/obd_poll_health.c',
                      'main/app_obd_dsp/obd_data_cache.c', '-o', str(exe)])
        result = subprocess.run([str(exe)], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS: ZC6 transport, switching, callback and cache', result.stdout)


if __name__ == '__main__':
    unittest.main()
