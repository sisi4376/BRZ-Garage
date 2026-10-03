import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android_app/app/src/main"
HARMONY = ROOT / "harmony_ble_bridge/entry/src/main"


class BleWakeAndBondingTests(unittest.TestCase):
    def test_android_establishes_a_system_le_bond_before_rearming_wake_paths(self):
        manager = (
            ANDROID / "java/com/brz/gauge/trips/GaugeBondManager.kt"
        ).read_text(encoding="utf-8")
        activity = (
            ANDROID / "java/com/brz/gauge/trips/MainActivity.kt"
        ).read_text(encoding="utf-8")
        manifest = (ANDROID / "AndroidManifest.xml").read_text(encoding="utf-8")

        self.assertIn("device.createBond(BluetoothDevice.TRANSPORT_LE)", manager)
        self.assertIn("BluetoothDevice.ACTION_BOND_STATE_CHANGED", manager)
        self.assertIn("BluetoothDevice.BOND_BONDED", manager)
        self.assertIn("GaugePresenceObserver.start(context)", manager)
        self.assertIn("BackgroundBleWake.register(context, force = true)", manager)
        self.assertIn("ServiceWatchdogReceiver.schedule(context)", manager)
        self.assertIn("TripSyncService.start(context", manager)
        self.assertIn(".GaugeBondReceiver", manifest)
        self.assertIn("android.bluetooth.device.action.BOND_STATE_CHANGED", manifest)
        self.assertIn("stopService(Intent(this, TripSyncService::class.java))", activity)
        self.assertIn("GaugeBondManager.SetupResult.STARTED", activity)

    def test_gauge_firmware_accepts_secure_bonding_without_touching_elm_link(self):
        firmware = (
            ROOT / "main/bsp_obd_dsp/racechrono_ble_diy.c"
        ).read_text(encoding="utf-8")

        self.assertIn("ESP_LE_AUTH_REQ_SC_BOND", firmware)
        self.assertIn("ESP_IO_CAP_NONE", firmware)
        self.assertIn("ESP_GAP_BLE_SEC_REQ_EVT", firmware)
        self.assertIn("esp_ble_gap_security_rsp", firmware)
        self.assertIn("esp_ble_gatts_app_register(RC_APP_ID)", firmware)
        self.assertIn("configure_phone_bonding()", firmware)

    def test_harmony_partner_agent_runs_an_isolated_one_shot_clock_sync(self):
        manager = (
            HARMONY / "ets/bridge/PartnerWakeManager.ets"
        ).read_text(encoding="utf-8")
        bridge = (
            HARMONY / "ets/bridge/BleTimeBridge.ets"
        ).read_text(encoding="utf-8")
        extension = (
            HARMONY / "ets/entryability/PartnerAgentAbility.ets"
        ).read_text(encoding="utf-8")
        module = (HARMONY / "module.json5").read_text(encoding="utf-8")

        self.assertIn("partnerAgent.bindDevice", manager)
        self.assertNotIn("createGattClientDevice", manager)
        self.assertNotIn("backgroundTaskManager", manager)
        self.assertIn("onDeviceDiscovered", extension)
        self.assertIn("bleTimeBridge.syncWhenDiscovered", extension)
        self.assertNotIn("wantAgent", extension)
        self.assertIn("ANDROID_PRIORITY_GRACE_MS = 8000", bridge)
        self.assertIn("ble.getConnectedBLEDevices()", bridge)
        self.assertIn("0000000f-0000-1000-8000-00805f9b34fb", bridge)
        self.assertIn("writeCharacteristicValue", bridge)
        self.assertNotIn("readCharacteristicValue", bridge)
        self.assertNotIn("startBLEScan", bridge)
        self.assertNotIn("backgroundTaskManager", bridge)
        self.assertIn('"type": "partnerAgent"', module)
        self.assertNotIn("WakeRelayAbility", module)
        self.assertNotIn("bluetoothInteraction", module)
        self.assertNotIn("KEEP_BACKGROUND_RUNNING", module)

    def test_harmony_clock_characteristic_does_not_claim_android_data_link(self):
        firmware = (
            ROOT / "main/bsp_obd_dsp/racechrono_ble_diy.c"
        ).read_text(encoding="utf-8")
        manifest = (ANDROID / "AndroidManifest.xml").read_text(encoding="utf-8")
        claim_start = firmware.index("case ESP_GATTS_WRITE_EVT:")
        claim_end = firmware.index("claim_phone_connection(param->write.conn_id)", claim_start)
        claim_block = firmware[claim_start:claim_end]

        self.assertIn("DEVICE_INFO_CHAR_HARMONY_TIME_SYNC 0x000F", firmware)
        self.assertIn("s_handle_harmony_time_sync", firmware)
        self.assertNotIn("s_handle_harmony_time_sync", claim_block)
        self.assertIn("no stream state, history cursor, settings or ELM327 task is touched", firmware)
        self.assertNotIn("HarmonyWakeRelayActivity", manifest)
        self.assertNotIn('android:scheme="brzgarage"', manifest)


if __name__ == "__main__":
    unittest.main()
