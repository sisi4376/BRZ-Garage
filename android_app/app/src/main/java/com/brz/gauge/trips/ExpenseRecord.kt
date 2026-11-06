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
    INSPECTION("年审", R.drawable.ic_expense_paperwork),
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
    val ledgerKind: String = "",
    val income: Boolean = false,
    val dueEpochDay: Long? = null,
    val startEpochDay: Long? = null,
    val party: String = "",
    val itemName: String = "",
    val assetRef: String = "",
) {
    internal var transferId: String = ""
    val isAssetCancellation: Boolean get() = ledgerKind == "asset_cancel"
    val isAssetEvent: Boolean get() = isAsset || isAssetExit || isAssetCancellation
    val isAssetExit: Boolean get() = ledgerKind in setOf("asset_sale", "asset_retire")
    val isAsset: Boolean get() = !income && !isAssetExit && !isAssetCancellation && section == "asset"
    val isRepair: Boolean get() = ledgerKind == "repair" ||
        (category == ExpenseCategory.DAILY && title == "维修") ||
        (category == ExpenseCategory.MAINTENANCE && resolvedMaintenanceType() == MaintenanceRecordType.REPAIR)
    val isMaintenance: Boolean get() = !income && (category == ExpenseCategory.MAINTENANCE || isRepair)
    val section: String get() = when {
        isAssetExit || isAssetCancellation -> "asset"
        income -> "income"
        isMaintenance -> "maintenance"
        ledgerKind.isNotEmpty() -> ledgerKind
        title == "保险" -> "insurance"
        title == "年审" -> "inspection"
        title == "证件手续" -> "paperwork"
        title == "配件用品" || title == "车辆配件" -> "asset"
        else -> "daily"
    }
    val isFixed: Boolean get() = section in setOf("maintenance", "insurance", "inspection", "paperwork", "asset")
}

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
    val assets: Double = 0.0,
    val income: Double = 0.0,
) {
    val total: Double get() = fuel + maintenance + daily + assets
    val netExpense: Double get() = total - income
}

/** The chart uses gross spending; income is applied exactly once to net expense. */
fun summarizeExpenseMonth(month: YearMonth, fuel: Double, records: List<ExpenseRecord>, refined: Boolean): MonthlyExpenseSummary {
    val rows=effectiveAssetLedger(records).filter { YearMonth.from(java.time.LocalDate.ofEpochDay(it.dateEpochDay))==month }
    val spending=rows.filter { !it.income && !it.isAssetExit }
    val fixed=spending.filter { if(refined) it.isFixed && !it.isAsset else it.isMaintenance }
    val assets=if(refined) spending.filter { it.isAsset } else emptyList()
    val daily=spending.filter { it !in fixed && it !in assets }
    return MonthlyExpenseSummary(month,fuel,fixed.sumOf { it.amount },daily.sumOf { it.amount },
        assets.sumOf { it.amount },rows.filter { it.income }.sumOf { it.amount })
}

/** Append-only asset history; cancellations change projections without erasing original rows. */
fun effectiveAssetLedger(records: List<ExpenseRecord>): List<ExpenseRecord> {
    val cancelled=records.filter { it.isAssetCancellation }.map { it.assetRef }.toSet()
    return records.filter { !it.isAssetCancellation && it.transferId !in cancelled }
}

/** Map sections by meaning, not by their different tab positions. */
fun accountingSectionAfterSwitch(section: Int, fromRefined: Boolean, fixedSection: String = ""): Int =
    if(fromRefined) when(section) { 0 -> 0; 1 -> if(fixedSection in setOf("insurance","inspection","paperwork")) 2 else 1; 2,3 -> 2; else -> 3 }
    else when(section) { 0 -> 0; 1 -> 1; 2 -> 3; else -> 4 }
