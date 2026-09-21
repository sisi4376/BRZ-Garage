import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class AndroidHomeDisplayTests(unittest.TestCase):
    def test_last_valid_gauge_fuel_survives_missing_snapshot_value(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/AppState.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn(
            "val rememberedFuel = receivedFuel ?: previousFuel ?: lastGaugeFuelPercent(deviceId)",
            source,
        )
        self.assertIn("lastGaugeFuelPercent()?.let { return it }", source)

    def test_home_mileage_is_compact_and_only_marks_uncalibrated(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MileageEstimator.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn('return "${kilometres}km"', source)
        self.assertIn('if (estimate.calibrated) "" else " · 未校准"', source)

    def test_page_rebuild_restores_scroll_position(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("private val pageScrollPositions = mutableMapOf<Int, Int>()", source)
        self.assertIn("pageScrollPositions[pageKey] = it.scrollY", source)
        self.assertIn("scroll.viewTreeObserver.addOnGlobalLayoutListener", source)
        self.assertIn("scroll.scrollTo(0, restoreY.coerceAtMost(maxScroll))", source)
        self.assertIn("pendingScrollRestoreKey != pageKey", source)
        self.assertIn("rememberCurrentScroll()\n        showingGaugeSettings = false", source)

    def test_trip_detail_page_exposes_driving_extremes(self):
        activity = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        adapter = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripAdapter.kt").read_text(
            encoding="utf-8"
        )
        database = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripDatabase.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("{ showTripDetails(it) }", activity)
        for label in ("平均时速", "最高速度", "最高转速", "最大加速", "最大减速"):
            self.assertIn(label, activity)
        self.assertIn("view.setOnClickListener { onOpenDetails(trip) }", adapter)
        self.assertIn('SQLiteOpenHelper(context, "brz_trip_history.db", null, 5)', database)
        self.assertIn("ALTER TABLE trips ADD COLUMN max_speed_kmh", database)

    def test_mileage_visibility_sync_and_local_trip_revision_are_wired(self):
        activity = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        state = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/AppState.kt").read_text(
            encoding="utf-8"
        )
        service = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripSyncService.kt").read_text(
            encoding="utf-8"
        )
        database = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripDatabase.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("state.odometerDisplayEnabled", activity)
        self.assertIn("TripSyncService.setOdometerDisplay", activity)
        self.assertIn("TripSyncService.calibrateGaugeOdometer", activity)
        self.assertIn('button("修订测试数据")', activity)
        self.assertIn("fun requestOdometerDisplay", state)
        self.assertIn("fun requestGaugeOdometerCalibration", state)
        self.assertIn("ODOMETER_CONFIG", service)
        self.assertIn("ALTER TABLE trips ADD COLUMN data_revised", database)
        self.assertIn("fun reviseData(record: TripRecord)", database)
        self.assertIn("if (cursor.getInt(12) != 0)", database)
        self.assertIn("fun reviseTime(deviceId: String, tripId: Long, startEpochS: Long, endEpochS: Long)", database)
        self.assertIn("endEpochS = cursor.getLong(1)", database)
        self.assertNotIn("put(\"end_epoch_s\", record.startEpochS + record.durationS)", database)
        self.assertIn('addDateTimeRows("开始", startSelection)', activity)
        self.assertIn('addDateTimeRows("结束", endSelection)', activity)
        self.assertIn("database.reviseTime(trip.deviceId, trip.tripId, start, end)", activity)

    def test_trip_data_revision_accepts_legacy_missing_fields(self):
        activity = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        database = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripDatabase.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("平均油耗（L/100km，留空则自动计算）", activity)
        self.assertIn("旧记录可留空", activity)
        self.assertIn("val average = enteredAverage ?: calculatedAverage", activity)
        self.assertIn("speed == null || speed in 0..500", activity)
        self.assertIn('saveButton = button("保存")', activity)
        self.assertIn("content.addView(scroller, LinearLayout.LayoutParams", activity)
        self.assertIn("record.maxSpeedKmh != null && record.maxSpeedKmh !in 0..500", database)
        self.assertIn('putNull("max_speed_kmh")', database)


if __name__ == "__main__":
    unittest.main()
