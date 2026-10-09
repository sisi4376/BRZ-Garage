"""Run the production telemetry queue and aggregator, including concurrent producers."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PerfMonitorTest(unittest.TestCase):
    def test_production_core(self):
        bundled = ROOT / "tools/.cache/w64devkit/bin/gcc.exe"
        compiler = str(bundled) if bundled.exists() else shutil.which("gcc")
        if not compiler:
            self.skipTest("Host C compiler required")
        env = os.environ.copy()
        env["PATH"] = str(Path(compiler).parent) + os.pathsep + env.get("PATH", "")
        with tempfile.TemporaryDirectory(prefix="perf-host-") as tmp:
            executable = str(Path(tmp) / "perf-test.exe")
            subprocess.run([compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                            "-Imain", "main/app_obd_dsp/perf_monitor_core.c",
                            "tests/perf_monitor_host_test.c", "-o", executable],
                           cwd=ROOT, env=env, check=True, capture_output=True, text=True)
            result = subprocess.run([executable], env=env, check=True, capture_output=True,
                                    text=True, timeout=15)
            self.assertIn("PASS:", result.stdout)


if __name__ == "__main__":
    unittest.main()
