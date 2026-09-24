"""Compile and execute the production Kotlin models without an Android emulator."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class PhoneModelTest(unittest.TestCase):
    def test_production_kotlin_models(self):
        java = ROOT / '.toolchains/jdk17/bin/java.exe'
        maven = ROOT / '.toolchains/local-maven'
        artifacts = [
            'org/jetbrains/kotlin/kotlin-compiler-embeddable/2.2.10',
            'org/jetbrains/kotlin/kotlin-stdlib/2.2.10',
            'org/jetbrains/kotlin/kotlin-script-runtime/2.2.10',
            'org/jetbrains/kotlin/kotlin-reflect/1.6.10',
            'org/jetbrains/kotlin/kotlin-daemon-embeddable/2.2.10',
            'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0',
            'org/jetbrains/annotations',
        ]
        jars = [str(p) for artifact in artifacts for p in (maven / artifact).rglob('*.jar')]
        jars += [str(ROOT / '.toolchains/gradle9.5/lib/annotations-24.0.1.jar')]
        if not java.exists() or not jars:
            self.skipTest('Bundled Kotlin compiler and JDK required')
        classpath = os.pathsep.join(jars)
        sources = ROOT / 'android_app/app/src/main/java/com/brz/gauge/trips'
        with tempfile.TemporaryDirectory(prefix='phone-model-') as temp:
            compile_result = subprocess.run([
                str(java), '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', temp,
                *[str(sources / name) for name in ['VehicleState.kt', 'TripBleProtocol.kt', 'TripRecord.kt', 'RefuelInterval.kt', 'CustomTripInterval.kt', 'FuelRecord.kt', 'RangeEstimator.kt', 'MileageEstimator.kt', 'VehicleDisplayName.kt', 'SupportedVehicleModel.kt', 'LicensePlateGenerator.kt']],
                str(ROOT / 'tests/phone_model_host_test.kt'),
            ], capture_output=True, text=True, encoding='utf-8', errors='replace')
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
            result = subprocess.run([str(java), '-cp', temp + os.pathsep + classpath,
                                     'com.brz.gauge.trips.Phone_model_host_testKt'],
                                    capture_output=True, text=True, encoding='utf-8', errors='replace')
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(result.stdout.count('PASS:'), 24)


if __name__ == '__main__':
    unittest.main()
