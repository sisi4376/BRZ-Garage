package com.brz.gauge.trips

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Waiting is independently user-blockable without disabling connection or safety notifications. */
object WaitingNotification {
    const val CHANNEL = "brz_waiting_for_gauge"
    fun ensure(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "等待仪表上电", NotificationManager.IMPORTANCE_LOW).apply {
                description = "未连接仪表时的后台等待通知；可单独关闭，自动连接仍保持开启"
                setShowBadge(false)
            })
    }
    fun enabled(context: Context): Boolean {
        ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
    }
    fun settingsIntent(context: Context): Intent {
        ensure(context)
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
    }
    fun channel(activeChannel: String, connected: Boolean, updating: Boolean): String =
        if (connected || updating) activeChannel else CHANNEL
}
