"""Run the production transfer codec/merge/store and DB migrations against real host SQLite.

Test-only jars (not bundled in the APK): org.json:json:20240303 and
org.xerial:sqlite-jdbc:3.46.1.0, in tmp/ or BRZ_TRANSFER_TEST_LIBS.
"""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLCHAIN_ROOT = Path(os.environ.get('BRZ_TOOLCHAIN_ROOT', ROOT))


class DataTransferTest(unittest.TestCase):
    def test_transfer(self):
        java = TOOLCHAIN_ROOT / '.toolchains/jdk17/bin/java.exe'
        maven = TOOLCHAIN_ROOT / '.toolchains/local-maven'
        libs = Path(os.environ.get('BRZ_TRANSFER_TEST_LIBS', TOOLCHAIN_ROOT / 'tmp'))
        runtime = [libs / 'json-20240303.jar', libs / 'sqlite-jdbc-3.46.1.0.jar',
                   TOOLCHAIN_ROOT / '.toolchains/gradle9.5/lib/slf4j-api-2.0.17.jar']
        self.assertTrue(all(p.is_file() for p in runtime), 'Missing test-only JSON/SQLite jars; see module docstring')
        jars = []
        for artifact in ['kotlin-compiler-embeddable/2.2.10', 'kotlin-stdlib/2.2.10',
                         'kotlin-script-runtime/2.2.10', 'kotlin-reflect/1.6.10',
                         'kotlin-daemon-embeddable/2.2.10']:
            jars.extend((maven / 'org/jetbrains/kotlin' / artifact).glob('*.jar'))
        jars.extend((maven / 'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0').glob('*.jar'))
        jars.append(TOOLCHAIN_ROOT / '.toolchains/gradle9.5/lib/annotations-24.0.1.jar')
        cp = os.pathsep.join(str(p) for p in jars + runtime)
        sources = ROOT / 'android_app/app/src/main/java/com/brz/gauge/trips'
        names = ['TransferSchema', 'TransferMerge', 'TransferArchive', 'TransferMigrations', 'TransferStore',
                 'TripDatabase', 'TripRecord', 'FuelDatabase', 'FuelRecord', 'ExpenseDatabase', 'ExpenseRecord', 'MaintenanceSchedule',
                 'CustomTripDatabase', 'CustomTripInterval', 'RefuelIntervalDatabase', 'RefuelInterval',
                 'VehicleState', 'TripBleProtocol', 'SupportedVehicleModel', 'PollHealth', 'AppState',
                 'MileageEstimator', 'RangeEstimator', 'VehicleDisplayName', 'LicensePlateGenerator']
        with tempfile.TemporaryDirectory(prefix='brz-transfer-') as temp:
            result = subprocess.run([str(java), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', temp,
                *[str(sources / (name + '.kt')) for name in names],
                *map(str, (ROOT / 'tests/transfer_host_stubs').glob('*.kt')),
                str(ROOT / 'tests/data_transfer_host_test.kt')], capture_output=True, text=True, encoding='utf-8', errors='replace')
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run([str(java), '-cp', temp + os.pathsep + cp,
                'com.brz.gauge.trips.Data_transfer_host_testKt', temp,
                str(ROOT / 'tmp/transfer-fresh-phone/android-store.zip'),
                os.environ.get('BRZ_HARMONY_STORE_FIXTURE', '')], capture_output=True,
                text=True, encoding='utf-8', errors='replace')
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout)


if __name__ == '__main__':
    unittest.main()
