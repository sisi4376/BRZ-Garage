package com.brz.gauge.trips

data class FuelRecord(
    val id: Long = 0,
    val dateEpochDay: Long,
    val odometerKm: Double,
    val litres: Double,
    val cost: Double,
    val fullTank: Boolean = true,
    val draft: Boolean = false,
)

data class FuelConsumptionPoint(
    val recordId: Long,
    val dateEpochDay: Long,
    val litresPer100Km: Double,
)

data class FuelAnalysis(
    val points: List<FuelConsumptionPoint>,
    val measuredLitres: Double,
    val measuredDistanceKm: Double,
) {
    val historicalAverage: Double?
        get() = if (measuredLitres > 0.0 && measuredDistanceKm > 0.0)
            measuredLitres / measuredDistanceKm * 100.0 else null
}

fun analyzeFuelRecords(records: List<FuelRecord>): FuelAnalysis {
    // Odometer order determines distance even if a record's date was edited
    // incorrectly. The first full fill only establishes the baseline. Every
    // later full fill, including the newest record, closes a measured interval.
    // Partial fills accumulate fuel and distance until the next full fill.
    val ordered = records.filter { !it.draft && it.litres > 0.0 }
        .sortedWith(compareBy<FuelRecord> { it.odometerKm }.thenBy { it.dateEpochDay }.thenBy { it.id })
    val points = ArrayList<FuelConsumptionPoint>()
    var baseline: FuelRecord? = null
    var previousOdometer = 0.0
    var pendingLitres = 0.0
    var pendingDistance = 0.0
    var measuredLitres = 0.0
    var measuredDistance = 0.0
    ordered.forEach { current ->
        val base = baseline
        if (base == null) {
            if (current.fullTank) {
                baseline = current
                previousOdometer = current.odometerKm
            }
            return@forEach
        }
        val distance = current.odometerKm - previousOdometer
        if (distance <= 0.0) return@forEach
        pendingDistance += distance
        previousOdometer = current.odometerKm
        pendingLitres += current.litres
        if (current.fullTank) {
            points += FuelConsumptionPoint(current.id, current.dateEpochDay, pendingLitres / pendingDistance * 100.0)
            measuredLitres += pendingLitres
            measuredDistance += pendingDistance
            baseline = current
            pendingLitres = 0.0
            pendingDistance = 0.0
        }
    }
    return FuelAnalysis(points, measuredLitres, measuredDistance)
}

fun fuelConsumptionPoints(records: List<FuelRecord>) = analyzeFuelRecords(records).points
