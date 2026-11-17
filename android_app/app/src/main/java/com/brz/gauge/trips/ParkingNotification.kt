package com.brz.gauge.trips

data class ParkingSnapshot(val deviceId: String, val mileage: MileageEstimate?, val rangeKm: Int?,
                           val vehicleDistanceM: Long? = null)

fun parkingNotificationText(showMileage: Boolean, snapshot: ParkingSnapshot?): String =
    buildList {
        if (showMileage) add(snapshot?.mileage?.let { homeMileageLabel(it) } ?: "里程暂无数据")
        add(snapshot?.rangeKm?.let { "剩余续航约 ${it}km" } ?: "剩余续航暂无数据")
    }.joinToString("，")

/** Advance the cached summary using the gauge's monotonic lifetime counter. */
fun connectedNotificationText(showMileage: Boolean, snapshot: ParkingSnapshot?, vehicle: VehicleState?): String {
    val baseline = snapshot?.vehicleDistanceM
    val distance = vehicle?.totalDistanceM
    val mileage = snapshot?.mileage
    val live = if (baseline != null && distance != null && mileage != null && distance >= baseline) {
        val increment = distance - baseline
        mileage.copy(distanceM = if (increment > Long.MAX_VALUE - mileage.distanceM)
            Long.MAX_VALUE else mileage.distanceM + increment)
    } else mileage
    return parkingNotificationText(showMileage, snapshot?.copy(mileage = live))
}
