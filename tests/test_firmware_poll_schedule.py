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
        self.assertIn('poll_pid_group(tick_count == 1', task)
        self.assertIn('s_slow_poll_index = 0;', function(source, 'do_elm_init'))
        compiler = ROOT / 'tools/.cache/w64devkit/bin/gcc.exe'
        compiler = str(compiler) if compiler.exists() else shutil.which('gcc')
        self.assertIsNotNone(compiler)
        harness = r'''
#include <stdio.h>
#include <stdint.h>
#include <stdbool.h>
static unsigned s_slow_poll_index, s_group_failures[2];
static int s_fuel_status_pid_support = 1;
static void elm327_ble_send_ascii_blocking(const char *cmd) {
    for (; *cmd; ++cmd) if (*cmd != '\r') putchar(*cmd);
    putchar('|');
}
''' + function(source, 'poll_pid_group') + r'''
int main(int argc, char **argv) {
    if (argc != 2) return 1;
    for (int pass = 0; pass < 2; ++pass) {
        s_slow_poll_index = 0;
        for (int n = 0; n < (pass ? 6 : 1000); ++n) {
            poll_pid_group(n % 2, argv[1][1] == 'c');
            putchar('\n');
        }
    }
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
