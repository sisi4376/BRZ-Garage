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
        val recovery = intent?.action == ACTION_RECOVER
        if (!recovery && intent?.action != ACTION_CHECK) return
        val state = AppState(context)
        state.prefs.edit()
            .putLong(if (recovery) "recovery_alarm_at" else "watchdog_at", System.currentTimeMillis())
            .apply()
        if (state.automatic && state.address.isNotEmpty() && TripSyncService.hasPermissions(context)) {
            BackgroundBleWake.register(context, force = true)
            GaugePresenceObserver.start(context)
            val requested = if (recovery) {
                TripSyncService.wakeFromGaugeSignal(
                    context,
                    reason = "息屏恢复闹钟",
                    retryWithAlarm = false,
                )
            } else {
                TripSyncService.start(context, reason = "后台自检")
            }
            state.prefs.edit()
                .putBoolean(if (recovery) "recovery_alarm_requested" else "watchdog_requested", requested)
                .apply()
            if (!requested && !recovery) scheduleRecovery(context)
        }
        schedule(context)
    }

    companion object {
        const val ACTION_CHECK = "com.brz.gauge.trips.SERVICE_WATCHDOG"
        const val ACTION_RECOVER = "com.brz.gauge.trips.SERVICE_RECOVERY"
        private const val INTERVAL_MS = 15L * 60L * 1000L
        private const val RECOVERY_DELAY_MS = 1_500L

        private fun periodicIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 87,
            Intent(context, ServiceWatchdogReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun recoveryIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 89,
            Intent(context, ServiceWatchdogReceiver::class.java).setAction(ACTION_RECOVER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun canScheduleExactRecovery(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return true
            return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        }

        fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
            val app = context.applicationContext
            val state = AppState(app)
            val alarm = app.getSystemService(AlarmManager::class.java) ?: return
            val intent = periodicIntent(app)
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

        /**
         * One-shot bridge used only after a BLE signal or service removal fails
         * to start the foreground service directly. Exact alarms are exempt
         * from Android's background foreground-service launch restriction.
         */
        fun scheduleRecovery(context: Context, delayMs: Long = RECOVERY_DELAY_MS): Boolean {
            val app = context.applicationContext
            val state = AppState(app)
            val alarm = app.getSystemService(AlarmManager::class.java) ?: return false
            val intent = recoveryIntent(app)
            if (!state.automatic || state.address.isEmpty()) {
                alarm.cancel(intent)
                return false
            }
            val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(RECOVERY_DELAY_MS)
            val exact = canScheduleExactRecovery(app)
            try {
                when {
                    Build.VERSION.SDK_INT >= 23 && exact ->
                        alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, intent)
                    Build.VERSION.SDK_INT >= 23 ->
                        alarm.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + 60_000L,
                            intent,
                        )
                    else -> alarm.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, intent)
                }
            } catch (_: SecurityException) {
                state.prefs.edit().putString("recovery_alarm_status", "系统拒绝精确唤醒权限").apply()
                return false
            }
            state.prefs.edit()
                .putString("recovery_alarm_status", if (exact) "已安排精确恢复" else "等待系统非精确恢复")
                .putLong("recovery_alarm_scheduled_at", System.currentTimeMillis())
                .apply()
            return exact
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.let { alarm ->
                alarm.cancel(periodicIntent(context))
                alarm.cancel(recoveryIntent(context))
            }
        }
    }
}
