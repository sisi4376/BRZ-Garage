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

    def test_app_update_download_has_visible_percentage_progress(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn('"下载进度：${download.progress}%"', source)
        self.assertIn("LinearLayout.LayoutParams(-1, dp(8))", source)
        self.assertIn("progressTintList = android.content.res.ColorStateList.valueOf(accent)", source)
        self.assertIn("handler.postDelayed(appUpdatePoll, 1000L)", source)

    def test_app_update_prefers_github_then_uses_verified_domestic_mirror(self):
        updater = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/AppUpdater.kt").read_text(
            encoding="utf-8"
        )
        activity = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("fetchLatestFromGitHub()", updater)
        self.assertIn("fetchLatestFromMirrorManifest()", updater)
        self.assertLess(updater.index("fetchLatestFromGitHub()"), updater.index("fetchLatestFromMirrorManifest()"))
        self.assertIn('private const val MIRROR_HOST = "ghfast.top"', updater)
        self.assertIn('putString("download_source", "official")', updater)
        self.assertIn('putString("download_source", "mirror")', updater)
        self.assertIn("已自动切换国内镜像", updater)
        self.assertIn('DownloadManager.STATUS_PAUSED -> if (mirrorSource)', updater)
        self.assertIn("安装包 SHA-256 校验失败", updater)
        self.assertIn("TRUSTED_SIGNER_SHA256", updater)
        self.assertIn("优先连接本项目 GitHub 官方 Release", activity)

    def test_trip_records_share_bookkeeping_card_hierarchy(self):
        layout = (ROOT / "android_app/app/src/main/res/layout/trip_row.xml").read_text(
            encoding="utf-8"
        )
        adapter = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripAdapter.kt").read_text(
            encoding="utf-8"
        )
        activity = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        for view_id in ("tripDateBadge", "tripStatus", "tripMetricStrip", "tripDistance",
                        "tripDuration", "tripEconomy", "tripFuel"):
            self.assertIn(f'android:id="@+id/{view_id}"', layout)
        self.assertNotIn("<Button", layout)
        for state in ("已同步", "已修订", "测试数据", "时间未知", "待核实"):
            self.assertIn(state, adapter)
        self.assertIn("tripDetailMetric", activity)
        self.assertIn('primaryBookkeepingAction(if (current)', activity)
        self.assertIn('background = rounded(soften(bookkeepingFuel), 17)', activity)

    def test_vehicle_settings_page_owns_name_model_and_refuel_threshold(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        vehicle_page = source.split("private fun vehicleSettingsPage()", 1)[1].split(
            "private fun showVehicleDisplayNameDialog()", 1
        )[0]
        gauge_page = source.split("private fun gaugeSettingsPage()", 1)[1].split(
            "private fun editableVehicleModel", 1
        )[0]
        self.assertIn('"车型与车辆名称", state.selectedVehicleModel.title', source)
        self.assertIn('page("车辆设置"', vehicle_page)
        self.assertIn("settingsBackPill(body)", vehicle_page)
        self.assertIn("settingsSubpageHero(", vehicle_page)
        self.assertIn('"首页车辆名称"', vehicle_page)
        self.assertIn("editableVehicleModel(model, settings?.vehicleProfile)", vehicle_page)
        self.assertIn("editableRefuelThreshold(refuel, settings?.refuelThresholdMl)", vehicle_page)
        self.assertIn('card(body, "油箱与续航")', vehicle_page)
        self.assertIn('"设置直接在本页完成，不再跳转到经典设置。"', vehicle_page)
        self.assertNotIn('"设置当前剩余油量"', vehicle_page)
        self.assertNotIn('"重新校准当前剩余油量"', vehicle_page)
        self.assertNotIn('"清除 App 油量估算"', vehicle_page)
        self.assertNotIn("editableVehicleModel", gauge_page)
        self.assertNotIn("editableRefuelThreshold", gauge_page)

    def test_since_refuel_explanation_uses_current_threshold(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("state.gaugeSettings()?.refuelThresholdMl", source)
        self.assertIn("两次独立油位样本均确认", source)
        self.assertNotIn("按 50 L 标称油箱折算达到 5 L", source)

    def test_grouped_settings_ui_keeps_an_in_app_classic_fallback(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        icons = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/SettingsIconView.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn('state.prefs.getBoolean("grouped_settings_ui", true)', source)
        self.assertIn('listOf("车辆", "驾驶", "应用")', source)
        self.assertNotIn('listOf("常用", "车辆", "驾驶", "应用")', source)
        self.assertNotIn("private fun renderCommonSettings", source)
        self.assertIn('private fun settingsGrouped()', source)
        self.assertIn('private fun settingsLegacy()', source)
        self.assertIn('putBoolean("grouped_settings_ui", grouped)', source)
        self.assertIn('"已回退到经典设置页"', source)
        self.assertIn('button("切换到新版分组设置页")', source)
        self.assertIn('"切换到经典设置页"', source)
        self.assertNotIn('label("经典版"', source)
        self.assertEqual(source.count('action = { switchSettingsLayout(false) }'), 1)
        self.assertIn("private fun settingsSubpageHero", source)
        self.assertIn("private fun settingsReadOnlyRow", source)
        self.assertIn('page("仪表设置"', source)
        self.assertIn("其余参数以整洁的只读行展示", source)
        self.assertIn("SettingsIconView(this@MainActivity)", source)
        self.assertIn("class SettingsIconView", icons)
        for icon in ("VEHICLE", "MILEAGE", "GAUGE", "FUEL", "TRIP", "BLUETOOTH",
                     "UPDATE", "FIRMWARE", "DISPLAY", "AUTOSTART", "HEALTH", "LEGACY", "PLATE"):
            self.assertIn(icon, icons)


if __name__ == "__main__":
    unittest.main()
