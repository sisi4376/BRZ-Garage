package com.brz.gauge.trips

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

fun refuelHistoryChecks(root: File) {
    val device = "refuel-history-test"
    fun row(id: Long) = RefuelInterval(device, id, 1000 + (id - 1) * 60, 1000 + id * 60,
        60, 1000, 100, id * 10, 10000, 1)
    fun meta(rows: List<RefuelInterval>, revision: Int = 0) = TripBleProtocol.RefuelMeta(
        rows.size, rows.firstOrNull()?.id ?: 0, rows.lastOrNull()?.id ?: 0,
        rows.lastOrNull()?.endEpochS ?: 0, 0, 0, 0, revision, 2)
    fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) }
    val context = Context(File(root, "refuel-history"))
    RefuelIntervalDatabase(context).use { db ->
        (1L..25L).forEach { check(db.upsert(row(it))) }
        val window = (6L..25L).map(::row)
        db.reconcileSnapshot(device, meta(window), window)
        check(db.all(device).size == 25)
        check(db.matchesSnapshot(device, meta(window)))
        check(db.mergeAfterDeletedNode(device, 3, archiveOnly = true))
        check(db.all(device).first { it.id == 4L }.let { it.distanceM == 2000L && it.startEpochS == row(3).startEpochS })
        check(!db.mergeAfterDeletedNode(device, 3, archiveOnly = true))
        check(db.mergeAfterDeletedNode(device, 5, archiveOnly = true))
        repeat(3) { db.reconcileSnapshot(device, meta(window), window) }
        check(db.all(device).first { it.id == 6L }.let { it.distanceM == 2000L && it.fuelMl == 200L && it.startEpochS == row(5).startEpochS })
        println("PASS: 25 records survive the 20-record window; old-node and boundary merges survive repeated sync")

        val merged7 = row(7).copy(startEpochS = row(6).startEpochS, durationS = 120, distanceM = 2000, fuelMl = 200)
        val changed = listOf(merged7) + (8L..25L).map(::row)
        repeat(3) { db.reconcileSnapshot(device, meta(changed, 1), changed) }
        check(db.all(device).none { it.id == 6L })
        check(db.all(device).first { it.id == 7L }.let { it.distanceM == 3000L && it.durationS == 180L && it.startEpochS == row(5).startEpochS })
        check(db.all(device).any { it.id == 1L })
        println("PASS: another phone deletes the boundary node; local archived contribution moves once into the successor")

        val rolled = (8L..27L).map(::row)
        db.reconcileSnapshot(device, meta(rolled, 1), rolled)
        check(db.all(device).first { it.id == 7L }.distanceM == 3000L)
        val deletedMiddle = rolled.filter { it.id != 15L }.map {
            if (it.id == 16L) it.copy(startEpochS = row(15).startEpochS, durationS = 120, distanceM = 2000, fuelMl = 200) else it
        }
        db.reconcileSnapshot(device, meta(deletedMiddle, 2), deletedMiddle)
        check(db.all(device).none { it.id == 15L })
        check(db.all(device).first { it.id == 16L }.distanceM == 2000L)
        check(db.all(device).any { it.id == 7L } && db.all(device).any { it.id == 1L })
        println("PASS: window rollover archives old records; revision change removes only authoritative deleted nodes")

        val before = db.all(device)
        rejects { db.reconcileSnapshot(device, meta(deletedMiddle, 3), deletedMiddle.dropLast(1)) }
        rejects { db.reconcileSnapshot(device, meta(deletedMiddle, 3), deletedMiddle.reversed()) }
        rejects { db.reconcileSnapshot(device, meta(deletedMiddle, 3), deletedMiddle.map { it.copy(deviceId = "other") }) }
        check(db.all(device) == before)
        SQLiteDatabase.failSqlContaining = "INSERT OR REPLACE INTO refuel_gauge_records"
        try { rejects { db.reconcileSnapshot(device, meta(deletedMiddle.drop(1), 3), deletedMiddle.drop(1)) } }
        finally { SQLiteDatabase.failSqlContaining = null }
        check(db.all(device) == before && db.matchesSnapshot(device, meta(deletedMiddle, 2)))
        println("PASS: incomplete/corrupt windows and injected storage failure leave records and revision unchanged")

        check(db.discardBeforeFirstNode(device, 1))
        check(db.all(device).first { it.id == 2L } == row(2))
        check(!db.mergeAfterDeletedNode(device, 27, archiveOnly = true))
        check(db.all(device).any { it.id == 27L })
        val newestDeleted = deletedMiddle.dropLast(1)
        db.reconcileSnapshot(device, meta(newestDeleted, 3), newestDeleted)
        check(db.all(device).none { it.id == 27L })
        check(db.all(device).any { it.id == 7L })
        db.upsert(row(1).copy(deviceId = "other"))
        db.reconcileSnapshot(device, meta(emptyList(), 4), emptyList())
        check(db.all(device).map { it.id }.toSet() == setOf(2L, 4L, 7L))
        check(db.all("other").size == 1)
        println("PASS: discard does not merge; newest deletion and empty gauge window preserve archives and other devices")
    }
    RefuelIntervalDatabase(Context(File(root, "refuel-history"))).use { db ->
        check(db.all(device).map { it.id }.toSet() == setOf(2L, 4L, 7L))
        check(db.matchesSnapshot(device, meta(emptyList(), 4)))
    }
    val migration = Context(File(root, "refuel-upgrade"))
    RefuelIntervalDatabase(migration).use { check(it.upsert(row(1))) }
    SQLiteDatabase.openDatabase(migration.getDatabasePath("brz_refuel_intervals.db").path, null, 0).use {
        it.execSQL("DROP TABLE refuel_gauge_records")
        it.execSQL("DROP TABLE refuel_sync_state")
        it.execSQL("PRAGMA user_version=1")
    }
    RefuelIntervalDatabase(migration).use { db ->
        check(db.all(device) == listOf(row(1)))
        db.reconcileSnapshot(device, meta(listOf(row(1))), listOf(row(1)))
        check(db.matchesSnapshot(device, meta(listOf(row(1)))))
    }
    println("PASS: process restart and schema-1 upgrade preserve history and rebuild only sync bookkeeping")

    RefuelIntervalDatabase(Context(File(root, "refuel-unresolved"))).use { db ->
        val original = listOf(row(1), row(2))
        db.reconcileSnapshot(device, meta(original), original)
        check(db.revise(row(2).copy(distanceM = 1500)))
        val before = db.all(device)
        val remaining = listOf(row(1))
        check(runCatching { db.reconcileSnapshot(device, meta(remaining, 1), remaining) }
            .exceptionOrNull() is RefuelIntervalDatabase.UnresolvedLocalRevision)
        check(db.all(device) == before && db.matchesSnapshot(device, meta(original)))
        // Next refuel completes the live interval; the saved correction can now move once.
        val successor = row(3).copy(startEpochS = row(2).startEpochS, durationS = 120, distanceM = 2000, fuelMl = 200)
        val completed = listOf(row(1), successor)
        repeat(3) { db.reconcileSnapshot(device, meta(completed, 1), completed) }
        check(db.all(device).none { it.id == 2L })
        check(db.all(device).first { it.id == 3L }.distanceM == 2500L)
        println("PASS: an external newest-node deletion preserves the local revision until its successor completes")
    }
}
