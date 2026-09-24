package com.brz.gauge.trips

import android.annotation.TargetApi
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build

/** Android 16+ association-id presence callback. Kept in its own class so
 * Android 12-15 never need to resolve API-36-only method parameter types. */
@TargetApi(36)
class GaugePresenceServiceModern : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event != DevicePresenceEvent.EVENT_BLE_APPEARED &&
            event.event != DevicePresenceEvent.EVENT_BT_CONNECTED) return
        val manager = getSystemService(CompanionDeviceManager::class.java) ?: return
        val association = try {
            manager.myAssociations.firstOrNull { it.id == event.associationId }
        } catch (_: RuntimeException) {
            null
        } ?: return
        val address = association.deviceMacAddress?.toString() ?: return
        if (!AppState(this).address.equals(address, ignoreCase = true)) return
        val state = AppState(this)
        state.prefs.edit().putLong("wake_at", System.currentTimeMillis())
            .putString("wake_reason", "系统伴生设备出现（关联 #${event.associationId}）").apply()
        BackgroundBleWake.register(this)
        ServiceWatchdogReceiver.schedule(this)
        TripSyncService.wakeFromGaugeSignal(this, reason = "系统伴生设备出现")
    }
}
