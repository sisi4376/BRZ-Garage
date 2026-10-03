import importlib.util
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('package_windows_flasher', ROOT / 'tools/package_windows_flasher.py')
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)

class WindowsFlasherTests(unittest.TestCase):
    @unittest.skipUnless(os.name == 'nt' and shutil.which('powershell.exe'), 'Requires Windows PowerShell')
    def test_live_progress_and_stderr_without_device(self):
        with tempfile.TemporaryDirectory(prefix='brz-progress-test-') as scratch:
            result = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                                     '-File', str(ROOT / 'tests/flasher_progress_host_test.ps1'), '-Scratch', scratch],
                                    capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(result.stdout.count('PASS:'), 3, result.stdout)

    @unittest.skipUnless(os.name == 'nt' and shutil.which('powershell.exe'), 'Requires Windows PowerShell')
    def test_usb_port_classification_without_opening_devices(self):
        result = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                                 '-File', str(ROOT / 'tests/serial_ports_host_test.ps1')],
                                capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.count('PASS:'), 6, result.stdout)

    def table(self, entries=None):
        import hashlib
        raw = b''.join(struct.pack('<HBBII16sI', 0x50AA, kind, subtype, offset, size,
                                  name.encode(), 0) for kind, subtype, offset, size, name in (entries or package.PARTITIONS))
        raw += b'\xeb\xeb' + b'\xff' * 14 + hashlib.md5(raw).digest()
        return raw.ljust(0xC00, b'\xff')

    def test_supported_partition_table(self):
        package.validate_partition_table(self.table())

    def test_rejects_changed_nvs_and_partition_checksum(self):
        changed = list(package.PARTITIONS)
        changed[0] = (1, 2, 0x9000, 0x5000, 'nvs')
        for payload in (self.table(changed), b'\0' + self.table()[1:], self.table()[:80]):
            with self.assertRaises(ValueError):
                package.validate_partition_table(payload)

    @unittest.skipUnless(os.name == 'nt' and shutil.which('powershell.exe'), 'Requires Windows PowerShell')
    def test_flash_sequences_with_fake_tool_and_no_serial_device(self):
        with tempfile.TemporaryDirectory(prefix='brz-flasher-test-') as scratch:
            result = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                                     '-File', str(ROOT / 'tests/flash_core_host_test.ps1'), '-Scratch', scratch],
                                    capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=120)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(result.stdout.count('PASS:'), 13, result.stdout)

if __name__ == '__main__':
    unittest.main()
