package com.brz.gauge.trips

import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Version-aware registration for Android's companion-device presence wake-up. */
@Suppress("DEPRECATION")
object GaugePresenceObserver {
    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    fun hasAssociation(context: Context, address: String = AppState(context).address): Boolean {
        if (address.isEmpty() || !isSupported(context)) return false
        val manager = context.applicationContext
            .getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.myAssociations.any {
                    it.deviceMacAddress?.toString().equals(address, ignoreCase = true)
                }
            } else {
                manager.associations.any { it.equals(address, ignoreCase = true) }
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun associationId(context: Context, address: String): Int? {
        if (Build.VERSION.SDK_INT < 33) return null
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return null
        return try {
            manager.myAssociations.firstOrNull {
                it.deviceMacAddress?.toString().equals(address, ignoreCase = true)
            }?.id
        } catch (_: RuntimeException) {
            null
        }
    }

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
        if (!hasAssociation(app, state.address)) {
            state.prefs.edit().putString("presence", "未建立系统伴生关联 · 请重新绑定").apply()
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= 36) {
                val associationId = associationId(app, state.address) ?: return false
                manager.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder()
                        .setAssociationId(associationId)
                        .build()
                )
                state.prefs.edit().putString("presence", "已启用 · 系统关联 #$associationId").apply()
            } else {
                manager.startObservingDevicePresence(state.address)
                state.prefs.edit().putString("presence", "已启用 · 系统关联有效").apply()
            }
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
            if (Build.VERSION.SDK_INT >= 36) {
                associationId(app, address)?.let {
                    manager.stopObservingDevicePresence(
                        ObservingDevicePresenceRequest.Builder().setAssociationId(it).build()
                    )
                }
            } else if (hasAssociation(app, address)) {
                manager.stopObservingDevicePresence(address)
            }
        } catch (_: RuntimeException) { }
        state.prefs.edit().putString("presence", "已停用").apply()
    }
}
