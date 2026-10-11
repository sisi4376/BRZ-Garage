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

    def test_refined_home_keeps_classic_in_app_fallback(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn('state.prefs.getBoolean("refined_home_ui", true)', source)
        self.assertIn('if (usesRefinedHome()) homeRefined() else homeClassic()', source)
        self.assertIn('private fun homeRefined()', source)
        self.assertIn('private fun homeClassic()', source)
        self.assertIn('"新版首页界面", "关闭后恢复 3.9.0 经典首页布局"', source)
        self.assertIn('toast(if (enabled) "已启用新版首页" else "已回退到经典首页")', source)
        self.assertIn('homeStatsCard(body, "本次行程 · 仪表统计"', source)
        self.assertIn('homeStatsCard(body, "累计驾驶 · 自仪表开始记录"', source)
        self.assertIn('progressTintList = android.content.res.ColorStateList.valueOf(bookkeepingFuel)', source)
        self.assertIn('LinearLayout.LayoutParams(-1, dp(228))', source)
        self.assertIn("setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1", source)
        self.assertIn("setAutoSizeTextTypeUniformWithConfiguration(8, 9, 1", source)
        self.assertIn("Gravity.CENTER_HORIZONTAL", source)
        self.assertIn("TextUtils.TruncateAt.END", source)
        refined = source.split("private fun homeRefined()", 1)[1].split(
            "private fun homeClassic()", 1
        )[0]
        self.assertNotIn("odometerRow", refined)
        self.assertNotIn("SettingsIconView.Icon.MILEAGE", refined)
        self.assertIn("add(body, odometerText, 9)", refined)

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
        self.assertIn('if (oldVersion < 5)', database)
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
        self.assertIn('homeDetailAction("修订测试数据", bookkeepingMaintenance)', activity)
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

    def test_grouped_app_update_opens_full_page_and_preserves_navigation(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        grouped = source.split("private fun renderAppSettingsOverview(", 1)[1].split(
            "private fun requestGroupedFirmwareCheck", 1)[0]
        self.assertIn("action = { appUpdatePage() }", grouped)
        self.assertNotIn("action = { checkAppUpdate() }", grouped)
        page = source.split("private fun appUpdatePage()", 1)[1].split(
            "private fun firmwareUpdatePage()", 1)[0]
        for expected in ('page("App 更新"', "appUpdateCard(body)",
                         "settingsBackPill(body) { showTab(3) }",
                         "resumeAppUpdateDownloadPolling()"):
            self.assertIn(expected, page)
        self.assertIn('getBoolean("app_update_page")', source)
        self.assertIn('putBoolean("app_update_page", showingAppUpdate)', source)
        self.assertIn("showingAppUpdate -> showTab(3)", source)
        self.assertIn("showingAppUpdate -> 13", source)
        self.assertIn("else if (updated && showingAppUpdate) Unit", source)
        refresh = source.split("private fun refreshAppUpdateUi()", 1)[1].split(
            "private fun checkAppUpdate()", 1)[0]
        self.assertIn("showingAppUpdate -> appUpdatePage()", refresh)
        self.assertIn("isSettingsOverviewVisible() -> showTab(3)", refresh)
        callbacks = source.split("private fun checkAppUpdate()", 1)[1].split(
            "private fun requestInstallDownloadedUpdate()", 1)[0]
        self.assertNotIn("showTab(3)", callbacks)
        self.assertEqual(callbacks.count("refreshAppUpdateUi()"), 4)
        self.assertIn("(showingAppUpdate || isSettingsOverviewVisible())", callbacks)
        self.assertIn('.setNegativeButton("稍后", null)', callbacks)

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
        self.assertIn('homeDetailBackPill(body, if (current)', activity)
        self.assertIn('if (current) "本次行程 · 实时快照" else "行程概览"', activity)

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

    def test_home_secondary_pages_share_refined_cards_and_actions(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("private fun homeDetailBackAction", source)
        self.assertIn("private fun homeDetailBackPill", source)
        self.assertIn("private fun homeDetailAction", source)
        self.assertIn('if (current) "本次行程 · 实时快照" else "行程概览"', source)
        self.assertIn('homeStatsCard(body, "当前 · $currentTitle", bookkeepingDaily)', source)
        self.assertIn('homeStatsCard(body, "当前 · 上次重置以来", bookkeepingMaintenance)', source)
        self.assertIn('homeStatsCard(body, customTripTitle(interval.name), bookkeepingDaily)', source)
        self.assertIn('homeStatsCard(body, reason, bookkeepingMaintenance)', source)
        self.assertIn('homeDetailAction("手动拆分这条行程", bookkeepingFuel)', source)
        self.assertIn('homeDetailAction("手动重置", bookkeepingMaintenance, filled = true)', source)
        trip_page = source.split("private fun showTripDetails", 1)[1].split(
            "private fun tripDetailMetric", 1
        )[0]
        custom_page = source.split("private fun showCustomTripDetails", 1)[1].split(
            "private fun showCustomTripNameDialog", 1
        )[0]
        refuel_page = source.split("private fun showRefuelDetails", 1)[1].split(
            "private fun confirmDeleteRefuelNode", 1
        )[0]
        for page in (trip_page, custom_page, refuel_page):
            self.assertIn("settingsSubpageHero(", page)
        self.assertIn("SettingsIconView.Icon.TRIP", trip_page)
        self.assertIn("SettingsIconView.Icon.FUEL", refuel_page)

    def test_trip_editors_do_not_fall_back_to_default_system_buttons(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        split_editor = source.split("private fun splitTripRecord", 1)[1].split(
            "private fun reviseTripTime", 1
        )[0]
        time_editor = source.split("private fun reviseTripTime", 1)[1].split(
            "private fun confirmDeleteTrip", 1
        )[0]
        for editor in (split_editor, time_editor):
            self.assertNotIn("Button(this).apply", editor)
            self.assertIn("val dateButton = button(", editor)
            self.assertIn("val timeButton = button(", editor)

    def test_dialogs_and_secondary_headers_share_refined_ui(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        styles = (ROOT / "android_app/app/src/main/res/values/styles.xml").read_text(
            encoding="utf-8"
        )
        surface = (ROOT / "android_app/app/src/main/res/drawable/dialog_surface.xml").read_text(
            encoding="utf-8"
        )
        plate = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/LicensePlateGeneratorActivity.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn('android:alertDialogTheme">@style/BrzAlertDialogTheme', styles)
        self.assertIn('android:datePickerDialogTheme">@style/BrzAlertDialogTheme', styles)
        self.assertIn('android:windowBackground">@drawable/dialog_surface', styles)
        self.assertIn('android:radius="24dp"', surface)
        self.assertIn("private fun EditText.refineDialogField()", source)
        self.assertGreaterEqual(source.count(".refineDialogField()"), 13)
        self.assertIn("body.addView(back, 0", source)
        self.assertIn("body.addView(homeDetailBackAction(text, color, action), 0", source)
        self.assertIn('label("‹  返回车辆设置"', plate)

    def test_first_run_notice_precedes_permissions_and_runtime_work(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        state = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/AppState.kt").read_text(
            encoding="utf-8"
        )
        notice = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/UserNotice.kt").read_text(
            encoding="utf-8"
        )
        on_create = source.split("override fun onCreate", 1)[1].split(
            "private fun initializeApp", 1
        )[0]
        self.assertIn("if (!state.hasAcceptedCurrentUserNotice)", on_create)
        self.assertLess(on_create.index("userNoticePage(firstRun = true)"), on_create.index("initializeApp(savedInstanceState)"))
        self.assertIn('primaryBookkeepingAction("同意并继续"', source)
        self.assertGreaterEqual(source.count("CheckBox(this)"), 2)
        self.assertIn("accept.isEnabled = safetyConsent.isChecked && copyrightConsent.isChecked", source)
        self.assertIn("isEnabled = false", source)
        self.assertIn("state.acceptCurrentUserNotice()", source)
        self.assertIn("state.withdrawUserNotice()", source)
        self.assertIn("BackgroundBleWake.cancel(this)", source)
        self.assertIn("GaugePresenceObserver.stop(this)", source)
        self.assertIn("ServiceWatchdogReceiver.cancel(this)", source)
        self.assertIn('"使用须知与版权"', source)
        self.assertIn("accepted_user_notice_version", state)
        self.assertIn('compatibleBoolean("automatic", true) && hasAcceptedCurrentUserNotice', state)
        self.assertIn("const val SAFETY", notice)
        self.assertIn("const val DATA_AND_PERMISSIONS", notice)
        self.assertIn("const val COPYRIGHT_AND_LICENSE", notice)
        self.assertIn("运行本软件本身不以接受 GPL 为前提", notice)
        self.assertIn("SettingsIconView.Icon.LEGAL", source)

    def test_bottom_navigation_uses_unified_vector_icons(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        icons = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/SettingsIconView.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("private fun bottomNavigationItem", source)
        self.assertIn("SettingsIconView.Icon.VEHICLE", source)
        self.assertIn("SettingsIconView.Icon.TRIP", source)
        self.assertIn("SettingsIconView.Icon.ACCOUNTING", source)
        self.assertIn("SettingsIconView.Icon.SETTINGS", source)
        self.assertIn("if (selected) background = rounded(soften(color), 15)", source)
        self.assertNotIn('0 -> "◉\\n"', source)
        self.assertIn("private fun drawAccounting", icons)
        self.assertIn("private fun drawSettings", icons)

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
                     "UPDATE", "FIRMWARE", "DISPLAY", "AUTOSTART", "HEALTH", "FEEDBACK",
                     "LEGAL", "LEGACY", "PLATE"):
            self.assertIn(icon, icons)

    def test_feedback_channel_prefills_reviewable_privacy_safe_report(self):
        source = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(
            encoding="utf-8"
        )
        page = source.split("private fun feedbackPage()", 1)[1].split(
            "private fun vehicleSettingsPage()", 1
        )[0]
        diagnostics = source.split("private fun feedbackDiagnostics()", 1)[1].split(
            "private fun feedbackTemplate()", 1
        )[0]
        self.assertIn('"问题反馈"', source)
        self.assertIn("https://github.com/sisi4376/BRZ-Garage/issues/new", source)
        self.assertIn('appendQueryParameter("body", feedbackTemplate())', source)
        self.assertIn('ClipData.newPlainText("BRZ Garage 问题反馈", feedbackTemplate())', source)
        self.assertIn('page("问题反馈"', page)
        self.assertIn('primaryBookkeepingAction("在 GitHub 提交问题"', page)
        self.assertIn('button("复制反馈模板")', page)
        self.assertIn("不会包含行程、位置、车牌、账目、日志正文或完整蓝牙地址", page)
        self.assertIn('"已绑定（蓝牙地址未包含）"', diagnostics)
        self.assertNotIn('"${state.address}"', diagnostics)
        self.assertIn('outState.putBoolean("feedback_page", showingFeedback)', source)
        self.assertIn('showingFeedback -> showTab(3)', source)


if __name__ == "__main__":
    unittest.main()
