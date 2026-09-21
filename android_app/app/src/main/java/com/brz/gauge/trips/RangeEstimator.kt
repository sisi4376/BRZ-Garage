package com.brz.gauge.trips

import kotlin.math.roundToInt

const val LOW_FUEL_THRESHOLD_PERCENT = 25.0

fun isLowFuel(fuelPercent: Double?): Boolean =
    fuelPercent != null && fuelPercent.isFinite() && fuelPercent < LOW_FUEL_THRESHOLD_PERCENT

enum class RangeConsumptionSource(val key: String, val title: String) {
    RECENT_FIVE("recent_five", "最近五次驾驶"),
    GAUGE_HISTORY("gauge_history", "仪表历史油耗"),
    FUEL_RECORDS("fuel_records", "加油记录平均油耗");

    companion object {
        fun fromKey(value: String?): RangeConsumptionSource =
            entries.firstOrNull { it.key == value } ?: GAUGE_HISTORY
    }
}

data class RangeConsumption(
    val source: RangeConsumptionSource,
    val displayedLitresPer100Km: Double,
    val calculationLitresPer100Km: Double,
    val correctionFactor: Double,
)

fun recentTripAverage(trips: List<TripRecord>, limit: Int = 5): Double? {
    val recent = trips.sortedByDescending { it.tripId }.asSequence()
        .filter { it.distanceM > 0L && it.fuelMl > 0L && it.avgL100X100 > 0 }
        .take(limit.coerceAtLeast(1))
        .toList()
    val distanceM = recent.sumOf { it.distanceM }
    val fuelMl = recent.sumOf { it.fuelMl }
    return if (recent.isNotEmpty() && distanceM > 0L && fuelMl > 0L)
        fuelMl.toDouble() / distanceM * 100.0 else null
}

fun selectRangeConsumption(
    source: RangeConsumptionSource,
    correctionFactor: Double,
    trips: List<TripRecord>,
    gaugeHistoricalAverage: Double?,
    fuelRecordAverage: Double?,
): RangeConsumption? {
    val displayed = when (source) {
        RangeConsumptionSource.RECENT_FIVE -> recentTripAverage(trips)
        RangeConsumptionSource.GAUGE_HISTORY -> gaugeHistoricalAverage
        RangeConsumptionSource.FUEL_RECORDS -> fuelRecordAverage
    }?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val factor = correctionFactor.takeIf { it.isFinite() && it in 0.5..2.0 } ?: 1.06
    val applied = if (source == RangeConsumptionSource.FUEL_RECORDS) 1.0 else factor
    return RangeConsumption(source, displayed, displayed * applied, applied)
}

fun estimateRangeKm(tankLitres: Double, fuelPercent: Double?, consumption: RangeConsumption?): Int? {
    if (tankLitres !in 1.0..150.0 || fuelPercent == null || fuelPercent !in 0.0..100.0) return null
    val adjusted = consumption?.calculationLitresPer100Km ?: return null
    if (!adjusted.isFinite() || adjusted <= 0.0) return null
    return (tankLitres * fuelPercent / 100.0 / adjusted * 100.0).roundToInt().coerceAtLeast(0)
}
