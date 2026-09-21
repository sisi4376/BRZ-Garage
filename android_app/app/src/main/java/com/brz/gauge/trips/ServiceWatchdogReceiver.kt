package com.brz.gauge.trips

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/** Periodic safety net for vendor systems that reclaim the foreground BLE service. */
class ServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CHECK) return
        val state = AppState(context)
        state.prefs.edit().putLong("watchdog_at", System.currentTimeMillis()).apply()
        if (state.automatic && state.address.isNotEmpty() && TripSyncService.hasPermissions(context)) {
            BackgroundBleWake.register(context, force = true)
            GaugePresenceObserver.start(context)
            val requested = TripSyncService.start(context, reason = "后台自检")
            state.prefs.edit().putBoolean("watchdog_requested", requested).apply()
            if (!requested) {
                schedule(context, 60_000L)
                return
            }
        }
        schedule(context)
    }

    companion object {
        const val ACTION_CHECK = "com.brz.gauge.trips.SERVICE_WATCHDOG"
        private const val INTERVAL_MS = 15L * 60L * 1000L

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 87,
            Intent(context, ServiceWatchdogReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
            val app = context.applicationContext
            val state = AppState(app)
            val alarm = app.getSystemService(AlarmManager::class.java) ?: return
            val intent = pendingIntent(app)
            if (!state.automatic || state.address.isEmpty()) {
                alarm.cancel(intent)
                return
            }
            val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceIn(60_000L, INTERVAL_MS)
            if (Build.VERSION.SDK_INT >= 23) {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, intent)
            } else {
                alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, intent)
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }
    }
}
