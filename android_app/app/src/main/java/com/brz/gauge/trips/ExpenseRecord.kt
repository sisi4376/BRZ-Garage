package com.brz.gauge.trips

import java.time.YearMonth

enum class ExpenseCategory(val code: Int, val title: String) {
    MAINTENANCE(1, "保养"),
    DAILY(2, "日常花费");

    companion object {
        fun fromCode(code: Int): ExpenseCategory = entries.firstOrNull { it.code == code } ?: DAILY
    }
}

enum class DailyExpenseKind(val title: String, val iconRes: Int) {
    PARKING("停车", R.drawable.ic_expense_parking),
    TOLL("通行", R.drawable.ic_expense_toll),
    CARE("车辆洗护", R.drawable.ic_expense_care),
    REPAIR("维修", R.drawable.ic_expense_repair),
    ACCESSORY("配件用品", R.drawable.ic_expense_accessory),
    INSURANCE("保险", R.drawable.ic_expense_insurance),
    PAPERWORK("证件手续", R.drawable.ic_expense_paperwork),
    VIOLATION("违章", R.drawable.ic_expense_violation),
    OTHER("其他", R.drawable.ic_expense_other);

    companion object {
        fun matchTitle(value: String): DailyExpenseKind? =
            entries.firstOrNull { it.title == value } ?:
                ACCESSORY.takeIf { value == "车辆配件" }

        fun fromTitle(value: String): DailyExpenseKind = matchTitle(value) ?: OTHER
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
    val maintenanceType: MaintenanceRecordType? = null,
)

/** User-selected heading, independent of the services actually carried out. */
enum class MaintenanceRecordType(val code: Int, val title: String) {
    A(1, "A保"), B(2, "B保"), TIRES(3, "轮胎更换"), REPAIR(4, "其他维修");

    companion object {
        fun fromCode(code: Int): MaintenanceRecordType? = entries.firstOrNull { it.code == code }
    }
}

data class MonthlyExpenseSummary(
    val month: YearMonth,
    val fuel: Double,
    val maintenance: Double,
    val daily: Double,
) {
    val total: Double get() = fuel + maintenance + daily
}
