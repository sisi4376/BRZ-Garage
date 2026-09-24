package com.brz.gauge.trips

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid

/**
 * A system-owned BLE scan that remains registered when the app process is reclaimed.
 * The foreground service still owns the GATT connection; this scan only wakes it
 * when the already-bound gauge starts advertising.
 */
@Suppress("MissingPermission")
object BackgroundBleWake {
    const val ACTION_GAUGE_FOUND = "com.brz.gauge.trips.GAUGE_FOUND"
    private const val REQUEST_CODE = 88
    private const val PREF_ADDRESS = "wake_scan_address"
    private const val PREF_STATUS = "wake_scan_status"
    private const val PREF_AT = "wake_scan_at"
    private const val PREF_MODE = "wake_scan_mode"
    private const val PREF_ERROR_AT = "wake_scan_error_at"
    private const val MODE_EXACT_AGGRESSIVE = 0
    private const val MODE_EXACT_FIRST_MATCH = 1
    private const val MODE_SERVICE_FIRST_MATCH = 2
    private const val MODE_SERVICE_ALL_MATCHES = 3

    private fun pendingIntent(context: Context): PendingIntent {
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, GaugeWakeReceiver::class.java).setAction(ACTION_GAUGE_FOUND),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }

    fun register(context: Context, force: Boolean = false): Boolean {
        val app = context.applicationContext
        val state = AppState(app)
        val address = state.address
        if (!state.automatic || !BluetoothAdapter.checkBluetoothAddress(address)) {
            cancel(app)
            return false
        }
        if (!TripSyncService.hasPermissions(app)) {
            record(state, "附近设备权限未授权")
            return false
        }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            record(state, "等待手机蓝牙开启")
            return false
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            record(state, "系统 BLE 扫描不可用")
            return false
        }
        val intent = pendingIntent(app)
        val registeredAddress = state.prefs.getString(PREF_ADDRESS, "")
        if (!force && registeredAddress.equals(address, true)) return true

        // One PendingIntent identifies one scan registration. Replace the old filter
        // after re-binding, reboot, Bluetooth restart, or watchdog recovery.
        try { scanner.stopScan(intent) } catch (_: RuntimeException) { }
        var compatibilityMode = state.prefs.getInt(PREF_MODE, MODE_EXACT_AGGRESSIVE)
            .coerceIn(MODE_EXACT_AGGRESSIVE, MODE_SERVICE_ALL_MATCHES)
        fun filters(mode: Int): List<ScanFilter> = listOf(
            if (mode >= MODE_SERVICE_FIRST_MATCH) {
                // Huawei/HarmonyOS may reject the controller's exact-address
                // hardware filter asynchronously (observed vendor error 108).
                // The gauge always advertises 0x1FFA, and the receiver verifies
                // the bound MAC before starting the connection service.
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(TripBleProtocol.INFO_SERVICE))
                    .build()
            } else {
                ScanFilter.Builder().setDeviceAddress(address).build()
            }
        )
        fun startWith(settings: ScanSettings): Int = try {
            scanner.startScan(filters(compatibilityMode), settings, intent)
        } catch (_: RuntimeException) {
            -1
        }
        fun settings(mode: Int): ScanSettings = ScanSettings.Builder()
            .setScanMode(if (mode == MODE_EXACT_AGGRESSIVE) {
                ScanSettings.SCAN_MODE_BALANCED
            } else {
                ScanSettings.SCAN_MODE_LOW_POWER
            })
            .setCallbackType(when (mode) {
                MODE_EXACT_AGGRESSIVE -> ScanSettings.CALLBACK_TYPE_FIRST_MATCH or
                    ScanSettings.CALLBACK_TYPE_MATCH_LOST
                MODE_SERVICE_ALL_MATCHES -> ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                else -> ScanSettings.CALLBACK_TYPE_FIRST_MATCH
            })
            .apply {
                if (mode != MODE_SERVICE_ALL_MATCHES) {
                    setMatchMode(if (mode == MODE_EXACT_AGGRESSIVE) {
                        ScanSettings.MATCH_MODE_AGGRESSIVE
                    } else {
                        ScanSettings.MATCH_MODE_STICKY
                    })
                    setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                }
            }
            .build()
        var result: Int
        while (true) {
            result = startWith(settings(compatibilityMode))
            if (result != ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ||
                compatibilityMode == MODE_SERVICE_ALL_MATCHES) break
            try { scanner.stopScan(intent) } catch (_: RuntimeException) { }
            compatibilityMode++
        }
        val ok = result == 0 || result == ScanCallback.SCAN_FAILED_ALREADY_STARTED
        if (ok) {
            state.prefs.edit().putString(PREF_ADDRESS, address)
                .putInt(PREF_MODE, compatibilityMode).apply()
            record(state, when (compatibilityMode) {
                MODE_EXACT_FIRST_MATCH -> "已启用（兼容模式）· 仪表出现时唤醒"
                MODE_SERVICE_FIRST_MATCH -> "已启用（华为 UUID 兼容模式）· 仪表出现时唤醒"
                MODE_SERVICE_ALL_MATCHES -> "已启用（基础兼容模式）· 华为 UUID · 仪表出现时唤醒"
                else -> "已启用 · 仪表出现时唤醒"
            })
        } else {
            state.prefs.edit().remove(PREF_ADDRESS).apply()
            record(state, "注册失败：$result")
        }
        return ok
    }

    /**
     * PendingIntent scan failures can be reported long after startScan returned
     * success. Values outside Android's documented 1..6 range are vendor codes.
     * Error 108 is observed on Huawei/HarmonyOS with an exact-address hardware
     * filter, so progressively remove controller-only features and re-arm once.
     */
    fun recoverFromAsyncError(context: Context, error: Int): Boolean {
        val app = context.applicationContext
        val state = AppState(app)
        val now = System.currentTimeMillis()
        val oldMode = state.prefs.getInt(PREF_MODE, MODE_EXACT_AGGRESSIVE)
            .coerceIn(MODE_EXACT_AGGRESSIVE, MODE_SERVICE_ALL_MATCHES)
        val nextMode = when {
            error == 108 && oldMode < MODE_SERVICE_FIRST_MATCH -> MODE_SERVICE_FIRST_MATCH
            oldMode < MODE_SERVICE_ALL_MATCHES -> oldMode + 1
            else -> MODE_SERVICE_ALL_MATCHES
        }
        val lastErrorAt = state.prefs.getLong(PREF_ERROR_AT, 0L)
        state.prefs.edit()
            .putInt(PREF_MODE, nextMode)
            .putLong(PREF_ERROR_AT, now)
            .remove(PREF_ADDRESS)
            .apply()
        try {
            app.getSystemService(BluetoothManager::class.java)?.adapter
                ?.bluetoothLeScanner?.stopScan(pendingIntent(app))
        } catch (_: RuntimeException) { }
        // Avoid a tight vendor callback loop if even the most conservative
        // scan is rejected immediately. The watchdog will retry after 60 s.
        if (nextMode == oldMode && now - lastErrorAt < 30_000L) return false
        return register(app, force = true)
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val state = AppState(app)
        try {
            app.getSystemService(BluetoothManager::class.java)?.adapter
                ?.bluetoothLeScanner?.stopScan(pendingIntent(app))
        } catch (_: RuntimeException) { }
        state.prefs.edit().remove(PREF_ADDRESS).putString(PREF_STATUS, "已停用").apply()
    }

    fun consumeBasicCompatibleSignal(context: Context) {
        val app = context.applicationContext
        val state = AppState(app)
        try {
            app.getSystemService(BluetoothManager::class.java)?.adapter
                ?.bluetoothLeScanner?.stopScan(pendingIntent(app))
        } catch (_: RuntimeException) { }
        state.prefs.edit().remove(PREF_ADDRESS)
            .putString(PREF_STATUS, "已收到仪表信号 · 连接服务接管")
            .putLong(PREF_AT, System.currentTimeMillis()).apply()
    }

    private fun record(state: AppState, status: String) {
        state.prefs.edit().putString(PREF_STATUS, status)
            .putLong(PREF_AT, System.currentTimeMillis()).apply()
    }
}

/** Receives a system BLE scan result even when the application process was not running. */
class GaugeWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != BackgroundBleWake.ACTION_GAUGE_FOUND) return
        val state = AppState(context)
        if (!state.automatic || state.address.isEmpty()) return
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) {
            val recovered = BackgroundBleWake.recoverFromAsyncError(context, error)
            state.prefs.edit().putString(
                "wake_scan_status",
                "系统回调错误：$error · " + if (recovered) "已切换兼容模式并重新注册" else "等待 60 秒后重试",
            ).putLong("wake_scan_at", System.currentTimeMillis()).apply()
            ServiceWatchdogReceiver.schedule(context, 60_000L)
            return
        }
        val results = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        }
        if (results.isNullOrEmpty() || results.none {
                it.device.address.equals(state.address, ignoreCase = true)
            }) return
        val callbackType = intent.getIntExtra(
            BluetoothLeScanner.EXTRA_CALLBACK_TYPE,
            ScanSettings.CALLBACK_TYPE_FIRST_MATCH,
        )
        if (callbackType and ScanSettings.CALLBACK_TYPE_MATCH_LOST != 0) {
            state.prefs.edit().putLong("gauge_signal_lost_at", System.currentTimeMillis()).apply()
            return
        }
        if (callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES) {
            BackgroundBleWake.consumeBasicCompatibleSignal(context)
        }
        state.prefs.edit().putLong("wake_at", System.currentTimeMillis())
            .putString("wake_reason", "发现仪表广播").apply()
        GaugePresenceObserver.start(context)
        ServiceWatchdogReceiver.schedule(context)
        // The PendingIntent scan result proves the bound gauge is present.
        // Connect directly instead of starting a second scan, then the GATT
        // service-discovery callback sends time before all other traffic.
        val requested = TripSyncService.wakeFromGaugeSignal(context)
        state.prefs.edit().putBoolean("wake_requested", requested).apply()
        if (!requested) {
            /* ALL_MATCHES compatibility mode consumes its one-shot system scan.
             * Re-arm it immediately when the vendor blocks foreground-service
             * startup, otherwise the next gauge power-on could be missed too. */
            BackgroundBleWake.register(context, force = true)
            ServiceWatchdogReceiver.scheduleRecovery(context)
        }
    }
}
