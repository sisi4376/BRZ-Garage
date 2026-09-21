package com.brz.gauge.trips

data class RefuelInterval(
    val deviceId: String,
    val id: Long,
    val startEpochS: Long,
    val endEpochS: Long,
    val durationS: Long,
    val distanceM: Long,
    val fuelMl: Long,
    val odometerX10Km: Long,
    val detectedAddedMl: Int,
    val flags: Int,
    val dataRevised: Boolean = false,
) {
    val automatic: Boolean get() = flags and 1 != 0
    val manual: Boolean get() = flags and 2 != 0
    val averageL100: Double?
        get() = distanceM.takeIf { it > 0 }?.let { fuelMl.toDouble() / it * 100.0 }
}

