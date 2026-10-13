package com.brz.gauge.trips

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun refuelServiceChecks(root: File) {
    val device = "refuel-service-test"
    fun row(id: Long) = RefuelInterval(device, id, 1000 + (id - 1) * 60, 1000 + id * 60,
        60, 1000, 100, id * 10, 10000, 1)
    fun meta(rows: List<RefuelInterval>, revision: Int = 0) = TripBleProtocol.RefuelMeta(
        rows.size, rows.firstOrNull()?.id ?: 0, rows.lastOrNull()?.id ?: 0,
        rows.lastOrNull()?.endEpochS ?: 0, 0, 0, 0, revision, 2)
    fun packet(r: RefuelInterval): ByteArray {
        val data = ByteArray(48)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1); put(r.flags.toByte()); putShort(48); putInt(r.id.toInt())
            putLong(r.startEpochS); putLong(r.endEpochS); putInt(r.durationS.toInt())
            putInt(r.distanceM.toInt()); putInt(r.fuelMl.toInt()); putInt(r.odometerX10Km.toInt())
            putShort(r.detectedAddedMl.toShort()); position(46)
            putShort(TripBleProtocol.crc16(data, 46).toShort())
        }
        check(TripBleProtocol.parseRefuelRecord(device, data) == r)
        return data
    }
    val context = Context(File(root, "refuel-service"))
    val h = RefuelServiceHarness(context, device)
    (1L..25L).forEach { h.refuelDatabase.upsert(row(it)) }
    val window = (6L..25L).map(::row)
    val before = h.refuelDatabase.all(device)
    h.receive(meta(window))
    window.take(7).forEach { h.receiveData(packet(it)) }
    check(h.refuelDatabase.all(device) == before && h.state.refuelHistoryRevision == null)
    // Simulate process death: no partial data/revision was saved.
    val recovered = RefuelServiceHarness(Context(File(root, "refuel-service")), device)
    recovered.receive(meta(window))
    window.forEach { recovered.receiveData(packet(it)) }
    check(recovered.refuelVerifyingSnapshot)
    recovered.receive(meta(window, 1)) // Concurrent gauge edit invalidates the download.
    check(recovered.refuelDatabase.all(device) == before && recovered.state.refuelHistoryRevision == null)
    check(recovered.refuelMutationDeferred)
    recovered.receive(meta(window))
    window.forEach { recovered.receiveData(packet(it)) }
    recovered.receive(meta(window))
    check(recovered.state.refuelHistoryRevision == 0 && recovered.refuelWindowReadyForMutation)
    println("PASS: real service methods stage GATT records, survive process death, and reject a changed end revision")

    recovered.state.requestRefuelDelete(3)
    val writes = recovered.writes.size
    check(recovered.prepare(3))
    check(recovered.writes.size == writes && recovered.state.pendingRefuelDeleteId == null)
    check(recovered.refuelDatabase.all(device).none { it.id == 3L })
    check(recovered.refuelDatabase.all(device).first { it.id == 4L }.distanceM == 2000L)
    recovered.state.requestRefuelDelete(10)
    check(recovered.prepare(10)) // Requires a new preflight window, not stale bounds.
    recovered.receive(meta(window))
    window.forEach { recovered.receiveData(packet(it)) }
    recovered.receive(meta(window))
    check(!recovered.prepare(10))
    check(recovered.refuelMutationId == 10L && recovered.refuelMutationCommand == 3)
    recovered.refuelDeleteAwaitingRevision = true
    recovered.receive(meta(window))
    check(recovered.state.pendingRefuelDeleteId == 10L && recovered.handler.tasks.isNotEmpty())
    // An unrelated revision change must not confirm deletion of a still-present node.
    recovered.receive(meta(window, 1))
    window.forEach { recovered.receiveData(packet(it)) }
    recovered.receive(meta(window, 1))
    check(recovered.messages.last().contains("未生效") && recovered.refuelDatabase.all(device).any { it.id == 10L })
    println("PASS: archived edits emit no gauge command; retained edits require fresh bounds and exact node confirmation")

    recovered.state.requestRefuelDelete(10)
    check(!recovered.prepare(10))
    recovered.refuelDeleteAwaitingRevision = true
    val changed = window.filter { it.id != 10L }.map {
        if (it.id == 11L) it.copy(startEpochS = row(10).startEpochS, durationS = 120, distanceM = 2000, fuelMl = 200) else it
    }
    recovered.receive(meta(changed, 2))
    changed.forEach { recovered.receiveData(packet(it)) }
    recovered.receive(meta(changed, 2))
    check(recovered.state.pendingRefuelDeleteId == null)
    check(recovered.refuelDatabase.all(device).none { it.id == 10L })
    check(recovered.refuelDatabase.all(device).first { it.id == 11L }.distanceM == 2000L)
    check(recovered.refuelDatabase.all(device).first { it.id == 4L }.distanceM == 2000L)
    println("PASS: acknowledged retained-node deletion updates once and preserves the archived phone-only edit")

    check(recovered.refuelDatabase.revise(row(25).copy(distanceM = 1500)))
    recovered.state.requestRefuelDelete(25)
    val beforeGuard = recovered.writes.size
    check(recovered.prepare(25))
    check(recovered.writes.size == beforeGuard && recovered.state.pendingRefuelDeleteId == null)
    check(recovered.refuelDatabase.all(device).first { it.id == 25L }.distanceM == 1500L)
    check(recovered.messages.last().contains("后续区间"))
    println("PASS: the newest interval cannot silently lose a local correction by merging into the live gauge trip")

    val archived = RefuelServiceHarness(Context(File(root, "refuel-empty-service")), device)
    (1L..3L).forEach { archived.refuelDatabase.upsert(row(it)) }
    archived.state.requestRefuelDelete(1)
    archived.receive(meta(emptyList()))
    archived.receive(meta(emptyList()))
    check(archived.prepare(1) && archived.writes.isEmpty())
    check(archived.refuelDatabase.all(device).first { it.id == 2L }.distanceM == 2000L)
    archived.state.requestRefuelDiscardOldest(2)
    archived.receive(meta(emptyList()))
    archived.receive(meta(emptyList()))
    check(archived.prepare(2, true) && archived.writes.isEmpty())
    check(archived.refuelDatabase.all(device) == listOf(row(3)))
    println("PASS: an empty gauge window permits safe phone-only merge and oldest discard without GATT writes")
}
