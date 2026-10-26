package com.brz.gauge.trips

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class DrivingDayActivity(
    val date: LocalDate, val trips: Int, val distanceM: Long,
    val durationS: Long, val fuelMl: Long,
)

/** Display-only row: zero is never a persisted gauge trip ID. */
fun currentDrivingRecord(deviceId: String, vehicle: VehicleState?): TripRecord? {
    if (deviceId.isBlank() || vehicle == null ||
        (vehicle.currentDistanceM == 0L && vehicle.currentDurationS == 0L && vehicle.currentFuelMl == 0L)) return null
    val start = vehicle.currentStartEpochS.takeIf { it in 1704067200L..4102444800L }
        ?: vehicle.epochS.takeIf { it in 1704067200L..4102444800L }
            ?.minus(vehicle.currentDurationS)?.takeIf { it >= 1704067200L } ?: 0L
    val end = vehicle.epochS
    return TripRecord(deviceId, 0L, start, end, vehicle.currentDurationS,
        vehicle.currentDistanceM, vehicle.currentFuelMl,
        if (vehicle.currentDistanceM > 0L)
            (vehicle.currentFuelMl.toDouble() / vehicle.currentDistanceM * 10_000.0).roundToInt() else 0,
        if (start > 0L && end in start..4102444800L) 1 else 0,
        maxSpeedKmh = vehicle.maxSpeedKmh, maxRpm = vehicle.maxRpm,
        maxAccelX100 = vehicle.maxAccelX100, maxDecelX100 = vehicle.maxDecelX100)
}

fun archivedCurrentDriving(archived: TripRecord, current: TripRecord): Boolean =
    archived.deviceId == current.deviceId && archived.tripId != 0L &&
        archived.hasValidTime && current.hasValidTime &&
        (archived.startEpochS == current.startEpochS ||
            // Older snapshots estimate their start from epoch minus duration.
            (current.startEpochS >= archived.startEpochS && current.endEpochS <= archived.endEpochS))

fun drivingHistory(archived: List<TripRecord>, deviceId: String, vehicle: VehicleState?,
                   snapshotArchived: Boolean = false): List<TripRecord> {
    val current = currentDrivingRecord(deviceId, vehicle)
    return if (current == null || snapshotArchived || archived.any { archivedCurrentDriving(it, current) })
        archived else listOf(current) + archived
}

fun drivingDays(records: List<TripRecord>, zone: ZoneId = ZoneId.systemDefault()): List<DrivingDayActivity> =
    records.filter { it.hasValidTime }.groupBy {
        Instant.ofEpochSecond(it.startEpochS).atZone(zone).toLocalDate()
    }.map { (date, trips) -> DrivingDayActivity(date, trips.size, trips.sumOf { it.distanceM },
        trips.sumOf { it.durationS }, trips.sumOf { it.fuelMl }) }
