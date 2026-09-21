package com.brz.gauge.trips

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

/** GATT callbacks and operations share one handler; only one request is in flight. */
@Suppress("DEPRECATION", "MissingPermission")
class TripSyncService : Service() {
    companion object {
        const val ACTION_SYNC_NOW = "com.brz.gauge.trips.SYNC_NOW"
        const val ACTION_AUTOSTART = "com.brz.gauge.trips.AUTOSTART"
        const val ACTION_GAUGE_SIGNAL = "com.brz.gauge.trips.GAUGE_SIGNAL"
        const val ACTION_SET_BRIGHTNESS = "com.brz.gauge.trips.SET_BRIGHTNESS"
        const val ACTION_SET_REFUEL_THRESHOLD = "com.brz.gauge.trips.SET_REFUEL_THRESHOLD"
        const val ACTION_SET_VEHICLE_PROFILE = "com.brz.gauge.trips.SET_VEHICLE_PROFILE"
        const val ACTION_SET_ODOMETER_DISPLAY = "com.brz.gauge.trips.SET_ODOMETER_DISPLAY"
        const val ACTION_CALIBRATE_ODOMETER = "com.brz.gauge.trips.CALIBRATE_ODOMETER"
        const val ACTION_RESET_REFUEL_TRIP = "com.brz.gauge.trips.RESET_REFUEL_TRIP"
        const val ACTION_DELETE_REFUEL_NODE = "com.brz.gauge.trips.DELETE_REFUEL_NODE"
        const val ACTION_DISCARD_REFUEL_BEFORE_FIRST = "com.brz.gauge.trips.DISCARD_REFUEL_BEFORE_FIRST"
        const val ACTION_SYNC_CUSTOM_TRIP = "com.brz.gauge.trips.SYNC_CUSTOM_TRIP"
        const val ACTION_CHECK_FIRMWARE = "com.brz.gauge.trips.CHECK_FIRMWARE"
        const val ACTION_UPDATE_FIRMWARE = "com.brz.gauge.trips.UPDATE_FIRMWARE"
        const val ACTION_STATUS = "com.brz.gauge.trips.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_UPDATED = "updated"
        const val EXTRA_BRIGHTNESS = "brightness"
        const val EXTRA_REFUEL_THRESHOLD_ML = "refuel_threshold_ml"
        const val EXTRA_VEHICLE_PROFILE = "vehicle_profile"
        const val EXTRA_ODOMETER_DISPLAY = "odometer_display"
        const val EXTRA_REFUEL_NODE_ID = "refuel_node_id"
        const val EXTRA_FIRMWARE_PACKAGE_ID = "firmware_package_id"
        const val EXTRA_START_REASON = "start_reason"
        private const val CHANNEL = "brz_trip_sync"
        private const val LOW_FUEL_CHANNEL = "brz_low_fuel"
        private const val NOTIFICATION = 86
        private const val LOW_FUEL_NOTIFICATION = 87
        private const val LOW_FUEL_PREF_PREFIX = "low_fuel_notified_"
        private const val LOG_TAG = "BRZ-TripSync"
        private const val CONNECT_TIMEOUT_MS = 25000L
        private const val OPERATION_TIMEOUT_MS = 12000L
        private const val FIRMWARE_SCAN_TIMEOUT_MS = 30000L
        private const val GATT_GAP_MS = 400L
        private const val FOREGROUND_CYCLE_MS = 15000L
        private const val BACKGROUND_CYCLE_MS = 30000L
        private const val MAX_OPERATION_RETRIES = 3
        private const val OTA_READY_RETRIES = 30
        @Volatile
        var running = false
            private set
        var foregroundUi = false
        fun hasPermissions(context: Context): Boolean =
            (if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
             else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        fun start(context: Context, manual: Boolean = false, reason: String? = null): Boolean {
            val state = AppState(context)
            if (!state.automatic || state.address.isEmpty() || !hasPermissions(context)) return false
            return try {
                // Vendor Bluetooth stacks may reject a PendingIntent scan even
                // while the foreground GATT path is usable.  Keep registration
                // inside the same guarded boundary as service startup.
                BackgroundBleWake.register(context)
                context.startForegroundService(Intent(context, TripSyncService::class.java).apply {
                    action = if (manual) ACTION_SYNC_NOW else ACTION_AUTOSTART
                    reason?.let { putExtra(EXTRA_START_REASON, it) }
                })
                true
            } catch (error: RuntimeException) {
                state.prefs.edit().putLong("service_start_failed_at", System.currentTimeMillis())
                    .putString("service_start_error", error.javaClass.simpleName).apply()
                state.status("系统限制后台启动，请打开应用并允许后台运行", false)
                false
            }
        }
        fun wakeFromGaugeSignal(context: Context): Boolean {
            val state = AppState(context)
            if (!state.automatic || state.address.isEmpty() || !hasPermissions(context)) return false
            return try {
                context.startForegroundService(Intent(context, TripSyncService::class.java)
                    .setAction(ACTION_GAUGE_SIGNAL))
                true
            } catch (error: RuntimeException) {
                state.prefs.edit().putLong("wake_start_failed_at", System.currentTimeMillis())
                    .putString("wake_start_error", error.javaClass.simpleName).apply()
                state.status("已收到仪表信号，但系统限制后台启动；请允许后台运行", false)
                BackgroundBleWake.register(context, force = true)
                ServiceWatchdogReceiver.schedule(context, 60_000L)
                false
            }
        }
        fun setBrightness(context: Context, percent: Int): Boolean {
            if (percent !in 10..100) return false
            val state = AppState(context)
            if (!state.automatic || state.address.isEmpty() || !state.connected ||
                !hasPermissions(context)) return false
            return try {
                context.startForegroundService(Intent(context, TripSyncService::class.java).apply {
                    action = ACTION_SET_BRIGHTNESS
                    putExtra(EXTRA_BRIGHTNESS, percent)
                })
                true
            } catch (_: RuntimeException) { false }
        }
        fun setRefuelThreshold(context: Context, litres: Int): Boolean {
            if (litres !in 5..20) return false
            val state = AppState(context)
            if (!state.automatic || state.address.isEmpty() || !state.connected ||
                !hasPermissions(context)) return false
            return try {
                context.startForegroundService(Intent(context, TripSyncService::class.java).apply {
                    action = ACTION_SET_REFUEL_THRESHOLD
                    putExtra(EXTRA_REFUEL_THRESHOLD_ML, litres * 1000)
                })
                true
            } catch (_: RuntimeException) { false }
        }
        fun setVehicleModel(context: Context, model: SupportedVehicleModel): Boolean {
            val state = AppState(context)
            state.requestVehicleModel(model)
            if (!state.automatic || state.address.isEmpty() || !hasPermissions(context)) return false
            return try {
                context.startForegroundService(Intent(context, TripSyncService::class.java).apply {
                    action = ACTION_SET_VEHICLE_PROFILE
                    putExtra(EXTRA_VEHICLE_PROFILE, model.profileIndex)
                })
                true
            } catch (_: RuntimeException) { false }
        }
        fun setOdometerDisplay(context: Context, enabled: Boolean): Boolean {
            val state = AppState(context)
            state.requestOdometerDisplay(enabled)
            if (!state.automatic || state.address.isEmpty() || !hasPermissions(context)) return false
            return try {
                context.startForegroundService(Intent(context, TripSyncService::class.java).apply {
                    action = ACTION_SET_ODOMETER_DISPLAY
                    putExtra(EXTRA_ODOMETER_DISPLAY, enabled)
                })
                true
            } catch (_: RuntimeException) { false }
        }
        fun calibrateGaugeOdometer(context: Context, distanceM: Long): Boolean {
            val state = AppState(context)
            state.requestGaugeOdometerCalibration(distanceM)
            if (!state.automatic || state.address.isEmpty() || !hasPermissions(context)) return false
            return try {
                context.startForegroundService(Intent(context, TripSyncService::class.java)
                    .setAction(ACTION_CALIBRATE_ODOMETER))
                true
            } catch (_: RuntimeException) { false }
        }
        fun resetRefuelTrip(context: Context): Boolean {
            val state = AppState(context)
            state.requestRefuelReset()
            if (!state.automatic || state.address.isEmpty() || !hasPermissions(context)) return false
            return try {
                context.startForegroundService(Intent(context, TripSyncService::class.java)
                    .setAction(ACTION_RESET_REFUEL_TRIP))
                true
            } catch (_: RuntimeException) { false }
        }
        fun deleteRefuelNode(context: Context, nodeId: Long): Boolean {
            if (nodeId <= 0L) return false
            val state = AppState(context)
            state.requestRefuelDelete(nodeId)
            if (!state.automatic || state.address.isEmpty() || !hasPermissions(context)) return false
            return try {
                context.startForegroundService(Intent(context, TripSyncService::class.java).apply {
                    action = ACTION_DELETE_REFUEL_NODE
                    putExtra(EXTRA_REFUEL_NODE_ID, nodeId)
                })
                true
            } catch (_: RuntimeException) { false }
        }
        fun discardRefuelBeforeFirstNode(context: Context, oldestId: Long): Boolean {
            if (oldestId <= 0L) return false
            val state = AppState(context)
            state.requestRefuelDiscardOldest(oldestId)
            if (!state.automatic || state.address.isEmpty() || !hasPermissions(context)) return false
            return try {
                context.startForegroundService(Intent(context, TripSyncService::class.java).apply {
                    action = ACTION_DISCARD_REFUEL_BEFORE_FIRST
                    putExtra(EXTRA_REFUEL_NODE_ID, oldestId)
                })
                true
            } catch (_: RuntimeException) { false }
        }
        fun syncCustomTripBaseline(context: Context): Boolean {
            val state = AppState(context)
            if (state.customTripBaseline() == null || !state.automatic ||
                state.address.isEmpty() || !hasPermissions(context)) return false
            return try {
                context.startForegroundService(Intent(context, TripSyncService::class.java)
                    .setAction(ACTION_SYNC_CUSTOM_TRIP))
                true
            } catch (_: RuntimeException) { false }
        }
        fun checkFirmware(context: Context): Boolean = startFirmwareAction(context, ACTION_CHECK_FIRMWARE)
        fun updateFirmware(
            context: Context,
            packageId: String = EmbeddedFirmware.LATEST_PACKAGE_ID,
        ): Boolean = startFirmwareAction(context, ACTION_UPDATE_FIRMWARE, packageId)
        private fun startFirmwareAction(
            context: Context,
            actionName: String,
            packageId: String? = null,
        ): Boolean {
            val state = AppState(context)
            if (!state.automatic || state.address.isEmpty() || !hasPermissions(context)) return false
            return try {
                context.startForegroundService(Intent(context, TripSyncService::class.java).apply {
                    action = actionName
                    packageId?.let { putExtra(EXTRA_FIRMWARE_PACKAGE_ID, it) }
                })
                true
            } catch (_: RuntimeException) { false }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var state: AppState
    private lateinit var database: TripDatabase
    private lateinit var refuelDatabase: RefuelIntervalDatabase
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var info: BluetoothGattService? = null
    private var ota: BluetoothGattService? = null
    private var connectionWakeLock: PowerManager.WakeLock? = null
    private var notificationVehicle: VehicleState? = null
    private var scanning = false
    private var connected = false
    private var address = ""
    private var meta: TripBleProtocol.Meta? = null
    private var cursor = 0L
    private var control = 0
    private var refuelMeta: TripBleProtocol.RefuelMeta? = null
    private var refuelCursor = 0L
    private var refuelControl = 0
    private var refuelDeleteAwaitingRevision = false
    private var refuelDeleteVerifyAttempts = 0
    private var operation: UUID? = null
    private var operationPayload: ByteArray? = null
    private var operationRetries = 0
    private var operationIsOta = false
    private var mtuReady = false
    private var awaitingMtu = false
    private var busy = false
    private var lastHistoryMs = 0L
    private var lastClockMs = 0L
    private var retryMs = 2000L
    private var destroyed = false
    private var legacy = false
    private var settingsReadForConnection = false
    private var odometerReadForConnection = false
    private var refuelReadForConnection = false
    private var customTripSyncedForConnection = false
    private var customTripDeferredForConnection = false
    private var manifestReadForConnection = false
    /* Only reuse identity data that was read on this exact GATT connection.
     * Persisted AppState data may describe the previous firmware after an
     * update, so it is intentionally not used to satisfy a manual scan. */
    private var firmwareInfoForConnection: TripBleProtocol.FirmwareInfo? = null
    private var pendingBrightness: Int? = null
    private var brightnessWriteValue: Int? = null
    private var brightnessVerificationValue: Int? = null
    private var pendingRefuelThresholdMl: Int? = null
    private var refuelThresholdWriteMl: Int? = null
    private var refuelThresholdVerificationMl: Int? = null
    private var vehicleProfileWriteValue: Int? = null
    private var vehicleProfileVerificationValue: Int? = null
    private var vehicleProfileDeferredForConnection = false
    private var odometerDeferredForConnection = false
    private var odometerDisplayVerificationValue: Boolean? = null
    private var odometerCalibrationVerificationM: Long? = null
    private var linkGeneration = 0L
    private var bluetoothReceiverRegistered = false
    private val firmwareExecutor = Executors.newSingleThreadExecutor()
    private var pendingFirmwareScan = false
    private var pendingFirmwareUpdate = false
    private var pendingFirmwarePackageId = EmbeddedFirmware.LATEST_PACKAGE_ID
    private var otaSessionActive = false
    private var otaStartCommandAttempted = false
    private var otaStartWriteQueued = false
    private var otaReadyRetries = 0
    private var otaWifiWorkerRetries = 0
    private var otaWifiUploader: FirmwareWifiUploader? = null
    private var preparedFirmware: EmbeddedFirmwarePackage? = null
    private var expectedFirmwareVersion: String? = null
    private var otaAwaitingVerification = false
    private val timeout = Runnable {
        if (connected && operation != null) retryCurrentOperation("仪表响应较慢")
        else fail("连接超时，自动重试")
    }
    private val operationRetry = Runnable { issueOperation() }
    private val otaStatusPoll = Runnable { if (otaSessionActive && connected && operation == null) readOta(TripBleProtocol.OTA_STATUS) }
    private val firmwareScanTimeout = Runnable {
        if (pendingFirmwareScan) {
            finishManifestReadFailure("检查更新已超时")
        }
    }
    private val retry = Runnable { beginReconnect() }
    private val scanFallback = Runnable {
        if (scanning && !connected && !destroyed) {
            stopScan()
            startAutoConnect()
        }
    }
    private val autoConnectFallback = Runnable {
        if (!connected && gatt != null && !destroyed) {
            closeConnection()
            startScan()
        }
    }
    private val cycle = Runnable { nextCycle() }
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    BackgroundBleWake.register(this@TripSyncService, force = true)
                    handler.removeCallbacks(retry)
                    beginReconnect()
                }
                BluetoothAdapter.STATE_OFF -> fail("蓝牙已关闭；开启后自动恢复")
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        running = true
        state = AppState(this)
        database = TripDatabase(this)
        refuelDatabase = RefuelIntervalDatabase(this)
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        connectionWakeLock = try {
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "$packageName:GaugeSync"
            ).apply { setReferenceCounted(false) }
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Unable to create gauge synchronization wake lock", error)
            null
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL, "车辆连接与自动授时", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(LOW_FUEL_CHANNEL, "低油量提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "当预估剩余油量低于 25% 时提醒及时加油"
            },
        ))
        startForeground(NOTIFICATION, notification("等待仪表上电"))
        try { BackgroundBleWake.register(this) } catch (_: RuntimeException) { }
        try { ServiceWatchdogReceiver.schedule(this) } catch (_: RuntimeException) { }
        otaAwaitingVerification = state.firmwareUpdateStage == "verifying"
        expectedFirmwareVersion = state.firmwareUpdateTarget
        if (state.firmwareUpdateStage == "checking") {
            state.firmwareUpdate("failed", "上次检查被系统中断，请重新扫描；正常数据未受影响")
        }
        if (state.firmwareUpdateStage in setOf("preparing", "wifi", "uploading", "installing")) {
            state.firmwareUpdate("failed",
                "上次更新流程被系统中断；未确认的镜像不会切换启动分区，原数据仍保留",
                state.firmwareUpdateProgress, expectedFirmwareVersion)
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(bluetoothReceiver, filter)
            bluetoothReceiverRegistered = true
        } catch (_: RuntimeException) {
            bluetoothReceiverRegistered = false
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!state.automatic || !hasPermissions(this) || state.address.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        state.prefs.edit().putLong("service_started_at", System.currentTimeMillis())
            .putString("service_start_reason", intent?.getStringExtra(EXTRA_START_REASON)
                ?: when (intent?.action) {
                    ACTION_GAUGE_SIGNAL -> "仪表通电广播"
                    ACTION_SYNC_NOW -> "手动启动"
                    else -> "后台恢复"
                })
            .remove("service_start_error").apply()
        val gaugeSignalWake = intent?.action == ACTION_GAUGE_SIGNAL
        if (gatt != null && address != state.address) closeConnection()
        // During Wi-Fi upload the gauge deliberately releases Bluetooth. A
        // watchdog/service restart must not launch a competing BLE scan.
        if (gatt == null && !otaSessionActive) {
            if (address != state.address) stopScan()
            if (gaugeSignalWake) connectFromGaugeSignal() else beginReconnect()
        }
        if (gaugeSignalWake && connected && !busy) {
            lastClockMs = 0
            handler.removeCallbacks(cycle)
            nextCycle()
        } else if (intent?.action == ACTION_SET_BRIGHTNESS) {
            val requested = intent.getIntExtra(EXTRA_BRIGHTNESS, -1)
            if (connected && requested in 10..100) requestBrightness(requested)
            else publish("亮度未修改 · 请先连接仪表")
        } else if (intent?.action == ACTION_SET_REFUEL_THRESHOLD) {
            val requested = intent.getIntExtra(EXTRA_REFUEL_THRESHOLD_ML, -1)
            if (connected && requested in 5_000..20_000 && requested % 1_000 == 0) {
                requestRefuelThreshold(requested)
            } else {
                publish("自动加油识别阈值未修改 · 请先连接仪表")
            }
        } else if (intent?.action == ACTION_SET_VEHICLE_PROFILE) {
            val requested = intent.getIntExtra(EXTRA_VEHICLE_PROFILE, -1)
            val model = SupportedVehicleModel.fromProfileIndex(requested)
            if (model == null) {
                publish("车型未修改 · 不支持该车型")
            } else if (connected) {
                requestVehicleProfile(model.profileIndex)
            } else {
                publish("${model.title} 已保存在手机 · 连接仪表后自动同步")
            }
        } else if (intent?.action == ACTION_SET_ODOMETER_DISPLAY) {
            val enabled = intent.getBooleanExtra(EXTRA_ODOMETER_DISPLAY, state.odometerDisplayEnabled)
            if (connected) requestOdometerConfig(if (enabled) "里程显示已排队" else "隐藏里程已排队")
            else publish("里程显示设置已保存 · 连接仪表后自动同步")
        } else if (intent?.action == ACTION_CALIBRATE_ODOMETER) {
            if (connected) requestOdometerConfig("里程校准已排队")
            else publish("里程校准已保存 · 连接仪表后自动同步")
        } else if (intent?.action == ACTION_RESET_REFUEL_TRIP) {
            if (connected) requestRefuelReset()
            else publish("手动重置已排队 · 连接仪表后执行")
        } else if (intent?.action == ACTION_DELETE_REFUEL_NODE) {
            val nodeId = intent.getLongExtra(EXTRA_REFUEL_NODE_ID, 0L)
            if (nodeId > 0L && state.pendingRefuelDeleteId != nodeId) {
                state.requestRefuelDelete(nodeId)
            }
            if (connected) requestRefuelDelete()
            else publish("删除加油节点已排队 · 连接仪表后执行")
        } else if (intent?.action == ACTION_DISCARD_REFUEL_BEFORE_FIRST) {
            val nodeId = intent.getLongExtra(EXTRA_REFUEL_NODE_ID, 0L)
            if (nodeId > 0L && state.pendingRefuelDiscardOldestId != nodeId) {
                state.requestRefuelDiscardOldest(nodeId)
            }
            if (connected) requestRefuelDiscardOldest()
            else publish("删除第一个节点以前的数据已排队 · 连接仪表后执行")
        } else if (intent?.action == ACTION_SYNC_CUSTOM_TRIP) {
            customTripDeferredForConnection = false
            if (connected) requestCustomTripSync()
            else publish("自定义行程重置已保存 · 连接仪表后自动同步")
        } else if (intent?.action == ACTION_SYNC_NOW && connected && !busy) {
            lastClockMs = 0
            lastHistoryMs = 0
            handler.removeCallbacks(cycle)
            nextCycle()
        } else if (intent?.action == ACTION_CHECK_FIRMWARE) {
            pendingFirmwareScan = true
            handler.removeCallbacks(firmwareScanTimeout)
            handler.postDelayed(firmwareScanTimeout, FIRMWARE_SCAN_TIMEOUT_MS)
            state.firmwareUpdate("checking", "正在读取仪表固件与硬件信息…")
            publish("正在检查仪表固件更新…", true)
            if (connected && !busy && operation == null) {
                handler.removeCallbacks(cycle)
                startPendingFirmwareScan()
            }
        } else if (intent?.action == ACTION_UPDATE_FIRMWARE) {
            if (!connected) {
                state.firmwareUpdate("failed", "仪表未连接，未开始更新")
                publish("固件未更新 · 请先连接仪表", true)
            } else {
                pendingFirmwarePackageId = intent.getStringExtra(EXTRA_FIRMWARE_PACKAGE_ID)
                    ?: EmbeddedFirmware.LATEST_PACKAGE_ID
                pendingFirmwareUpdate = true
                state.firmwareUpdate("preparing", "等待当前授时与数据同步完成…")
                publish("固件更新已排队 · 等待当前数据同步完成", true)
                if (!busy && operation == null) {
                    handler.removeCallbacks(cycle)
                    continueAfterGattIdle { startPendingFirmwareUpdate() }
                }
            }
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (state.automatic) ServiceWatchdogReceiver.schedule(this, 60_000L)
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        destroyed = true
        running = false
        stopScan()
        closeConnection()
        otaWifiUploader?.cancel()
        otaWifiUploader = null
        firmwareExecutor.shutdownNow()
        if (state.firmwareUpdateStage == "checking") {
            state.firmwareUpdate("failed", "检查被中断，请重新扫描；正常数据未受影响")
        }
        if (state.firmwareUpdateStage in setOf("preparing", "wifi", "uploading", "installing")) {
            state.firmwareUpdate("failed", "更新被中断；原固件、设置和行程数据仍保留",
                state.firmwareUpdateProgress, expectedFirmwareVersion)
        }
        handler.removeCallbacksAndMessages(null)
        if (bluetoothReceiverRegistered) {
            try { unregisterReceiver(bluetoothReceiver) } catch (_: RuntimeException) { }
            bluetoothReceiverRegistered = false
        }
        database.close()
        refuelDatabase.close()
        state.status("自动连接服务未运行", false)
        if (state.automatic) {
            BackgroundBleWake.register(this, force = true)
            ServiceWatchdogReceiver.schedule(this, 60_000L)
        }
        super.onDestroy()
    }
    private fun notification(message: String): Notification {
        val vehicle = notificationVehicle
        val driving = vehicle?.takeIf {
            connected && !otaSessionActive && !pendingFirmwareUpdate && !pendingFirmwareScan && it.rpm > 0
        }?.let {
            val hours = it.currentDurationS / 3600L
            val minutes = it.currentDurationS % 3600L / 60L
            String.format(Locale.getDefault(),
                "驾驶中 %02d:%02d    已行驶 %.1f km",
                hours, minutes, it.currentDistanceM / 1000.0)
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (driving != null) "BRZ Garage · 驾驶状态" else "BRZ Garage · 自动连接")
            .setContentText(driving ?: message)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (driving != null) builder.setStyle(Notification.BigTextStyle().bigText(driving))
        return builder.build()
    }
    private fun lowFuelNotification(fuelPercent: Double): Notification {
        val message = "剩余油量约 ${String.format(Locale.getDefault(), "%.0f", fuelPercent)}%，请及时加油"
        return Notification.Builder(this, LOW_FUEL_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("BRZ Garage · 低油量")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText("$message。续航仅为估计结果，请合理规划加油。"))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(PendingIntent.getActivity(
                this, LOW_FUEL_NOTIFICATION, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }
    private fun updateLowFuelNotification(vehicle: VehicleState) {
        // NotificationManager is outside the data contract. Some vendor Android
        // builds throw here even after the permission check (disabled channel,
        // background policy, or a stale preference type from a development APK).
        // A low-fuel reminder must never terminate the GATT callback/process.
        try {
            val fuelPercent = state.effectiveFuelPercent(vehicle) ?: return
            val preferenceKey = LOW_FUEL_PREF_PREFIX + address
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val alreadyNotified = try {
                state.prefs.getBoolean(preferenceKey, false)
            } catch (_: ClassCastException) {
                state.prefs.edit().remove(preferenceKey).apply()
                false
            }
            if (!isLowFuel(fuelPercent)) {
                if (alreadyNotified) {
                    state.prefs.edit().putBoolean(preferenceKey, false).apply()
                    manager.cancel(LOW_FUEL_NOTIFICATION)
                }
                return
            }
            if (alreadyNotified) return
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            manager.notify(LOW_FUEL_NOTIFICATION, lowFuelNotification(fuelPercent))
            state.prefs.edit().putBoolean(preferenceKey, true).apply()
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Low-fuel notification rejected; BLE sync continues", error)
        }
    }
    private fun publish(message: String, updated: Boolean = false) {
        try {
            state.status(message, connected)
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Status cache rejected; BLE sync continues", error)
        }
        // Status delivery is auxiliary. A denied notification or a vendor
        // receiver race must not terminate the foreground BLE service.
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION, notification(message))
        } catch (_: RuntimeException) { }
        try {
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_STATUS, message).putExtra(EXTRA_UPDATED, updated))
        } catch (_: RuntimeException) { }
    }
    private fun startScan() {
        if (destroyed || scanning || gatt != null || !state.automatic) return
        handler.removeCallbacks(retry)
        handler.removeCallbacks(autoConnectFallback)
        if (!hasPermissions(this)) { publish("附近设备权限已关闭，请重新授权"); return }
        if (adapter?.isEnabled != true) { publish("请开启蓝牙，之后自动连接仪表"); return }
        val scanner = adapter?.bluetoothLeScanner ?: return
        address = state.address
        if (!BluetoothAdapter.checkBluetoothAddress(address)) { publish("请先绑定自己的仪表"); return }
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
                ScanSettings.Builder().setScanMode(
                    if (foregroundUi) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED
                ).build(), scanCallback)
            scanning = true
            publish("正在查找绑定仪表 $address…")
            // HarmonyOS may throttle a scan even for a foreground service. Keep
            // the controller auto-connect path as an alternating fallback.
            handler.postDelayed(scanFallback, 15000)
        } catch (_: RuntimeException) { startAutoConnect() }
    }
    /**
     * Keep a controller-level auto-connect request pending while the car is off.
     * This is substantially more reliable than a long-running callback scan on
     * Huawei/HarmonyOS, where background scans can be delayed or suspended.
     * If a vendor stack does not honour autoConnect within 45 seconds, fall back
     * to a filtered balanced scan and a direct connection.
     */
    private fun startAutoConnect() {
        if (destroyed || scanning || gatt != null || !state.automatic) return
        handler.removeCallbacks(retry)
        handler.removeCallbacks(autoConnectFallback)
        if (!hasPermissions(this)) { publish("附近设备权限已关闭，请重新授权"); return }
        if (adapter?.isEnabled != true) { publish("请开启蓝牙，之后自动连接仪表"); return }
        address = state.address
        if (!BluetoothAdapter.checkBluetoothAddress(address)) { publish("请先绑定自己的仪表"); return }
        try {
            val device = adapter?.getRemoteDevice(address) ?: run {
                startScan(); return
            }
            publish("等待仪表上电 · 系统自动重连已开启 ($address)")
            gatt = device.connectGatt(this, true, callback,
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
            if (gatt == null) startScan()
            else handler.postDelayed(autoConnectFallback, 30000)
        } catch (_: RuntimeException) {
            gatt = null
            fail("系统自动重连未启动，稍后重试")
        }
    }
    private fun beginReconnect() {
        // Scan immediately. The previous 45-second autoConnect-first strategy
        // made a powered gauge appear unresponsive on several HarmonyOS builds.
        startScan()
    }
    private fun connectFromGaugeSignal() {
        if (destroyed || gatt != null || !state.automatic) return
        stopScan()
        if (!hasPermissions(this)) { publish("已发现仪表，但附近设备权限已关闭"); return }
        if (adapter?.isEnabled != true) { publish("已发现仪表，但手机蓝牙未开启"); return }
        address = state.address
        if (!BluetoothAdapter.checkBluetoothAddress(address)) { publish("请先绑定自己的仪表"); return }
        try {
            val device = adapter?.getRemoteDevice(address) ?: run { beginReconnect(); return }
            busy = true
            publish("已收到仪表广播 · 直接连接并优先授时…")
            armConnectTimeout()
            gatt = device.connectGatt(this, false, callback,
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
            if (gatt == null) fail("仪表信号连接未启动，正在重新扫描")
        } catch (_: RuntimeException) {
            fail("仪表信号直连失败，正在重新扫描")
        }
    }
    private fun stopScan() {
        handler.removeCallbacks(scanFallback)
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: RuntimeException) { }
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning || gatt != null || result.device.address != address) return
            stopScan()
            busy = true
            publish("已发现绑定仪表，连接中…")
            armConnectTimeout()
            try {
                gatt = result.device.connectGatt(this@TripSyncService, false, callback,
                    BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
                if (gatt == null) fail("蓝牙连接未启动，稍后重试")
            } catch (_: RuntimeException) { fail("蓝牙连接失败，稍后重试") }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanFallback)
            if (!destroyed && gatt == null) startAutoConnect()
        }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (otaSessionActive && otaStartCommandAttempted && preparedFirmware != null) {
                    recoverOtaWifiAfterBleDrop("蓝牙握手中断")
                } else if (otaSessionActive) failFirmwareUpdate("固件更新握手中断，原固件未被改写")
                else fail("仪表已断开，等待重新上电")
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.removeCallbacks(autoConnectFallback)
                handler.removeCallbacks(scanFallback)
                connected = true
                acquireConnectionWakeLock()
                retryMs = 2000L
                publish("仪表已连接 · 准备自动授时")
                armConnectTimeout()
                if (!g.discoverServices()) fail("无法读取仪表服务")
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("服务发现失败：$status"); return }
            info = g.getService(TripBleProtocol.INFO_SERVICE)
            if (info == null) { fail("仪表缺少授时服务，请升级固件"); return }
            ota = g.getService(TripBleProtocol.OTA_SERVICE)
            legacy = info?.getCharacteristic(TripBleProtocol.VEHICLE_STATE) == null
            syncClock() // 8-byte time goes first, before MTU negotiation or history.
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt || mtuReady || !awaitingMtu) return
            awaitingMtu = false
            mtuReady = true
            read(TripBleProtocol.TRIP_META)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt || c.uuid != operation) return
            handler.removeCallbacks(timeout)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                retryCurrentOperation("写入暂时失败：$status")
                return
            }
            if (operationIsOta) {
                completeOperation()
                publish("仪表更新热点正在启动…", true)
                handler.postDelayed(otaStatusPoll, 700)
                return
            }
            when (c.uuid) {
                TripBleProtocol.TIME_SYNC -> {
                    completeOperation()
                    continueAfterGattIdle {
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) read(TripBleProtocol.TIME_SYNC)
                        else { state.recordTimeSync(false); afterClock() }
                    }
                }
                TripBleProtocol.TRIP_CONTROL -> {
                    completeOperation()
                    continueAfterGattIdle {
                        if (control == 1) read(TripBleProtocol.TRIP_DATA) else finishHistory()
                    }
                }
                TripBleProtocol.BRIGHTNESS_CONTROL -> {
                    completeOperation()
                    brightnessVerificationValue = brightnessWriteValue
                    brightnessWriteValue = null
                    continueAfterGattIdle { read(TripBleProtocol.GAUGE_SETTINGS) }
                }
                TripBleProtocol.VEHICLE_PROFILE_CONTROL -> {
                    completeOperation()
                    vehicleProfileVerificationValue = vehicleProfileWriteValue
                    vehicleProfileWriteValue = null
                    continueAfterGattIdle { read(TripBleProtocol.GAUGE_SETTINGS) }
                }
                TripBleProtocol.ODOMETER_CONFIG -> {
                    completeOperation()
                    continueAfterGattIdle { read(TripBleProtocol.ODOMETER_CONFIG) }
                }
                TripBleProtocol.REFUEL_CONTROL -> {
                    completeOperation()
                    if (refuelControl == 1) {
                        continueAfterGattIdle { read(TripBleProtocol.REFUEL_DATA) }
                    } else if (refuelControl == 2) {
                        state.confirmRefuelReset()
                        handler.postDelayed({
                            if (connected && operation == null) read(TripBleProtocol.REFUEL_META)
                        }, 900L)
                    } else if (refuelControl == 3 || refuelControl == 5) {
                        refuelDeleteAwaitingRevision = true
                        refuelDeleteVerifyAttempts = 0
                        handler.postDelayed({
                            if (connected && operation == null) read(TripBleProtocol.REFUEL_META)
                        }, 900L)
                    } else if (refuelControl == 4) {
                        refuelThresholdVerificationMl = refuelThresholdWriteMl
                        refuelThresholdWriteMl = null
                        continueAfterGattIdle { read(TripBleProtocol.GAUGE_SETTINGS) }
                    }
                }
                TripBleProtocol.CUSTOM_TRIP_CONTROL -> {
                    completeOperation()
                    customTripSyncedForConnection = true
                    customTripDeferredForConnection = false
                    state.confirmCustomTripSync()
                    continueAfterGattIdle { readRefuelOrSettings() }
                }
            }
        }
        @Deprecated("API 26–32 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            handleReadSafely(g, c.uuid, c.value ?: byteArrayOf(), status)
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            handleReadSafely(g, c.uuid, value, status)
    }
    private fun handleReadSafely(g: BluetoothGatt, uuid: UUID, bytes: ByteArray, status: Int) {
        try {
            handleRead(g, uuid, bytes, status)
        } catch (error: RuntimeException) {
            // Settings/profile adoption and vendor notification code runs after
            // time sync, which made an exception here look like a clock crash.
            // Keep the process alive. Optional manifest failures stay on the
            // current link; required synchronization failures may reconnect.
            Log.e(LOG_TAG, "GATT read processing failed for $uuid", error)
            if (!destroyed) {
                if (otaSessionActive) failFirmwareUpdate("更新状态处理异常，原固件未被改写")
                else if (uuid == TripBleProtocol.MANIFEST) {
                    finishManifestReadFailure("固件信息处理异常（${error.javaClass.simpleName}）")
                }
                else fail("同步数据处理异常，稍后自动重试")
            }
        }
    }
    private fun syncClock() {
        val epoch = System.currentTimeMillis() / 1000
        if (epoch !in 1704067200L..4102444800L) { fail("手机日期异常，请开启系统自动日期与时间"); return }
        publish("正在给仪表授时…")
        write(TripBleProtocol.TIME_SYNC, TripBleProtocol.timePacket(epoch))
    }
    private fun afterClock() {
        lastClockMs = SystemClock.elapsedRealtime()
        publish(if (state.timeVerified) "仪表时间已回读校验" else "授时写入成功 · 旧固件不支持回读校验")
        // Do not gate synchronization on MTU negotiation. Several HarmonyOS
        // Bluetooth stacks return true from requestMtu() but never deliver
        // onMtuChanged(), leaving the app apparently frozen until timeout.
        // The gauge implements ATT Read Blob, so the default MTU can safely
        // carry the 40/48-byte history and 64-byte vehicle-state values in fragments.
        awaitingMtu = false
        mtuReady = true
        continueAfterGattIdle { read(TripBleProtocol.TRIP_META) }
    }
    private fun handleRead(g: BluetoothGatt, uuid: UUID, bytes: ByteArray, status: Int) {
        if (g !== gatt || operation != uuid) return
        handler.removeCallbacks(timeout)
        if (operationIsOta) {
            handleOtaStatus(bytes, status)
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (uuid == TripBleProtocol.MANIFEST) {
                finishManifestReadFailure("无法读取仪表固件信息（GATT $status）")
                return
            }
            retryCurrentOperation("读取暂时失败：$status")
            return
        }
        when (uuid) {
            TripBleProtocol.TIME_SYNC -> {
                val epoch = if (bytes.size == 8) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long else 0L
                if (epoch !in 1704067200L..4102444800L || abs(epoch - System.currentTimeMillis() / 1000) > 5) {
                    retryOperation("仪表时间回读校验失败")
                    return
                }
                completeOperation()
                state.recordTimeSync(true)
                afterClock()
            }
            TripBleProtocol.TRIP_META -> {
                meta = TripBleProtocol.parseMeta(bytes)
                val m = meta ?: run { retryOperation("行程元数据暂时不完整"); return }
                completeOperation()
                cursor = m.lastAckedId
                if (m.overflowed) state.prefs.edit().putBoolean("overflow_$address", true).apply()
                continueAfterGattIdle {
                    if (m.pendingCount == 0) finishHistory() else requestNext()
                }
            }
            TripBleProtocol.TRIP_DATA -> {
                val record = TripBleProtocol.parseRecord(address, bytes)
                if (record == null || record.tripId <= cursor || record.tripId > (meta?.newestId ?: 0)) {
                    retryOperation("行程数据暂时不完整，未发送 ACK")
                    return
                }
                val saved = try { database.upsert(record) } catch (_: RuntimeException) { false }
                if (!saved) {
                    completeOperation()
                    idle("手机保存失败，仪表记录已保留")
                    return
                }
                completeOperation()
                cursor = record.tripId
                publish("已保存行程 #$cursor", true)
                continueAfterGattIdle {
                    if (cursor < (meta?.newestId ?: cursor)) requestNext() else {
                        control = 2
                        write(TripBleProtocol.TRIP_CONTROL, TripBleProtocol.controlPacket(TripBleProtocol.COMMAND_ACK, cursor))
                    }
                }
            }
            TripBleProtocol.VEHICLE_STATE -> {
                val previousVehicle = state.vehicle()
                val vehicle = VehicleState.parse(bytes)
                if (vehicle == null) {
                    retryOperation("车辆数据暂时不完整，保留上次数据")
                    return
                }
                completeOperation()
                notificationVehicle = vehicle
                state.saveVehicle(bytes)
                updateLowFuelNotification(vehicle)
                val previouslyActive = previousVehicle?.let {
                    it.currentDurationS > 0L || it.currentDistanceM > 0L || it.currentFuelMl > 0L
                } == true
                val nowInactive = vehicle.currentDurationS == 0L &&
                    vehicle.currentDistanceM == 0L && vehicle.currentFuelMl == 0L
                if (previouslyActive && nowInactive) {
                    // The gauge has just finalized the trip. Pull its newly
                    // queued history record now instead of waiting up to the
                    // regular 60-second history cycle.
                    lastHistoryMs = 0L
                    continueAfterGattIdle { read(TripBleProtocol.TRIP_META) }
                } else {
                    syncCustomTripBaselineOrContinue(vehicle)
                }
            }
            TripBleProtocol.REFUEL_META -> {
                val parsed = TripBleProtocol.parseRefuelMeta(bytes)
                if (parsed == null) {
                    retryOperation("加油以来数据暂时不完整")
                    return
                }
                completeOperation()
                val previousRevision = state.refuelHistoryRevision
                val revisionChanged = previousRevision != null &&
                    previousRevision != parsed.historyRevision
                if (revisionChanged) {
                    val pendingDeleteId = state.pendingRefuelDeleteId
                        ?.takeIf { refuelDeleteAwaitingRevision && refuelControl == 3 }
                    val pendingDiscardId = state.pendingRefuelDiscardOldestId
                        ?.takeIf { refuelDeleteAwaitingRevision && refuelControl == 5 }
                    val locallyMerged = pendingDeleteId != null && try {
                        refuelDatabase.mergeAfterDeletedNode(address, pendingDeleteId)
                    } catch (_: RuntimeException) { false }
                    val locallyDiscarded = pendingDiscardId != null && try {
                        refuelDatabase.discardBeforeFirstNode(address, pendingDiscardId)
                    } catch (_: RuntimeException) { false }
                    if (!locallyMerged && !locallyDiscarded) {
                        try { refuelDatabase.clearDevice(address) } catch (_: RuntimeException) { }
                    }
                    state.confirmRefuelHistoryRevision(parsed.historyRevision)
                    if (pendingDeleteId != null) state.confirmRefuelDelete()
                    if (pendingDiscardId != null) state.confirmRefuelDiscardOldest()
                    refuelDeleteAwaitingRevision = false
                    refuelDeleteVerifyAttempts = 0
                    publish(when {
                        pendingDiscardId != null -> "第一个节点以前的数据已删除"
                        pendingDeleteId != null -> "加油节点已删除 · 相邻区间已自动合并"
                        else -> "加油节点历史已变化 · 正在重新同步"
                    }, true)
                } else if (previousRevision == null) {
                    state.confirmRefuelHistoryRevision(parsed.historyRevision)
                } else if (refuelDeleteAwaitingRevision &&
                    (state.pendingRefuelDeleteId != null || state.pendingRefuelDiscardOldestId != null)) {
                    if (refuelDeleteVerifyAttempts < 4) {
                        ++refuelDeleteVerifyAttempts
                        state.saveRefuelMeta(parsed)
                        handler.postDelayed({
                            if (connected && operation == null) read(TripBleProtocol.REFUEL_META)
                        }, 600L)
                        return
                    }
                    refuelDeleteAwaitingRevision = false
                    refuelDeleteVerifyAttempts = 0
                    val wasDiscard = state.pendingRefuelDiscardOldestId != null
                    state.confirmRefuelDelete()
                    state.confirmRefuelDiscardOldest()
                    publish(if (wasDiscard) "第一个节点以前的数据删除未生效 · 正常数据同步继续"
                        else "加油节点删除未生效 · 正常数据同步继续", true)
                }
                state.saveRefuelMeta(parsed)
                refuelMeta = parsed
                refuelCursor = try { refuelDatabase.latestId(address) } catch (_: RuntimeException) { 0L }
                if (refuelCursor < parsed.newestId) {
                    refuelControl = 1
                    continueAfterGattIdle {
                        write(TripBleProtocol.REFUEL_CONTROL,
                            TripBleProtocol.refuelControlPacket(1, refuelCursor))
                    }
                } else {
                    readSettingsAfterVehicle()
                }
            }
            TripBleProtocol.REFUEL_DATA -> {
                val record = TripBleProtocol.parseRefuelRecord(address, bytes)
                val newest = refuelMeta?.newestId ?: 0L
                if (record == null || record.id <= refuelCursor || record.id > newest) {
                    retryOperation("加油以来历史暂时不完整")
                    return
                }
                val saved = try { refuelDatabase.upsert(record) } catch (_: RuntimeException) { false }
                if (!saved) {
                    completeOperation()
                    idle("加油以来记录暂时无法保存；正常车辆同步继续")
                    return
                }
                completeOperation()
                refuelCursor = record.id
                if (refuelCursor < newest) {
                    refuelControl = 1
                    continueAfterGattIdle {
                        write(TripBleProtocol.REFUEL_CONTROL,
                            TripBleProtocol.refuelControlPacket(1, refuelCursor))
                    }
                } else {
                    readSettingsAfterVehicle()
                }
            }
            TripBleProtocol.GAUGE_SETTINGS -> {
                val parsed = TripBleProtocol.parseGaugeSettings(bytes)
                if (parsed == null) {
                    retryCurrentOperation("仪表设置快照暂时不完整")
                    return
                }
                val expectedBrightness = brightnessVerificationValue
                if (expectedBrightness != null && parsed.brightness != expectedBrightness) {
                    retryBrightnessOperation("等待仪表应用亮度")
                    return
                }
                val expectedVehicleProfile = vehicleProfileVerificationValue
                if (expectedVehicleProfile != null && parsed.vehicleProfile != expectedVehicleProfile) {
                    retryVehicleProfileOperation("等待仪表应用车型")
                    return
                }
                val expectedRefuelThreshold = refuelThresholdVerificationMl
                if (expectedRefuelThreshold != null &&
                    parsed.refuelThresholdMl != expectedRefuelThreshold) {
                    retryRefuelThresholdOperation("等待仪表应用自动加油识别阈值")
                    return
                }
                completeOperation()
                state.saveGaugeSettings(bytes)
                brightnessVerificationValue = null
                vehicleProfileVerificationValue = null
                refuelThresholdVerificationMl = null
                val pendingProfile = state.pendingVehicleProfile
                if (expectedVehicleProfile != null) {
                    state.confirmVehicleProfile(expectedVehicleProfile)
                } else if (pendingProfile == parsed.vehicleProfile) {
                    state.confirmVehicleProfile(parsed.vehicleProfile)
                } else {
                    state.adoptVehicleProfileFromGauge(parsed.vehicleProfile)
                }
                if (expectedBrightness != null) {
                    idle("亮度已调整为 $expectedBrightness% · 已由仪表回读确认")
                } else if (expectedVehicleProfile != null) {
                    val name = SupportedVehicleModel.fromProfileIndex(expectedVehicleProfile)?.title ?: "车型"
                    idle("$name 已同步到仪表 · 已回读确认")
                } else if (expectedRefuelThreshold != null) {
                    idle("自动加油识别阈值已调整为 ${expectedRefuelThreshold / 1000} L · 已由仪表回读确认")
                } else {
                    readOdometerOrManifest("已连接 · 仪表设置与固件版本已同步")
                }
            }
            TripBleProtocol.ODOMETER_CONFIG -> {
                val parsed = TripBleProtocol.parseOdometerConfig(bytes)
                if (parsed == null) {
                    retryOdometerOperation("里程设置回读暂时不完整")
                    return
                }
                val expectedDisplay = odometerDisplayVerificationValue
                val expectedCalibrationM = odometerCalibrationVerificationM
                if (expectedDisplay != null && parsed.displayEnabled != expectedDisplay) {
                    retryOdometerOperation("等待仪表应用里程显示设置")
                    return
                }
                val actualM = parsed.odometerX10Km * 100L
                if (expectedCalibrationM != null &&
                    (!parsed.calibrated || abs(actualM - expectedCalibrationM) > 100L)) {
                    retryOdometerOperation("等待仪表应用里程校准")
                    return
                }
                completeOperation()
                state.adoptOdometerDisplayFromGauge(parsed.displayEnabled)
                if (expectedDisplay != null) state.confirmOdometerDisplay(expectedDisplay)
                if (expectedCalibrationM != null) state.confirmGaugeOdometerCalibration(actualM)
                odometerDisplayVerificationValue = null
                odometerCalibrationVerificationM = null
                if (expectedDisplay != null) {
                    idle(if (expectedDisplay) "仪表设备信息页与 App 首页已显示里程" else "仪表设备信息页与 App 首页已隐藏里程")
                } else if (expectedCalibrationM != null) {
                    idle("仪表里程已同步校准 · 已回读确认")
                } else {
                    readManifestOrIdle("已连接 · 里程设置已同步")
                }
            }
            TripBleProtocol.MANIFEST -> {
                val parseResult = runCatching { TripBleProtocol.parseFirmwareInfo(bytes) }
                val parsed = parseResult.getOrNull()
                completeOperation()
                if (parsed != null) {
                    firmwareInfoForConnection = parsed
                    try {
                        state.saveFirmwareInfo(parsed)
                    } catch (error: RuntimeException) {
                        // The live value is sufficient for this scan. A vendor
                        // SharedPreferences failure must not poison GATT.
                        Log.w(LOG_TAG, "Firmware cache rejected; using live manifest", error)
                    }
                    when {
                        otaAwaitingVerification -> verifyInstalledFirmware(parsed)
                        pendingFirmwareScan -> finishFirmwareScan(parsed)
                        else -> idle("已连接 · 仪表设置与固件版本已同步")
                    }
                } else {
                    val parseError = parseResult.exceptionOrNull()
                    if (pendingFirmwareScan) {
                        finishManifestReadFailure(if (parseError == null)
                            "仪表固件信息格式无法识别"
                        else
                            "仪表固件信息解析失败（${parseError.javaClass.simpleName}）")
                    } else {
                        idle("已连接 · 固件版本格式无法识别，正常同步继续")
                    }
                }
            }
        }
    }
    private fun readRefuelOrSettings() {
        val supported = info?.getCharacteristic(TripBleProtocol.REFUEL_META) != null
        if (supported && (!refuelReadForConnection || state.showSinceRefuelTrip)) {
            refuelReadForConnection = true
            continueAfterGattIdle { read(TripBleProtocol.REFUEL_META) }
        } else {
            refuelReadForConnection = true
            readSettingsAfterVehicle()
        }
    }
    private fun syncCustomTripBaselineOrContinue(vehicle: VehicleState) {
        if (state.customTripBaseline() == null) state.customTrip(vehicle)
        if ((customTripSyncedForConnection && !state.pendingCustomTripSync) ||
            customTripDeferredForConnection) {
            readRefuelOrSettings()
            return
        }
        val baseline = state.customTripBaseline()
        val supported = info?.getCharacteristic(TripBleProtocol.CUSTOM_TRIP_CONTROL) != null
        if (baseline == null || !supported) {
            customTripDeferredForConnection = !supported
            readRefuelOrSettings()
            return
        }
        publish("正在同步自定义行程起点…")
        write(TripBleProtocol.CUSTOM_TRIP_CONTROL,
            TripBleProtocol.customTripBaselinePacket(baseline.first, baseline.second, baseline.third))
    }
    private fun readSettingsAfterVehicle() {
        if (!settingsReadForConnection &&
            info?.getCharacteristic(TripBleProtocol.GAUGE_SETTINGS) != null) {
            settingsReadForConnection = true
            continueAfterGattIdle { read(TripBleProtocol.GAUGE_SETTINGS) }
        } else {
            settingsReadForConnection = true
            readManifestOrIdle()
        }
    }
    private fun readOdometerOrManifest(message: String? = null) {
        val supported = info?.getCharacteristic(TripBleProtocol.ODOMETER_CONFIG) != null
        if (!odometerReadForConnection && supported) {
            odometerReadForConnection = true
            continueAfterGattIdle { read(TripBleProtocol.ODOMETER_CONFIG) }
        } else {
            odometerReadForConnection = true
            readManifestOrIdle(message)
        }
    }
    /** Optional metadata is read only after vehicle/settings traffic is idle. */
    private fun readManifestOrIdle(message: String? = null) {
        val supported = info?.getCharacteristic(TripBleProtocol.MANIFEST) != null
        if (!manifestReadForConnection && supported) {
            manifestReadForConnection = true
            continueAfterGattIdle { read(TripBleProtocol.MANIFEST) }
        } else {
            manifestReadForConnection = true
            if (pendingFirmwareScan && !supported) {
                finishManifestReadFailure("当前仪表固件不支持固件信息扫描")
            } else {
                idle(message)
            }
        }
    }
    private fun requestNext() {
        control = 1
        write(TripBleProtocol.TRIP_CONTROL, TripBleProtocol.controlPacket(TripBleProtocol.COMMAND_CURSOR, cursor))
    }
    private fun finishHistory() {
        lastHistoryMs = SystemClock.elapsedRealtime()
        if (legacy) idle() else read(TripBleProtocol.VEHICLE_STATE)
    }
    private fun requestBrightness(percent: Int) {
        pendingBrightness = percent // latest request wins; never interrupt an active sync chain
        publish("亮度 $percent% 已排队 · 等待当前数据同步完成")
        if (!busy && operation == null) {
            handler.removeCallbacks(cycle)
            continueAfterGattIdle { startPendingBrightness() }
        }
    }
    private fun startPendingBrightness(): Boolean {
        val requested = pendingBrightness ?: return false
        val supported = info?.getCharacteristic(TripBleProtocol.BRIGHTNESS_CONTROL) != null
        if (!supported) {
            pendingBrightness = null
            publish("亮度未修改 · 仪表固件不支持手机亮度控制", true)
            handler.postDelayed(cycle, if (foregroundUi) FOREGROUND_CYCLE_MS else BACKGROUND_CYCLE_MS)
            return true
        }
        pendingBrightness = null
        brightnessWriteValue = requested
        publish("正在调整仪表亮度为 $requested%…")
        write(TripBleProtocol.BRIGHTNESS_CONTROL, TripBleProtocol.brightnessPacket(requested))
        return true
    }
    private fun requestRefuelThreshold(thresholdMl: Int) {
        pendingRefuelThresholdMl = thresholdMl
        publish("自动加油识别阈值 ${thresholdMl / 1000} L 已排队 · 等待当前数据同步完成")
        if (!busy && operation == null) {
            handler.removeCallbacks(cycle)
            continueAfterGattIdle { startPendingRefuelThreshold() }
        }
    }
    private fun startPendingRefuelThreshold(): Boolean {
        val requested = pendingRefuelThresholdMl ?: return false
        val supported = info?.getCharacteristic(TripBleProtocol.REFUEL_CONTROL) != null &&
            state.gaugeSettings()?.refuelThresholdMl != null
        if (!supported) {
            pendingRefuelThresholdMl = null
            publish("阈值未修改 · 请先升级仪表固件", true)
            handler.postDelayed(cycle, if (foregroundUi) FOREGROUND_CYCLE_MS else BACKGROUND_CYCLE_MS)
            return true
        }
        pendingRefuelThresholdMl = null
        refuelThresholdWriteMl = requested
        refuelControl = 4
        publish("正在调整自动加油识别阈值为 ${requested / 1000} L…")
        write(TripBleProtocol.REFUEL_CONTROL, TripBleProtocol.refuelControlPacket(4, requested.toLong()))
        return true
    }
    private fun requestVehicleProfile(profileIndex: Int) {
        vehicleProfileDeferredForConnection = false
        val model = SupportedVehicleModel.fromProfileIndex(profileIndex) ?: return
        publish("${model.title} 已排队 · 等待当前数据同步完成")
        if (!busy && operation == null) {
            handler.removeCallbacks(cycle)
            continueAfterGattIdle { startPendingVehicleProfile() }
        }
    }
    private fun startPendingVehicleProfile(): Boolean {
        val requested = state.pendingVehicleProfile ?: return false
        if (vehicleProfileDeferredForConnection) return false
        val model = SupportedVehicleModel.fromProfileIndex(requested) ?: return false
        val supported = info?.getCharacteristic(TripBleProtocol.VEHICLE_PROFILE_CONTROL) != null
        if (!supported) {
            vehicleProfileDeferredForConnection = true
            publish("${model.title} 已保存在手机 · 请先升级仪表固件后再同步", true)
            handler.postDelayed(cycle, if (foregroundUi) FOREGROUND_CYCLE_MS else BACKGROUND_CYCLE_MS)
            return true
        }
        vehicleProfileWriteValue = requested
        publish("正在将车型同步为 ${model.title}…")
        write(TripBleProtocol.VEHICLE_PROFILE_CONTROL, TripBleProtocol.vehicleProfilePacket(requested))
        return true
    }
    private fun requestOdometerConfig(message: String) {
        odometerDeferredForConnection = false
        publish("$message · 等待当前数据同步完成")
        if (!busy && operation == null) {
            handler.removeCallbacks(cycle)
            continueAfterGattIdle { startPendingOdometerConfig() }
        }
    }
    private fun requestRefuelReset() {
        publish("手动重置已排队 · 等待当前同步完成")
        if (!busy && operation == null) {
            handler.removeCallbacks(cycle)
            continueAfterGattIdle { startPendingRefuelReset() }
        }
    }
    private fun requestCustomTripSync() {
        publish("自定义行程重置已排队 · 等待当前同步完成")
        if (!busy && operation == null) {
            handler.removeCallbacks(cycle)
            continueAfterGattIdle { startPendingCustomTripSync() }
        }
    }
    private fun startPendingCustomTripSync(): Boolean {
        val baseline = state.customTripBaseline() ?: return false
        val supported = info?.getCharacteristic(TripBleProtocol.CUSTOM_TRIP_CONTROL) != null
        if (!supported) {
            customTripDeferredForConnection = true
            publish("手机已重置自定义行程 · 仪表固件升级后将自动同步", true)
            handler.postDelayed(cycle, if (foregroundUi) FOREGROUND_CYCLE_MS else BACKGROUND_CYCLE_MS)
            return true
        }
        publish("正在把自定义行程起点同步到仪表…")
        write(TripBleProtocol.CUSTOM_TRIP_CONTROL,
            TripBleProtocol.customTripBaselinePacket(baseline.first, baseline.second, baseline.third))
        return true
    }
    private fun requestRefuelDelete() {
        publish("删除加油节点已排队 · 等待当前同步完成")
        if (!busy && operation == null) {
            handler.removeCallbacks(cycle)
            continueAfterGattIdle { startPendingRefuelDelete() }
        }
    }
    private fun startPendingRefuelDelete(): Boolean {
        val nodeId = state.pendingRefuelDeleteId ?: return false
        val supported = info?.getCharacteristic(TripBleProtocol.REFUEL_CONTROL) != null
        if (!supported) {
            state.confirmRefuelDelete()
            publish("加油节点未删除 · 请先升级仪表固件", true)
            handler.postDelayed(cycle, if (foregroundUi) FOREGROUND_CYCLE_MS else BACKGROUND_CYCLE_MS)
            return true
        }
        val protocolVersion = refuelMeta?.protocolVersion
        if (protocolVersion == null) {
            publish("正在确认仪表是否支持删除加油节点…")
            continueAfterGattIdle { read(TripBleProtocol.REFUEL_META) }
            return true
        }
        if (protocolVersion < 2) {
            state.confirmRefuelDelete()
            publish("加油节点未删除 · 请先将仪表升级到 3.2.4", true)
            handler.postDelayed(cycle, if (foregroundUi) FOREGROUND_CYCLE_MS else BACKGROUND_CYCLE_MS)
            return true
        }
        refuelControl = 3
        refuelDeleteAwaitingRevision = false
        refuelDeleteVerifyAttempts = 0
        publish("正在删除加油节点并合并相邻区间…")
        write(TripBleProtocol.REFUEL_CONTROL, TripBleProtocol.refuelControlPacket(3, nodeId))
        return true
    }
    private fun requestRefuelDiscardOldest() {
        publish("删除第一个节点以前的数据已排队 · 等待当前同步完成")
        if (!busy && operation == null) {
            handler.removeCallbacks(cycle)
            continueAfterGattIdle { startPendingRefuelDiscardOldest() }
        }
    }
    private fun startPendingRefuelDiscardOldest(): Boolean {
        val nodeId = state.pendingRefuelDiscardOldestId ?: return false
        if (info?.getCharacteristic(TripBleProtocol.REFUEL_CONTROL) == null) {
            state.confirmRefuelDiscardOldest()
            publish("数据未删除 · 请先升级仪表固件", true)
            handler.postDelayed(cycle, if (foregroundUi) FOREGROUND_CYCLE_MS else BACKGROUND_CYCLE_MS)
            return true
        }
        if (!settingsReadForConnection) {
            publish("正在确认仪表是否支持删除第一个节点以前的数据…")
            settingsReadForConnection = true
            continueAfterGattIdle { read(TripBleProtocol.GAUGE_SETTINGS) }
            return true
        }
        if (state.gaugeSettings()?.refuelDiscardOldestSupported != true) {
            state.confirmRefuelDiscardOldest()
            publish("数据未删除 · 请先升级仪表固件", true)
            handler.postDelayed(cycle, if (foregroundUi) FOREGROUND_CYCLE_MS else BACKGROUND_CYCLE_MS)
            return true
        }
        if (refuelMeta == null || state.refuelHistoryRevision == null) {
            publish("正在确认第一个加油节点…")
            continueAfterGattIdle { read(TripBleProtocol.REFUEL_META) }
            return true
        }
        refuelControl = 5
        refuelDeleteAwaitingRevision = false
        refuelDeleteVerifyAttempts = 0
        publish("正在删除第一个节点以前的数据…")
        write(TripBleProtocol.REFUEL_CONTROL, TripBleProtocol.refuelControlPacket(5, nodeId))
        return true
    }
    private fun startPendingRefuelReset(): Boolean {
        if (!state.pendingRefuelReset) return false
        val supported = info?.getCharacteristic(TripBleProtocol.REFUEL_CONTROL) != null
        if (!supported) {
            publish("手动重置未执行 · 请先升级仪表固件", true)
            handler.postDelayed(cycle, if (foregroundUi) FOREGROUND_CYCLE_MS else BACKGROUND_CYCLE_MS)
            return true
        }
        refuelControl = 2
        publish("正在重置上次加油以来的统计…")
        write(TripBleProtocol.REFUEL_CONTROL, TripBleProtocol.refuelControlPacket(2))
        return true
    }
    private fun startPendingOdometerConfig(): Boolean {
        val pendingDisplay = state.pendingOdometerDisplay
        val pendingCalibrationM = state.pendingGaugeOdometerCalibrationM
        if (pendingDisplay == null && pendingCalibrationM == null) return false
        if (odometerDeferredForConnection) return false
        val supported = info?.getCharacteristic(TripBleProtocol.ODOMETER_CONFIG) != null
        if (!supported) {
            odometerDeferredForConnection = true
            publish("里程设置已保存在手机 · 请先升级仪表固件后再同步", true)
            handler.postDelayed(cycle, if (foregroundUi) FOREGROUND_CYCLE_MS else BACKGROUND_CYCLE_MS)
            return true
        }
        if (pendingDisplay != null) {
            odometerDisplayVerificationValue = pendingDisplay
            odometerCalibrationVerificationM = null
            publish(if (pendingDisplay) "正在开启仪表里程显示…" else "正在关闭仪表里程显示…")
            write(TripBleProtocol.ODOMETER_CONFIG,
                TripBleProtocol.odometerDisplayPacket(pendingDisplay))
        } else {
            val distanceM = pendingCalibrationM ?: return false
            odometerDisplayVerificationValue = null
            odometerCalibrationVerificationM = ((distanceM + 50L) / 100L) * 100L
            publish("正在同步校准仪表里程…")
            write(TripBleProtocol.ODOMETER_CONFIG,
                TripBleProtocol.odometerCalibrationPacket(distanceM))
        }
        return true
    }
    private fun finishFirmwareScan(device: TripBleProtocol.FirmwareInfo) {
        pendingFirmwareScan = false
        handler.removeCallbacks(firmwareScanTimeout)
        val result = runCatching {
            val metadata = EmbeddedFirmware.metadata(this)
            EmbeddedFirmware.assess(device, metadata) to metadata.device.version
        }
        result.onSuccess { (assessment, targetVersion) ->
            recordFirmwareUpdateSafely(
                if (assessment.updateAvailable) "available" else if (assessment.canUpdate) "available" else "current",
                assessment.message,
                target = targetVersion)
            idle(assessment.message)
        }.onFailure { error ->
            Log.e(LOG_TAG, "Embedded firmware assessment failed", error)
            recordFirmwareUpdateSafely("failed", "App 内置固件清单损坏，已禁止更新")
            idle("固件检查失败 · ${error.message ?: "内置清单不可用"}")
        }
    }

    private fun recordFirmwareUpdateSafely(
        stage: String,
        message: String,
        progress: Int = 0,
        target: String? = null,
    ) {
        try {
            state.firmwareUpdate(stage, message, progress, target)
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Firmware status cache rejected; BLE sync continues", error)
        }
    }
    /**
     * Firmware inspection is optional and must never own the vehicle/trip
     * transport. Reuse a manifest read on this connection when possible;
     * otherwise issue one isolated read after the normal GATT chain is idle.
     */
    private fun startPendingFirmwareScan(): Boolean {
        if (!pendingFirmwareScan) return false
        firmwareInfoForConnection?.let {
            finishFirmwareScan(it)
            return true
        }
        if (!connected || gatt == null) return false
        if (info?.getCharacteristic(TripBleProtocol.MANIFEST) == null) {
            finishManifestReadFailure("当前仪表固件不支持固件信息扫描")
            return true
        }
        read(TripBleProtocol.MANIFEST)
        return true
    }

    /** End only the optional manifest operation; keep the healthy GATT link. */
    private fun finishManifestReadFailure(reason: String) {
        val manualScan = pendingFirmwareScan
        pendingFirmwareScan = false
        handler.removeCallbacks(firmwareScanTimeout)
        if (operation == TripBleProtocol.MANIFEST && !operationIsOta) {
            completeOperation()
        }
        if (manualScan) {
            recordFirmwareUpdateSafely("failed", "$reason；正常授时、行程与车辆数据同步继续")
        }
        if (connected && operation == null) {
            busy = false
            idle(if (manualScan) "固件检查失败 · $reason；正常数据同步继续"
                 else "已连接 · 固件版本暂未读取，正常同步继续")
        } else if (manualScan) {
            publish("固件检查失败 · $reason；正常数据同步继续", true)
        }
    }
    private fun startPendingFirmwareUpdate() {
        if (!pendingFirmwareUpdate || otaSessionActive || operation != null) return
        pendingFirmwareUpdate = false
        val device = state.firmwareInfo()
        val metadata = try {
            EmbeddedFirmware.metadata(this, pendingFirmwarePackageId)
        } catch (error: RuntimeException) {
            failFirmwareUpdate("所选固件清单无法读取：${error.message ?: "格式错误"}")
            return
        }
        val assessment = if (metadata.isRollback)
            EmbeddedFirmware.assessRollback(device, metadata)
        else EmbeddedFirmware.assess(device, metadata)
        if (!assessment.canUpdate || !assessment.updateAvailable) {
            state.firmwareUpdate("failed", assessment.message, target = metadata.device.version)
            idle(assessment.message)
            return
        }
        if (ota?.getCharacteristic(TripBleProtocol.OTA_CONTROL) == null ||
            ota?.getCharacteristic(TripBleProtocol.OTA_STATUS) == null) {
            failFirmwareUpdate("仪表缺少安全 OTA 服务，未写入任何固件数据")
            return
        }
        otaSessionActive = true
        otaStartWriteQueued = false
        otaWifiWorkerRetries = 0
        busy = true
        expectedFirmwareVersion = metadata.device.version
        val operationName = if (metadata.isRollback) "回滚" else "更新"
        state.firmwareUpdate("preparing", "正在校验 App 内置${operationName}固件…", target = metadata.device.version)
        publish("正在校验${operationName}固件 v${metadata.device.version}…", true)
        firmwareExecutor.execute {
            val result = runCatching { EmbeddedFirmware.loadVerified(this, metadata) }
            handler.post {
                if (!otaSessionActive || destroyed) return@post
                result.onSuccess {
                    preparedFirmware = it
                    otaReadyRetries = 0
                    otaStartCommandAttempted = true
                    state.firmwareUpdate("preparing", "内置固件校验通过 · 正在启动仪表更新热点",
                        target = it.metadata.device.version)
                    writeOta(TripBleProtocol.OTA_CONTROL, TripBleProtocol.otaWifiStartPacket())
                }.onFailure {
                    failFirmwareUpdate(it.message ?: "内置固件完整性校验失败")
                }
            }
        }
    }
    private fun handleOtaStatus(bytes: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            retryFirmwareOperation("读取更新热点状态失败：$status")
            return
        }
        val wifi = TripBleProtocol.parseOtaWifiInfo(bytes)
        completeOperation()
        if (wifi == null) {
            scheduleOtaStatusPoll("仪表更新状态暂时不完整")
            return
        }
        if (wifi.state.contains("error")) {
            if (wifi.message.equals("wifi start task unavailable", ignoreCase = true)) {
                if (otaWifiWorkerRetries < 2 && connected && otaSessionActive) {
                    otaWifiWorkerRetries++
                    otaStartWriteQueued = false
                    val attempt = otaWifiWorkerRetries
                    state.firmwareUpdate("preparing",
                        "仪表更新任务内存暂时不足 · 正在重新申请 $attempt/2",
                        target = expectedFirmwareVersion)
                    publish("仪表更新任务内存暂时不足 · 正在安全重试 $attempt/2", true)
                    handler.postDelayed({
                        if (!destroyed && connected && otaSessionActive && operation == null) {
                            writeOta(TripBleProtocol.OTA_CONTROL, TripBleProtocol.otaWifiStartPacket())
                        }
                    }, 1200L * attempt)
                    return
                }
                failFirmwareUpdate(
                    "仪表当前固件的内部内存不足，未能启动 Wi‑Fi 更新任务。原固件和行程数据未被改写；请将仪表断电重启后立即重试，仍失败则需先通过 USB 烧录 v3.2.9 一次"
                )
                return
            }
            failFirmwareUpdate("仪表无法启动安全更新：${wifi.message.ifEmpty { wifi.state }}")
            return
        }
        if (wifi.state != "wifi-ready" || wifi.ssid.isBlank() || wifi.token.isBlank()) {
            scheduleOtaStatusPoll("仪表更新热点正在准备")
            return
        }
        val packageToUpload = preparedFirmware ?: run {
            failFirmwareUpdate("内置固件缓存丢失，更新未开始")
            return
        }
        startFirmwareWifiUpload(packageToUpload, wifi, false)
    }
    private fun startFirmwareWifiUpload(
        packageToUpload: EmbeddedFirmwarePackage,
        wifi: TripBleProtocol.OtaWifiInfo,
        recoveredFromBle: Boolean,
    ) {
        if (otaWifiUploader != null || destroyed) return
        state.firmwareUpdate("wifi", if (recoveredFromBle)
            "BLE 回调中断 · 正在直接寻找仪表更新热点"
            else "仪表热点已就绪 · 等待手机连接", target = packageToUpload.metadata.device.version)
        publish(if (recoveredFromBle) "蓝牙握手未返回 · 正在从更新热点恢复会话"
            else "仪表更新热点已就绪 · 正在切换 Wi‑Fi", true)
        if (gatt != null) closeConnection()
        try {
            otaWifiUploader = FirmwareWifiUploader(this, packageToUpload, wifi,
                object : FirmwareWifiUploader.Callback {
                    override fun onStatus(message: String) {
                        handler.post {
                            state.firmwareUpdate(if (message.contains("复核")) "wifi" else "uploading",
                                message, state.firmwareUpdateProgress, expectedFirmwareVersion)
                            publish(message, true)
                        }
                    }
                    override fun onProgress(percent: Int) {
                        handler.post {
                            state.firmwareUpdate("uploading", "正在上传固件 · $percent%", percent,
                                expectedFirmwareVersion)
                            publish("正在上传仪表固件 · $percent%", true)
                        }
                    }
                    override fun onComplete() { handler.post { firmwareUploadAccepted() } }
                    override fun onError(message: String) { handler.post { failFirmwareUpdate(message) } }
                }).also { it.start() }
        } catch (error: RuntimeException) {
            failFirmwareUpdate(error.message ?: "无法启动 Wi‑Fi 固件传输")
        }
    }
    private fun recoverOtaWifiAfterBleDrop(reason: String) {
        val packageToUpload = preparedFirmware
        if (!otaSessionActive || !otaStartCommandAttempted || packageToUpload == null) {
            failFirmwareUpdate("$reason，原固件未被改写")
            return
        }
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        handler.removeCallbacks(otaStatusPoll)
        completeOperation()
        busy = true
        if (gatt != null) closeConnection()
        /* Older firmware may already have accepted OTA1/4 and started its
         * SoftAP even though coexistence prevented the Android write callback.
         * Recover through the fixed bootstrap SSID/password and fetch the
         * per-session token from /ota/discover instead of declaring failure. */
        handler.postDelayed({
            if (!destroyed && otaSessionActive && otaWifiUploader == null) {
                startFirmwareWifiUpload(packageToUpload, FirmwareWifiUploader.recoveryInfo(), true)
            }
        }, 900L)
    }
    private fun scheduleOtaStatusPoll(message: String) {
        otaReadyRetries++
        if (otaReadyRetries > OTA_READY_RETRIES) {
            recoverOtaWifiAfterBleDrop("$message，BLE 状态等待超时")
            return
        }
        state.firmwareUpdate("preparing", "$message…", target = expectedFirmwareVersion)
        handler.postDelayed(otaStatusPoll, 700)
    }
    private fun firmwareUploadAccepted() {
        otaWifiUploader = null
        preparedFirmware = null
        otaSessionActive = false
        otaStartCommandAttempted = false
        otaStartWriteQueued = false
        otaWifiWorkerRetries = 0
        otaAwaitingVerification = true
        state.firmwareUpdate("verifying", "固件已完整送达 · 等待仪表写入、重启并回读版本", 100,
            expectedFirmwareVersion)
        publish("固件已校验接收 · 仪表写入备用分区并重启中", true)
        handler.removeCallbacks(retry)
        handler.postDelayed({ if (!destroyed && gatt == null) beginReconnect() }, 20_000)
    }
    private fun verifyInstalledFirmware(info: TripBleProtocol.FirmwareInfo) {
        otaAwaitingVerification = false
        val expected = expectedFirmwareVersion ?: state.firmwareUpdateTarget
        if (expected != null && info.version == expected) {
            state.firmwareUpdate("complete", "更新完成 · 仪表已运行 v$expected，原设置和行程数据已保留", 100, expected)
            expectedFirmwareVersion = null
            pendingFirmwarePackageId = EmbeddedFirmware.LATEST_PACKAGE_ID
            idle("仪表固件已更新到 v$expected · 已回读确认")
        } else {
            val actual = info.version
            state.firmwareUpdate("failed", "仪表重启后仍为 v$actual；可能已自动回滚，原数据未损坏", 100, expected)
            expectedFirmwareVersion = null
            pendingFirmwarePackageId = EmbeddedFirmware.LATEST_PACKAGE_ID
            idle("固件更新未生效或已安全回滚 · 当前 v$actual")
        }
    }
    private fun retryFirmwareOperation(message: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        if (otaStartWriteQueued && operation == TripBleProtocol.OTA_CONTROL &&
            operationPayload != null) {
            /* A true return from Android's writeCharacteristic means the
             * OTA1/4 command entered its GATT queue. Older gauge firmware can
             * start Wi-Fi yet lose the completion callback. Never resend that
             * non-idempotent command: a duplicate used to make old firmware
             * reboot its already-running hotspot. */
            recoverOtaWifiAfterBleDrop("OTA 启动写入已提交，但蓝牙回调未返回")
            return
        }
        if (!otaSessionActive || !connected || operation == null) {
            if (otaSessionActive && otaStartCommandAttempted && preparedFirmware != null) {
                recoverOtaWifiAfterBleDrop("$message；蓝牙操作已结束")
            } else {
                failFirmwareUpdate("$message；原固件未被改写")
            }
            return
        }
        if (operationRetries >= MAX_OPERATION_RETRIES) {
            if (otaStartCommandAttempted && preparedFirmware != null) {
                recoverOtaWifiAfterBleDrop("$message；写入回调未返回")
            } else {
                failFirmwareUpdate("$message；连续失败，原固件未被改写")
            }
            return
        }
        operationRetries++
        publish("$message · 更新握手重试 $operationRetries/$MAX_OPERATION_RETRIES", true)
        handler.postDelayed(operationRetry, when (operationRetries) { 1 -> 800L; 2 -> 1500L; else -> 2500L })
    }
    private fun failFirmwareUpdate(message: String) {
        handler.removeCallbacks(otaStatusPoll)
        pendingFirmwareUpdate = false
        pendingFirmwarePackageId = EmbeddedFirmware.LATEST_PACKAGE_ID
        otaSessionActive = false
        otaStartCommandAttempted = false
        otaStartWriteQueued = false
        otaWifiWorkerRetries = 0
        preparedFirmware = null
        completeOperation()
        otaWifiUploader?.cancel()
        otaWifiUploader = null
        state.firmwareUpdate("failed", message, state.firmwareUpdateProgress, expectedFirmwareVersion)
        publish("固件更新已停止 · $message", true)
        busy = false
        if (connected) handler.postDelayed(cycle, FOREGROUND_CYCLE_MS)
        else if (!destroyed && state.automatic) handler.postDelayed(retry, 5000)
    }
    private fun readOta(uuid: UUID) {
        if (operation != null) return
        operationIsOta = true
        operation = uuid
        operationPayload = null
        operationRetries = 0
        busy = true
        issueOperation()
    }
    private fun writeOta(uuid: UUID, bytes: ByteArray) {
        if (operation != null) return
        operationIsOta = true
        operation = uuid
        operationPayload = bytes.copyOf()
        operationRetries = 0
        busy = true
        issueOperation()
    }
    private fun idle(message: String? = null) {
        completeOperation()
        busy = false
        retryMs = 2000
        if (pendingFirmwareUpdate) {
            continueAfterGattIdle { startPendingFirmwareUpdate() }
            return
        }
        if (pendingFirmwareScan) {
            continueAfterGattIdle { startPendingFirmwareScan() }
            return
        }
        if (pendingBrightness != null) {
            continueAfterGattIdle { startPendingBrightness() }
            return
        }
        if (pendingRefuelThresholdMl != null) {
            continueAfterGattIdle { startPendingRefuelThreshold() }
            return
        }
        if (state.pendingVehicleProfile != null && !vehicleProfileDeferredForConnection) {
            continueAfterGattIdle { startPendingVehicleProfile() }
            return
        }
        if ((state.pendingOdometerDisplay != null ||
                state.pendingGaugeOdometerCalibrationM != null) &&
            !odometerDeferredForConnection) {
            continueAfterGattIdle { startPendingOdometerConfig() }
            return
        }
        if (state.pendingCustomTripSync && !customTripDeferredForConnection) {
            continueAfterGattIdle { startPendingCustomTripSync() }
            return
        }
        if (state.pendingRefuelReset) {
            continueAfterGattIdle { startPendingRefuelReset() }
            return
        }
        if (state.pendingRefuelDiscardOldestId != null) {
            continueAfterGattIdle { startPendingRefuelDiscardOldest() }
            return
        }
        if (state.pendingRefuelDeleteId != null) {
            continueAfterGattIdle { startPendingRefuelDelete() }
            return
        }
        publish(message ?: if (legacy) "已连接 · 历史已同步；车辆首页数据需升级仪表固件" else "已连接 · 自动授时与行程同步正常", true)
        handler.removeCallbacks(cycle)
        handler.postDelayed(cycle, if (foregroundUi) FOREGROUND_CYCLE_MS else BACKGROUND_CYCLE_MS)
    }
    private fun nextCycle() {
        if (!connected || busy || destroyed) return
        busy = true
        val now = SystemClock.elapsedRealtime()
        if (lastClockMs == 0L || now - lastClockMs >= 600000) syncClock()
        else if (lastHistoryMs == 0L || now - lastHistoryMs >= 60000) read(TripBleProtocol.TRIP_META)
        else if (!legacy) read(TripBleProtocol.VEHICLE_STATE)
        else idle()
    }
    private fun armConnectTimeout() {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
    }
    private fun read(uuid: UUID) {
        if (operation != null) return
        operation = uuid
        operationPayload = null
        operationRetries = 0
        busy = true
        issueOperation()
    }
    private fun write(uuid: UUID, bytes: ByteArray) {
        if (operation != null) return
        operation = uuid
        operationPayload = bytes.copyOf()
        operationRetries = 0
        busy = true
        issueOperation()
    }
    private fun issueOperation() {
        handler.removeCallbacks(operationRetry)
        val uuid = operation ?: return
        val c = (if (operationIsOta) ota else info)?.getCharacteristic(uuid)
        val g = gatt
        if (!connected || c == null || g == null) {
            if (otaSessionActive) failFirmwareUpdate("仪表安全 OTA 服务不可用，未写入固件")
            else if (uuid == TripBleProtocol.MANIFEST) {
                finishManifestReadFailure("仪表固件信息服务暂不可用")
            }
            else fail("仪表服务不可用，稍后重新连接")
            return
        }
        try {
            val payload = operationPayload
            val started = if (payload == null) {
                g.readCharacteristic(c)
            } else if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = payload
                g.writeCharacteristic(c)
            }
            if (started) {
                if (operationIsOta && uuid == TripBleProtocol.OTA_CONTROL && payload != null) {
                    otaStartWriteQueued = true
                }
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, OPERATION_TIMEOUT_MS)
            } else {
                retryCurrentOperation(if (payload == null) "读取队列繁忙" else "写入队列繁忙")
            }
        } catch (_: RuntimeException) {
            retryCurrentOperation("手机蓝牙栈暂时繁忙")
        }
    }
    private fun retryCurrentOperation(message: String) {
        when {
            operationIsOta || otaSessionActive -> retryFirmwareOperation(message)
            operation == TripBleProtocol.MANIFEST -> retryManifestOperation(message)
            operation == TripBleProtocol.BRIGHTNESS_CONTROL || brightnessVerificationValue != null ->
                retryBrightnessOperation(message)
            (operation == TripBleProtocol.REFUEL_CONTROL && refuelControl == 4) ||
                refuelThresholdVerificationMl != null -> retryRefuelThresholdOperation(message)
            operation == TripBleProtocol.VEHICLE_PROFILE_CONTROL || vehicleProfileVerificationValue != null ->
                retryVehicleProfileOperation(message)
            operation == TripBleProtocol.ODOMETER_CONFIG ||
                odometerDisplayVerificationValue != null ||
                odometerCalibrationVerificationM != null -> retryOdometerOperation(message)
            operation == TripBleProtocol.REFUEL_META ||
                operation == TripBleProtocol.REFUEL_CONTROL ||
            operation == TripBleProtocol.REFUEL_DATA -> retryRefuelOperation(message)
            operation == TripBleProtocol.CUSTOM_TRIP_CONTROL -> retryCustomTripOperation(message)
            else -> retryOperation(message)
        }
    }
    /** Manifest failures are isolated: retry locally, then resume normal sync. */
    private fun retryManifestOperation(message: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        if (!connected || operation != TripBleProtocol.MANIFEST) {
            finishManifestReadFailure(message)
            return
        }
        if (operationRetries >= MAX_OPERATION_RETRIES) {
            finishManifestReadFailure("$message；固件信息读取失败")
            return
        }
        operationRetries++
        val delayMs = when (operationRetries) { 1 -> 800L; 2 -> 1500L; else -> 2500L }
        publish("$message · 固件检查重试 $operationRetries/$MAX_OPERATION_RETRIES")
        handler.postDelayed(operationRetry, delayMs)
    }
    /** A settings failure must never tear down healthy vehicle/trip transport. */
    private fun retryBrightnessOperation(message: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        if (!connected || operation == null) return
        if (operationRetries >= MAX_OPERATION_RETRIES) {
            completeOperation()
            brightnessWriteValue = null
            brightnessVerificationValue = null
            idle("$message；亮度未确认，车辆数据同步继续")
            return
        }
        operationRetries++
        val delayMs = when (operationRetries) { 1 -> 800L; 2 -> 1500L; else -> 2500L }
        publish("$message · 亮度操作重试 $operationRetries/$MAX_OPERATION_RETRIES")
        handler.postDelayed(operationRetry, delayMs)
    }
    private fun retryRefuelThresholdOperation(message: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        if (!connected || operation == null) return
        if (operationRetries >= MAX_OPERATION_RETRIES) {
            completeOperation()
            refuelThresholdWriteMl = null
            refuelThresholdVerificationMl = null
            idle("$message；阈值未确认，车辆数据同步继续")
            return
        }
        operationRetries++
        val delayMs = when (operationRetries) { 1 -> 800L; 2 -> 1500L; else -> 2500L }
        publish("$message · 阈值操作重试 $operationRetries/$MAX_OPERATION_RETRIES")
        handler.postDelayed(operationRetry, delayMs)
    }
    /** Vehicle setting failures are isolated from normal time/trip/OBD synchronization. */
    private fun retryVehicleProfileOperation(message: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        if (!connected || operation == null) return
        if (operationRetries >= MAX_OPERATION_RETRIES) {
            completeOperation()
            vehicleProfileWriteValue = null
            vehicleProfileVerificationValue = null
            vehicleProfileDeferredForConnection = true
            idle("$message；车型本次未确认，车辆数据同步继续")
            return
        }
        operationRetries++
        val delayMs = when (operationRetries) { 1 -> 800L; 2 -> 1500L; else -> 2500L }
        publish("$message · 车型操作重试 $operationRetries/$MAX_OPERATION_RETRIES")
        handler.postDelayed(operationRetry, delayMs)
    }
    private fun retryOdometerOperation(message: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        if (!connected || operation == null) return
        if (operationRetries >= MAX_OPERATION_RETRIES) {
            completeOperation()
            odometerDisplayVerificationValue = null
            odometerCalibrationVerificationM = null
            odometerDeferredForConnection = true
            idle("$message；里程设置本次未确认，车辆数据同步继续")
            return
        }
        operationRetries++
        val delayMs = when (operationRetries) { 1 -> 800L; 2 -> 1500L; else -> 2500L }
        publish("$message · 里程设置重试 $operationRetries/$MAX_OPERATION_RETRIES")
        handler.postDelayed(operationRetry, delayMs)
    }
    private fun retryRefuelOperation(message: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        if (!connected || operation == null) return
        if (operationRetries >= MAX_OPERATION_RETRIES) {
            completeOperation()
            refuelMeta = null
            if (refuelControl == 3 || refuelControl == 5) {
                refuelDeleteAwaitingRevision = false
                refuelDeleteVerifyAttempts = 0
                if (refuelControl == 3) state.confirmRefuelDelete()
                else state.confirmRefuelDiscardOldest()
            }
            readSettingsAfterVehicle()
            publish("$message；加油以来数据本次未同步，正常车辆采集继续", true)
            return
        }
        operationRetries++
        val delayMs = when (operationRetries) { 1 -> 800L; 2 -> 1500L; else -> 2500L }
        publish("$message · 加油以来同步重试 $operationRetries/$MAX_OPERATION_RETRIES")
        handler.postDelayed(operationRetry, delayMs)
    }
    private fun retryCustomTripOperation(message: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        if (!connected || operation != TripBleProtocol.CUSTOM_TRIP_CONTROL) return
        if (operationRetries >= MAX_OPERATION_RETRIES) {
            completeOperation()
            customTripDeferredForConnection = true
            readRefuelOrSettings()
            publish("$message；自定义行程本次未同步，正常车辆采集继续", true)
            return
        }
        operationRetries++
        handler.postDelayed(operationRetry, 500L * operationRetries)
    }
    private fun retryOperation(message: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        if (!connected || operation == null) {
            fail("$message，等待重新连接")
            return
        }
        if (operationRetries >= MAX_OPERATION_RETRIES) {
            fail("$message；连续失败，重新连接手机链路")
            return
        }
        operationRetries++
        val delayMs = when (operationRetries) {
            1 -> 800L
            2 -> 1500L
            else -> 2500L
        }
        publish("$message · 保持连接重试 $operationRetries/$MAX_OPERATION_RETRIES")
        handler.postDelayed(operationRetry, delayMs)
    }
    private fun completeOperation() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        operation = null
        operationPayload = null
        operationRetries = 0
        operationIsOta = false
    }
    private fun continueAfterGattIdle(action: () -> Unit) {
        val generation = linkGeneration
        handler.postDelayed({
            if (!destroyed && connected && generation == linkGeneration && operation == null) action()
        }, GATT_GAP_MS)
    }
    private fun acquireConnectionWakeLock() {
        try {
            connectionWakeLock?.let { if (!it.isHeld) it.acquire() }
        } catch (error: RuntimeException) {
            /* The foreground GATT service still works while the screen is on;
             * keep synchronization alive even if a vendor rejects wake locks. */
            Log.w(LOG_TAG, "Unable to hold gauge synchronization wake lock", error)
        }
    }
    private fun releaseConnectionWakeLock() {
        try {
            connectionWakeLock?.let { if (it.isHeld) it.release() }
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Unable to release gauge synchronization wake lock", error)
        }
    }
    private fun closeConnection() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(operationRetry)
        handler.removeCallbacks(cycle)
        handler.removeCallbacks(autoConnectFallback)
        handler.removeCallbacks(scanFallback)
        handler.removeCallbacks(otaStatusPoll)
        val old = gatt
        gatt = null
        linkGeneration++
        connected = false
        releaseConnectionWakeLock()
        busy = false
        completeOperation()
        info = null
        ota = null
        notificationVehicle = null
        meta = null
        refuelMeta = null
        refuelCursor = 0L
        refuelControl = 0
        refuelDeleteAwaitingRevision = false
        refuelDeleteVerifyAttempts = 0
        mtuReady = false
        awaitingMtu = false
        settingsReadForConnection = false
        odometerReadForConnection = false
        refuelReadForConnection = false
        customTripSyncedForConnection = false
        customTripDeferredForConnection = false
        manifestReadForConnection = false
        firmwareInfoForConnection = null
        pendingBrightness = null
        brightnessWriteValue = null
        brightnessVerificationValue = null
        pendingRefuelThresholdMl = null
        refuelThresholdWriteMl = null
        refuelThresholdVerificationMl = null
        vehicleProfileWriteValue = null
        vehicleProfileVerificationValue = null
        vehicleProfileDeferredForConnection = false
        odometerDeferredForConnection = false
        odometerDisplayVerificationValue = null
        odometerCalibrationVerificationM = null
        lastHistoryMs = 0
        lastClockMs = 0
        try { old?.disconnect(); old?.close() } catch (_: RuntimeException) { }
    }
    private fun fail(message: String) {
        if (pendingFirmwareScan) {
            pendingFirmwareScan = false
            handler.removeCallbacks(firmwareScanTimeout)
            state.firmwareUpdate("failed", "检查更新因连接中断而结束；正常连接恢复后数据同步继续")
        }
        stopScan()
        closeConnection()
        try { BackgroundBleWake.register(this, force = true) } catch (_: RuntimeException) { }
        publish(message)
        handler.removeCallbacks(retry)
        if (!destroyed && state.automatic) handler.postDelayed(retry, retryMs)
        retryMs = (retryMs * 2).coerceAtMost(30000)
    }
}
