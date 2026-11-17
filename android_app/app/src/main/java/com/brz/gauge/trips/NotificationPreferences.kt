package com.brz.gauge.trips

import android.content.Context

data class NotificationOptions(val inspection: Boolean = true, val insurance: Boolean = true, val maintenance: Boolean = true) {
    fun allowsReminder(key: String): Boolean = when (key.substringBefore('|')) {
        "inspection" -> inspection
        "insurance" -> insurance
        "maintenance" -> maintenance
        else -> false
    }
}

/** Device-local delivery controls; system permission is a separate prerequisite. */
object NotificationPreferences {
    fun prefs(context: Context) = context.getSharedPreferences("notification_controls", Context.MODE_PRIVATE)
    fun read(context: Context): NotificationOptions = prefs(context).let {
        NotificationOptions(it.getBoolean("inspection", true), it.getBoolean("insurance", true), it.getBoolean("maintenance", true))
    }
    fun set(context: Context, key: String, enabled: Boolean) {
        require(key in setOf("inspection", "insurance", "maintenance"))
        prefs(context).edit().putBoolean(key, enabled).apply()
        LedgerReminderJob.refresh(context)
    }
}
