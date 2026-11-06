package com.brz.gauge.trips

import java.time.LocalDate
import java.time.YearMonth

/** Explicit opt-in item name; legacy free-text records keep manual semantics. */
internal const val PRIVATE_INSPECTION = "常规私家车检验"
internal const val MANUAL_INSPECTION = "其他 / 特殊车辆检验"
internal fun insuranceName(name: String): String = when (val value = name.trim()) {
    "机动车交通事故责任强制保险", "机动车交强险", "交强保险", "交强险" -> "交强险"
    "商业车险", "车辆商业险", "商业保险", "商业险" -> "商业险"
    else -> value.ifBlank { "未分类保险" }
}

internal data class DeadlineTrack(
    val record: ExpenseRecord, val dueDay: Long?, val status: String,
    val detail: String = "", val reminderDay: Long? = dueDay,
    val fromVehicleInfo: Boolean = false,
)

/** Mainland China regular private passenger cars only. Official validity always wins. */
internal fun inspectionAction(registered: LocalDate?, due: LocalDate, regular: Boolean): String {
    if (!regular || registered == null) return "办理方式待核实 · 以交管12123为准"
    val age = due.year - registered.year
    if (due.month != registered.month || age < 2 || (age < 10 && age % 2 != 0))
        return "周期与常规规则不一致 · 请核对交管12123"
    return if (age in listOf(2, 4, 8)) "免上线 · 仍须申领检验标志" else "需上线安全技术检验"
}

internal fun inspectionWindow(due: LocalDate): LocalDate =
    YearMonth.from(due).minusMonths(2).atDay(1)

/** Advance only from a confirmed completed deadline, never from today's date. */
internal fun nextInspectionDue(registered: LocalDate, completedDue: LocalDate): LocalDate? {
    if (inspectionAction(registered, completedDue, true).startsWith("周期")) return null
    val age = completedDue.year - registered.year
    val next = if (age < 10) age + 2 else age + 1
    return YearMonth.from(registered.plusYears(next.toLong())).atEndOfMonth()
}

internal fun deadlineTracks(records: List<ExpenseRecord>, today: LocalDate,
    registrations: Map<String, VehicleRegistration> = emptyMap()): List<DeadlineTrack> {
    val day = today.toEpochDay()
    fun status(due: Long?) = when {
        due == null -> "待补充到期日期"
        due < day -> "已到期 ${day - due} 天"
        due == day -> "今天到期"
        else -> "剩余 ${due - day} 天"
    }
    val result = mutableListOf<DeadlineTrack>()
    val outgoings = records.filter { !it.income }
    outgoings.filter { it.section == "insurance" }
        .groupBy { it.deviceId to insuranceName(it.itemName) }.forEach { (_, rows) ->
            val dated = rows.filter { it.dueEpochDay != null &&
                (it.startEpochDay == null || it.startEpochDay <= it.dueEpochDay) }
            val active = dated.filter { it.startEpochDay != null && it.startEpochDay <= day && it.dueEpochDay!! >= day }
                .maxByOrNull { it.dueEpochDay!! }
            // Without starts we cannot prove that a later receipt replaces the
            // nearest known expiry. Keep the conservative outstanding deadline.
            val unknown = dated.filter { it.startEpochDay == null && it.dueEpochDay!! >= day }
                .minByOrNull { it.dueEpochDay!! }
            val past = dated.filter { it.dueEpochDay!! < day }.maxByOrNull { it.dueEpochDay!! }
            val future = dated.filter { it.startEpochDay != null && it.startEpochDay > day }.minByOrNull { it.startEpochDay!! }
            val current = active ?: past ?: unknown ?: future
            if (current == null) {
                val last = rows.maxWith(compareBy<ExpenseRecord> { it.dateEpochDay }.thenBy { it.id })
                result += DeadlineTrack(last, null, "待补充保单保障日期", "付款日期不代表保障生效日期")
            } else {
                var end = current.dueEpochDay!!
                // Only explicit start/end dates establish a continuous renewal chain.
                if (current.startEpochDay != null) {
                    for (policy in dated.filter { it.startEpochDay != null }.sortedBy { it.startEpochDay }) {
                        if (policy.startEpochDay!! <= end + 1 && policy.dueEpochDay!! > end) end = policy.dueEpochDay
                    }
                }
                val pending = future?.takeIf { it.id != current.id || it != current }
                val detail = buildList {
                    if (end > current.dueEpochDay!!) add("已记录连续续保，后续保障至 ${LocalDate.ofEpochDay(end)}")
                    else if (pending != null) add("后续保单 ${LocalDate.ofEpochDay(pending.startEpochDay!!)} 生效；日期存在空档，请核对")
                    if (current.startEpochDay == null) add("生效日期未知，不能确认当前保障状态")
                    if (rows.any { it.dueEpochDay == null }) add("另有保单缺少到期日，未用于取消已有提醒")
                    if (current.startEpochDay != null && rows.any { it != current && it.startEpochDay == null })
                        add("另有保单缺少生效日，未认定为连续续保")
                    add("按日期追踪，起止时刻及承保状态以保单为准")
                }.joinToString("；")
                val label = when {
                    current.startEpochDay != null && current.startEpochDay > day -> "待生效 · ${LocalDate.ofEpochDay(current.startEpochDay)}"
                    current.startEpochDay != null && current.dueEpochDay!! >= day -> "保障日期内 · ${status(current.dueEpochDay)}"
                    else -> status(current.dueEpochDay)
                }
                result += DeadlineTrack(current, current.dueEpochDay, label, detail,
                    if (current.startEpochDay != null && current.startEpochDay > day) null else end)
            }
        }
    val profileRows = registrations.map { (device, registration) -> ExpenseRecord(
        id = -44001L, deviceId = device, dateEpochDay = registration.registeredDay,
        category = ExpenseCategory.DAILY, amount = 0.0, title = "年审", ledgerKind = "inspection",
        startEpochDay = registration.registeredDay, dueEpochDay = registration.inspectionDueDay,
        itemName = if (registration.regularPrivateCar) PRIVATE_INSPECTION else MANUAL_INSPECTION)
    }
    val documents = outgoings.filter { it.section == "inspection" &&
        !(it.section == "inspection" && registrations[it.deviceId]?.inspectionDueDay != null) } + profileRows
    documents
        .groupBy { Triple(it.deviceId, it.section, if (it.section == "inspection") "" else it.itemName.trim()) }
        .forEach { (_, rows) ->
            // An incomplete receipt cannot clear a confirmed validity deadline.
            val current = rows.maxWith(compareBy<ExpenseRecord> { it.dueEpochDay ?: Long.MIN_VALUE }
                .thenBy { it.dateEpochDay }.thenBy { it.id })
            if (current.section == "inspection" && current.dueEpochDay != null) {
                val due = YearMonth.from(LocalDate.ofEpochDay(current.dueEpochDay)).atEndOfMonth()
                val action = inspectionAction(current.startEpochDay?.let(LocalDate::ofEpochDay), due,
                    current.itemName == PRIVATE_INSPECTION)
                val window = inspectionWindow(due)
                val registration = registrations[current.deviceId].takeIf { current.id == -44001L }
                val origin = if (registration?.estimated == true) "按规则预计，初次设置假定往期已办结，请核对12123；" else ""
                val detail = "$origin$action；可办理窗口 $window 至 $due；请先处理违法和事故，核对有效交强险、车船税。以交管12123为准"
                result += DeadlineTrack(current, due.toEpochDay(), status(due.toEpochDay()), detail,
                    fromVehicleInfo = registration != null)
            } else result += DeadlineTrack(current, current.dueEpochDay, status(current.dueEpochDay),
                if (current.id == -44001L) "特殊车辆请按交管12123填写实际有效期" else "",
                fromVehicleInfo = current.id == -44001L)
        }
    return result.sortedBy { it.dueDay ?: Long.MAX_VALUE }
}
