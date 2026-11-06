package com.brz.gauge.trips

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDate
import java.util.concurrent.Executors

class LedgerReminderJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        executor.execute {
            var retry = false
            try { checkNow(this) } catch (_: RuntimeException) { retry = true }
            finally { jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB = 44001
        private const val NOTICE = 44002
        private const val CHANNEL = "ledger_deadlines"
        private val executor = Executors.newSingleThreadExecutor()
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB) == null) scheduler.schedule(JobInfo.Builder(JOB,
                ComponentName(context, LedgerReminderJob::class.java)).setPersisted(true)
                .setPeriodic(6 * 60 * 60 * 1000L).build())
        }
        fun refresh(context: Context) {
            val app = context.applicationContext
            schedule(app)
            executor.execute { runCatching { checkNow(app) } }
        }
        @Synchronized private fun checkNow(context: Context) {
            val state = AppState(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!state.hasAcceptedCurrentUserNotice) { manager.cancel(NOTICE); return }
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "保险、年检与保养提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "年检办理窗口、到期前30/7/3天、当天及逾期；错过节点后补提醒一次"
            })
            val records = ExpenseDatabase(context).use { it.allForVehicle("") }.map {
                if (it.deviceId == "local" && state.dataDeviceId.isNotBlank()) it.copy(deviceId = state.dataDeviceId) else it
            }
            val today = LocalDate.now()
            val reminders = ledgerReminders(records, today, state.vehicleRegistrations())
            val prefs = context.getSharedPreferences("ledger_reminder_delivery", Context.MODE_PRIVATE)
            val sent = prefs.getStringSet("sent", emptySet()).orEmpty().toMutableSet()
            val oldVisible = prefs.getStringSet("visible", emptySet()).orEmpty()
            val due = reminders.filter { it.deliveryStage(today) != null }
            val fresh = due.filter { it.deliveryKey(today) !in sent }
            val visible = reminders.filter { it.key in oldVisible }.toMutableList()
            fresh.forEach { item -> if (visible.none { it.key == item.key }) visible += item }
            if (visible.isEmpty()) { manager.cancel(NOTICE); prefs.edit().remove("visible").apply(); return }
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
            val content = visible.joinToString("\n") { it.message(today) }
            if (fresh.isEmpty() && visible.map { it.key }.toSet() == oldVisible && prefs.getString("content", "") == content) return
            val intent = Intent(context, MainActivity::class.java).putExtra("open_bookkeeping", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, NOTICE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(NOTICE, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_expense_care).setContentTitle("用车到期提醒")
                .setContentText(content).setStyle(Notification.BigTextStyle().bigText(content))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(fresh.isEmpty()).build())
            fresh.forEach { sent += it.deliveryKey(today) }
            // Drop obsolete cycles; switching modes and editing notes never create new delivery IDs.
            val activePrefixes = reminders.map { it.key + "|" }
            sent.removeAll { key -> activePrefixes.none { key.startsWith(it) } }
            prefs.edit().putStringSet("sent", sent).putStringSet("visible", visible.map { it.key }.toSet())
                .putString("content", content).apply()
        }
    }
}
