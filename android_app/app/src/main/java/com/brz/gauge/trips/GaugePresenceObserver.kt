package com.brz.gauge.trips

import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Version-aware registration for Android's companion-device presence wake-up. */
@Suppress("DEPRECATION")
object GaugePresenceObserver {
    fun start(context: Context): Boolean {
        val app = context.applicationContext
        val state = AppState(app)
        if (Build.VERSION.SDK_INT < 31 || state.address.isEmpty() || !state.automatic) {
            state.prefs.edit().putString("presence", "使用系统 BLE 唤醒").apply()
            return false
        }
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            state.prefs.edit().putString("presence", "系统不支持 · 使用 BLE 唤醒").apply()
            return false
        }
        val manager = app.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            manager.startObservingDevicePresence(state.address)
            state.prefs.edit().putString("presence", "已启用").apply()
            true
        } catch (_: RuntimeException) {
            state.prefs.edit().putString("presence", "不可用 · 使用系统 BLE 唤醒").apply()
            false
        }
    }

    fun stop(context: Context, address: String = AppState(context).address) {
        if (Build.VERSION.SDK_INT < 31 || address.isEmpty()) return
        val app = context.applicationContext
        val state = AppState(app)
        val manager = app.getSystemService(CompanionDeviceManager::class.java) ?: return
        try {
            manager.stopObservingDevicePresence(address)
        } catch (_: RuntimeException) { }
        state.prefs.edit().putString("presence", "已停用").apply()
    }
}
