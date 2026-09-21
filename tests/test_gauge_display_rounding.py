import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def round_unsigned(value: int, divisor: int) -> int:
    return value // divisor + ((value % divisor) >= ((divisor + 1) // 2))


class GaugeDisplayRoundingTest(unittest.TestCase):
    def test_half_up_boundaries(self):
        self.assertEqual(round_unsigned(47_249, 100), 472)  # 47.249 km -> 47.2
        self.assertEqual(round_unsigned(47_250, 100), 473)  # 47.250 km -> 47.3
        self.assertEqual(round_unsigned(804, 10), 80)       # 8.04 -> 8.0
        self.assertEqual(round_unsigned(805, 10), 81)       # 8.05 -> 8.1
        self.assertEqual(round_unsigned(29, 60), 0)
        self.assertEqual(round_unsigned(30, 60), 1)

    def test_all_precision_reductions_use_shared_rounding(self):
        overview = (ROOT / "main/export_path/screens/ui_ScreenPageTripOverview.c").read_text(encoding="utf-8")
        intervals = (ROOT / "main/export_path/screens/ui_ScreenPageTripIntervals.c").read_text(encoding="utf-8")
        history = (ROOT / "main/export_path/screens/ui_ScreenPageTripHistory.c").read_text(encoding="utf-8")
        fuel = (ROOT / "main/export_path/screens/ui_ScreenPageFuel.c").read_text(encoding="utf-8")
        items = (ROOT / "main/export_path/ui_disp_item.c").read_text(encoding="utf-8")

        self.assertIn("ui_round_div_u64(metres, 100ULL)", overview)
        self.assertIn("ui_round_div_u64(seconds, 60ULL)", overview)
        self.assertIn("ui_round_div_u64(data.lifetime_fuel_ml, 100ULL)", overview)
        self.assertIn("ui_round_div_u64(metres, 100ULL)", intervals)
        self.assertIn("ui_round_div_u64(seconds, 60ULL)", intervals)
        self.assertIn("ui_round_div_u64(millilitres, 100ULL)", intervals)
        self.assertIn("ui_round_div_u64(trip->distance_m, 100U)", history)
        self.assertIn("ui_round_div_u64(trip->avg_l100_x100, 10U)", history)
        self.assertIn("ui_round_div_u64(value, 10U)", fuel)
        self.assertIn("ui_round_div_i32(value, 100)", items)
        self.assertIn("ui_round_div_i32(value, 10)", items)

    def test_total_odometer_is_integer_and_deliberately_not_rounded(self):
        device = (ROOT / "main/export_path/screens/ui_ScreenPageEasterEgg.c").read_text(encoding="utf-8")
        self.assertIn('"%lu km"', device)
        self.assertIn("data.odometer_x10_km / 10U", device)
        self.assertNotIn("ui_round_div", device)


if __name__ == "__main__":
    unittest.main()
