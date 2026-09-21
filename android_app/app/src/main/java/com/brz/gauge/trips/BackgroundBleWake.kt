package com.brz.gauge.trips

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

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
        val filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build())
        fun startWith(settings: ScanSettings): Int = try {
            scanner.startScan(filters, settings, intent)
        } catch (_: RuntimeException) {
            -1
        }
        var compatibilityMode = 0
        var result = startWith(
            ScanSettings.Builder()
                    // The filter contains one exact, already-bound MAC address,
                    // so BALANCED + aggressive matching gives a much faster car
                    // power-on wake without turning this into an unfiltered scan.
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or
                        ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .build()
        )
        if (result == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) {
            // Some Huawei/HarmonyOS Bluetooth stacks reject MATCH_LOST (error
            // 4) even though the proven FIRST_MATCH scan works. Retry once with
            // the conservative settings instead of leaving wake-up disabled.
            try { scanner.stopScan(intent) } catch (_: RuntimeException) { }
            compatibilityMode = 1
            result = startWith(
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                    .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .build()
            )
        }
        if (result == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) {
            // A few vendor stacks do not implement FIRST_MATCH at all. The
            // basic ALL_MATCHES callback is universally available. It remains
            // filtered to the single bound MAC and is consumed after the first
            // signal, so it cannot keep waking the app for every advertisement.
            try { scanner.stopScan(intent) } catch (_: RuntimeException) { }
            compatibilityMode = 2
            result = startWith(
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build()
            )
        }
        val ok = result == 0 || result == ScanCallback.SCAN_FAILED_ALREADY_STARTED
        if (ok) {
            state.prefs.edit().putString(PREF_ADDRESS, address).apply()
            record(state, when (compatibilityMode) {
                1 -> "已启用（兼容模式）· 仪表出现时唤醒"
                2 -> "已启用（基础兼容模式）· 仪表出现时唤醒"
                else -> "已启用 · 仪表出现时唤醒"
            })
        } else {
            state.prefs.edit().remove(PREF_ADDRESS).apply()
            record(state, "注册失败：$result")
        }
        return ok
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
            state.prefs.edit().putString("wake_scan_status", "系统回调错误：$error")
                .putLong("wake_scan_at", System.currentTimeMillis()).apply()
            ServiceWatchdogReceiver.schedule(context, 60_000L)
            return
        }
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
            ServiceWatchdogReceiver.schedule(context, 60_000L)
        }
    }
}
