import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class OdometerPidContractTest(unittest.TestCase):
    def test_pid_a6_capability_and_decode_are_wired(self):
        source = (ROOT / "main/bsp_obd_dsp/elm327_ble_client.c").read_text(encoding="utf-8")
        self.assertIn('elm327_ble_send_ascii_blocking("01 A0\\r")', source)
        self.assertIn("d[0] & 0x04u", source)
        self.assertIn('elm327_ble_send_ascii_blocking("01 A6\\r")', source)
        self.assertIn("case 0xA6", source)
        self.assertIn("((uint32_t)d[0] << 24)", source)
        self.assertIn("obd_data_set_factory_odometer_x10_km(raw)", source)

    def test_synced_odometer_is_on_device_info_and_local_ota_is_hidden(self):
        device = (ROOT / "main/export_path/screens/ui_ScreenPageEasterEgg.c").read_text(encoding="utf-8")
        info = (ROOT / "main/export_path/screens/ui_ScreenPageInfo.c").read_text(encoding="utf-8")
        fuel = (ROOT / "main/export_path/screens/ui_ScreenPageFuel.c").read_text(encoding="utf-8")
        ui = (ROOT / "main/export_path/ui.c").read_text(encoding="utf-8")
        self.assertIn("nvs_odometer_get_snapshot", device)
        self.assertIn("data.display_enabled", device)
        self.assertIn('"%lu km"', device)
        self.assertIn("data.odometer_x10_km / 10U", device)
        self.assertNotIn('"ODO %lu', device)
        self.assertNotIn('km  EST', device)
        self.assertNotIn("lv_obj_t *btn_ota = lv_btn_create", device)
        self.assertNotIn('lv_label_set_text(lbl_ota, "OTA Mode")', device)
        self.assertIn("phone's explicit", device)
        self.assertIn("ui_device_odometer_refresh();", ui)
        self.assertNotIn("Odometer", info)
        self.assertNotIn("s_odometer_label", fuel)


if __name__ == "__main__":
    unittest.main()
