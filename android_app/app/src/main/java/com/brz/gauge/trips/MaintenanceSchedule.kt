package com.brz.gauge.trips

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.round

/**
 * Replacement/addition/rotation intervals transcribed from 养护维修表update.pdf.
 * Tire replacement uses the user-confirmed reminder interval of 50,000 km / 8 years.
 *
 * The app intentionally omits inspection-only rows. The source table uses general driving
 * conditions and says to service by distance or elapsed time, whichever comes first. Rows with
 * no time value keep a null month interval so the app does not invent a date deadline.
 */
enum class MaintenanceServiceKind(
    val title: String,
    val action: String,
    val intervalKm: Int?,
    val intervalMonths: Int?,
    val firstIntervalKm: Int? = intervalKm,
    val firstIntervalMonths: Int? = intervalMonths,
    val recordTitle: String = "$action$title",
    private vararg val aliases: String,
) {
    ENGINE_OIL(
        "发动机油", "更换", 5_000, 6,
        aliases = arrayOf("发动机油及机油滤清器", "机油机滤", "常规保养", "机油"),
    ),
    ENGINE_OIL_FILTER(
        "机油滤清器", "更换", 5_000, 6,
        aliases = arrayOf("发动机油及机油滤清器", "机油机滤", "常规保养", "机油滤芯"),
    ),
    FUEL_ADDITIVE(
        "燃油系统添加剂", "添加", 5_000, null,
        aliases = arrayOf("燃油添加剂"),
    ),
    CABIN_FILTER(
        "空调滤清器", "更换", 10_000, 12,
        aliases = arrayOf("空调滤芯", "空调滤清器", "空调滤", "空气调节滤芯"),
    ),
    TIRE_ROTATION(
        "轮胎换位", "执行", 10_000, null,
        aliases = arrayOf("轮胎换位", "轮胎换位与检查", "轮胎检查"),
    ),
    TIRE_REPLACEMENT(
        "轮胎", "更换", 50_000, 96,
        aliases = arrayOf("轮胎更换", "换轮胎"),
    ),
    AIR_CLEANER_FILTER(
        "发动机空气滤芯", "更换", 20_000, 24,
        aliases = arrayOf("发动机空气滤芯", "空气滤芯", "空气滤清器", "空滤"),
    ),
    BRAKE_FLUID(
        "制动液", "更换", 30_000, 24,
        aliases = arrayOf("制动液 / 离合器液", "制动液", "刹车油", "离合器液"),
    ),
    MANUAL_TRANSMISSION_OIL(
        "手动变速箱油", "更换", 40_000, 48,
        aliases = arrayOf("更换手动变速器油", "更换齿轮油"),
    ),
    REAR_DIFFERENTIAL_OIL(
        "后差速器油", "更换", 40_000, 48,
        aliases = arrayOf("后差速器油", "差速器油", "差速油", "后桥油"),
    ),
    SPARK_PLUGS(
        "火花塞", "更换", 60_000, null,
        aliases = arrayOf("火花塞", "更换火花塞"),
    ),
    FUEL_FILTER(
        "燃油滤清器", "更换", 60_000, 36,
        aliases = arrayOf("汽油滤芯", "燃油滤芯"),
    ),
    DRIVE_BELT_REPLACEMENT(
        "驱动皮带", "更换", 100_000, 120,
        aliases = arrayOf("更换传动皮带"),
    ),
    ENGINE_COOLANT(
        "发动机冷却液", "更换", 100_000, 60,
        firstIntervalKm = 220_000, firstIntervalMonths = 132,
        aliases = arrayOf("发动机冷却液", "冷却液", "防冻液"),
    );

    fun intervalLabel(): String {
        if (intervalKm == null && intervalMonths == null) return "按实际情况更换，不设固定周期"
        if (this == TIRE_REPLACEMENT) return "50000 km / 8年"
        val regular = intervalText(intervalKm, intervalMonths)
        return if (firstIntervalKm != intervalKm || firstIntervalMonths != intervalMonths) {
            "首次 ${intervalText(firstIntervalKm, firstIntervalMonths)}，之后 $regular"
        } else {
            regular
        }
    }

    fun matches(recordValue: String): Boolean {
        val value = normalize(recordValue)
        val canonical = normalize(recordTitle)
        val tokens = splitRecordTitles(recordValue).map(::normalize)
        if (value == canonical || tokens.any { it == canonical }) return true
        return aliases.any { alias ->
            val normalizedAlias = normalize(alias)
            value == normalizedAlias || tokens.any { it == normalizedAlias }
        }
    }

    companion object {
        private fun normalize(value: String): String = value
            .replace(" ", "")
            .replace("/", "")
            .replace("·", "")

        private fun splitRecordTitles(value: String): List<String> = value.split('、', '，', ',', '；', ';')

        private fun intervalText(km: Int?, months: Int?): String = buildList {
            km?.let { add("${it} km") }
            months?.let { add("${it}个月") }
        }.joinToString(" / ")

        fun fromRecordTitle(value: String): MaintenanceServiceKind? = entries.firstOrNull { it.matches(value) }
    }
}

/** Recommends a service package around the entered odometer milestone; callers may edit it. */
fun recommendedMaintenanceKinds(odometerKm: Double): Set<MaintenanceServiceKind> {
    if (!odometerKm.isFinite() || odometerKm < 0.0) return emptySet()
    val result = linkedSetOf(
        MaintenanceServiceKind.ENGINE_OIL,
        MaintenanceServiceKind.ENGINE_OIL_FILTER,
        MaintenanceServiceKind.FUEL_ADDITIVE,
    )
    MaintenanceServiceKind.entries.forEach { kind ->
        if (kind in result) return@forEach
        if (isNearServiceMilestone(odometerKm, kind)) result += kind
    }
    return result
}

private fun isNearServiceMilestone(odometerKm: Double, kind: MaintenanceServiceKind): Boolean {
    val interval = kind.intervalKm ?: return false
    val tolerance = when {
        interval <= 10_000 -> 1_500.0
        interval <= 40_000 -> 2_500.0
        else -> 3_500.0
    }
    if (kind == MaintenanceServiceKind.ENGINE_COOLANT) {
        val first = kind.firstIntervalKm?.toDouble() ?: return false
        if (odometerKm < first - tolerance) return false
        val subsequent = interval.toDouble()
        val target = first + round((odometerKm - first) / subsequent).coerceAtLeast(0.0) * subsequent
        return abs(odometerKm - target) <= tolerance
    }
    val target = round(odometerKm / interval) * interval
    return target >= interval && abs(odometerKm - target) <= tolerance
}

enum class MaintenanceDueStatus { OVERDUE, DUE_SOON, MISSING_ODOMETER, OK, TRACKED, UNTRACKED }

/** Historical rows keep their original service text; this is only an editable display fallback. */
fun ExpenseRecord.resolvedMaintenanceType(): MaintenanceRecordType {
    maintenanceType?.let { return it }
    MaintenanceRecordType.entries.firstOrNull { it.title == title.trim() }?.let { return it }
    val kinds = MaintenanceServiceKind.entries.filter { it.matches(title) }
    if (MaintenanceServiceKind.TIRE_REPLACEMENT in kinds) return MaintenanceRecordType.TIRES
    val basic = setOf(MaintenanceServiceKind.ENGINE_OIL, MaintenanceServiceKind.ENGINE_OIL_FILTER,
        MaintenanceServiceKind.FUEL_ADDITIVE)
    if (kinds.any { it !in basic }) return MaintenanceRecordType.B
    return if (kinds.isNotEmpty()) MaintenanceRecordType.A else MaintenanceRecordType.REPAIR
}

data class MaintenanceDueState(
    val kind: MaintenanceServiceKind,
    val lastRecord: ExpenseRecord?,
    val dueDate: LocalDate?,
    val dueOdometerKm: Double?,
    val daysRemaining: Long?,
    val kmRemaining: Double?,
    val status: MaintenanceDueStatus,
    val daysSinceService: Long? = null,
    val kmSinceService: Double? = null,
)

fun calculateMaintenanceDueStates(
    records: List<ExpenseRecord>,
    currentOdometerKm: Double?,
    today: LocalDate,
): List<MaintenanceDueState> = MaintenanceServiceKind.entries.map { kind ->
    val latest = records
        .filter { it.category == ExpenseCategory.MAINTENANCE && kind.matches(it.title) }
        .maxWithOrNull(compareBy<ExpenseRecord> { it.dateEpochDay }.thenBy { it.id })
    if (latest == null) {
        if (kind.intervalKm == null && kind.intervalMonths == null) {
            // No replacement record means the original tires start at 0 km; their date is unknown.
            return@map MaintenanceDueState(kind, null, null, null, null, null,
                if (currentOdometerKm != null) MaintenanceDueStatus.TRACKED else MaintenanceDueStatus.UNTRACKED,
                kmSinceService = currentOdometerKm)
        }
        val dueKm = kind.firstIntervalKm?.toDouble()
        val km = if (currentOdometerKm != null && dueKm != null) dueKm - currentOdometerKm else null
        val status = when {
            km == null -> MaintenanceDueStatus.UNTRACKED
            km <= 0.0 -> MaintenanceDueStatus.OVERDUE
            km <= 1_000.0 -> MaintenanceDueStatus.DUE_SOON
            else -> MaintenanceDueStatus.OK
        }
        MaintenanceDueState(kind, null, null, dueKm, null, km, status,
            kmSinceService = if (kind == MaintenanceServiceKind.TIRE_REPLACEMENT) currentOdometerKm else null)
    } else if (kind.intervalKm == null && kind.intervalMonths == null) {
        // Track usage without implying a tire lifespan, deadline or condition assessment.
        MaintenanceDueState(kind, latest, null, null, null, null, MaintenanceDueStatus.TRACKED,
            daysSinceService = ChronoUnit.DAYS.between(LocalDate.ofEpochDay(latest.dateEpochDay), today),
            kmSinceService = if (currentOdometerKm != null && latest.odometerKm != null)
                currentOdometerKm - latest.odometerKm else null)
    } else {
        val dueDate = kind.intervalMonths?.let {
            LocalDate.ofEpochDay(latest.dateEpochDay).plusMonths(it.toLong())
        }
        val dueKm = if (kind.intervalKm != null && latest.odometerKm != null) {
            latest.odometerKm + kind.intervalKm
        } else null
        val days = dueDate?.let { ChronoUnit.DAYS.between(today, it) }
        val km = if (currentOdometerKm != null && dueKm != null) dueKm - currentOdometerKm else null
        val status = when {
            days != null && days < 0 -> MaintenanceDueStatus.OVERDUE
            km != null && km < 0.0 -> MaintenanceDueStatus.OVERDUE
            days != null && days <= 30 -> MaintenanceDueStatus.DUE_SOON
            km != null && km <= 1_000.0 -> MaintenanceDueStatus.DUE_SOON
            kind.intervalMonths == null && (latest.odometerKm == null || currentOdometerKm == null) ->
                MaintenanceDueStatus.MISSING_ODOMETER
            else -> MaintenanceDueStatus.OK
        }
        MaintenanceDueState(kind, latest, dueDate, dueKm, days, km, status,
            daysSinceService = if (kind == MaintenanceServiceKind.TIRE_REPLACEMENT)
                ChronoUnit.DAYS.between(LocalDate.ofEpochDay(latest.dateEpochDay), today) else null,
            kmSinceService = if (kind == MaintenanceServiceKind.TIRE_REPLACEMENT &&
                currentOdometerKm != null && latest.odometerKm != null) currentOdometerKm - latest.odometerKm else null)
    }
}.sortedWith(compareBy<MaintenanceDueState> {
    when (it.status) {
        MaintenanceDueStatus.OVERDUE -> 0
        MaintenanceDueStatus.DUE_SOON -> 1
        MaintenanceDueStatus.MISSING_ODOMETER -> 2
        MaintenanceDueStatus.OK -> 3
        MaintenanceDueStatus.TRACKED -> 4
        MaintenanceDueStatus.UNTRACKED -> 5
    }
}.thenBy { it.daysRemaining ?: Long.MAX_VALUE }.thenBy { it.kind.ordinal })
