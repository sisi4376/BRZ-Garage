import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class BleRoleIsolationTest(unittest.TestCase):
    def test_local_services_are_ready_before_elm_connect(self):
        app = (ROOT / "main/app_main.c").read_text(encoding="utf-8")
        start = app.index("racechrono_ble_diy_start(user_cfg->rc_enabled)")
        wait = app.index("racechrono_ble_diy_services_ready()", start)
        elm = app.index("elm327_ble_start_default", wait)
        self.assertLess(start, wait)
        self.assertLess(wait, elm)

    def test_bare_gatts_link_does_not_claim_phone_connection(self):
        server = (ROOT / "main/bsp_obd_dsp/racechrono_ble_diy.c").read_text(encoding="utf-8")
        connect_case = server.index("case ESP_GATTS_CONNECT_EVT:")
        disconnect_case = server.index("case ESP_GATTS_DISCONNECT_EVT:", connect_case)
        block = server[connect_case:disconnect_case]
        self.assertNotIn("s_connected = true", block)
        self.assertIn("claim_phone_connection(param->write.conn_id)", server)
        self.assertIn("claim_phone_connection(param->read.conn_id)", server)

    def test_ota_ignores_unrelated_disconnects(self):
        ota = (ROOT / "main/app_obd_dsp/ota_update_ble.c").read_text(encoding="utf-8")
        self.assertIn("if (!s_connected || conn_id != s_conn_id) return;", ota)
        self.assertIn("ota_update_ble_on_disconnect(param->disconnect.conn_id)", ota)


if __name__ == "__main__":
    unittest.main()
