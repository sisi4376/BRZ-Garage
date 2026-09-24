package com.brz.gauge.trips

import java.time.YearMonth

enum class ExpenseCategory(val code: Int, val title: String) {
    MAINTENANCE(1, "保养"),
    DAILY(2, "日常花费");

    companion object {
        fun fromCode(code: Int): ExpenseCategory = entries.firstOrNull { it.code == code } ?: DAILY
    }
}

data class ExpenseRecord(
    val id: Long = 0,
    val deviceId: String,
    val dateEpochDay: Long,
    val category: ExpenseCategory,
    val amount: Double,
    val title: String,
    val note: String = "",
    val odometerKm: Double? = null,
)

data class MonthlyExpenseSummary(
    val month: YearMonth,
    val fuel: Double,
    val maintenance: Double,
    val daily: Double,
) {
    val total: Double get() = fuel + maintenance + daily
}
