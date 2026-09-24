import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class RefuelAndHomeFeaturesTest(unittest.TestCase):
    def test_home_and_fuel_ui_contract(self):
        main = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(encoding="utf-8")
        adapter = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripAdapter.kt").read_text(encoding="utf-8")
        fuel = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/FuelRecord.kt").read_text(encoding="utf-8")
        self.assertNotIn("点击查看行程详情", adapter)
        self.assertIn("showCurrentTripDetails", main)
        self.assertIn('"已更新 · ${date(fuelAt)}"', main)
        self.assertIn("未更新 · 上次更新", main)
        self.assertIn("显示上次加油以来", main)
        self.assertIn("显示自定义行程", main)
        self.assertIn('listOf("车辆", "行程", "记账", "设置")', main)
        self.assertIn("floor(currentMileageEstimate().distanceM / 1000.0)", main)
        self.assertIn("暂存，不参与统计", main)
        self.assertIn("records.filter { !it.draft", fuel)

    def test_refuel_detection_lives_on_gauge_and_is_isolated(self):
        storage = (ROOT / "main/bsp_obd_dsp/nvs_storage.c").read_text(encoding="utf-8")
        ble = (ROOT / "main/bsp_obd_dsp/racechrono_ble_diy.c").read_text(encoding="utf-8")
        sync = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripSyncService.kt").read_text(encoding="utf-8")
        self.assertIn("NVS_REFUEL_THRESHOLD_DEFAULT_ML", storage)
        self.assertIn("threshold_x100 = s_cfg.refuel_detect_threshold_ml / 5U", storage)
        self.assertIn("candidate_count >= 2U", storage)
        self.assertIn("KEY_REFUEL_HISTORY", storage)
        self.assertIn("nvs_refuel_manual_reset", storage)
        self.assertIn("DEVICE_INFO_CHAR_REFUEL_META", ble)
        self.assertIn("APP_EVT_PHONE_REFUEL_RESET", ble)
        self.assertIn("APP_EVT_PHONE_REFUEL_DELETE", ble)
        self.assertIn("APP_EVT_PHONE_REFUEL_DISCARD_OLDEST", ble)
        self.assertIn("nvs_refuel_delete_node", storage)
        self.assertIn("nvs_refuel_discard_before_first_node", storage)
        self.assertIn("history_revision", storage)
        self.assertIn("REFUEL_PROTOCOL_VERSION 2U", storage)
        self.assertIn("refuelControlPacket(3, nodeId)", sync)
        self.assertIn("refuelControlPacket(4, requested.toLong())", sync)
        self.assertIn("refuelControlPacket(5, nodeId)", sync)
        self.assertIn("editableRefuelThreshold", (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(encoding="utf-8"))
        self.assertIn("retryRefuelOperation", sync)
        self.assertIn("正常车辆采集继续", sync)

    def test_refuel_revision_can_delete_and_merge_nodes(self):
        main = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(encoding="utf-8")
        database = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/RefuelIntervalDatabase.kt").read_text(encoding="utf-8")
        protocol = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripBleProtocol.kt").read_text(encoding="utf-8")
        self.assertIn("删除此加油节点", main)
        self.assertIn("refuelIntervals.forEachIndexed", main)
        self.assertIn("index == 0 || refuelRevisionMode", main)
        self.assertIn("删除最近加油节点", main)
        self.assertIn("删除并合并", main)
        self.assertIn("mergeAfterDeletedNode", database)
        self.assertIn("historyRevision", protocol)

    def test_refuel_history_can_discard_data_before_first_node(self):
        main = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(encoding="utf-8")
        service = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripSyncService.kt").read_text(encoding="utf-8")
        state = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/AppState.kt").read_text(encoding="utf-8")
        database = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/RefuelIntervalDatabase.kt").read_text(encoding="utf-8")
        storage = (ROOT / "main/bsp_obd_dsp/nvs_storage.c").read_text(encoding="utf-8")
        self.assertIn('button("删除第一个节点以前的数据")', main)
        self.assertIn("discardRefuelBeforeFirstNode", service)
        self.assertIn("pendingRefuelDiscardOldestId", state)
        self.assertIn("discardBeforeFirstNode", database)
        self.assertIn("s_refuel.records[0].id != oldest_id", storage)
        self.assertNotIn("nvs_refuel_delete_node(oldest_id)", storage)

    def test_vehicle_snapshot_marks_only_new_fuel_samples(self):
        cache = (ROOT / "main/app_obd_dsp/obd_data_cache.c").read_text(encoding="utf-8")
        wire = (ROOT / "main/bsp_obd_dsp/racechrono_ble_diy.c").read_text(encoding="utf-8")
        state = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/AppState.kt").read_text(encoding="utf-8")
        self.assertIn("s_fuel_level_sequence", cache)
        self.assertIn("obd_data_get_fuel_level_sequence", wire)
        self.assertIn("sequence != previousSequence", state)
        self.assertIn("last_gauge_fuel_at", state)

    def test_gauge_trip_intervals_page_and_custom_baseline_sync(self):
        screen = (ROOT / "main/export_path/screens/ui_ScreenPageTripIntervals.c").read_text(encoding="utf-8")
        ui = (ROOT / "main/export_path/ui.c").read_text(encoding="utf-8")
        storage = (ROOT / "main/bsp_obd_dsp/nvs_storage.c").read_text(encoding="utf-8")
        ble = (ROOT / "main/bsp_obd_dsp/racechrono_ble_diy.c").read_text(encoding="utf-8")
        protocol = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripBleProtocol.kt").read_text(encoding="utf-8")
        sync = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripSyncService.kt").read_text(encoding="utf-8")
        self.assertIn('"SINCE REFUEL"', screen)
        self.assertIn('"CUSTOM TRIP"', screen)
        self.assertIn("nvs_refuel_get_meta(&refuel)", screen)
        self.assertIn("nvs_custom_trip_get_meta(&custom)", screen)
        self.assertIn("ui_ScreenPageTripIntervals", ui)
        self.assertIn("LV_SCR_LOAD_ANIM_MOVE_TOP", ui)
        self.assertIn("LV_SCR_LOAD_ANIM_MOVE_BOTTOM", ui)
        self.assertIn('"SWIPE DOWN  TRIP OVERVIEW"', screen)
        overview = (ROOT / "main/export_path/screens/ui_ScreenPageTripOverview.c").read_text(encoding="utf-8")
        self.assertIn('"SWIPE UP  TRIP INTERVALS"', overview)
        history_gesture = ui.split("void ui_event_trip_history_background", 1)[1]
        history_gesture = history_gesture.split("// Chart data-source", 1)[0]
        self.assertIn("ui_ScreenPageTripOverview", history_gesture)
        self.assertNotIn("ui_ScreenPageTripIntervals", history_gesture)
        self.assertIn("KEY_CUSTOM_TRIP", storage)
        self.assertIn("DEVICE_INFO_CHAR_CUSTOM_TRIP_CONTROL 0x000E", ble)
        self.assertIn("CUSTOM_TRIP_CONTROL: UUID = uuid16(0x000E)", protocol)
        self.assertIn("syncCustomTripBaselineOrContinue(vehicle)", sync)

    def test_phone_custom_trip_names_and_history(self):
        main = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(encoding="utf-8")
        state = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/AppState.kt").read_text(encoding="utf-8")
        database = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/CustomTripDatabase.kt").read_text(encoding="utf-8")
        model = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/CustomTripInterval.kt").read_text(encoding="utf-8")
        self.assertIn("MAX_CUSTOM_TRIP_NAME_LENGTH = 8", model)
        self.assertIn('if (it.isEmpty()) "自定义行程" else "自定义行程-$it"', model)
        self.assertIn('"上次保养以来", "上次洗车以来", "上次长途以来"', main)
        self.assertIn("archiveAndResetCustomTrip", main)
        self.assertIn("历次自定义行程", main)
        self.assertIn("CustomTripDatabase", main)
        self.assertIn("custom_trip_intervals", database)
        self.assertIn("custom_trip_name", state)


if __name__ == "__main__":
    unittest.main()
