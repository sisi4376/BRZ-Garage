"""Host only: run the actual service snapshot/edit methods with a queued GATT adapter."""
from pathlib import Path


def write_harness(root: Path, destination: Path):
    source = (root / 'android_app/app/src/main/java/com/brz/gauge/trips/TripSyncService.kt').read_text(encoding='utf8')
    methods = source.split('    private fun sameRefuelWindow', 1)[1].split('    private fun readRefuelOrSettings()', 1)[0]
    methods = '    private fun sameRefuelWindow' + methods
    packet = source.split('            TripBleProtocol.REFUEL_DATA -> {', 1)[1].split('            TripBleProtocol.GAUGE_SETTINGS -> {', 1)[0]
    packet = packet.rsplit('}', 1)[0]
    custom = '    private fun receiveCustomTripReceipt' + source.split('    private fun receiveCustomTripReceipt', 1)[1].split('    private fun requestRefuelDelete()', 1)[0]
    destination.write_text('''package com.brz.gauge.trips
import android.content.Context
import java.util.UUID

class RefuelServiceHarness(context: Context, val address: String) {
    val state = AppState(context).apply { this.address = this@RefuelServiceHarness.address }
    val refuelDatabase = RefuelIntervalDatabase(context)
    val database = TripDatabase(context)
    var refuelMeta: TripBleProtocol.RefuelMeta? = null
    var refuelCursor = 0L
    var refuelControl = 0
    var refuelDeleteAwaitingRevision = false
    var refuelDeleteVerifyAttempts = 0
    val refuelSnapshot = mutableListOf<RefuelInterval>()
    var refuelSnapshotMeta: TripBleProtocol.RefuelMeta? = null
    var refuelVerifyingSnapshot = false
    var refuelWindowReadyForMutation = false
    var refuelMutationDeferred = false
    var refuelMutationId: Long? = null
    var refuelMutationCommand = 0
    var refuelMutationRevision: Int? = null
    var connected = true
    var operation: UUID? = null
    var customTripRequest: CustomTripSyncRequest? = null
    var customTripSyncedForConnection = false
    var customTripDeferredForConnection = false
    var customTripReceiptAttempts = 0
    var customTripReceiptSupported = false
    val messages = mutableListOf<String>()
    val reads = mutableListOf<UUID>()
    val writes = mutableListOf<Pair<UUID,ByteArray>>()
    var settingsReads = 0
    var retries = 0
    class Queue {
        val tasks = mutableListOf<() -> Unit>()
        fun postDelayed(task: () -> Unit, delay: Long) { tasks += task }
    }
    val handler = Queue()
    fun publish(message: String, refresh: Boolean = false) { messages += message }
    fun idle(message: String) { messages += message }
    fun read(uuid: UUID) { reads += uuid }
    fun write(uuid: UUID, bytes: ByteArray) { writes += uuid to bytes }
    fun readSettingsAfterVehicle() { settingsReads++ }
    fun readRefuelOrSettings() { settingsReads++ }
    fun retryOperation(message: String) { retries++; messages += message }
    fun completeOperation() { operation = null }
    fun continueAfterGattIdle(action: () -> Unit) = action()
    fun receive(meta: TripBleProtocol.RefuelMeta) = receiveRefuelMeta(meta)
    fun prepare(id: Long, discard: Boolean = false) = prepareRefuelMutation(id, discard)
    fun receiveCustom(bytes: ByteArray) = receiveCustomTripReceipt(bytes)
    fun finishCustomWrite() = finishCustomTripWrite()
    fun receiveData(bytes: ByteArray) {
''' + packet + '\n    }\n' + methods + custom + '\n}\n', encoding='utf8')
