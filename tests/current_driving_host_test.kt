package com.brz.gauge.trips

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId

fun currentDrivingChecks(root: File) {
    val device = "AA:BB:CC:DD:EE:91"
    val start = Instant.parse("2026-10-20T15:55:00Z").epochSecond
    val v = VehicleState(2, start + 600, 3600, 0, 0, 1000, 600, 100,
        10000, 6000, 1000, 800, 0, currentStartEpochS = start)
    val current = currentDrivingRecord(device, v)!!
    check(current.tripId == 0L && current.avgL100X100 == 1000)
    val previous = current.copy(tripId = 1, startEpochS = start - 86400, endEpochS = start - 85800)
    val history = listOf(previous)
    val first = drivingHistory(history, device, v)
    check(first.size == 2 && first.first() == current && history == listOf(previous))
    val changed = v.copy(currentDistanceM = 2500, currentDurationS = 800, currentFuelMl = 200, epochS = start + 800)
    val second = drivingHistory(history, device, changed)
    check(second.size == 2 && second.first().distanceM == 2500L)
    val hongKong = ZoneId.of("Asia/Hong_Kong")
    val days = drivingDays(second, hongKong)
    // Cross-midnight trips follow the same start-date rule as archived trips.
    check(days.single { it.date.toString() == "2026-10-20" }.let {
        it.trips == 1 && it.distanceM == 2500L && it.durationS == 800L && it.fuelMl == 200L
    })
    repeat(4) { check(drivingHistory(history, device, changed) == second) }
    check(drivingHistory(history, device, null) == history)
    check(drivingHistory(history, "", v) == history)
    check(drivingHistory(history, device, v.copy(currentDistanceM = 0, currentDurationS = 0, currentFuelMl = 0)) == history)
    val unknown = v.copy(epochS = 0, currentStartEpochS = 0)
    check(drivingHistory(emptyList(), device, unknown).size == 1)
    check(drivingDays(drivingHistory(emptyList(), device, unknown)).isEmpty())
    val completed = current.copy(tripId = 2, endEpochS = start + 900, durationS = 900)
    check(drivingHistory(listOf(completed, previous), device, v) == listOf(completed, previous))
    check(drivingHistory(listOf(completed.copy(deviceId = "other")), device, v).size == 2)
    val next = v.copy(currentStartEpochS = start + 1800, epochS = start + 2400)
    check(drivingHistory(listOf(completed), device, next).size == 2)
    check(drivingHistory(emptyList(), device, v, snapshotArchived = true).isEmpty())
    println("PASS: current trip updates calendar/list once, preserves dates/devices and hands off to archive")

    val context = Context(File(root, "current-driving"))
    val state = AppState(context).apply { address = device }
    val bytes = ByteArray(84)
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    b.put(2); b.put(2); b.putShort(84); b.putLong(v.epochS); b.putInt(3600)
    b.putShort(0); b.putShort(0); b.putInt(1000); b.putInt(600); b.putInt(100)
    b.putLong(10000); b.putLong(6000); b.putLong(1000); b.putShort(800); b.putShort(0)
    repeat(4) { b.putShort(0) }; b.putLong(start); b.putInt(1)
    b.putShort(82, TripBleProtocol.crc16(bytes, 82).toShort())
    state.saveVehicle(bytes)
    check(!state.currentTripSnapshotArchived())
    state.markCurrentTripArchived(completed)
    check(state.currentTripSnapshotArchived())
    // Deleted/revised/split historical rows must not resurrect their old cache.
    check(drivingHistory(emptyList(), device, state.vehicle(), state.currentTripSnapshotArchived()).isEmpty())
    val reopened = AppState(Context(File(root, "current-driving")))
    check(reopened.currentTripSnapshotArchived())
    b.putLong(4, next.epochS); b.putLong(68, next.currentStartEpochS)
    b.putShort(82, TripBleProtocol.crc16(bytes, 82).toShort())
    state.saveVehicle(bytes)
    check(!state.currentTripSnapshotArchived())
    check(drivingHistory(emptyList(), device, state.vehicle(), state.currentTripSnapshotArchived()).size == 1)
    TripDatabase(context).use { check(it.allTrips().isEmpty()) }
    println("PASS: archive marker survives restart/deletion and does not hide a new trip; live rows are not persisted")
}
