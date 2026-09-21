package com.brz.gauge.trips

import android.annotation.TargetApi
import android.companion.CompanionDeviceService
import android.companion.AssociationInfo
import android.os.Build

/** Bound by Android when the associated gauge is nearby (API 31+). */
@TargetApi(Build.VERSION_CODES.S)
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class GaugePresenceService : CompanionDeviceService() {
    private fun wake() {
        val state = AppState(this)
        state.prefs.edit().putLong("wake_at", System.currentTimeMillis())
            .putString("wake_reason", "系统伴生设备出现").apply()
        BackgroundBleWake.register(this)
        TripSyncService.wakeFromGaugeSignal(this)
    }

    override fun onDeviceAppeared(address: String) {
        if (AppState(this).address.equals(address, true)) wake()
    }
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= 33) {
            associationInfo.deviceMacAddress?.toString()?.let { onDeviceAppeared(it) }
        }
    }

}
