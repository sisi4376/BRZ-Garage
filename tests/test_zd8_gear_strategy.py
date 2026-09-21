import re
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def extract_bits_le(data: bytes, bit_offset: int, bit_length: int) -> int:
    value = 0
    for index in range(bit_length):
        bit = bit_offset + index
        if data[bit // 8] & (1 << (bit % 8)):
            value |= 1 << index
    return value


ZD8_RATIOS = (0.0, 3.626, 2.189, 1.541, 1.213, 1.000, 0.767)
ZD8_FINAL_DRIVE = 4.100
ZD8_RADIUS_M = 0.315


def map_a4_ratio(ratio_x1000: int, tolerance: float = 0.08) -> int | None:
    reported = ratio_x1000 / 1000.0
    candidates = [
        (abs(reported - expected) / expected, gear)
        for gear, expected in enumerate(ZD8_RATIOS[1:], start=1)
    ]
    error, gear = min(candidates)
    return gear if error <= tolerance else None


def infer_from_rpm_speed(rpm: float, speed_kmh: float, tolerance: float = 0.07) -> int | None:
    total_ratio = rpm * 0.377 * ZD8_RADIUS_M / speed_kmh
    candidates = [
        (abs(total_ratio - ratio * ZD8_FINAL_DRIVE) / (ratio * ZD8_FINAL_DRIVE), gear)
        for gear, ratio in enumerate(ZD8_RATIOS[1:], start=1)
    ]
    error, gear = min(candidates)
    return gear if error <= tolerance else None


class Zd8GearStrategyTest(unittest.TestCase):
    def test_can_241_bitfield_decodes_neutral_and_all_forward_gears(self):
        for expected in range(7):
            payload = bytearray(8)
            payload[4] = expected << 3  # bit offset 35 = byte 4, bit 3
            self.assertEqual(extract_bits_le(payload, 35, 3), expected)

    def test_a4_ratio_maps_to_zd8_gears(self):
        for gear, ratio in enumerate(ZD8_RATIOS[1:], start=1):
            self.assertEqual(map_a4_ratio(round(ratio * 1000)), gear)
        self.assertIsNone(map_a4_ratio(2750))

    def test_rpm_speed_fallback_matches_every_zd8_gear(self):
        speed = 60.0
        for gear, ratio in enumerate(ZD8_RATIOS[1:], start=1):
            rpm = speed * ratio * ZD8_FINAL_DRIVE / (0.377 * ZD8_RADIUS_M)
            self.assertEqual(infer_from_rpm_speed(rpm, speed), gear)

    def test_firmware_enables_direct_signal_and_fallback_contract(self):
        config = (ROOT / "main/app_obd_dsp/vehicle_custom_config.h").read_text(encoding="utf-8")
        profiles = (ROOT / "main/app_obd_dsp/vehicle_profiles.c").read_text(encoding="utf-8")
        cache = (ROOT / "main/app_obd_dsp/obd_data_cache.c").read_text(encoding="utf-8")
        ui = (ROOT / "main/export_path/ui.c").read_text(encoding="utf-8")

        self.assertNotIn("can_rules_zd8", config)
        self.assertRegex(profiles, r'\.name\s*=\s*"ZD8"[\s\S]*?\.obd_standard_gear_pid\s*=\s*true')
        self.assertRegex(profiles, r'\.name\s*=\s*"ZD8"[\s\S]*?\.can_broadcast_mode\s*=\s*false')
        self.assertIn("DIRECT_GEAR_STALE_MS 2500", cache)
        self.assertNotIn("eGear = calculate_gear(usRpm, ucSpeed);", ui)
        constant_body = re.search(
            r"float vehicle_profile_calc_constant\([^)]*\)\s*\{([\s\S]*?)\n\}", profiles
        ).group(1)
        self.assertNotIn("final_drive_ratio * 0.377f", constant_body)

    def test_zd8_is_default_and_uses_user_tire_size(self):
        header = (ROOT / "main/app_obd_dsp/vehicle_profiles.h").read_text(encoding="utf-8")
        profiles = (ROOT / "main/app_obd_dsp/vehicle_profiles.c").read_text(encoding="utf-8")
        storage = (ROOT / "main/bsp_obd_dsp/nvs_storage.c").read_text(encoding="utf-8")

        self.assertIn("#define VEHICLE_PROFILE_DEFAULT_INDEX VEHICLE_PROFILE_ZD8_INDEX", header)
        self.assertRegex(
            profiles,
            r'\.name\s*=\s*"ZD8"[\s\S]*?\.tire_rolling_radius_m\s*=\s*0\.315f',
        )
        self.assertIn(".vehicle_profile_idx = VEHICLE_PROFILE_DEFAULT_INDEX", storage)
        self.assertIn("stored_ver < 9 && s_cfg.vehicle_profile_idx == 0", storage)

    def test_production_cache_with_serial_response_delays(self):
        bundled = ROOT / "tools/.cache/w64devkit/bin/gcc.exe"
        compiler = str(bundled) if bundled.exists() else shutil.which("gcc")
        if not compiler:
            self.skipTest("A host C compiler is required")
        env = os.environ.copy()
        env["PATH"] = str(Path(compiler).parent) + os.pathsep + env.get("PATH", "")
        with tempfile.TemporaryDirectory(prefix="gear-host-") as tmp:
            executable = str(Path(tmp) / "gear_cache_test.exe")
            subprocess.run([
                compiler, "-std=c11", "-Wall", "-Wextra",
                "-Itests/host_stubs", "-Imain", "main/app_obd_dsp/obd_data_cache.c",
                "tests/gear_cache_host_test.c", "-o", executable,
            ], cwd=ROOT, env=env, check=True, capture_output=True, text=True)
            result = subprocess.run([executable], env=env, check=True, capture_output=True, text=True)
            self.assertEqual(result.stdout.count("PASS:"), 6)


if __name__ == "__main__":
    unittest.main()
