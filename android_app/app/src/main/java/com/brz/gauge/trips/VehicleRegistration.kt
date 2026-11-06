package com.brz.gauge.trips

import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONObject

internal data class VehicleRegistration(
    val registeredDay: Long, val regularPrivateCar: Boolean,
    val inspectionDueDay: Long?, val estimated: Boolean = true,
) {
    fun encode(): String = JSONObject().put("registeredDay", registeredDay)
        .put("regularPrivateCar", regularPrivateCar).put("inspectionDueDay", inspectionDueDay ?: JSONObject.NULL)
        .put("estimated", estimated).toString()

    companion object {
        fun decode(value: String?): VehicleRegistration? = try {
            if (value == null) null else JSONObject(value).let { json ->
                val registered = json.getLong("registeredDay")
                val due = if (json.isNull("inspectionDueDay")) null else json.getLong("inspectionDueDay")
                if (registered !in 0L..47482L || (due != null && due !in registered..47482L)) null
                else VehicleRegistration(registered, json.getBoolean("regularPrivateCar"), due,
                    json.optBoolean("estimated", true))
            }
        } catch (_: Exception) { null }
    }
}

/** A first estimate assumes earlier cycles were completed; keep this due date once saved. */
internal fun initialInspectionDue(registered: LocalDate, today: LocalDate): LocalDate {
    val age = (today.year - registered.year).coerceAtLeast(0)
    val milestone = when {
        age <= 2 -> 2
        age < 10 -> if (age % 2 == 0) age else age + 1
        else -> age
    }
    // Retain an anniversary earlier in the current year, so it can show overdue.
    return YearMonth.from(registered.plusYears(milestone.toLong())).atEndOfMonth()
}

/** Inclusive calendar-date suggestion. Actual policy times and short terms remain editable. */
internal fun annualInsuranceEnd(start: LocalDate): LocalDate = start.plusYears(1).minusDays(1)

internal fun insuranceRenewalStart(purchased: LocalDate, previousEnd: LocalDate?): LocalDate =
    previousEnd?.plusDays(1)?.takeIf { it.isAfter(purchased) } ?: purchased
