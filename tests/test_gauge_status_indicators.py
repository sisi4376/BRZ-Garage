from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class GaugeStatusIndicatorsTest(unittest.TestCase):
    def test_obd_and_time_reuse_no_signal_footprint(self):
        source = (ROOT / "main/export_path/ui_ext.c").read_text(encoding="utf-8")
        self.assertIn('status_indicator_create("OBD", -30)', source)
        self.assertIn('status_indicator_create("TIME", 30)', source)
        self.assertIn("LV_ALIGN_TOP_MID, x, 34", source)
        self.assertNotIn('lv_label_set_text(s_no_signal_lbl, "NO SIGNAL")', source)
        self.assertIn("status_indicator_set(s_obd_status_lbl, active,", source)
        self.assertIn("obd_ok ? 0x45D66F : 0xFF4D4D", source)
        self.assertIn("!time_synchronized ? 0xFF4D4D", source)
        self.assertIn("phone_connected ? 0x45D66F : 0xFFD43B", source)
        self.assertIn("act == ui_ScreenPageTripHistory", source)
        self.assertIn("act == ui_ScreenPageTripOverview", source)
        self.assertIn("act == ui_ScreenPageTripIntervals", source)

    def test_time_indicator_reuses_cached_phone_link_state(self):
        ui = (ROOT / "main/export_path/ui.c").read_text(encoding="utf-8")
        self.assertIn("ui_ext_status_indicators_update(ble_now, racechrono_ble_diy_is_connected(),", ui)
        self.assertIn("nvs_trip_phone_time_is_valid());", ui)
        phone = (ROOT / "main/bsp_obd_dsp/racechrono_ble_diy.c").read_text(encoding="utf-8")
        getter = phone.split("bool racechrono_ble_diy_is_connected(void)", 1)[1].split("}", 1)[0]
        self.assertIn("return s_connected", getter)
        self.assertNotIn("xSemaphore", getter)


if __name__ == "__main__":
    unittest.main()
