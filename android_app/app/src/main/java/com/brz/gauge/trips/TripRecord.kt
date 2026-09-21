package com.brz.gauge.trips

data class TripRecord(
    val deviceId: String,
    val tripId: Long,
    val startEpochS: Long,
    val endEpochS: Long,
    val durationS: Long,
    val distanceM: Long,
    val fuelMl: Long,
    val avgL100X100: Int,
    val flags: Int,
    val timeRevised: Boolean = false,
    val maxSpeedKmh: Int? = null,
    val maxRpm: Int? = null,
    val maxAccelX100: Int? = null,
    val maxDecelX100: Int? = null,
    val dataRevised: Boolean = false,
) {
    val hasValidTime: Boolean
        get() = flags and 1 != 0 && startEpochS in 1704067200L..4102444800L && endEpochS in startEpochS..4102444800L
    val timeInconsistent: Boolean
        get() = hasValidTime && durationS > endEpochS - startEpochS + 120
    val averageSpeedKmh: Double?
        get() = durationS.takeIf { it > 0 }?.let { distanceM.toDouble() * 3.6 / it }
    val hasDrivingDetails: Boolean
        get() = maxSpeedKmh != null || maxRpm != null ||
            maxAccelX100 != null || maxDecelX100 != null
}
