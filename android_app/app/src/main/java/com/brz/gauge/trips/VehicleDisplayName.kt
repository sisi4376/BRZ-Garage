package com.brz.gauge.trips

const val DEFAULT_VEHICLE_DISPLAY_NAME = "我的BRZ STI"
const val MAX_VEHICLE_DISPLAY_NAME_LENGTH = 9

/** Keep the large home-page heading on one line on narrow supported phones. */
fun normalizeVehicleDisplayName(value: String): String {
    val singleLine = value.replace('\r', ' ').replace('\n', ' ')
        .replace(Regex("\\s+"), " ").trim()
    return singleLine.take(MAX_VEHICLE_DISPLAY_NAME_LENGTH)
        .ifEmpty { DEFAULT_VEHICLE_DISPLAY_NAME }
}
