package com.brz.gauge.trips

/** Bit order is append-only; matches obd_poll_health.c. */
object PollHealth {
    data class Item(val bit: Int, val title: String, val request: String)
    enum class Status(val text: String) {
        UNRECORDED("未记录"), RECEIVED("已收到响应"),
        REQUESTED("已请求 · 未收到有效响应"), NOT_REQUESTED("未请求")
    }
    val items = listOf(
        Item(0, "发动机转速", "01 0C"), Item(1, "车速", "01 0D"),
        Item(2, "进气温度", "01 0F"), Item(3, "冷却液温度", "01 05"),
        Item(4, "机油温度 · ZD8", "01 5C"), Item(5, "控制模块电压", "01 42"),
        Item(6, "发动机负荷", "01 04"), Item(7, "节气门位置", "01 11"),
        Item(8, "燃油系统状态", "01 03"), Item(9, "指令当量比 λ", "01 44"),
        Item(10, "进气质量流量 MAF", "01 10"), Item(11, "油箱液位", "01 2F"),
        Item(12, "燃油消耗率", "01 5E"), Item(13, "直接挡位／传动比", "01 A4"),
        Item(14, "机油温度 · ZC6", "21 01"),
        Item(15, "基础 PID 支持查询", "01 00"),
        Item(16, "燃油消耗率支持查询", "01 40"),
        Item(17, "挡位 PID 支持查询", "01 A0"),
    )
    fun status(trip: TripRecord, item: Item): Status = when {
        !trip.hasPollHealth -> Status.UNRECORDED
        trip.pollReceived and (1L shl item.bit) != 0L -> Status.RECEIVED
        trip.pollRequested and (1L shl item.bit) != 0L -> Status.REQUESTED
        else -> Status.NOT_REQUESTED
    }
}
