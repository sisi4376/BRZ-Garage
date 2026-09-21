package com.brz.gauge.trips

const val MAX_CUSTOM_TRIP_NAME_LENGTH = 8

fun normalizeCustomTripName(value: String?): String {
    val cleaned = value.orEmpty().replace(Regex("\\s+"), " ").trim()
    if (cleaned.isEmpty()) return ""
    val count = cleaned.codePointCount(0, cleaned.length)
    if (count <= MAX_CUSTOM_TRIP_NAME_LENGTH) return cleaned
    return cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_CUSTOM_TRIP_NAME_LENGTH))
}

fun customTripTitle(name: String?): String = normalizeCustomTripName(name).let {
    if (it.isEmpty()) "自定义行程" else "自定义行程-$it"
}

data class CustomTripInterval(
    val id: Long = 0,
    val deviceId: String,
    val name: String,
    val startEpochS: Long,
    val endEpochS: Long,
    val durationS: Long,
    val distanceM: Long,
    val fuelMl: Long,
)
