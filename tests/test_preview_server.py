import importlib.util
import math
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "tools" / "preview_server.py"
SPEC = importlib.util.spec_from_file_location("preview_server", MODULE_PATH)
preview_server = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = preview_server
SPEC.loader.exec_module(preview_server)


class ElmParserTests(unittest.TestCase):
    def test_parses_spaced_mode01_response(self):
        data = preview_server.Elm327Tcp.payload("41 0C 1A F8\r", 0x01, 0x0C)
        self.assertEqual(data[:2], [0x1A, 0xF8])
        self.assertEqual(((data[0] << 8) | data[1]) / 4, 1726)

    def test_parses_headered_response(self):
        data = preview_server.Elm327Tcp.payload("7E8 04 41 42 36 B0\r", 0x01, 0x42)
        self.assertEqual(data[:2], [0x36, 0xB0])
        self.assertAlmostEqual(((data[0] << 8) | data[1]) / 1000, 14.0)

    def test_missing_pid_raises(self):
        with self.assertRaises(ValueError):
            preview_server.Elm327Tcp.payload("NO DATA\r", 0x01, 0x5C)

    def test_parses_maf_for_fuel_consumption(self):
        data = preview_server.Elm327Tcp.payload("41 10 07 08\r", 0x01, 0x10)
        self.assertEqual((data[0] << 8) | data[1], 1800)  # 18.00 g/s


class ModelTests(unittest.TestCase):
    def test_demo_values_are_finite_and_bounded(self):
        for scenario in ("road", "track", "idle"):
            data = preview_server.demo_data(scenario, 12.5)
            self.assertTrue(math.isfinite(data.rpm))
            self.assertGreaterEqual(data.throttle, 0)
            self.assertLessEqual(data.throttle, 100)
            self.assertGreaterEqual(data.load, 0)
            self.assertLessEqual(data.load, 100)
            self.assertIn(data.gear, range(0, 7))

    def test_zd8_gear_estimation(self):
        # Approximate 6MT third gear at 60 km/h.
        radius = 0.318
        wheel_rpm = 60 * 1000 / 60 / (2 * math.pi * radius)
        rpm = wheel_rpm * 4.1 * 1.541
        self.assertEqual(preview_server.infer_gear(rpm, 60), 3)


if __name__ == "__main__":
    unittest.main()
