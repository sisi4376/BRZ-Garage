package com.brz.obdstudio

data class CaptureStage(val profile: String, val label: String, val seconds: Double)

fun quickCapturePlan(vehicle: String): List<CaptureStage> {
    require(vehicle in listOf("zd8-mt", "zd8-at", "zc6-mt", "zc6-at")) { "请选择 ZD8/ZC6 的手动挡或自动挡车型" }
    return listOf(CaptureStage("all", "全部支持的标准 PID", 300.0),
        CaptureStage(vehicle, Modes.labels[Modes.ids.indexOf(vehicle)], 300.0))
}

fun runQuickCapture(vehicle: String, cancelled: () -> Boolean, capture: (Int, CaptureStage) -> Unit) {
    quickCapturePlan(vehicle).forEachIndexed { index, stage ->
        if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException("已停止")
        capture(index + 1, stage)
    }
}
