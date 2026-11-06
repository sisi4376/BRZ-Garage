package com.brz.gauge.trips

import java.time.LocalDate

internal data class LedgerReminder(val key: String, val title: String, val dueDay: Long,
    val windowDay: Long? = null) {
    fun offset(today: LocalDate): Long = dueDay - today.toEpochDay()
    fun message(today: LocalDate): String = "$title · " + when {
        offset(today) < 0 -> "已到期 ${-offset(today)} 天，请核实办理"
        offset(today) == 0L -> "今天到期"
        else -> "还有 ${offset(today)} 天到期"
    }
}

/** No persisted schedule copies: each check derives deadlines from the latest ledger records. */
internal fun ledgerReminders(records: List<ExpenseRecord>, today: LocalDate,
    registrations: Map<String, VehicleRegistration> = emptyMap()): List<LedgerReminder> {
    val result = mutableListOf<LedgerReminder>()
    deadlineTracks(records, today, registrations).filter { it.record.section in setOf("insurance", "inspection") }.forEach { track ->
        val record = track.record
        track.reminderDay?.let { due ->
            val name = if (record.section == "insurance") insuranceName(record.itemName) else "机动车年检"
            val inspectionTitle = if (record.section == "inspection") inspectionAction(
                record.startEpochDay?.let(LocalDate::ofEpochDay), LocalDate.ofEpochDay(due), record.itemName == PRIVATE_INSPECTION)
                .let { if (it.startsWith("免上线")) "免上线领标" else if (it.startsWith("需上线")) "上线年检" else "年检 / 领标（请核实）" } else ""
            result += LedgerReminder("${record.section}|${record.deviceId}|$name|$due",
                if (record.section == "insurance") "保险续保 · $name" else inspectionTitle,
                due, if (record.section == "inspection") inspectionWindow(LocalDate.ofEpochDay(due)).toEpochDay() else null)
        }
    }
    records.filter { !it.income && it.category == ExpenseCategory.MAINTENANCE }.groupBy { it.deviceId }.forEach { (device, rows) ->
        calculateMaintenanceDueStates(rows, null, today).forEach { state ->
            state.dueDate?.toEpochDay()?.let { due -> result += LedgerReminder("maintenance|$device|${state.kind.name}|$due",
                "保养 · ${state.kind.title}", due) }
        }
    }
    return result
}
internal val reminderOffsets = setOf(30L, 7L, 3L, 0L)
/** Catch up once in the current urgency band after background restrictions or missed days. */
internal fun LedgerReminder.deliveryStage(today: LocalDate): Long? = when {
    offset(today) < 0 -> -1L
    offset(today) == 0L -> 0L
    offset(today) <= 3 -> 3L
    offset(today) <= 7 -> 7L
    offset(today) <= 30 -> 30L
    windowDay != null && today.toEpochDay() >= windowDay -> 90L
    else -> null
}
internal fun LedgerReminder.deliveryKey(today: LocalDate): String = "$key|${deliveryStage(today)}"
