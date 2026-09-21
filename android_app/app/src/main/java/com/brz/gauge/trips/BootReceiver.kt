package com.brz.gauge.trips

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.os.UserManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        val bluetoothOn = action == BluetoothAdapter.ACTION_STATE_CHANGED &&
            intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
        val accepted = action in setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT,
            ACTION_HUAWEI_QUICKBOOT,
        ) || bluetoothOn
        if (!accepted) return

        /* Credential-encrypted trip/config storage is intentionally not opened
         * before the first unlock. Remember that the boot broadcast arrived;
         * USER_UNLOCKED/BOOT_COMPLETED will perform the real startup safely. */
        val users = context.getSystemService(UserManager::class.java)
        if (Build.VERSION.SDK_INT >= 24 && users?.isUserUnlocked == false) {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(DIRECT_BOOT_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("start_after_unlock", true)
                .putLong("locked_boot_at", System.currentTimeMillis()).apply()
            return
        }

        val state = AppState(context)
        val reason = when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> "手机开机（待解锁）"
            Intent.ACTION_BOOT_COMPLETED -> "手机开机"
            Intent.ACTION_USER_UNLOCKED -> "手机首次解锁"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "应用升级"
            Intent.ACTION_USER_PRESENT -> "手机解锁"
            ACTION_QUICKBOOT, ACTION_HUAWEI_QUICKBOOT -> "手机快速开机"
            else -> "蓝牙开启"
        }
        state.prefs.edit().putLong("autostart_broadcast_at", System.currentTimeMillis())
            .putString("autostart_broadcast_action", action ?: "unknown")
            .putString("autostart_reason", reason).apply()
        // Do not override the user's pause switch or start before initial pairing/permissions.
        if (!state.automatic || state.address.isEmpty()) {
            state.prefs.edit().putString("autostart_result", "未启用自动连接或尚未绑定仪表").apply()
            return
        }
        ServiceWatchdogReceiver.schedule(context, 60_000L)
        if (!TripSyncService.hasPermissions(context)) {
            state.prefs.edit().putString("autostart_result", "附近设备权限未授权").apply()
            return
        }
        state.prefs.edit().putLong("autostart_at", System.currentTimeMillis())
            .putString("autostart_result", "正在恢复后台连接").apply()
        BackgroundBleWake.register(context, force = true)
        GaugePresenceObserver.start(context)
        val requested = TripSyncService.start(context, reason = reason)
        state.prefs.edit().putBoolean("autostart_requested", requested)
            .putString("autostart_result", if (requested) "已请求启动后台服务" else "系统拒绝后台启动").apply()
        if (!requested) ServiceWatchdogReceiver.schedule(context, 60_000L)
    }

    companion object {
        private const val DIRECT_BOOT_PREFS = "brz_direct_boot"
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HUAWEI_QUICKBOOT = "com.huawei.intent.action.QUICKBOOT_POWERON"
    }
}
