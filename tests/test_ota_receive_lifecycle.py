"""Exercise the OTA receive watchdog and shutdown with a simulated HTTP task."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class OtaReceiveLifecycleTest(unittest.TestCase):
    def test_resume_and_shutdown(self):
        source = (ROOT / "main/app_obd_dsp/ota_wifi_server.c").read_text(encoding="utf-8")
        bundled = ROOT / "tools/.cache/w64devkit/bin/gcc.exe"
        compiler = os.environ.get("HOST_CC") or shutil.which("gcc") or str(bundled)
        definitions = []
        for name in ("OTA_PROGRESS_STEP", "OTA_IDLE_TIMEOUT_MS", "OTA_ERROR_REBOOT_MS"):
            definitions.append(re.search(r"^#define " + name + r"\s+.*$", source, re.M)[0])
        for name in ("record_activity", "notify_status", "record_received_bytes",
                     "ota_wifi_server_stop", "ota_timeout_task"):
            start = re.search(r"^(?:static )?(?:void|bool) " + name + r"\(", source, re.M)
            definitions.append(source[start.start():source.index("\n}", start.end()) + 2])
        env = os.environ.copy()
        env["PATH"] = str(Path(compiler).parent) + os.pathsep + env["PATH"]
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            (temp / "ota_functions.inc").write_text("\n".join(definitions), encoding="utf-8")
            exe = temp / "ota-test.exe"
            subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror",
                            "-I" + str(temp), "-I" + str(ROOT / "main/app_obd_dsp"),
                            str(ROOT / "tests/ota_receive_host_test.c"), "-o", str(exe)],
                           env=env, check=True)
            subprocess.run([str(exe)], env=env, check=True)
