package com.brz.gauge.trips

import kotlin.math.roundToLong

data class MileageEstimate(val distanceM: Long, val calibrated: Boolean)

fun homeMileageLabel(estimate: MileageEstimate): String {
    val kilometres = (estimate.distanceM.coerceAtLeast(0L) / 1000.0).roundToLong()
    return "${kilometres}km" + if (estimate.calibrated) "" else " · 未校准"
}

fun estimateMileage(
    trips: List<TripRecord>,
    deviceId: String,
    calibrationM: Long?,
    anchorTripId: Long,
): MileageEstimate {
    val matching = trips.asSequence().filter {
        (deviceId.isBlank() || it.deviceId.equals(deviceId, ignoreCase = true)) && it.distanceM > 0L
    }
    val base = calibrationM?.coerceAtLeast(0L)
    val additions = matching
        .filter { base == null || it.tripId > anchorTripId }
        .map { it.distanceM }
        .fold(0L, ::safeDistanceAdd)
    return MileageEstimate(
        distanceM = if (base == null) additions else safeDistanceAdd(base, additions),
        calibrated = base != null,
    )
}

fun newestTripId(trips: List<TripRecord>, deviceId: String): Long = trips.asSequence()
    .filter { deviceId.isBlank() || it.deviceId.equals(deviceId, ignoreCase = true) }
    .map { it.tripId }
    .filter { it >= 0L }
    .maxOrNull() ?: 0L

private fun safeDistanceAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
