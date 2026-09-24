package com.brz.gauge.trips

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.companion.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.*
import android.widget.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Suppress("DEPRECATION", "MissingPermission")
class MainActivity : Activity() {
    private lateinit var state: AppState
    private lateinit var database: TripDatabase
    private lateinit var fuelDatabase: FuelDatabase
    private lateinit var refuelDatabase: RefuelIntervalDatabase
    private lateinit var customTripDatabase: CustomTripDatabase
    private lateinit var tripAdapter: TripAdapter
    private lateinit var content: FrameLayout
    private lateinit var navigation: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var rangeText: TextView
    private lateinit var odometerText: TextView
    private lateinit var fuelText: TextView
    private lateinit var fuelBar: ProgressBar
    private lateinit var dataAge: TextView
    private lateinit var currentStats: TextView
    private lateinit var totalStats: TextView
    private var sinceRefuelStats: TextView? = null
    private var customStats: TextView? = null
    private lateinit var clockText: TextView
    private lateinit var countText: TextView
    private var homeVehicleHero: VehicleHeroView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var trips = emptyList<TripRecord>()
    private var fuelRecords = emptyList<FuelRecord>()
    private var fuelLoading = false
    private var fuelLoaded = false
    private var refuelIntervals = emptyList<RefuelInterval>()
    private var refuelLoading = false
    private var customTripIntervals = emptyList<CustomTripInterval>()
    private var customTripLoading = false
    private var tab = 0
    private var bindAfterPermission = false
    private var updateAfterWifiPermission = false
    private var firmwarePackagePendingPermission = EmbeddedFirmware.LATEST_PACKAGE_ID
    private var fallbackStop: (() -> Unit)? = null
    private var historyLoading = false
    private var historyRevisionMode = false
    private var showingGaugeSettings = false
    private var showingVehicleSettings = false
    private var showingTripDetail = false
    private var showingRefuelDetail = false
    private var showingCustomTripDetail = false
    private var tripDetailReturnTab = 1
    private var refuelRevisionMode = false
    private var currentScrollView: ScrollView? = null
    private val pageScrollPositions = mutableMapOf<Int, Int>()
    private var pendingScrollRestoreKey: Int? = null
    private var renderedHomeVehicleProfile: Int? = null
    private var homeRebuildPosted = false
    private var statusReceiverRegistered = false
    private var appUpdateChecking = false
    private var appUpdateCheck: AppUpdateCheck? = null
    private var appUpdateCheckError: String? = null
    private var appUpdateInstallAfterPermission = false
    private var appUpdatePromptedDownloadId = -1L
    private var appUpdatePollScheduled = false
    private var appUpdateLastStatus: AppDownloadStatus? = null
    private var appUpdateLastProgress = -1
    // Keep the two small decoded hero bitmaps for the Activity lifetime.  Do not
    // manually recycle the previous one while replacing the page: a settings
    // snapshot can change ZD8 -> ZC6 during an active draw traversal, and some
    // Android/HarmonyOS renderers then throw "trying to use a recycled bitmap".
    // Dropping this map in onDestroy lets the runtime reclaim the native pixels
    // only after detached ImageViews can no longer draw them.
    private val vehicleHeroBitmaps = mutableMapOf<Int, Bitmap>()
    private val ink = Color.rgb(24, 29, 39)
    private val muted = Color.rgb(109, 119, 132)
    private val accent = Color.rgb(220, 49, 86)
    private val pageColor = Color.rgb(244, 245, 247)
    private val lowFuelYellow = Color.rgb(255, 209, 102)
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val updated = intent?.getBooleanExtra(TripSyncService.EXTRA_UPDATED, false) == true
            // Do not rebuild the ZD8/ZC6 page re-entrantly from a BLE status
            // broadcast. Huawei renderers may still be traversing the old view.
            handler.post { refreshAfterStatus(updated) }
        }
    }
    private val refresh = object : Runnable {
        override fun run() { refreshHome(); handler.postDelayed(this, 5000) }
    }
    private val appUpdatePoll = object : Runnable {
        override fun run() {
            appUpdatePollScheduled = false
            refreshAppUpdateDownload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        state = AppState(this)
        if (state.firmwareUpdateStage == "checking" && !TripSyncService.running) {
            state.firmwareUpdate("failed", "上次检查已中断，请重新扫描；正常数据未受影响")
        }
        database = TripDatabase(this)
        fuelDatabase = FuelDatabase(this)
        refuelDatabase = RefuelIntervalDatabase(this)
        customTripDatabase = CustomTripDatabase(this)
        tripAdapter = TripAdapter(this, { reviseTripTime(it) }, { confirmDeleteTrip(it) },
            { showTripDetails(it) })
        val root = column().apply { setBackgroundColor(pageColor) }
        // Respect system bars on edge-to-edge Android 15+ and retain gesture navigation space.
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        navigation = LinearLayout(this).apply { setPadding(dp(12), dp(6), dp(12), dp(10)); elevation = dp(4).toFloat(); setBackgroundColor(Color.WHITE) }
        root.addView(navigation)
        setContentView(root)
        tab = savedInstanceState?.getInt("tab") ?: 0
        historyRevisionMode = savedInstanceState?.getBoolean("history_revision_mode") ?: false
        showingGaugeSettings = savedInstanceState?.getBoolean("gauge_settings_page") ?: false
        showingVehicleSettings = savedInstanceState?.getBoolean("vehicle_settings_page") ?: false
        when {
            showingVehicleSettings -> vehicleSettingsPage()
            showingGaugeSettings -> gaugeSettingsPage()
            else -> showTab(tab)
        }
        permissions(false)
        loadTrips()
        loadFuelRecords()
        loadRefuelIntervals()
        loadCustomTripIntervals()
    }
    private fun configureSystemBars() {
        window.statusBarColor = pageColor
        window.navigationBarColor = pageColor
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        outState.putBoolean("history_revision_mode", historyRevisionMode)
        outState.putBoolean("gauge_settings_page", showingGaugeSettings)
        outState.putBoolean("vehicle_settings_page", showingVehicleSettings)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Android framework callback retained for Android 8+ compatibility")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        when {
            showingTripDetail -> showTab(tripDetailReturnTab)
            showingRefuelDetail -> showTab(0)
            showingCustomTripDetail -> showTab(0)
            showingVehicleSettings -> showTab(3)
            showingGaugeSettings -> showTab(3)
            else -> super.onBackPressed()
        }
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        TripSyncService.foregroundUi = true
        val filter = IntentFilter(TripSyncService.ACTION_STATUS)
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(statusReceiver, filter)
            statusReceiverRegistered = true
        } catch (_: RuntimeException) {
            statusReceiverRegistered = false
            state.status("系统暂时无法注册状态回调，请重新打开应用", false)
        }
        handler.post(refresh)
        try { BackgroundBleWake.register(this) } catch (_: RuntimeException) { }
        try { ServiceWatchdogReceiver.schedule(this) } catch (_: RuntimeException) { }
        TripSyncService.start(this)
        resumeAppUpdateDownloadPolling()
    }
    override fun onResume() {
        super.onResume()
        if (appUpdateInstallAfterPermission &&
            (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) {
            appUpdateInstallAfterPermission = false
            handler.post { requestInstallDownloadedUpdate() }
        }
    }
    override fun onStop() {
        TripSyncService.foregroundUi = false
        if (statusReceiverRegistered) {
            try { unregisterReceiver(statusReceiver) } catch (_: RuntimeException) { }
            statusReceiverRegistered = false
        }
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(appUpdatePoll)
        appUpdatePollScheduled = false
        super.onStop()
    }
    override fun onDestroy() {
        fallbackStop?.invoke()
        handler.removeCallbacksAndMessages(null)
        vehicleHeroBitmaps.clear()
        io.execute { database.close(); fuelDatabase.close(); refuelDatabase.close(); customTripDatabase.close() }
        io.shutdown()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun rounded(color: Int, radius: Int = 22) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun add(parent: LinearLayout, view: View, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f; setTextColor(ink)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(233, 237, 242))
        setOnClickListener { action() }
    }
    private fun card(parent: LinearLayout, title: String): LinearLayout = column().apply {
        background = rounded(Color.WHITE); setPadding(dp(20), dp(18), dp(20), dp(18))
        add(this, label(title, 13f, muted, true))
        add(parent, this, 14)
    }
    private fun page(title: String, subtitle: String, singleLineTitle: Boolean = false): LinearLayout {
        val body = column().apply { setPadding(dp(22), dp(22), dp(22), dp(24)) }
        add(body, label(title, 30f, ink, true).apply {
            if (singleLineTitle) {
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        })
        add(body, label(subtitle, 12f, muted), 5)
        val pageKey = currentPageKey()
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        currentScrollView = scroll
        content.addView(scroll)
        val restoreY = pageScrollPositions[pageKey] ?: 0
        if (restoreY > 0) {
            pendingScrollRestoreKey = pageKey
            scroll.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (currentScrollView !== scroll) {
                        if (scroll.viewTreeObserver.isAlive)
                            scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        return
                    }
                    val childHeight = scroll.getChildAt(0)?.height ?: 0
                    val maxScroll = (childHeight - scroll.height).coerceAtLeast(0)
                    scroll.scrollTo(0, restoreY.coerceAtMost(maxScroll))
                    pageScrollPositions[pageKey] = scroll.scrollY
                    pendingScrollRestoreKey = null
                    if (scroll.viewTreeObserver.isAlive)
                        scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            })
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (pendingScrollRestoreKey != pageKey) pageScrollPositions[pageKey] = scrollY
        }
        return body
    }
    private fun currentPageKey(): Int = when {
        showingTripDetail -> 5
        showingRefuelDetail -> 6
        showingCustomTripDetail -> 7
        showingVehicleSettings -> 8
        showingGaugeSettings -> 4
        else -> tab
    }
    private fun rememberCurrentScroll() {
        val pageKey = currentPageKey()
        currentScrollView?.let {
            // A replacement page reports scrollY=0 until its first layout.
            // Do not let rapid firmware-status broadcasts overwrite the last
            // real position before the pending restoration has run.
            if (pendingScrollRestoreKey != pageKey || it.scrollY > 0 ||
                pageScrollPositions[pageKey] == null) {
                pageScrollPositions[pageKey] = it.scrollY
            }
        }
        currentScrollView = null
    }
    private fun showTab(index: Int) {
        rememberCurrentScroll()
        showingGaugeSettings = false
        showingVehicleSettings = false
        showingTripDetail = false
        showingRefuelDetail = false
        showingCustomTripDetail = false
        tab = index
        content.removeAllViews()
        navigation.removeAllViews()
        navigation.visibility = View.VISIBLE
        listOf("车辆", "行程", "加油", "设置").forEachIndexed { i, name ->
            navigation.addView(TextView(this).apply {
                text = (when (i) { 0 -> "◉\n"; 1 -> "≡\n"; 2 -> "＋\n"; else -> "◎\n" }) + name
                textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
                setTextColor(if (i == index) accent else muted)
                if (i == index) background = rounded(Color.rgb(253, 235, 240), 15)
                setOnClickListener { showTab(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        when (index) { 0 -> home(); 1 -> history(); 2 -> fueling(); else -> settings() }
    }
    private fun home() {
        val model = state.selectedVehicleModel
        renderedHomeVehicleProfile = model.profileIndex
        val body = page(state.vehicleDisplayName, model.homeSubtitle, singleLineTitle = true)
        odometerText = label("", 14f, ink, true)
        add(body, odometerText, 8)
        val hero = column().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(37, 50, 70), Color.rgb(15, 21, 32))).apply { cornerRadius = dp(24).toFloat() }
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        add(body, hero, 22)
        val heroView = VehicleHeroView(this).apply {
            loadVehicleHero(this, model, when (model) {
                SupportedVehicleModel.ZD8 -> R.drawable.brz_zd8_hero
                SupportedVehicleModel.ZC6 -> R.drawable.brz_zc6_hero
            })
            showInstalledPlate(
                state.customLicensePlate?.let { LicensePlateGenerator.parse(it).plate },
                state.showCustomLicensePlate,
            )
        }
        homeVehicleHero = heroView
        hero.addView(heroView, LinearLayout.LayoutParams(-1, dp(190)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        add(hero, label("预估剩余续航", 13f, Color.rgb(173, 190, 209)))
        rangeText = label("— km", 48f, Color.WHITE, true)
        add(hero, rangeText, 2)
        fuelText = label("剩余油量暂不可用", 14f, Color.rgb(209, 221, 234))
        add(hero, fuelText, 8)
        fuelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10000; progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(238, 89, 126))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(60, 76, 96))
        }
        hero.addView(fuelBar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(13) })
        dataAge = label("连接仪表后显示车辆数据", 11f, Color.rgb(173, 190, 209))
        add(hero, dataAge, 12)
        add(hero, label("续航仅为估计结果，请合理规划加油。", 11f, Color.rgb(173, 190, 209)), 10)
        val currentCard = card(body, "本次行程 · 仪表统计")
        currentStats = label("", 18f, ink, true); add(currentCard, currentStats, 12)
        currentCard.isClickable = true
        currentCard.setOnClickListener { state.vehicle()?.let { showCurrentTripDetails(it) } }
        totalStats = label("", 15f, ink, true); add(card(body, "累计驾驶 · 自仪表开始记录"), totalStats, 12)
        sinceRefuelStats = null
        if (state.showSinceRefuelTrip) {
            val refuelCard = card(body, "上次加油以来 · 仪表自动判断")
            sinceRefuelStats = label("", 15f, ink, true).also { add(refuelCard, it, 12) }
            refuelCard.isClickable = true
            refuelCard.setOnClickListener { showRefuelDetails() }
        }
        customStats = null
        if (state.showCustomTrip) {
            val customCard = card(body, customTripTitle(state.customTripName))
            customStats = label("", 15f, ink, true).also { add(customCard, it, 12) }
            customCard.isClickable = true
            customCard.setOnClickListener { showCustomTripDetails() }
        }
        val connection = card(body, "仪表状态")
        statusText = label("", 14f, ink, true); add(connection, statusText, 8)
        clockText = label("", 12f, muted); add(connection, clockText, 8)
        add(connection, button(if (state.address.isEmpty()) "绑定我的仪表" else "立即连接 / 同步") {
            if (state.address.isEmpty()) permissions(true) else {
                state.automatic = true; permissions(false)
                if (!TripSyncService.start(this, true)) showTab(3)
            }
        }, 12)
        refreshHome()
    }

    /** Decode the large source artwork at half resolution for the 190 dp card. */
    private fun loadVehicleHero(
        view: VehicleHeroView,
        model: SupportedVehicleModel,
        resourceId: Int,
    ) {
        val decoded = vehicleHeroBitmaps[resourceId] ?: try {
            BitmapFactory.decodeResource(resources, resourceId, BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })?.also { vehicleHeroBitmaps[resourceId] = it }
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            vehicleHeroBitmaps.clear()
            null
        }
        try {
            view.setVehicleArtwork(decoded, model)
        } catch (error: RuntimeException) {
            vehicleHeroBitmaps.remove(resourceId)
            Log.w("BRZ-MainActivity", "Vehicle artwork rejected by renderer", error)
        } catch (error: OutOfMemoryError) {
            vehicleHeroBitmaps.clear()
            Log.w("BRZ-MainActivity", "Vehicle artwork exceeded renderer memory", error)
        }
    }
    private fun refreshAfterStatus(updated: Boolean) {
        if (isFinishing || isDestroyed) return
        try {
            if (!state.firmwareUpdateActive) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (updated) { loadTrips(); loadRefuelIntervals() }
            if (updated && showingVehicleSettings) vehicleSettingsPage()
            else if (updated && showingGaugeSettings) gaugeSettingsPage()
            else if (updated && tab == 3) showTab(3)
            else refreshHome()
        } catch (error: RuntimeException) {
            Log.e("BRZ-MainActivity", "Post-sync UI refresh failed", error)
        } catch (error: OutOfMemoryError) {
            vehicleHeroBitmaps.clear()
            Log.e("BRZ-MainActivity", "Post-sync UI refresh ran out of memory", error)
        }
    }
    private fun scheduleHomeRebuild() {
        if (homeRebuildPosted) return
        homeRebuildPosted = true
        handler.post {
            homeRebuildPosted = false
            if (isFinishing || isDestroyed || tab != 0 ||
                renderedHomeVehicleProfile == state.selectedVehicleModel.profileIndex) return@post
            try {
                showTab(0)
            } catch (error: RuntimeException) {
                renderedHomeVehicleProfile = state.selectedVehicleModel.profileIndex
                Log.e("BRZ-MainActivity", "Vehicle page rebuild failed", error)
            } catch (error: OutOfMemoryError) {
                vehicleHeroBitmaps.clear()
                renderedHomeVehicleProfile = state.selectedVehicleModel.profileIndex
                Log.e("BRZ-MainActivity", "Vehicle page rebuild ran out of memory", error)
            }
        }
    }
    private fun refreshHome() {
        if (tab != 0 || !::rangeText.isInitialized) return
        if (renderedHomeVehicleProfile != state.selectedVehicleModel.profileIndex) {
            scheduleHomeRebuild()
            return
        }
        val v = state.vehicle()
        homeVehicleHero?.showInstalledPlate(
            state.customLicensePlate?.let { LicensePlateGenerator.parse(it).plate },
            state.showCustomLicensePlate,
        )
        val mileage = currentMileageEstimate()
        odometerText.text = homeMileageLabel(mileage)
        odometerText.visibility = if (state.odometerDisplayEnabled) View.VISIBLE else View.GONE
        val effectiveFuel = state.effectiveFuelPercent(v)
        val manualFuel = v?.fuelPercent == null && state.lastGaugeFuelPercent() == null &&
            state.manualFuelPercent != null
        val rangeSource = state.rangeConsumptionSource
        val rangeConsumption = selectRangeConsumption(
            source = rangeSource,
            correctionFactor = state.rangeCorrectionFactor,
            trips = trips.filter { state.address.isEmpty() || it.deviceId.equals(state.address, true) },
            gaugeHistoricalAverage = v?.average,
            fuelRecordAverage = if (fuelLoaded) analyzeFuelRecords(fuelRecords).historicalAverage else null,
        )
        val now = System.currentTimeMillis()
        val recentService = now - state.prefs.getLong("status_at", 0) in 0..65000
        statusText.text = if (!state.automatic) "自动连接已暂停"
            else if (state.address.isEmpty()) "首次使用，请绑定自己的仪表"
            else if (recentService) state.prefs.getString("status", "等待仪表上电")
            else "当前离线 · 打开连接页检查后台运行权限"
        clockText.text = if (state.timeAt == 0L) "尚未收到成功授时记录"
            else "上次授时 ${date(state.timeAt)} · " + if (state.timeVerified) "已回读校验" else "仅写入确认（旧固件）"
        rangeText.text = estimateRangeKm(state.tankLitres, effectiveFuel, rangeConsumption)?.let { "$it km" } ?: "— km"
        rangeText.setTextColor(if (isLowFuel(effectiveFuel)) lowFuelYellow else Color.WHITE)
        fuelText.text = effectiveFuel?.let {
            fmt("剩余 %.0f%%   ·   约 %.1f L%s", it, state.tankLitres * it / 100.0,
                if (manualFuel) "   ·   App 估算" else "")
        } ?: "车辆未提供油量 · 可在连接页设置当前油量"
        fuelBar.progress = effectiveFuel?.let { (it * 100).toInt().coerceIn(0, 10000) } ?: 0
        fuelBar.visibility = if (effectiveFuel == null) View.INVISIBLE else View.VISIBLE
        val rangeBasis = rangeConsumption?.let {
            val correction = if (it.source == RangeConsumptionSource.FUEL_RECORDS) ""
                else "，续航计算 ×${fmt("%.2f", it.correctionFactor)}"
            "${it.source.title} ${fmt("%.2f", it.displayedLitresPer100Km)} L/100km$correction"
        } ?: "${rangeSource.title}暂无有效样本"
        val fuelAt = state.lastGaugeFuelAt()
        val fuelUpdateText = if (fuelAt > 0L && now - fuelAt in 0..20_000L) "已更新 · ${date(fuelAt)}"
            else if (fuelAt > 0L) "未更新 · 上次更新 ${date(fuelAt)}"
            else "未更新 · 尚未收到油量"
        dataAge.text = (if (v == null) "尚未收到车辆数据 · 需要配套新版仪表固件"
            else fuelUpdateText) +
            "\n按 ${fmt("%.0f", state.tankLitres)} L油箱 · $rangeBasis"
        if (v == null) {
            currentStats.text = "— km    ·    — 分钟\n消耗燃油 — L\n平均油耗 —"
            totalStats.text = "— km    ·    — 小时\n累计消耗 — L\n平均油耗 —"
        } else {
            val currentAverage = if (v.average != null && v.currentDistanceM >= 100L)
                v.currentFuelMl.toDouble() / v.currentDistanceM * 100.0 else null
            currentStats.text = fmt(
                "%.1f km    ·    %s\n消耗燃油 %.2f L\n平均油耗 %s",
                v.currentDistanceM / 1000.0, duration(v.currentDurationS), v.currentFuelMl / 1000.0,
                currentAverage?.let { fmt("%.1f L/100km", it) } ?: "—"
            )
            totalStats.text = fmt(
                "%.1f km    ·    %s\n累计消耗 %.2f L\n平均油耗 %s",
                v.totalDistanceM / 1000.0, duration(v.totalDurationS), v.totalFuelMl / 1000.0,
                v.average?.let { fmt("%.1f L/100km", it) } ?: "—"
            )
        }
        val since = state.currentRefuelMeta()
        sinceRefuelStats?.text = if (since == null) {
            "等待仪表同步加油以来统计"
        } else statsText(since.currentDistanceM, since.currentDurationS, since.currentFuelMl)
        customStats?.text = state.customTrip(v)?.let { statsText(it.first, it.second, it.third) }
            ?: "等待车辆数据"
    }

    private fun statsText(distanceM: Long, durationS: Long, fuelMl: Long): String {
        val average = if (distanceM > 0) fuelMl.toDouble() / distanceM * 100.0 else null
        return fmt("%.1f km    ·    %s\n消耗燃油 %.2f L\n平均油耗 %s",
            distanceM / 1000.0, duration(durationS), fuelMl / 1000.0,
            average?.let { fmt("%.1f L/100km", it) } ?: "—")
    }
    private fun history() {
        val body = column().apply { setPadding(dp(22), dp(22), dp(22), dp(8)) }
        content.addView(body)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("驾驶足迹", 30f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Switch(this).apply {
            text = "修订"
            textSize = 13f
            isChecked = historyRevisionMode
            setOnCheckedChangeListener { _, checked ->
                historyRevisionMode = checked
                tripAdapter.setRevisionMode(checked)
            }
        })
        add(body, header)
        countText = label("", 12f, muted); add(body, countText, 7)
        val empty = label("还没有同步行程\n仪表连接后会自动保存到手机", 15f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(50), 0, 0) }
        val list = ListView(this).apply {
            adapter = tripAdapter; divider = null; dividerHeight = dp(10)
            emptyView = empty
        }
        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(20) })
        add(body, empty)
        updateHistory()
    }
    private fun updateHistory() {
        // Retain all devices' old records; never delete on a new association.
        tripAdapter.submit(trips)
        tripAdapter.setRevisionMode(historyRevisionMode)
        if (tab == 1 && ::countText.isInitialized) {
            val valid = trips.count { it.hasValidTime && !it.timeInconsistent }
            countText.text = "${trips.size} 次行程 · $valid 次时间完整 · 记录仅保存在本机" +
                if (state.prefs.getBoolean("overflow_${state.address}", false)) "\n仪表曾发生队列溢出，部分旧行程可能缺失" else ""
        }
    }

    private fun showCurrentTripDetails(vehicle: VehicleState) {
        val start = vehicle.currentStartEpochS.takeIf { it in 1704067200L..4102444800L }
            ?: vehicle.epochS.takeIf { it in 1704067200L..4102444800L }
                ?.let { (it - vehicle.currentDurationS).coerceAtLeast(0L) } ?: 0L
        val end = vehicle.epochS.takeIf { it >= start } ?: 0L
        val averageX100 = if (vehicle.currentDistanceM > 0)
            (vehicle.currentFuelMl.toDouble() / vehicle.currentDistanceM * 10_000.0).roundToInt()
        else 0
        showTripDetails(TripRecord(
            deviceId = state.address, tripId = 0, startEpochS = start, endEpochS = end,
            durationS = vehicle.currentDurationS, distanceM = vehicle.currentDistanceM,
            fuelMl = vehicle.currentFuelMl, avgL100X100 = averageX100,
            flags = if (start > 0L && end >= start) 1 else 0,
            maxSpeedKmh = vehicle.maxSpeedKmh, maxRpm = vehicle.maxRpm,
            maxAccelX100 = vehicle.maxAccelX100, maxDecelX100 = vehicle.maxDecelX100,
        ), current = true)
    }

    private fun showTripDetails(trip: TripRecord, current: Boolean = false) {
        rememberCurrentScroll()
        showingGaugeSettings = false
        showingVehicleSettings = false
        showingTripDetail = true
        showingRefuelDetail = false
        showingCustomTripDetail = false
        tripDetailReturnTab = if (current) 0 else 1
        content.removeAllViews()
        navigation.removeAllViews()
        navigation.visibility = View.GONE

        val subtitle = if (current) "正在进行 · 数据来自仪表实时快照" else if (trip.hasValidTime) {
            val start = Instant.ofEpochSecond(trip.startEpochS).atZone(ZoneId.systemDefault())
            start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " · #${trip.tripId}"
        } else "时间未知 · #${trip.tripId}"
        val body = page("行程详情", subtitle)
        add(body, button(if (current) "‹ 返回首页" else "‹ 返回驾驶足迹") {
            showTab(if (current) 0 else 1)
        }, 18)
        if (!current) add(body, button("修订测试数据") { reviseTripData(trip) }, 8)
        if (!current && trip.dataRevised) {
            add(body, label("此行程的数值已作为测试数据手动修订；不会写回仪表。", 12f, accent, true), 10)
        }

        val overview = card(body, "行程概览")
        add(overview, label(fmt("%.1f km", trip.distanceM / 1000.0), 32f, ink, true), 10)
        add(overview, label(
            "驾驶时长 ${durationDetailed(trip.durationS)}\n" +
                "平均时速 ${trip.averageSpeedKmh?.let { fmt("%.1f km/h", it) } ?: "—"}",
            15f, muted), 8)

        val performance = card(body, "速度与转速")
        add(performance, detailMetric("最高速度", trip.maxSpeedKmh?.let { "$it km/h" }), 10)
        add(performance, detailMetric("最高转速", trip.maxRpm?.let { "$it rpm" }), 8)
        add(performance, label(
            if (trip.hasDrivingDetails) "极值由仪表在行程中实时统计。"
            else "该行程来自旧版记录，仪表当时尚未采集速度与转速极值。",
            11f, muted), 10)

        val acceleration = card(body, "纵向加速度")
        add(acceleration, detailMetric("最大加速", trip.maxAccelX100?.let { fmt("%+.2f m/s²", it / 100.0) }), 10)
        add(acceleration, detailMetric("最大减速", trip.maxDecelX100?.let { fmt("%+.2f m/s²", it / 100.0) }), 8)
        add(acceleration, label(
            "根据 OBD 车速变化和采样间隔估算，并过滤超过约 3 g 的异常跳变；仅供驾驶回顾，不作为专业性能或事故分析数据。",
            11f, muted), 10)

        val fuel = card(body, "燃油表现")
        add(fuel, detailMetric("平均油耗", fmt("%.1f L/100km", trip.avgL100X100 / 100.0)), 10)
        add(fuel, detailMetric("消耗燃油", fmt("%.2f L", trip.fuelMl / 1000.0)), 8)

        val timing = card(body, "时间记录")
        if (trip.hasValidTime) {
            val zone = ZoneId.systemDefault()
            val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            add(timing, detailMetric("开始", format.format(Instant.ofEpochSecond(trip.startEpochS).atZone(zone))), 10)
            add(timing, detailMetric("结束", format.format(Instant.ofEpochSecond(trip.endEpochS).atZone(zone))), 8)
            if (current) add(timing, label("本次行程结束后，最终记录会同步到驾驶足迹。", 11f, muted), 8)
            else if (trip.timeRevised) add(timing, label("时间由用户手动修订", 11f, accent, true), 8)
            else if (trip.timeInconsistent) add(timing, label("时间跨度与驾驶时长不一致，请核实", 11f, accent, true), 8)
        } else {
            add(timing, label("升级前记录或该次行程未完成手机授时，因此没有可靠的开始/结束时间。", 13f, muted), 10)
        }
    }

    private fun detailMetric(name: String, value: String?): TextView =
        label("$name\n${value ?: "旧记录未采集"}", 16f, if (value == null) muted else ink, value != null)

    private fun showCustomTripDetails() {
        rememberCurrentScroll()
        showingGaugeSettings = false
        showingVehicleSettings = false
        showingTripDetail = false
        showingRefuelDetail = false
        showingCustomTripDetail = true
        content.removeAllViews()
        navigation.removeAllViews()
        navigation.visibility = View.GONE
        val currentTitle = customTripTitle(state.customTripName)
        val body = page("自定义行程", "$currentTitle · 名称仅保存在手机")
        add(body, button("‹ 返回首页") { showTab(0) }, 18)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(button("修改名称") { showCustomTripNameDialog(reset = false) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        controls.addView(button("重置并归档") {
            val vehicle = state.vehicle()
            if (vehicle == null) toast("尚未收到车辆数据")
            else showCustomTripNameDialog(reset = true, vehicle = vehicle)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        add(body, controls, 12)
        add(body, label("重置时会先把当前区间保存到下方历史，再从仪表当前累计值开始新的区间；自定义名称不会发送到仪表。", 11f, muted), 8)

        val current = card(body, "当前 · $currentTitle")
        val stats = state.customTrip(state.vehicle())
        add(current, label(stats?.let { statsText(it.first, it.second, it.third) }
            ?: "等待车辆数据", 16f, ink, true), 10)
        val startedAt = state.customTripResetAtMs
        if (startedAt > 0L) add(current, label("开始于 ${date(startedAt)}", 11f, muted), 6)

        val historyCard = card(body, "历次自定义行程")
        if (customTripLoading) add(historyCard, label("正在读取…", 13f, muted), 10)
        else if (customTripIntervals.isEmpty()) add(historyCard, label("暂无已完成的自定义行程", 13f, muted), 10)
        else customTripIntervals.forEach { interval ->
            val row = column().apply {
                background = rounded(Color.rgb(247, 248, 250), 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            fun time(epoch: Long): String = if (epoch in 1704067200L..4102444800L)
                format.format(Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault())) else "时间未知"
            add(row, label(customTripTitle(interval.name), 14f, ink, true))
            add(row, label("${time(interval.startEpochS)}  →  ${time(interval.endEpochS)}", 11f, muted), 5)
            add(row, label(statsText(interval.distanceM, interval.durationS, interval.fuelMl), 13f, muted), 7)
            add(historyCard, row, 9)
        }
    }

    private fun showCustomTripNameDialog(reset: Boolean, vehicle: VehicleState? = null) {
        val form = column().apply { setPadding(dp(20), dp(4), dp(20), 0) }
        val presets = listOf("", "上次保养以来", "上次洗车以来", "上次长途以来")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val currentName = state.customTripName
        var selectedName: String? = null
        presets.forEachIndexed { index, value ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = value
                text = if (value.isEmpty()) "不设置名称" else value
                isChecked = if (reset) index == 0 else currentName == value
            })
        }
        val customOption = RadioButton(this).apply {
            id = View.generateViewId()
            tag = "__custom__"
            text = "自己输入"
            isChecked = !reset && currentName.isNotEmpty() && currentName !in presets
        }
        group.addView(customOption)
        add(form, group, 4)
        val customInput = EditText(this).apply {
            hint = "最多 $MAX_CUSTOM_TRIP_NAME_LENGTH 个字符"
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MAX_CUSTOM_TRIP_NAME_LENGTH))
            setText(if (customOption.isChecked) currentName else "")
            visibility = if (customOption.isChecked) View.VISIBLE else View.GONE
        }
        add(form, customInput, 4)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val value = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? String
            selectedName = value
            customInput.visibility = if (value == "__custom__") View.VISIBLE else View.GONE
            if (value == "__custom__") customInput.requestFocus()
        }
        selectedName = if (customOption.isChecked) "__custom__" else
            presets.firstOrNull { if (reset) it.isEmpty() else it == currentName } ?: ""
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (reset) "重置后的自定义行程名称" else "修改自定义行程名称")
            .setMessage(if (reset) "直接确认将不设置名称，并把当前区间归入历史。" else
                "名称只影响 App 显示，不会同步到仪表。")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton(if (reset) "重置" else "保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosen = if (selectedName == "__custom__") normalizeCustomTripName(customInput.text.toString())
                    else normalizeCustomTripName(selectedName)
                if (selectedName == "__custom__" && chosen.isEmpty()) {
                    toast("请输入名称，或选择“不设置名称”")
                    return@setOnClickListener
                }
                dialog.dismiss()
                if (reset) archiveAndResetCustomTrip(vehicle ?: return@setOnClickListener, chosen)
                else {
                    state.setCustomTripName(chosen)
                    toast(if (chosen.isEmpty()) "已恢复显示“自定义行程”" else "名称已修改为 $chosen")
                    if (showingCustomTripDetail) showCustomTripDetails()
                }
            }
        }
        dialog.show()
    }

    private fun archiveAndResetCustomTrip(vehicle: VehicleState, newName: String) {
        val hadBaseline = state.customTripBaseline() != null
        val stats = if (hadBaseline) state.customTrip(vehicle) else null
        val oldName = state.customTripName
        val endEpochS = System.currentTimeMillis() / 1000L
        val fallbackStart = endEpochS - (stats?.second ?: 0L)
        val startEpochS = (state.customTripResetAtMs / 1000L).takeIf { it in 1..endEpochS }
            ?: fallbackStart.coerceAtLeast(0L)
        fun finishReset() {
            state.resetCustomTrip(vehicle, newName)
            TripSyncService.syncCustomTripBaseline(this)
            loadCustomTripIntervals()
            toast(if (state.connected) "自定义行程已归档并重置 · 正在同步到仪表"
                else "自定义行程已归档并重置 · 下次连接时同步到仪表")
            if (showingCustomTripDetail) showCustomTripDetails()
        }
        if (!hadBaseline || stats == null) {
            finishReset()
            return
        }
        val record = CustomTripInterval(deviceId = state.address, name = oldName,
            startEpochS = startEpochS, endEpochS = endEpochS,
            durationS = stats.second, distanceM = stats.first, fuelMl = stats.third)
        io.execute {
            val saved = try { customTripDatabase.insert(record) } catch (_: RuntimeException) { false }
            runOnUiThread {
                if (saved) finishReset() else toast("当前自定义行程归档失败，尚未重置")
            }
        }
    }

    private fun loadCustomTripIntervals() {
        if (customTripLoading || isDestroyed || !::customTripDatabase.isInitialized) return
        customTripLoading = true
        val deviceId = state.address
        io.execute {
            val result = try { customTripDatabase.all(deviceId) } catch (_: RuntimeException) { null }
            runOnUiThread {
                customTripLoading = false
                if (!isDestroyed && result != null) {
                    customTripIntervals = result
                    if (showingCustomTripDetail) showCustomTripDetails()
                }
            }
        }
    }

    private fun showRefuelDetails() {
        rememberCurrentScroll()
        showingGaugeSettings = false
        showingVehicleSettings = false
        showingTripDetail = false
        showingRefuelDetail = true
        showingCustomTripDetail = false
        content.removeAllViews()
        navigation.removeAllViews()
        navigation.visibility = View.GONE
        val body = page("上次加油以来", "按当前识别阈值连续确认并独立记录")
        add(body, button("‹ 返回首页") { showTab(0) }, 18)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(Switch(this).apply {
            text = "修订模式"
            textSize = 13f
            isChecked = refuelRevisionMode
            setOnCheckedChangeListener { _, checked -> refuelRevisionMode = checked; showRefuelDetails() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("手动重置") { confirmManualRefuelReset() }, LinearLayout.LayoutParams(dp(120), -2))
        add(body, controls, 12)
        val refuelThresholdText = state.gaugeSettings()?.refuelThresholdMl?.let {
            "当前设置为 ${it / 1000} L"
        } ?: "默认值为 10 L；连接仪表后显示实际设置"
        add(body, label(
            "仪表需要两次独立油位样本均确认油量上升达到自动加油识别阈值，才会建立新重置点；$refuelThresholdText，可在“设置 → 我的车辆 → 车辆设置”中调整。这里的记录与手动加油记录完全独立。",
            11f,
            muted,
        ), 8)

        refuelIntervals.minByOrNull { it.id }?.let { oldest ->
            add(body, button("删除第一个节点以前的数据") {
                confirmDiscardBeforeFirstRefuelNode(oldest)
            }, 10)
        }

        val current = card(body, "当前 · 上次重置以来")
        val meta = state.currentRefuelMeta()
        add(current, label(if (meta == null) "等待仪表同步" else
            statsText(meta.currentDistanceM, meta.currentDurationS, meta.currentFuelMl), 16f, ink, true), 10)

        val historyCard = card(body, "历次加油以来")
        if (refuelLoading) add(historyCard, label("正在读取…", 13f, muted), 10)
        else if (refuelIntervals.isEmpty()) add(historyCard, label("暂无已完成的加油以来区间", 13f, muted), 10)
        else refuelIntervals.forEachIndexed { index, interval ->
            val row = column().apply {
                background = rounded(Color.rgb(247, 248, 250), 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val dateText = interval.endEpochS.takeIf { it in 1704067200L..4102444800L }?.let {
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(
                    Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()))
            } ?: "时间未知"
            val reason = when {
                interval.automatic -> "自动识别加油" + if (interval.detectedAddedMl > 0)
                    fmt(" · 约 +%.1f L", interval.detectedAddedMl / 1000.0) else ""
                interval.manual -> "手动重置"
                else -> "重置记录"
            }
            add(row, label("$dateText   ·   $reason", 13f, ink, true))
            add(row, label(statsText(interval.distanceM, interval.durationS, interval.fuelMl), 13f, muted), 7)
            if (interval.dataRevised) add(row, label("测试数据已在手机端修订", 11f, accent, true), 6)
            if (refuelRevisionMode) {
                add(row, button("修订测试数据") { reviseRefuelInterval(interval) }, 8)
            }
            // History is newest first. Keep the most recent node directly
            // removable so an accidental refuel detection can be undone
            // without enabling the broader test-data revision mode.
            if (index == 0 || refuelRevisionMode) add(row,
                button(if (index == 0) "删除最近加油节点" else "删除此加油节点") {
                    confirmDeleteRefuelNode(interval)
                }, 6)
            add(historyCard, row, 9)
        }
    }

    private fun confirmDeleteRefuelNode(interval: RefuelInterval) {
        val newestId = refuelIntervals.maxOfOrNull { it.id }
        val mergeTarget = if (interval.id == newestId) "当前尚未结束的区间"
            else "下一个加油节点对应的区间"
        AlertDialog.Builder(this)
            .setTitle("删除这个加油节点？")
            .setMessage("删除后，该节点前的行程数据会自动合并到$mergeTarget。此操作会同步到仪表，且无法撤销；不会影响普通行程和手动加油记录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除并合并") { _, _ ->
                val started = TripSyncService.deleteRefuelNode(this, interval.id)
                toast(if (started) "删除已排队，等待仪表确认" else
                    "删除已排队，连接仪表后执行")
            }.show()
    }

    private fun confirmDiscardBeforeFirstRefuelNode(oldest: RefuelInterval) {
        AlertDialog.Builder(this)
            .setTitle("删除第一个节点以前的数据？")
            .setMessage("这会永久删除最早一次重置点之前累计的加油以来数据，但保留该重置点之后的所有区间和当前累计。数据不会合并到下一段，且无法撤销；普通行程和手动加油记录不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认删除") { _, _ ->
                val started = TripSyncService.discardRefuelBeforeFirstNode(this, oldest.id)
                toast(if (started) "删除已排队，等待仪表确认" else
                    "删除已排队，连接仪表后执行")
            }.show()
    }

    private fun confirmManualRefuelReset() {
        AlertDialog.Builder(this).setTitle("手动重置上次加油以来？")
            .setMessage("仪表会保存当前区间并从现在重新累计。不会清除普通行程、累计驾驶或手动加油记录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认重置") { _, _ ->
                val started = TripSyncService.resetRefuelTrip(this)
                toast(if (started) "重置已排队，等待仪表确认" else "已排队，连接仪表后执行")
            }.show()
    }

    private fun reviseRefuelInterval(interval: RefuelInterval) {
        val form = column().apply { setPadding(dp(20), dp(4), dp(20), 0) }
        fun field(title: String, value: String): EditText {
            add(form, label(title, 12f, muted, true), 10)
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(value); form.addView(this)
            }
        }
        val distance = field("区间里程（km）", fmt("%.1f", interval.distanceM / 1000.0))
        val minutes = field("驾驶时长（分钟）", fmt("%.1f", interval.durationS / 60.0))
        val fuel = field("消耗燃油（L）", fmt("%.2f", interval.fuelMl / 1000.0))
        val dialog = AlertDialog.Builder(this).setTitle("修订测试数据")
            .setMessage("仅修改手机中的显示记录，不会写回仪表。")
            .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val km = distance.text.toString().replace(',', '.').toDoubleOrNull()
                val min = minutes.text.toString().replace(',', '.').toDoubleOrNull()
                val litres = fuel.text.toString().replace(',', '.').toDoubleOrNull()
                if (km == null || min == null || litres == null || km < 0 || min < 0 || litres < 0) {
                    toast("请输入有效的非负数值")
                } else {
                    io.execute {
                        val saved = refuelDatabase.revise(interval.copy(
                            distanceM = (km * 1000).roundToLong(),
                            durationS = (min * 60).roundToLong(),
                            fuelMl = (litres * 1000).roundToLong()))
                        runOnUiThread { if (saved) { dialog.dismiss(); loadRefuelIntervals() } else toast("修订失败") }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun loadRefuelIntervals() {
        if (refuelLoading || isDestroyed || !::refuelDatabase.isInitialized) return
        refuelLoading = true
        val deviceId = state.address
        io.execute {
            val result = try { refuelDatabase.all(deviceId) } catch (_: RuntimeException) { null }
            runOnUiThread {
                refuelLoading = false
                if (!isDestroyed && result != null) {
                    refuelIntervals = result
                    if (showingRefuelDetail) showRefuelDetails() else refreshHome()
                }
            }
        }
    }

    private fun durationDetailed(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = seconds / 60 % 60
        val secs = seconds % 60
        return fmt("%02d:%02d:%02d", hours, minutes, secs)
    }

    private fun reviseTripData(trip: TripRecord) {
        val editor = column().apply { setPadding(dp(20), dp(2), dp(20), dp(8)) }
        fun numberField(title: String, value: String, signed: Boolean = false): EditText {
            add(editor, label(title, 12f, muted), 7)
            return EditText(this).apply {
                setText(value)
                selectAll()
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0
                editor.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val distance = numberField("行程里程（km，0～10000）", fmt("%.3f", trip.distanceM / 1000.0))
        val duration = numberField("驾驶时长（秒，1～604800）", trip.durationS.toString())
        val fuel = numberField("消耗燃油（L，0～1000）", fmt("%.3f", trip.fuelMl / 1000.0))
        val averageFuel = numberField("平均油耗（L/100km，留空则自动计算）",
            trip.avgL100X100.takeIf { it in 0..10_000 }?.let { fmt("%.2f", it / 100.0) } ?: "")
        val maxSpeed = numberField("最高速度（km/h，旧记录可留空）", trip.maxSpeedKmh?.toString() ?: "")
        val maxRpm = numberField("最高转速（rpm，旧记录可留空）", trip.maxRpm?.toString() ?: "")
        val maxAccel = numberField("最大加速（m/s²，旧记录可留空）",
            trip.maxAccelX100?.let { fmt("%.2f", it / 100.0) } ?: "", true)
        val maxDecel = numberField("最大减速（m/s²，旧记录可留空）",
            trip.maxDecelX100?.let { fmt("%.2f", it / 100.0) } ?: "", true)
        val scroller = ScrollView(this).apply { addView(editor) }
        val content = column().apply { setPadding(dp(8), 0, dp(8), dp(8)) }
        content.addView(scroller, LinearLayout.LayoutParams(-1,
            minOf(dp(460), (resources.displayMetrics.heightPixels * 0.55f).toInt())))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        lateinit var dialog: AlertDialog
        lateinit var saveButton: Button
        fun saveRevision() {
            fun decimal(field: EditText) = field.text.toString().replace(',', '.').toDoubleOrNull()
            val distanceKm = decimal(distance)
            val durationS = duration.text.toString().toLongOrNull()
            val fuelL = decimal(fuel)
            val enteredAverage = decimal(averageFuel)
            val speed = maxSpeed.text.toString().trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            val rpm = maxRpm.text.toString().trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            val accel = maxAccel.text.toString().trim().takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDoubleOrNull()
            val decel = maxDecel.text.toString().trim().takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDoubleOrNull()
            val calculatedAverage = if (distanceKm != null && fuelL != null && distanceKm > 0.0)
                fuelL / distanceKm * 100.0 else 0.0
            val average = enteredAverage ?: calculatedAverage
            val valid = distanceKm != null && distanceKm.isFinite() && distanceKm in 0.0..10_000.0 &&
                durationS != null && durationS in 1L..604_800L &&
                fuelL != null && fuelL.isFinite() && fuelL in 0.0..1_000.0 &&
                average.isFinite() && average in 0.0..100.0 &&
                (speed == null || speed in 0..500) && (rpm == null || rpm in 0..20_000) &&
                (accel == null || accel.isFinite() && accel in 0.0..30.0) &&
                (decel == null || decel.isFinite() && decel in -30.0..0.0)
            if (!valid) {
                toast("请检查输入值及标注的范围")
                return
            }
            val revised = trip.copy(
                durationS = durationS!!,
                distanceM = (distanceKm!! * 1000.0).roundToLong(),
                fuelMl = (fuelL!! * 1000.0).roundToLong(),
                avgL100X100 = (average * 100.0).roundToLong().toInt(),
                maxSpeedKmh = speed,
                maxRpm = rpm,
                maxAccelX100 = accel?.let { (it * 100.0).roundToLong().toInt() },
                maxDecelX100 = decel?.let { (it * 100.0).roundToLong().toInt() },
                dataRevised = true,
            )
            saveButton.isEnabled = false
            io.execute {
                val saved = try { database.reviseData(revised) } catch (_: RuntimeException) { false }
                runOnUiThread {
                    if (!isDestroyed) {
                        if (saved) {
                            trips = trips.map { if (it.deviceId == revised.deviceId && it.tripId == revised.tripId) revised else it }
                            tripAdapter.submit(trips)
                            dialog.dismiss()
                            showTripDetails(revised)
                            toast("测试数据已修订")
                        } else {
                            saveButton.isEnabled = true
                            toast("数据修订失败，请重试")
                        }
                    }
                }
            }
        }
        actions.addView(button("取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
        saveButton = button("保存") { saveRevision() }
        actions.addView(saveButton, LinearLayout.LayoutParams(0, -2, 1f))
        add(content, actions, 8)
        dialog = AlertDialog.Builder(this)
            .setTitle("修订行程 #${trip.tripId} 测试数据")
            .setMessage("仅修改手机本地副本，不写回仪表。相同行程再次同步时仍保留修订值。时间请使用行程列表的“修订”模式修改。")
            .setView(content)
            .create()
        dialog.show()
    }

    private fun reviseTripTime(trip: TripRecord) {
        val zone = ZoneId.systemDefault()
        val initialEnd = if (trip.hasValidTime)
            Instant.ofEpochSecond(trip.endEpochS).atZone(zone)
        else Instant.now().atZone(zone)
        val initialStart = if (trip.hasValidTime)
            Instant.ofEpochSecond(trip.startEpochS).atZone(zone)
        else initialEnd.minusSeconds(trip.durationS)
        data class Selection(var date: LocalDate, var hour: Int, var minute: Int)
        val startSelection = Selection(initialStart.toLocalDate(), initialStart.hour, initialStart.minute)
        val endSelection = Selection(initialEnd.toLocalDate(), initialEnd.hour, initialEnd.minute)
        val editor = column().apply { setPadding(dp(20), dp(2), dp(20), dp(2)) }
        add(editor, label("开始和结束时间可分别修订；结束不能早于开始，时间跨度不能明显短于仪表记录的驾驶时长。", 12f, muted), 8)
        fun addDateTimeRows(prefix: String, selection: Selection) {
            val dateButton = Button(this).apply {
                text = selection.date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                isAllCaps = false
                setOnClickListener {
                    DatePickerDialog(this@MainActivity, { _, year, month, day ->
                        selection.date = LocalDate.of(year, month + 1, day)
                        text = selection.date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    }, selection.date.year, selection.date.monthValue - 1,
                        selection.date.dayOfMonth).show()
                }
            }
            val timeButton = Button(this).apply {
                text = fmt("%02d:%02d", selection.hour, selection.minute)
                isAllCaps = false
                setOnClickListener {
                    TimePickerDialog(this@MainActivity, { _, hour, minute ->
                        selection.hour = hour
                        selection.minute = minute
                        text = fmt("%02d:%02d", selection.hour, selection.minute)
                    }, selection.hour, selection.minute, true).show()
                }
            }
            add(editor, LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("${prefix}日期", 15f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(dateButton, LinearLayout.LayoutParams(dp(150), -2))
            }, 4)
            add(editor, LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("${prefix}时间", 15f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(timeButton, LinearLayout.LayoutParams(dp(150), -2))
            }, 4)
        }
        addDateTimeRows("开始", startSelection)
        addDateTimeRows("结束", endSelection)
        val dialog = AlertDialog.Builder(this)
            .setTitle("修订行程 #${trip.tripId} 时间")
            .setView(editor)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val start = startSelection.date
                    .atTime(startSelection.hour, startSelection.minute)
                    .atZone(zone).toEpochSecond()
                val end = endSelection.date
                    .atTime(endSelection.hour, endSelection.minute)
                    .atZone(zone).toEpochSecond()
                if (end < start) {
                    toast("结束时间不能早于开始时间")
                    return@setOnClickListener
                }
                if (end - start + 59L < trip.durationS) {
                    toast("起止时间跨度不能短于驾驶时长 ${durationDetailed(trip.durationS)}")
                    return@setOnClickListener
                }
                io.execute {
                    val saved = try { database.reviseTime(trip.deviceId, trip.tripId, start, end) }
                    catch (_: RuntimeException) { false }
                    runOnUiThread {
                        if (!isDestroyed) {
                            if (saved) {
                                dialog.dismiss()
                                toast("行程开始和结束时间已修订")
                                loadTrips()
                            }
                            else toast("时间修订失败，请重试")
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteTrip(trip: TripRecord) {
        AlertDialog.Builder(this)
            .setTitle("删除行程 #${trip.tripId}？")
            .setMessage("该行程会从手机历史中永久删除，仪表中的其他行程不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                io.execute {
                    val deleted = try { database.deleteTrip(trip.deviceId, trip.tripId) }
                    catch (_: RuntimeException) { false }
                    runOnUiThread {
                        if (!isDestroyed) {
                            if (deleted) { toast("行程已删除"); loadTrips() }
                            else toast("删除失败，请重试")
                        }
                    }
                }
            }
            .show()
    }
    private fun loadTrips() {
        if (historyLoading || isDestroyed) return
        historyLoading = true
        io.execute {
            val result = try { database.allTrips() } catch (_: RuntimeException) { null }
            runOnUiThread {
                historyLoading = false
                if (!isDestroyed && result != null) { trips = result; updateHistory(); refreshHome() }
            }
        }
    }
    private fun currentMileageEstimate(records: List<TripRecord> = trips): MileageEstimate =
        estimateMileage(records, state.address, state.odometerCalibrationM(), state.odometerAnchorTripId())

    private fun calibrateMileage() {
        val deviceId = state.address
        if (deviceId.isBlank()) {
            toast("请先绑定仪表，再校准里程")
            return
        }
        io.execute {
            val records = try { database.allTrips() } catch (_: RuntimeException) { null }
            runOnUiThread {
                if (!isDestroyed && records != null) showMileageCalibration(deviceId, records)
                else if (!isDestroyed) toast("暂时无法读取行程，请稍后重试")
            }
        }
    }

    private fun showMileageCalibration(deviceId: String, records: List<TripRecord>) {
        val estimate = estimateMileage(records, deviceId,
            state.odometerCalibrationM(deviceId), state.odometerAnchorTripId(deviceId))
        val entry = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(fmt("%.1f", estimate.distanceM / 1000.0))
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("手动校准车辆里程")
            .setMessage("请输入车辆当前里程。保存后会先更新 App，并在正常同步空闲时校准仪表；不会暂停或重置 OBD 采集与行程统计。")
            .setView(entry)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val km = entry.text.toString().replace(',', '.').toDoubleOrNull()
                if (km == null || !km.isFinite() || km !in 0.0..999999.9) {
                    toast("请输入 0～999999.9 km")
                } else {
                    val distanceM = (km * 1000.0).roundToLong()
                    state.calibrateOdometer(deviceId, distanceM, newestTripId(records, deviceId))
                    val started = TripSyncService.calibrateGaugeOdometer(this@MainActivity, distanceM)
                    trips = records
                    dialog.dismiss()
                    showTab(3)
                    toast(if (started) "App 已校准，仪表校准已排队同步"
                        else "App 已校准，连接仪表后自动同步")
                }
            }
        }
        dialog.show()
    }
    private fun fueling() {
        val body = page("加油记录", "记录每次加油，查看真实长期用车油耗")
        add(body, button("＋ 记录一次加油") { editFuelRecord(null) }, 18)

        val analysis = analyzeFuelRecords(fuelRecords)
        val measuredLitres = analysis.measuredLitres
        val measuredDistance = analysis.measuredDistanceKm
        val intervalById = analysis.points.associate { it.recordId to it.litresPer100Km }
        val statisticalRecords = fuelRecords.filter { !it.draft }
        val totalLitres = statisticalRecords.sumOf { it.litres }
        val totalCost = statisticalRecords.sumOf { it.cost }
        val totalAverage = analysis.historicalAverage
        val summary = card(body, "加油统计 · 仅本页口径")
        add(summary, label(fmt("累计加油  %.2f L", totalLitres), 22f, ink, true), 10)
        add(summary, label(totalAverage?.let { fmt("总平均油耗  %.2f L/100km", it) } ?: "总平均油耗  —", 22f, accent, true), 8)
        if (totalAverage != null) {
            add(summary, label(fmt("统计用油 %.2f L    ·    统计里程 %.1f km", measuredLitres, measuredDistance), 12f, muted), 6)
        }
        add(summary, label(fmt("累计花费  ¥%.2f    ·    %d 条记录", totalCost, fuelRecords.size), 13f, muted), 9)
        add(summary, label("第一次加满只建立里程基准，不计算油耗。从第二次开始，本次加油量视为此前区间的消耗量；未满时同时累计油量和里程，到下一次加满再合并计算。最新一条若已加满，也正常计入历史油耗。", 11f, muted), 12)

        val chartCard = card(body, "平均油耗变化 · 蓝色虚线为历史均值")
        val chart = FuelChartView(this).apply { submit(analysis.points, analysis.historicalAverage) }
        chartCard.addView(chart, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(10) })

        val listCard = card(body, "全部记录")
        if (!fuelLoaded || fuelLoading) add(listCard, label("正在读取…", 14f, muted), 12)
        else if (fuelRecords.isEmpty()) add(listCard, label("还没有加油记录\n点击上方按钮录入第一次加油。", 14f, muted), 12)
        else fuelRecords.forEach { record ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.rgb(247, 248, 250), 14)
                setPadding(dp(14), dp(12), dp(8), dp(12))
            }
            val text = buildString {
                append(LocalDate.ofEpochDay(record.dateEpochDay).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                if (record.draft) append("   ·   暂存，不参与统计")
                else if (!record.fullTank) append("   ·   未满")
                append(fmt("\n%.1f km", record.odometerKm))
                append(if (record.litres > 0.0) fmt("   ·   %.2f L", record.litres) else "   ·   加油量未填")
                append(if (!record.draft || record.cost > 0.0) fmt("   ·   ¥%.2f", record.cost) else "   ·   花费未填")
                intervalById[record.id]?.let { append(fmt("\n本次区间油耗  %.2f L/100km", it)) }
            }
            row.addView(label(text, 13f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("编辑") { editFuelRecord(record) }, LinearLayout.LayoutParams(dp(72), -2))
            add(listCard, row, 9)
        }
        add(body, label("加油记录保存在手机本机，不写入仪表，也不会影响主页使用仪表 lifetime 数据计算的续航。", 11f, muted), 18)
        if (!fuelLoaded && !fuelLoading) loadFuelRecords()
    }

    private fun editFuelRecord(existing: FuelRecord?) {
        var selectedDate = existing?.let { LocalDate.ofEpochDay(it.dateEpochDay) } ?: LocalDate.now()
        val form = column().apply { setPadding(dp(22), dp(4), dp(22), 0) }
        val dateButton = button(selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)) { }
        fun field(title: String, value: String): EditText {
            add(form, label(title, 12f, muted, true), 12)
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(value)
                form.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        add(form, label("日期", 12f, muted, true), 12)
        add(form, dateButton, 4)
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                dateButton.text = selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).show()
        }
        val suggestedOdometer = existing?.odometerKm ?: if (state.odometerDisplayEnabled)
            floor(currentMileageEstimate().distanceM / 1000.0)
        else fuelRecords.maxOfOrNull { it.odometerKm }
        val odometer = field("车辆里程（km）", suggestedOdometer?.let { fmt("%.0f", it) } ?: "")
        val litres = field("加油量（L，可暂不填写）", existing?.let {
            if (it.litres > 0.0) fmt("%.2f", it.litres) else ""
        } ?: "")
        val cost = field("花费（元，可暂不填写）", existing?.let {
            if (!it.draft || it.cost > 0.0) fmt("%.2f", it.cost) else ""
        } ?: "")
        val notFull = CheckBox(this).apply {
            text = "未满（本次只记录，等下次加满后再计算区间油耗）"
            textSize = 13f
            isChecked = existing?.fullTank == false
        }
        add(form, notFull, 10)
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "记录加油" else "编辑加油记录")
            .setView(form).setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .apply { if (existing != null) setNeutralButton("删除", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val km = odometer.text.toString().replace(',', '.').toDoubleOrNull()
                val amount = litres.text.toString().replace(',', '.').toDoubleOrNull()
                val paid = cost.text.toString().replace(',', '.').toDoubleOrNull()
                if (km == null || km < 0 || (amount != null && amount < 0) || (paid != null && paid < 0)) {
                    toast("请填写有效的车辆里程；加油量和花费可留空")
                } else {
                    val draft = amount == null || amount <= 0.0 || paid == null
                    saveFuelRecord(FuelRecord(existing?.id ?: 0, selectedDate.toEpochDay(), km,
                        amount ?: 0.0, paid ?: 0.0, !notFull.isChecked, draft))
                    dialog.dismiss()
                }
            }
            if (existing != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this).setTitle("删除这条加油记录？")
                    .setMessage("删除后将重新计算总平均油耗和曲线。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> deleteFuelRecord(existing.id); dialog.dismiss() }.show()
            }
        }
        dialog.show()
    }

    private fun saveFuelRecord(record: FuelRecord) {
        io.execute {
            val ok = try { fuelDatabase.save(record) != -1L } catch (_: RuntimeException) { false }
            val result = if (ok) try { fuelDatabase.allRecords() } catch (_: RuntimeException) { null } else null
            runOnUiThread { if (!isDestroyed) { if (result != null) { fuelRecords = result; fuelLoaded = true; if (tab == 2) showTab(2) } else toast("保存失败，请重试") } }
        }
    }

    private fun deleteFuelRecord(id: Long) {
        io.execute {
            val result = try { fuelDatabase.delete(id); fuelDatabase.allRecords() } catch (_: RuntimeException) { null }
            runOnUiThread { if (!isDestroyed) { if (result != null) { fuelRecords = result; fuelLoaded = true; if (tab == 2) showTab(2) } else toast("删除失败，请重试") } }
        }
    }

    private fun loadFuelRecords() {
        if (fuelLoading || isDestroyed) return
        fuelLoading = true
        io.execute {
            val result = try { fuelDatabase.allRecords() } catch (_: RuntimeException) { null }
            runOnUiThread {
                fuelLoading = false
                if (!isDestroyed && result != null) {
                    fuelRecords = result; fuelLoaded = true
                    if (tab == 2) showTab(2)
                    else refreshHome()
                }
            }
        }
    }
    private fun settings() {
        val body = page("设置", "连接、显示与仪表功能")
        val vehicleDisplay = card(body, "我的车辆")
        add(vehicleDisplay, label(state.vehicleDisplayName, 18f, ink, true), 10)
        add(vehicleDisplay, label(state.selectedVehicleModel.title, 13f, muted), 5)
        add(vehicleDisplay, button("车辆设置") { vehicleSettingsPage() }, 8)
        add(vehicleDisplay, label("在车辆设置中修改首页名称、车型和自动加油识别阈值。", 12f, muted), 8)
        add(vehicleDisplay, button("自定义车牌") {
            startActivity(Intent(this, LicensePlateGeneratorActivity::class.java))
        }, 10)
        add(vehicleDisplay, label(
            "输入完整 7 位车牌号后，可将生成的蓝牌透视安装在首页当前车型的前保险杠牌照位。",
            12f,
            muted,
        ), 8)
        val mileage = currentMileageEstimate()
        val mileageCard = card(body, "里程估算")
        add(mileageCard, label("${fmt("%.1f", mileage.distanceM / 1000.0)} km" +
            if (mileage.calibrated) "  ·  已校准" else "  ·  按已同步行程估算", 18f, ink, true), 10)
        add(mileageCard, Switch(this).apply {
            text = "在仪表设备信息页和 App 首页显示里程"
            textSize = 14f
            isChecked = state.odometerDisplayEnabled
            isEnabled = state.address.isNotBlank()
            setOnCheckedChangeListener { _, checked ->
                val started = TripSyncService.setOdometerDisplay(this@MainActivity, checked)
                if (::odometerText.isInitialized) {
                    odometerText.visibility = if (checked) View.VISIBLE else View.GONE
                }
                toast(if (started) "里程显示设置已排队同步" else "设置已保存，连接仪表后自动同步")
            }
        }, 10)
        add(mileageCard, button("手动校准里程") { calibrateMileage() }.apply {
            isEnabled = state.address.isNotBlank()
        }, 10)
        add(mileageCard, label(
            "校准前，里程为已记录行程的累计估算；校准后，App 与仪表使用同一当前里程并分别累加后续行程。显示开关和校准命令只在正常数据同步空闲时发送。",
            12f, muted), 8)
        val homeTrips = card(body, "首页行程")
        add(homeTrips, Switch(this).apply {
            text = "显示上次加油以来"
            textSize = 14f
            isChecked = state.showSinceRefuelTrip
            setOnCheckedChangeListener { _, checked ->
                state.showSinceRefuelTrip = checked
                toast(if (checked) "首页将显示上次加油以来" else "首页已隐藏上次加油以来")
            }
        }, 10)
        add(homeTrips, Switch(this).apply {
            text = "显示自定义行程"
            textSize = 14f
            isChecked = state.showCustomTrip
            setOnCheckedChangeListener { _, checked ->
                state.showCustomTrip = checked
                if (checked) state.customTrip(state.vehicle())
                toast(if (checked) "首页将显示自定义行程" else "首页已隐藏自定义行程")
            }
        }, 6)
        val customControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        customControls.addView(button("修改名称") { showCustomTripNameDialog(reset = false) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        customControls.addView(button("重置并归档") {
            val vehicle = state.vehicle()
            if (vehicle == null) toast("尚未收到车辆数据")
            else showCustomTripNameDialog(reset = true, vehicle = vehicle)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        add(homeTrips, customControls, 8)
        add(homeTrips, button("查看自定义行程与历史") { showCustomTripDetails() }, 8)
        add(homeTrips, label("重置前会把当前自定义区间和名称保存到手机历史；新名称可使用预设或最多 8 个字符的自定义输入。名称不发送到仪表，两个开关仅控制首页显示。", 12f, muted), 8)
        val binding = card(body, "我的仪表")
        add(binding, label(state.address.ifEmpty { "尚未绑定" }, 18f, ink, true), 10)
        add(binding, button(if (state.address.isEmpty()) "搜索并绑定仪表" else "更换 / 重新绑定") { permissions(true) }, 10)
        appUpdateCard(body)
        firmwareUpdateCard(body)
        val gaugeSettings = card(body, "仪表设置")
        val settingsAt = state.gaugeSettingsAt
        add(gaugeSettings, label(
            state.firmwareVersion?.let { version ->
                "仪表固件  v$version" + (state.firmwareBuildTag?.let { "  ·  $it" } ?: "")
            } ?: "仪表固件  等待连接后读取",
            16f, if (state.firmwareVersion == null) muted else ink, true), 10)
        add(gaugeSettings, label(
            if (state.gaugeSettings() != null && settingsAt > 0L)
                "已收到仪表设置 · ${date(settingsAt)}"
            else "尚未收到仪表设置",
            14f, ink, true), 10)
        add(gaugeSettings, label("连接仪表后自动读取当前参数。目前可调整日间亮度，其余仪表参数保持冻结；车型与自动加油识别阈值已移至“我的车辆 → 车辆设置”。", 12f, muted), 8)
        add(gaugeSettings, button("查看仪表设置") { gaugeSettingsPage() }, 10)
        val auto = card(body, "开机自启与自动连接")
        add(auto, Switch(this).apply {
            text = "开机自启 / 自动连接"; textSize = 14f; isChecked = state.automatic
            setOnCheckedChangeListener { _, checked ->
                state.automatic = checked
                if (checked) {
                    observePresence()
                    BackgroundBleWake.register(this@MainActivity, force = true)
                    ServiceWatchdogReceiver.schedule(this@MainActivity)
                    permissions(false)
                } else {
                    BackgroundBleWake.cancel(this@MainActivity)
                    ServiceWatchdogReceiver.cancel(this@MainActivity)
                    stopService(Intent(this@MainActivity, TripSyncService::class.java))
                    stopPresence()
                }
            }
        }, 10)
        add(auto, label("开启后，手机重启并首次解锁时自动恢复后台服务；覆盖升级后也会恢复。不自动弹出首页，发现仪表后自动连接授时。首次安装须先打开一次并完成绑定、授权。", 12f, muted), 10)
        add(auto, button("重新注册开机与仪表唤醒") {
            state.automatic = true
            val scanReady = BackgroundBleWake.register(this, force = true)
            val presenceReady = GaugePresenceObserver.start(this)
            ServiceWatchdogReceiver.schedule(this, 60_000L)
            val serviceReady = TripSyncService.start(this, reason = "用户重新注册后台唤醒")
            toast("BLE 唤醒${if (scanReady) "已注册" else "未注册"}；系统伴生唤醒${if (presenceReady) "已启用" else "使用兼容模式"}；后台服务${if (serviceReady) "已请求启动" else "被系统限制"}")
        }, 8)
        val broadcastAt = state.prefs.getLong("autostart_broadcast_at", 0)
        if (broadcastAt > 0) add(auto, label(
            "最近收到系统启动广播：${date(broadcastAt)}\n${state.prefs.getString("autostart_result", "等待处理")}",
            12f, muted), 8)
        val autoAt = state.prefs.getLong("autostart_at", 0)
        if (autoAt > 0) add(auto, label("上次自启触发：${date(autoAt)}（${state.prefs.getString("autostart_reason", "")}）\n" +
            if (state.prefs.getBoolean("autostart_requested", false)) "已请求启动；连接情况请查看首页" else "系统未允许启动，请检查后台权限", 12f, muted), 8)
        add(auto, label("仪表上电 → 系统收到广播并唤醒 App → 按绑定地址直接连接 → 优先授时 → 回读校验 → 同步行程。连接中每 10 分钟重新校时，断线后自动重连。", 13f, muted), 10)
        val watchdogAt = state.prefs.getLong("watchdog_at", 0)
        add(auto, label(if (watchdogAt > 0L) "后台自检最近运行：${date(watchdogAt)}" else "后台自检将在启用后每15分钟检查一次", 12f, muted), 8)
        val health = card(body, "后台运行检查")
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val notifications = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        val wakeScanAt = state.prefs.getLong("wake_scan_at", 0)
        val wakeAt = state.prefs.getLong("wake_at", 0)
        val serviceStartedAt = state.prefs.getLong("service_started_at", 0)
        val serviceFailureAt = state.prefs.getLong("service_start_failed_at", 0)
        add(health, label("附近设备权限：" + (if (TripSyncService.hasPermissions(this)) "已授权" else "未授权") +
            "\n通知：" + (if (notifications) "已开启" else "未开启") +
            "\n系统电池优化：" + (if (power.isIgnoringBatteryOptimizations(packageName)) "已豁免" else "仍启用") +
            "\n系统伴生唤醒：" + state.prefs.getString("presence", "尚未设置") +
            "\n系统 BLE 唤醒：" + state.prefs.getString("wake_scan_status", "尚未设置") +
            (if (wakeScanAt > 0) "（${date(wakeScanAt)}）" else "") +
            (if (wakeAt > 0) "\n上次被仪表唤醒：${date(wakeAt)}（${state.prefs.getString("wake_reason", "")}）" else "") +
            (if (serviceStartedAt > 0) "\n后台服务实际启动：${date(serviceStartedAt)}（${state.prefs.getString("service_start_reason", "")}）" else "") +
            (if (serviceFailureAt > serviceStartedAt) "\n最近启动被系统拒绝：${date(serviceFailureAt)}（${state.prefs.getString("service_start_error", "unknown")}）" else ""), 14f), 12)
        add(health, button("打开应用权限与后台设置") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }, 10)
        if (Build.VERSION.SDK_INT in 29..30) {
            val granted = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            add(health, label("后台蓝牙扫描定位权限：" + if (granted) "已允许" else "未允许，可能只在前台发现仪表", 12f, muted), 10)
            add(health, button("允许后台扫描（定位：始终允许）") {
                if (Build.VERSION.SDK_INT == 29) requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 102)
                else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }, 4)
        }
        add(health, button(if (power.isIgnoringBatteryOptimizations(packageName)) "已允许忽略电池优化" else "请求忽略电池优化") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }, 4)
        add(health, button("打开鸿蒙 / 华为应用启动管理") { openAutostartSettings() }, 4)
        add(health, label("鸿蒙 / 华为手机：在应用启动管理中允许自启动、关联启动及后台活动，必要时锁定后台任务。系统强制停止或禁止后台运行后，无法保证上车自动连接。手机端仅支持可运行 APK 的系统。", 12f, muted), 12)
        val estimate = card(body, "续航估算")
        add(estimate, label("油箱容量 ${fmt("%.1f", state.tankLitres)} L", 18f, ink, true), 10)
        add(estimate, button("校准油箱容量") {
            val entry = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(state.tankLitres.toString())
            }
            AlertDialog.Builder(this).setTitle("油箱容量（升）").setView(entry)
                .setMessage("默认按 50 L 估算，可按车辆规格校准。不会修改车辆或仪表数据。")
                .setPositiveButton("保存") { _, _ ->
                    val litres = entry.text.toString().toDoubleOrNull()
                    if (litres != null && litres in 1.0..150.0) { state.tankLitres = litres; showTab(3) }
                    else toast("请输入 1～150 L")
                }.setNegativeButton("取消", null).show()
        }, 10)
        add(estimate, label("续航估算油耗来源", 14f, ink, true), 16)
        val sourceGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val sourceById = linkedMapOf<Int, RangeConsumptionSource>()
        RangeConsumptionSource.entries.forEach { source ->
            val option = RadioButton(this).apply {
                id = View.generateViewId()
                text = source.title
                textSize = 14f
                isChecked = source == state.rangeConsumptionSource
            }
            sourceById[option.id] = source
            sourceGroup.addView(option)
        }
        sourceGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = sourceById[checkedId] ?: return@setOnCheckedChangeListener
            if (selected != state.rangeConsumptionSource) {
                state.rangeConsumptionSource = selected
                showTab(3)
            }
        }
        add(estimate, sourceGroup, 5)
        if (state.rangeConsumptionSource != RangeConsumptionSource.FUEL_RECORDS) {
            add(estimate, button("续航修正系数  ${fmt("%.2f", state.rangeCorrectionFactor)}") {
                val entry = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(fmt("%.2f", state.rangeCorrectionFactor))
                    selectAll()
                }
                AlertDialog.Builder(this).setTitle("续航油耗修正系数").setView(entry)
                    .setMessage("仅在续航计算时用所选油耗乘此系数；页面显示的油耗保持原值。默认 1.06。")
                    .setPositiveButton("保存") { _, _ ->
                        val factor = entry.text.toString().replace(',', '.').toDoubleOrNull()
                        if (factor != null && factor in 0.5..2.0) {
                            state.rangeCorrectionFactor = factor
                            showTab(3)
                        } else toast("请输入 0.50～2.00")
                    }.setNegativeButton("取消", null).show()
            }, 6)
        }
        add(estimate, label("最近五次按五次行程的总耗油量 ÷ 总里程计算；仪表历史使用仪表累计油耗；加油记录使用已完成加满区间的历史平均。修正系数只用于前两种续航计算。", 12f, muted), 9)
        add(estimate, button(if (state.manualFuelPercent == null) "设置当前剩余油量" else "重新校准当前剩余油量") {
            val entry = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                state.manualFuelPercent?.let { setText(fmt("%.1f", it)) }
            }
            AlertDialog.Builder(this).setTitle("当前剩余油量（%）").setView(entry)
                .setMessage("当车辆不支持 OBD PID 01 2F 时，App 会从此油量扣除仪表统计的燃油消耗。每次加油后重新校准即可。")
                .setPositiveButton("保存") { _, _ ->
                    val percent = entry.text.toString().replace(',', '.').toDoubleOrNull()
                    if (percent != null && percent in 0.0..100.0) {
                        state.setManualFuel(percent, state.vehicle()); showTab(0)
                    } else toast("请输入 0～100")
                }.setNegativeButton("取消", null).show()
        }, 8)
        if (state.manualFuelPercent != null) add(estimate, button("清除 App 油量估算") {
            state.clearManualFuel(); showTab(3)
        }, 6)
        add(estimate, label("优先读取车辆 PID 01 2F；若车型不输出油箱液位，可人工校准一次，之后按累计耗油量递减。", 12f, muted), 10)
        add(body, label("BRZ Garage ${BuildConfig.VERSION_NAME} · 本地优先\n保留原有历史数据库。不将车辆数据上传云端，不控制车锁或发动机。", 12f, muted), 20)
        resumeAppUpdateDownloadPolling()
    }
    private fun vehicleSettingsPage() {
        rememberCurrentScroll()
        showingTripDetail = false
        showingRefuelDetail = false
        showingCustomTripDetail = false
        showingGaugeSettings = false
        showingVehicleSettings = true
        tab = 3
        content.removeAllViews()
        navigation.removeAllViews()
        navigation.visibility = View.GONE
        val body = page("车辆设置", "首页名称、车型与自动加油识别")
        add(body, button("‹ 返回设置") { showTab(3) }, 18)

        val name = card(body, "首页车辆名称")
        add(name, label(state.vehicleDisplayName, 18f, ink, true), 10)
        add(name, button("修改首页车辆名称") { showVehicleDisplayNameDialog() }, 8)
        add(name, label(
            "默认名称：$DEFAULT_VEHICLE_DISPLAY_NAME。最多 $MAX_VEHICLE_DISPLAY_NAME_LENGTH 个字符，仅保存在手机本地。",
            12f,
            muted,
        ), 8)

        val settings = state.gaugeSettings()
        val model = card(body, "车型设置")
        editableVehicleModel(model, settings?.vehicleProfile)

        val refuel = card(body, "自动加油识别")
        editableRefuelThreshold(refuel, settings?.refuelThresholdMl)
        add(refuel, label(
            if (settings == null) "等待仪表设置数据；车型仍可先保存在手机，识别阈值需连接支持该功能的仪表后调整。"
            else "仪表设置读取时间：${date(state.gaugeSettingsAt)}",
            11f,
            muted,
        ), 5)
    }
    private fun showVehicleDisplayNameDialog() {
        val entry = EditText(this).apply {
            setText(state.vehicleDisplayName)
            selectAll()
            isSingleLine = true
            maxLines = 1
            filters = arrayOf(InputFilter.LengthFilter(MAX_VEHICLE_DISPLAY_NAME_LENGTH))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this).setTitle("首页车辆名称").setView(entry)
            .setMessage("最多 $MAX_VEHICLE_DISPLAY_NAME_LENGTH 个字符，仅保存在手机本地；换行会自动移除。")
            .setPositiveButton("保存") { _, _ ->
                state.vehicleDisplayName = entry.text.toString()
                vehicleSettingsPage()
            }.setNegativeButton("取消", null).show()
    }
    private fun appUpdateCard(body: LinearLayout) {
        val update = card(body, "手机 App 更新")
        val download = AppUpdater.downloadState(this)
        val statusMessage = when {
            appUpdateChecking -> "正在连接 GitHub 检查最新版本…"
            appUpdateCheckError != null -> appUpdateCheckError!!
            download.status in setOf(AppDownloadStatus.DOWNLOADING,
                AppDownloadStatus.READY, AppDownloadStatus.FAILED) -> download.message
            appUpdateCheck != null -> appUpdateCheck!!.message
            download.status != AppDownloadStatus.NONE -> download.message
            else -> "尚未检查更新"
        }
        add(update, label(
            "当前版本：v${BuildConfig.VERSION_NAME}\n$statusMessage",
            15f,
            if (download.status == AppDownloadStatus.FAILED || appUpdateCheckError != null) accent else ink,
            true
        ), 8)
        if (download.status == AppDownloadStatus.DOWNLOADING) {
            add(update, label(
                if (download.progress > 0) "下载进度：${download.progress}%"
                else "下载进度：正在获取安装包大小…",
                13f,
                ink,
                true,
            ), 8)
            update.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = download.progress
                isIndeterminate = download.progress <= 0
                progressTintList = android.content.res.ColorStateList.valueOf(accent)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.rgb(224, 227, 232))
                indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(6) })
        }
        add(update, button(if (appUpdateChecking) "正在检查…" else "检查 App 最新版本") {
            checkAppUpdate()
        }.apply {
            isEnabled = !appUpdateChecking && download.status != AppDownloadStatus.DOWNLOADING
        }, 10)
        val downloadable = when {
            appUpdateCheck?.release?.apkUrl != null -> appUpdateCheck?.release
            download.status == AppDownloadStatus.FAILED -> download.release
            else -> null
        }
        if (downloadable?.apkUrl != null && download.status != AppDownloadStatus.READY) {
            add(update, button(if (download.status == AppDownloadStatus.FAILED)
                "重新下载 v${downloadable.version}" else "下载并安装 v${downloadable.version}") {
                confirmAppUpdateDownload(downloadable)
            }.apply { isEnabled = download.status != AppDownloadStatus.DOWNLOADING }, 5)
        }
        if (download.status == AppDownloadStatus.READY) {
            add(update, button("安装已下载的 v${download.release?.version ?: "新版本"}") {
                requestInstallDownloadedUpdate()
            }, 5)
        }
        val release = appUpdateCheck?.release ?: download.release
        if (release != null && release.htmlUrl.isNotBlank()) {
            add(update, button("查看 GitHub Release") {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(release.htmlUrl)))
            }, 5)
        }
        add(update, label(
            "从本项目 GitHub Release 检查更新（包含测试版）。下载完成后会校验包名、版本以及与当前 App 相同的签名，再交给 Android 系统安装；覆盖安装会保留行程和加油记录。",
            12f, muted), 9)
    }
    private fun checkAppUpdate() {
        if (appUpdateChecking) return
        appUpdateChecking = true
        appUpdateCheckError = null
        if (tab == 3 && !showingGaugeSettings && !showingVehicleSettings) showTab(3)
        AppUpdater.checkAsync(this) { result ->
            if (isFinishing || isDestroyed) return@checkAsync
            appUpdateChecking = false
            result.onSuccess {
                appUpdateCheck = it
                appUpdateCheckError = null
            }.onFailure {
                appUpdateCheck = null
                appUpdateCheckError = "检查失败：${it.message ?: "网络不可用"}"
            }
            if (tab == 3 && !showingGaugeSettings && !showingVehicleSettings) showTab(3)
        }
    }
    private fun confirmAppUpdateDownload(release: AppRelease) {
        AlertDialog.Builder(this)
            .setTitle("下载 BRZ Garage v${release.version}")
            .setMessage("安装包将从本项目 GitHub Release 下载。下载完成后 App 会先校验版本和签名，再打开 Android 系统安装界面。\n\n请使用稳定网络；覆盖安装会保留现有行程、加油记录和设置。")
            .setPositiveButton("下载") { _, _ ->
                try {
                    AppUpdater.startDownload(this, release)
                    appUpdatePromptedDownloadId = -1L
                    toast("已交给系统下载")
                    showTab(3)
                    resumeAppUpdateDownloadPolling()
                } catch (error: RuntimeException) {
                    toast("无法开始下载：${error.message ?: "系统错误"}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    private fun resumeAppUpdateDownloadPolling() {
        if (appUpdatePollScheduled || isFinishing || isDestroyed) return
        if (AppUpdater.downloadState(this).status != AppDownloadStatus.DOWNLOADING) return
        appUpdatePollScheduled = true
        handler.postDelayed(appUpdatePoll, 800L)
    }
    private fun refreshAppUpdateDownload() {
        if (isFinishing || isDestroyed) return
        val snapshot = AppUpdater.downloadState(this)
        val changed = snapshot.status != appUpdateLastStatus || snapshot.progress != appUpdateLastProgress
        appUpdateLastStatus = snapshot.status
        appUpdateLastProgress = snapshot.progress
        if (changed && tab == 3 && !showingGaugeSettings && !showingVehicleSettings) showTab(3)
        if (snapshot.status == AppDownloadStatus.DOWNLOADING) {
            if (!appUpdatePollScheduled) {
                appUpdatePollScheduled = true
                handler.postDelayed(appUpdatePoll, 1000L)
            }
        } else if (snapshot.status == AppDownloadStatus.READY &&
            snapshot.downloadId != appUpdatePromptedDownloadId && tab == 3 &&
            !showingGaugeSettings && !showingVehicleSettings) {
            appUpdatePromptedDownloadId = snapshot.downloadId
            AlertDialog.Builder(this)
                .setTitle("App 更新已下载")
                .setMessage("v${snapshot.release?.version ?: "新版本"} 已完成版本和签名校验。是否现在打开 Android 系统安装界面？")
                .setPositiveButton("现在安装") { _, _ -> requestInstallDownloadedUpdate() }
                .setNegativeButton("稍后", null)
                .show()
        }
    }
    private fun requestInstallDownloadedUpdate() {
        try {
            when (AppUpdater.installDownloaded(this)) {
                AppInstallResult.STARTED -> Unit
                AppInstallResult.NEED_PERMISSION -> AlertDialog.Builder(this)
                    .setTitle("允许安装此来源的应用")
                    .setMessage("Android 需要你为 BRZ Garage 单独开启一次“安装未知应用”权限。该权限仅允许本 App 请求系统安装界面；下载的 APK 仍会先经过包名、版本和签名校验。")
                    .setPositiveButton("打开系统设置") { _, _ ->
                        appUpdateInstallAfterPermission = true
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName")))
                        } catch (_: ActivityNotFoundException) {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:$packageName")))
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        } catch (error: RuntimeException) {
            AlertDialog.Builder(this)
                .setTitle("无法安装更新")
                .setMessage(error.message ?: "安装包不可用，请重新检查并下载。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }
    private fun firmwareUpdateCard(body: LinearLayout) {
        val update = card(body, "仪表固件更新")
        val embedded = runCatching { EmbeddedFirmware.metadata(this) }.getOrNull()
        val assessment = embedded?.let { EmbeddedFirmware.assess(state.firmwareInfo(), it) }
        add(update, label(
            "仪表当前：${state.firmwareVersion?.let { "v$it" } ?: "等待连接后读取"}\n" +
                "App 内置：${embedded?.device?.version?.let { "v$it" } ?: "固件包不可用"}",
            15f, ink, true), 8)
        val active = state.firmwareUpdateActive
        val stageMessage = if (state.firmwareUpdateStage == "idle" && assessment != null)
            assessment.message else state.firmwareUpdateMessage
        add(update, label(stageMessage, 13f,
            if (state.firmwareUpdateStage in setOf("failed")) accent else muted, true), 8)
        add(update, label(
            "安全提示：检查更新和更新固件期间，仪表可能暂停 OBD 采集、行程记录与统计。请停车后操作，严禁在行驶中使用此功能。",
            12f, accent, true), 10)
        if (active || state.firmwareUpdateProgress > 0 && state.firmwareUpdateStage in setOf("complete", "failed")) {
            add(update, ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = state.firmwareUpdateProgress
                isIndeterminate = state.firmwareUpdateStage in setOf("preparing", "wifi")
            }, 6)
        }
        add(update, button("扫描是否需要更新") {
            if (!state.connected) {
                toast("请保持仪表上电，等待连接后再扫描")
            } else {
                AlertDialog.Builder(this)
                    .setTitle("确认已停车")
                    .setMessage("检查固件信息时，仪表可能暂时无法采集 OBD 数据或记录行程。请勿在行驶中操作。\n\n确认车辆已经停稳并处于安全位置后再继续。")
                    .setPositiveButton("已停车，开始检查") { _, _ ->
                        if (!TripSyncService.checkFirmware(this))
                            toast("无法启动固件扫描，请检查附近设备权限")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.apply { isEnabled = state.connected && !active }, 8)
        val canStart = state.connected && !active && state.firmwareUpdateStage == "available" &&
            assessment?.canUpdate == true
        add(update, button("手动进入 OTA 并更新") {
            AlertDialog.Builder(this)
                .setTitle("更新仪表固件")
                .setMessage("只有点击下方确认后，App 才会命令仪表进入一次性 OTA 模式；连接或扫描更新不会自动进入。\n\nOTA 期间仪表暂停 OBD 采集、行程记录和统计，请勿在行驶中操作。请确认车辆已经停稳、仪表供电稳定，并让 App 保持在前台。成功、失败、断开或等待超时后，仪表都会重启回正常模式。\n\n仅写入备用应用分区；NVS 设置、累计数据、未同步行程和开机媒体不会被擦除。校验失败不会切换启动分区。")
                .setPositiveButton("已停车，确认更新") { _, _ ->
                    requestFirmwareWifiPermissionAndStart(EmbeddedFirmware.LATEST_PACKAGE_ID)
                }
                .setNegativeButton("取消", null).show()
        }.apply { isEnabled = canStart }, 5)
        add(update, button("选择历史版本回滚") {
            showFirmwareRollbackPicker()
        }.apply { isEnabled = state.connected && !active }, 5)
        add(update, label(
            "回滚有风险：旧版本会缺少部分新功能；操作期间停止数据记录。请先完成行程同步，并确保车辆停稳、仪表持续供电。",
            12f, accent, true), 8)
        add(update, label(
            "安全机制：仅手动确认进入 OTA → 型号与屏幕强制匹配 → 64 KB 分块重试/续传 → 完整 SHA‑256 校验 → 写入并回读备用分区 → 必定重启退出 OTA；新固件启动异常时自动回滚。",
            11f, muted), 8)
    }
    private fun showFirmwareRollbackPicker() {
        val packages = runCatching { EmbeddedFirmware.historical(this) }.getOrElse {
            toast("历史固件清单不可用：${it.message ?: "格式错误"}")
            return
        }
        if (packages.isEmpty()) {
            toast("App 内暂无可用的历史固件")
            return
        }
        val labels = packages.map { "${it.displayName} · v${it.device.version}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择历史固件")
            .setItems(labels) { _, index -> confirmFirmwareRollback(packages[index]) }
            .setNegativeButton("取消", null)
            .show()
    }
    private fun confirmFirmwareRollback(firmware: EmbeddedFirmwareMetadata) {
        val assessment = EmbeddedFirmware.assessRollback(state.firmwareInfo(), firmware)
        if (!assessment.canUpdate || !assessment.updateAvailable) {
            AlertDialog.Builder(this)
                .setTitle("暂时不能回滚")
                .setMessage(assessment.message)
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("固件回滚风险提示")
            .setMessage("即将回滚至 v${firmware.device.version}，请确认你已了解以下风险：\n\n• 回滚后部分新功能会暂时不可用，界面和运行逻辑会恢复为旧版本。\n• OTA 期间会暂停 OBD 采集、行程记录和统计，严禁在行驶中操作。\n• 供电中断、手机离开或无线连接异常可能导致更新失败；仪表通常会保留原固件或自动回滚，但任何固件操作都不能保证绝对无风险。\n• 固件回滚不是数据恢复，无法找回已经删除或修改的数据。建议先等待行程全部同步完成。\n\n请保持车辆停稳、仪表持续供电，并让 App 留在前台。本数据安全版仅写入备用应用分区，不主动擦除 NVS、累计数据、里程、行程详情、未同步行程或开机媒体。")
            .setPositiveButton("已停车并了解风险，确认回滚") { _, _ ->
                requestFirmwareWifiPermissionAndStart(firmware.packageId)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    private fun requestFirmwareWifiPermissionAndStart(packageId: String) {
        firmwarePackagePendingPermission = packageId
        val wifiPermissions = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = wifiPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            updateAfterWifiPermission = true
            requestPermissions(missing.toTypedArray(), 103)
            return
        }
        startFirmwareUpdate()
    }
    private fun startFirmwareUpdate() {
        updateAfterWifiPermission = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!TripSyncService.updateFirmware(this, firmwarePackagePendingPermission)) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            toast("无法启动固件更新，请确认仪表已连接且权限完整")
        } else {
            toast("已进入安全更新流程，请保持仪表供电并让 App 留在前台")
            showTab(3)
        }
    }
    private fun gaugeSettingsPage() {
        rememberCurrentScroll()
        showingTripDetail = false
        showingRefuelDetail = false
        showingCustomTripDetail = false
        showingVehicleSettings = false
        showingGaugeSettings = true
        content.removeAllViews()
        navigation.removeAllViews()
        navigation.visibility = View.GONE
        val body = page("仪表设置", "查看仪表参数并调整日间亮度")
        add(body, button("‹ 返回连接与设置") { showTab(3) }, 18)
        val notice = card(body, "受限设置")
        add(notice, label("当前可由手机调整日间亮度，其余选择框、开关和输入框继续冻结。车型与自动加油识别阈值在“我的车辆 → 车辆设置”中调整；设置命令会等待车辆与行程同步空闲后发送，并由仪表回读确认。", 14f, accent, true), 10)
        val settings = state.gaugeSettings()
        val received = card(body, "同步状态")
        add(received, label(
            state.firmwareVersion?.let { "仪表固件版本：v$it" }
                ?: "仪表固件版本：等待仪表数据",
            14f, if (state.firmwareVersion == null) muted else accent, true), 10)
        add(received, label(if (settings == null) {
            if (state.address.isEmpty()) "尚未绑定仪表。当前先显示完整参数结构，绑定并连接后自动填入实际值。"
            else "等待仪表设置数据。当前先显示完整参数结构；请保持仪表上电并等待自动连接。"
        } else "已读取仪表设置\n读取时间：${date(state.gaugeSettingsAt)} · 协议版本：${settings.version}", 14f, ink, settings != null), 10)

        val basic = card(body, "基本设置")
        frozenChoice(basic, "OBD 协议", obdProtocolNames(), settings?.protocol)
        frozenValue(basic, "ELM327 名称", settings?.obdName?.ifEmpty { "未设置" })
        frozenValue(basic, "ELM327 MAC", settings?.obdMac?.let(::formatMac))
        frozenChoice(basic, "主题", listOf("DEFAULT", "AMBER", "OCEAN"), settings?.theme)
        editableBrightness(basic, settings?.brightness)
        frozenChoice(basic, "默认页面", gaugePageNames(), settings?.defaultPage)

        val warning = card(body, "提醒设置")
        frozenValue(warning, "转速黄线", settings?.rpmWarning?.let { "$it rpm" })
        frozenSwitch(warning, "转速闪烁", settings?.rpmWarnAnimation)
        frozenSwitch(warning, "多表联动闪烁", settings?.rpmWarnLinked)
        frozenValue(warning, "刹车温度提醒", settings?.brakeTempWarningC?.let { "$it °C" })
        frozenValue(warning, "机油压力提醒", settings?.oilPressureWarningX10?.let { fmt("%.1f bar", it / 10.0) })

        val display = card(body, "页面显示")
        val itemNames = displayItemNames()
        repeat(3) { index -> frozenChoice(display, "温度页项目 ${index + 1}", itemNames, settings?.tempDisplayItems?.getOrNull(index)) }
        repeat(5) { index -> frozenChoice(display, "信息页项目 ${index + 1}", itemNames, settings?.infoDisplayItems?.getOrNull(index)) }
        frozenChoice(display, "指针页数据", itemNames, settings?.needleItem)
        frozenChoice(display, "图表页数据", itemNames, settings?.chartItem)

        val chart = card(body, "图表报警阈值")
        repeat(12) { index ->
            frozenValue(chart, displayItemName(index), settings?.chartAlarms?.getOrNull(index)?.let { formatChartAlarm(index, it) })
        }

        val other = card(body, "联动与启动")
        frozenChoice(other, "仪表角色", listOf("MASTER", "SLAVE", "STANDALONE"), settings?.deviceRole)
        frozenChoice(other, "仪表位置", listOf("第 1 表", "第 2 表", "第 3 表"), settings?.devicePosition?.takeIf { it in 1..3 }?.minus(1))
        frozenChoice(other, "联动开机动画", listOf("关闭", "RACE", "VIDEO"), settings?.introMode)
        frozenChoice(other, "启动画面", listOf("默认动画", "自定义图片", "视频动画"), settings?.bootMode)
        frozenSwitch(other, "RaceChrono 服务", settings?.raceChronoEnabled)
        frozenValue(other, "主表 MAC", settings?.masterMac?.let(::formatMac))
        frozenValue(other, "行程合并间隔", settings?.let { "${it.tripMergeMinutes} 分钟" + if (it.tripMergeLocked) "（固件锁定）" else "" })
    }
    private fun editableVehicleModel(parent: LinearLayout, gaugeProfile: Int?) {
        add(parent, label("车型", 12f, muted), 12)
        val selectedAtOpen = state.selectedVehicleModel
        var selected = selectedAtOpen
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        SupportedVehicleModel.entries.forEach { model ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = model
                text = model.title
                textSize = 14f
                isChecked = model == selectedAtOpen
            })
        }
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            selected = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? SupportedVehicleModel
                ?: selectedAtOpen
        }
        add(parent, group, 3)
        val pending = state.pendingVehicleProfile
        val gaugeModel = SupportedVehicleModel.fromProfileIndex(gaugeProfile)
        add(parent, label(when {
            pending != null -> "待同步到仪表：${state.selectedVehicleModel.title}"
            gaugeProfile == null -> "仪表当前车型：等待连接后读取"
            gaugeModel != null -> "仪表当前车型：${gaugeModel.title}"
            else -> "仪表当前为未开放配置（编号 $gaugeProfile）"
        }, 11f, if (pending != null) accent else muted), 5)
        add(parent, button("保存并同步车型") {
            val dispatched = TripSyncService.setVehicleModel(this, selected)
            toast(if (dispatched) {
                "${selected.title} 已保存，将在同步空闲后写入仪表"
            } else {
                "${selected.title} 已保存在手机，连接仪表后自动同步"
            })
            vehicleSettingsPage()
        }, 5)
        add(parent, label("切换后首页车辆图立即变化。ZC6 使用与 ZD8 相同的单路 OBD/PID 采集结构，并保留 FA20 专用机油温度请求；车型命令不会打断正在进行的授时、行程或车辆数据同步。", 11f, muted), 5)
    }
    private fun frozenChoice(parent: LinearLayout, name: String, choices: List<String>, selected: Int?) {
        add(parent, label(name, 12f, muted), 12)
        val entries: List<String>
        val position: Int
        when {
            selected == null -> { entries = listOf("等待仪表数据") + choices; position = 0 }
            selected in choices.indices -> { entries = choices; position = selected }
            else -> { entries = listOf("未知值 ($selected)") + choices; position = 0 }
        }
        add(parent, Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, entries)
            setSelection(position)
            isEnabled = false
            alpha = 0.82f
        }, 3)
    }
    private fun editableBrightness(parent: LinearLayout, value: Int?) {
        add(parent, label("日间亮度", 12f, muted), 12)
        val initial = (value ?: 100).coerceIn(10, 100)
        val canApply = value != null && state.connected
        var selected = initial
        val valueLabel = label(if (value == null) "等待仪表数据" else "$initial%", 16f,
            if (value == null) muted else ink, true)
        add(parent, valueLabel, 5)
        add(parent, SeekBar(this).apply {
            min = 10
            max = 100
            progress = initial
            isEnabled = canApply
            alpha = if (canApply) 1f else 0.55f
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    selected = progress.coerceIn(10, 100)
                    if (value != null) valueLabel.text = "$selected%"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }, 3)
        val apply = button("应用亮度到仪表") {
            if (TripSyncService.setBrightness(this, selected)) {
                toast("亮度 $selected% 已提交，将在当前同步完成后应用")
            } else {
                toast("仪表当前未连接，亮度没有修改")
                gaugeSettingsPage()
            }
        }.apply { isEnabled = canApply }
        add(parent, apply, 5)
        add(parent, label(when {
            value == null -> "连接仪表并读取当前设置后才能调整。"
            !state.connected -> "当前显示上次读取值；重新连接仪表后才能调整。"
            else -> "范围 10%～100%。拖动后点击应用，不会连续发送命令。"
        }, 11f, muted), 5)
    }
    private fun editableRefuelThreshold(parent: LinearLayout, valueMl: Int?) {
        add(parent, label("自动加油识别阈值", 12f, muted), 12)
        val initial = ((valueMl ?: 10_000) / 1000).coerceIn(5, 20)
        val canApply = valueMl != null && state.connected
        var selected = initial
        val valueLabel = label(if (valueMl == null) "等待支持此设置的仪表固件" else "$initial L", 16f,
            if (valueMl == null) muted else ink, true)
        add(parent, valueLabel, 5)
        add(parent, SeekBar(this).apply {
            min = 5
            max = 20
            progress = initial
            isEnabled = canApply
            alpha = if (canApply) 1f else 0.55f
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    selected = progress.coerceIn(5, 20)
                    valueLabel.text = "$selected L"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, 3)
        val apply = button("应用加油识别阈值") {
            if (TripSyncService.setRefuelThreshold(this, selected)) {
                toast("阈值 $selected L 已提交，将在当前同步完成后应用")
            } else {
                toast("仪表当前未连接，阈值没有修改")
                vehicleSettingsPage()
            }
        }.apply { isEnabled = canApply }
        add(parent, apply, 5)
        add(parent, label(when {
            valueMl == null -> "需要配套新固件；升级后默认值为 10 L。"
            !state.connected -> "当前显示上次读取值；重新连接仪表后才能调整。"
            else -> "范围 5～20 L，每次 1 L。油位上升达到阈值并连续确认两次后，才自动建立加油节点。"
        }, 11f, muted), 5)
    }
    private fun frozenSwitch(parent: LinearLayout, name: String, value: Boolean?) {
        add(parent, Switch(this).apply {
            text = name + "  ·  " + when (value) { true -> "开启"; false -> "关闭"; null -> "等待仪表数据" }
            textSize = 14f
            isChecked = value == true
            isEnabled = false
            alpha = 0.82f
        }, 12)
    }
    private fun frozenValue(parent: LinearLayout, name: String, value: String?) {
        add(parent, label(name, 12f, muted), 12)
        add(parent, EditText(this).apply {
            setText(value ?: "等待仪表数据")
            textSize = 15f
            setTextColor(if (value == null) muted else ink)
            isEnabled = false
            isFocusable = false
            alpha = 0.82f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }, 3)
    }
    private fun valueName(index: Int, names: List<String>): String =
        names.getOrNull(index) ?: "未知 ($index)"
    private fun formatMac(bytes: ByteArray): String = if (bytes.all { it == 0.toByte() }) "未绑定"
        else bytes.joinToString(":") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
    private fun displayItemNames() = listOf(
        "CLT 水温", "IAT 进气温度", "OIL 机油温度", "LOD 发动机负荷",
        "TPS 节气门", "RPM 转速", "SPD 车速", "BAT 电压",
        "OIP 机油压力", "BKT 刹车温度", "BST 增压压力", "AFR 空燃比")
    private fun displayItemName(index: Int): String = valueName(index, displayItemNames())
    private fun formatChartAlarm(index: Int, raw: Int): String {
        if (raw == 32767) return "关闭"
        return when (index) {
            0, 1, 2 -> "$raw °C"
            3, 4 -> "$raw%"
            5 -> "$raw rpm"
            6 -> "$raw km/h"
            7 -> fmt("%.1f V", raw / 1000.0)
            8, 10 -> fmt("%.1f bar", raw / 10.0)
            9 -> fmt("%.1f °C", raw / 10.0)
            11 -> fmt("%.1f", raw / 100.0)
            else -> raw.toString()
        }
    }
    private fun gaugePageNames() = listOf("温度", "信息", "图表", "指针", "挡位 + 转速", "车速", "油量", "行程历史", "行程总览")
    private fun obdProtocolNames() = listOf("自动", "SAE J1850 PWM", "SAE J1850 VPW", "ISO 9141-2", "ISO 14230-4 KWP（慢）", "ISO 14230-4 KWP（快）", "CAN 11bit / 500k", "CAN 29bit / 500k", "CAN 11bit / 250k", "CAN 29bit / 250k")
    private fun permissions(bind: Boolean) {
        bindAfterPermission = bind
        val required = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
        else if (bind) bindGauge() else TripSyncService.start(this)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 103) {
            val wifiPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
                else Manifest.permission.ACCESS_FINE_LOCATION
            if (checkSelfPermission(wifiPermission) == PackageManager.PERMISSION_GRANTED) {
                if (updateAfterWifiPermission) startFirmwareUpdate()
            } else {
                updateAfterWifiPermission = false
                toast("需要附近 Wi‑Fi 权限才能连接仪表更新热点")
            }
            return
        }
        if (code != 100) return
        if (TripSyncService.hasPermissions(this)) {
            if (bindAfterPermission) bindGauge() else TripSyncService.start(this)
        } else toast("需要附近设备权限才能发现仪表")
    }
    private fun bindGauge() {
        val bluetooth = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bluetooth?.isEnabled != true) { toast("请先开启手机蓝牙，并让仪表保持上电"); return }
        val manager = getSystemService(CompanionDeviceManager::class.java)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) || manager == null) {
            fallbackPicker(); return
        }
        val request = AssociationRequest.Builder().addDeviceFilter(BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("^(SkyGauge|SkyGarageRC).*")).build()).setSingleDevice(false).build()
        try {
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    try { startIntentSenderForResult(chooserLauncher, 201, null, 0, 0, 0) }
                    catch (_: IntentSender.SendIntentException) { toast("无法打开系统设备选择器") }
                }
                override fun onAssociationPending(intentSender: IntentSender) = onDeviceFound(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    if (Build.VERSION.SDK_INT >= 33) associationInfo.deviceMacAddress?.toString()?.let { saveBinding(it) }
                }
                override fun onFailure(error: CharSequence?) { toast("系统绑定未完成，可重试。$error") }
            }, handler)
        } catch (_: RuntimeException) { fallbackPicker() }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 201 || resultCode != RESULT_OK) return
        val selected: Parcelable? = data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        when (selected) {
            is ScanResult -> saveBinding(selected.device.address)
            is BluetoothDevice -> saveBinding(selected.address)
        }
    }
    private fun saveBinding(address: String) {
        val previous = state.address
        if (previous != address) {
            BackgroundBleWake.cancel(this)
            stopPresence()
        }
        state.address = address
        state.automatic = true
        observePresence()
        BackgroundBleWake.register(this, force = true)
        ServiceWatchdogReceiver.schedule(this)
        TripSyncService.start(this, true)
        showTab(0)
    }
    private fun observePresence() {
        GaugePresenceObserver.start(this)
    }
    private fun stopPresence() {
        GaugePresenceObserver.stop(this)
    }
    private fun fallbackPicker() {
        val scanner = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner ?: return
        val devices = linkedMapOf<String, String>()
        val items = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val list = ListView(this).apply { adapter = items }
        val dialog = AlertDialog.Builder(this).setTitle("选择自己的 BRZ 仪表").setMessage("保持仪表上电，扫描最长 30 秒")
            .setView(list).setNegativeButton("取消", null).create()
        val scan = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: return
                if (!(name.startsWith("SkyGauge") || name.startsWith("SkyGarageRC"))) return
                devices[result.device.address] = "$name\n${result.device.address}"
                items.clear(); items.addAll(devices.values); items.notifyDataSetChanged()
            }
            override fun onScanFailed(errorCode: Int) { toast("扫描失败：$errorCode"); dialog.dismiss() }
        }
        val stop: () -> Unit = { try { scanner.stopScan(scan) } catch (_: RuntimeException) { } }
        fallbackStop = stop
        dialog.setOnDismissListener { stop(); fallbackStop = null }
        list.setOnItemClickListener { _, _, position, _ ->
            val address = devices.keys.elementAt(position); dialog.dismiss(); saveBinding(address)
        }
        dialog.show()
        try { scanner.startScan(scan) } catch (_: RuntimeException) { dialog.dismiss(); toast("无法扫描仪表") }
        handler.postDelayed({ stop(); if (dialog.isShowing && devices.isEmpty()) toast("未发现仪表，请检查供电和蓝牙") }, 30000)
    }
    private fun openAutostartSettings() {
        val candidates = listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        candidates.forEach { component ->
            try {
                startActivity(Intent().setComponent(component))
                return
            } catch (_: ActivityNotFoundException) { }
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun fmt(format: String, vararg args: Any) = String.format(Locale.getDefault(), format, *args)
    private fun date(ms: Long) = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    private fun duration(s: Long) = if (s >= 3600) "${s / 3600} 小时 ${s / 60 % 60} 分钟" else "${s / 60} 分钟"
}
