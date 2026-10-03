package com.brz.gauge.trips

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Establishes a real LE bond in addition to Android's logical companion-device
 * association. Huawei/HarmonyOS can use the bonded device as a system-owned
 * Bluetooth identity even while the Android compatibility process is absent.
 */
@Suppress("DEPRECATION", "MissingPermission")
object GaugeBondManager {
    enum class SetupResult { READY, STARTED, UNAVAILABLE }

    fun isBonded(context: Context, address: String = AppState(context).address): Boolean {
        if (!BluetoothAdapter.checkBluetoothAddress(address) || !hasConnectPermission(context)) {
            return false
        }
        return try {
            remoteDevice(context, address)?.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: RuntimeException) {
            false
        }
    }

    fun status(context: Context, address: String = AppState(context).address): String {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return "尚未绑定仪表"
        if (!hasConnectPermission(context)) return "附近设备权限未授权"
        val state = AppState(context)
        return try {
            when (remoteDevice(context, address)?.bondState) {
                BluetoothDevice.BOND_BONDED -> "已完成系统 LE 配对"
                BluetoothDevice.BOND_BONDING -> "正在建立系统 LE 配对"
                else -> state.prefs.getString(PREF_STATUS, "尚未建立系统 LE 配对")
                    ?: "尚未建立系统 LE 配对"
            }
        } catch (_: RuntimeException) {
            state.prefs.getString(PREF_STATUS, "无法读取系统配对状态")
                ?: "无法读取系统配对状态"
        }
    }

    fun ensureBond(context: Context, address: String = AppState(context).address): SetupResult {
        val app = context.applicationContext
        val state = AppState(app)
        if (!BluetoothAdapter.checkBluetoothAddress(address) || !hasConnectPermission(app)) {
            record(state, "系统 LE 配对不可用 · 使用兼容模式")
            return SetupResult.UNAVAILABLE
        }
        val device = try {
            remoteDevice(app, address)
        } catch (_: RuntimeException) {
            null
        } ?: run {
            record(state, "无法取得仪表蓝牙设备 · 使用兼容模式")
            return SetupResult.UNAVAILABLE
        }
        return when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                record(state, "已完成系统 LE 配对")
                SetupResult.READY
            }
            BluetoothDevice.BOND_BONDING -> {
                record(state, "正在建立系统 LE 配对")
                SetupResult.STARTED
            }
            else -> {
                val requested = try {
                    if (Build.VERSION.SDK_INT >= 23) {
                        device.createBond(BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.createBond()
                    }
                } catch (_: RuntimeException) {
                    false
                }
                if (requested) {
                    record(state, "正在建立系统 LE 配对 · 请确认系统提示")
                    SetupResult.STARTED
                } else {
                    record(state, "系统拒绝 LE 配对 · 使用兼容模式")
                    SetupResult.UNAVAILABLE
                }
            }
        }
    }

    private fun remoteDevice(context: Context, address: String): BluetoothDevice? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.let { adapter ->
            if (!adapter.isEnabled) null else adapter.getRemoteDevice(address)
        }

    private fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun record(state: AppState, value: String) {
        state.prefs.edit().putString(PREF_STATUS, value)
            .putLong(PREF_AT, System.currentTimeMillis()).apply()
    }

    private const val PREF_STATUS = "gauge_bond_status"
    private const val PREF_AT = "gauge_bond_at"
}

/** Continues the main-app wake registration after asynchronous system pairing. */
class GaugeBondReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        val state = AppState(context)
        if (!state.automatic || !state.address.equals(device.address, ignoreCase = true)) return

        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
            BluetoothDevice.BOND_BONDED -> {
                state.prefs.edit().putString("gauge_bond_status", "已完成系统 LE 配对")
                    .putLong("gauge_bond_at", System.currentTimeMillis()).apply()
                GaugePresenceObserver.start(context)
                BackgroundBleWake.register(context, force = true)
                ServiceWatchdogReceiver.schedule(context)
                TripSyncService.start(context, manual = true, reason = "仪表系统配对完成")
            }
            BluetoothDevice.BOND_NONE -> {
                state.prefs.edit().putString("gauge_bond_status", "系统 LE 配对未完成 · 使用兼容模式")
                    .putLong("gauge_bond_at", System.currentTimeMillis()).apply()
                GaugePresenceObserver.start(context)
                BackgroundBleWake.register(context, force = true)
                ServiceWatchdogReceiver.schedule(context)
                TripSyncService.start(context, manual = true, reason = "系统配对未完成，兼容模式")
            }
        }
    }
}
