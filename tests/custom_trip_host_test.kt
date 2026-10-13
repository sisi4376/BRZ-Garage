package com.brz.gauge.trips

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

fun customTripChecks(root: File) {
    val device = "AA:BB:CC:DD:EE:61"
    fun vehicle(distance: Long) = VehicleState(0, 0, 0, 0, 0, 0, 0, 0, distance, distance / 10, distance / 20, 0, 0)
    val context = Context(File(root, "custom-atomic"))
    val state = AppState(context).apply { address = device }
    state.resetCustomTrip(vehicle(1000), "起点")
    val expected = state.customTripGeneration()
    val record = CustomTripInterval(deviceId = device, name = "起点", startEpochS = 1, endEpochS = 101,
        durationS = 100, distanceM = 1000, fuelMl = 50)
    SQLiteDatabase.failSqlContaining = "INSERT OR REPLACE INTO custom_reset_state"
    try { check(runCatching { state.commitCustomTripReset(vehicle(2000), "下一段", record, expected) }.isFailure) }
    finally { SQLiteDatabase.failSqlContaining = null }
    check(state.customTripBaseline()?.first == 1000L)
    CustomTripDatabase(context).use { check(it.all(device).isEmpty()) }
    check(state.commitCustomTripReset(vehicle(2000), "下一段", record, expected))
    repeat(3) { check(!state.commitCustomTripReset(vehicle(2000), "下一段", record, expected)) }
    CustomTripDatabase(context).use { check(it.all(device).size == 1) }
    println("PASS: custom archive/new baseline/pending task roll back together; repeated reset cannot duplicate history")

    val nextExpected = state.customTripGeneration()
    context.getSharedPreferences("vehicle_companion", 0).failCommit = true
    check(runCatching { state.commitCustomTripReset(vehicle(3000), "恢复后", record, nextExpected) }.isFailure)
    // Simulate a killed process after the DB commit, before preferences publication.
    val reopened = Context(File(root, "custom-atomic"))
    val restored = AppState(reopened)
    check(restored.customTripBaseline()?.first == 3000L && restored.customTripName == "恢复后")
    check(restored.pendingCustomTripSync)
    check(!restored.commitCustomTripReset(vehicle(3000), "恢复后", record, nextExpected))
    CustomTripDatabase(reopened).use { check(it.all(device).size == 2) }
    println("PASS: committed custom reset recovers after preferences failure and process death without archiving again")

    val old = restored.customTripSyncRequest()!!
    check(restored.confirmCustomTripSync(old, null))
    check(restored.pendingCustomTripSync && restored.customTripSyncStatus == "legacy_sent")
    check(restored.commitCustomTripReset(vehicle(4000), "最新", record, restored.customTripGeneration()))
    check(!restored.confirmCustomTripSync(old, Triple(old.distanceM, old.durationS, old.fuelMl)))
    check(restored.pendingCustomTripSync && restored.customTripBaseline()?.first == 4000L)
    val latest = restored.customTripSyncRequest()!!
    check(restored.confirmCustomTripSync(latest, Triple(3999, latest.durationS, latest.fuelMl)))
    check(!restored.pendingCustomTripSync && restored.customTripBaseline()?.first == 3999L)
    println("PASS: legacy send remains unconfirmed; late old acknowledgement cannot clear a newer reset; applied clamp is recovered")

    val h = RefuelServiceHarness(Context(File(root, "custom-service")), device)
    h.state.resetCustomTrip(vehicle(1000), "")
    val request = h.state.customTripSyncRequest()!!
    h.customTripRequest = request
    val receipt = TripBleProtocol.customTripConfirmedPacket(request)
    check(receipt.size == 20 && TripBleProtocol.parseCustomTripReceipt(receipt)?.token == request.token)
    val bad = receipt.copyOf().apply { this[5] = (this[5].toInt() xor 1).toByte() }
    h.receiveCustom(bad)
    check(h.state.pendingCustomTripSync && h.handler.tasks.isNotEmpty())
    val olderReceipt = TripBleProtocol.customTripConfirmedPacket(request.copy(token = request.token xor 1L).let {
        if (it.token == 0L) it.copy(token = 2) else it
    })
    h.receiveCustom(olderReceipt)
    check(h.state.pendingCustomTripSync)
    h.receiveCustom(receipt)
    check(!h.state.pendingCustomTripSync && h.customTripSyncedForConnection)
    h.state.resetCustomTrip(vehicle(2000))
    h.customTripRequest = request
    h.receiveCustom(receipt)
    check(h.state.pendingCustomTripSync && !h.customTripSyncedForConnection)
    h.customTripRequest = h.state.customTripSyncRequest()
    repeat(5) { h.receiveCustom(bad) }
    check(h.customTripDeferredForConnection && h.state.pendingCustomTripSync)
    h.customTripRequest = request // Late legacy callback for the previous generation.
    h.finishCustomWrite()
    check(!h.customTripDeferredForConnection && h.state.pendingCustomTripSync)
    h.customTripRequest = h.state.customTripSyncRequest()
    h.finishCustomWrite()
    check(h.customTripDeferredForConnection && h.state.pendingCustomTripSync && h.state.customTripSyncStatus == "legacy_sent")
    h.customTripReceiptSupported = true
    h.customTripRequest = h.state.customTripSyncRequest()
    h.finishCustomWrite()
    check(h.reads.last() == TripBleProtocol.CUSTOM_TRIP_CONTROL && h.state.pendingCustomTripSync)
    println("PASS: production receipt handler rejects corrupt/old replies, confirms exact intent, and retains timed-out tasks")

    val legacyContext = Context(File(root, "custom-v2-upgrade"))
    CustomTripDatabase(legacyContext).use { it.insert(record) }
    SQLiteDatabase.openDatabase(legacyContext.getDatabasePath("brz_custom_trip_intervals.db").path, null, 0).use {
        it.execSQL("DROP TABLE custom_reset_state"); it.execSQL("PRAGMA user_version=2")
    }
    legacyContext.getSharedPreferences("vehicle_companion", 0).edit().putString("address", device)
        .putLong("custom_base_distance_$device", 750).putLong("custom_base_duration_$device", 75)
        .putLong("custom_base_fuel_$device", 35).putLong("custom_reset_at_$device", 100).commit()
    val upgraded = AppState(legacyContext)
    check(upgraded.customTripSyncRequest()?.distanceM == 750L)
    check(upgraded.customTripResetAtMs == 100L)
    CustomTripDatabase(legacyContext).use { check(it.all(device).size == 1) }
    println("PASS: schema-2 upgrade preserves custom history and adopts the original baseline without a spurious archive")

    val transfer = TransferStore(reopened)
    val snapshot = transfer.snapshot()
    val baselineKey = "custom_base_distance_$device"
    val incoming = snapshot.copy(preferences = snapshot.preferences +
        (baselineKey to (snapshot.preferences.getValue(baselineKey) + ("value" to 5000L))))
    transfer.import(incoming, true)
    check(restored.customTripBaseline()?.first == 5000L && restored.pendingCustomTripSync)
    check(!restored.confirmCustomTripSync(latest, Triple(latest.distanceM, latest.durationS, latest.fuelMl)))
    val imported = restored.customTripSyncRequest()!!
    check(imported.generation != latest.generation && imported.distanceM == 5000L)
    check(restored.confirmCustomTripSync(imported, Triple(imported.distanceM, imported.durationS, imported.fuelMl)))
    println("PASS: imported custom baseline creates a fresh intent and cannot inherit an old acknowledgement")
}
