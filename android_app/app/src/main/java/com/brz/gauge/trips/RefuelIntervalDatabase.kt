package com.brz.gauge.trips

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class RefuelIntervalDatabase(context: Context) :
    SQLiteOpenHelper(context, "brz_refuel_intervals.db", null, 2) {

    class UnresolvedLocalRevision : IllegalStateException("No verified successor for a local refuel revision")

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE refuel_intervals (
                device_id TEXT NOT NULL,
                interval_id INTEGER NOT NULL,
                start_epoch_s INTEGER NOT NULL,
                end_epoch_s INTEGER NOT NULL,
                duration_s INTEGER NOT NULL,
                distance_m INTEGER NOT NULL,
                fuel_ml INTEGER NOT NULL,
                odometer_x10_km INTEGER NOT NULL,
                detected_added_ml INTEGER NOT NULL,
                flags INTEGER NOT NULL,
                data_revised INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(device_id, interval_id)
            )
            """.trimIndent()
        )
        createSyncTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSyncTables(db)
    }

    private fun createSyncTables(db: SQLiteDatabase) {
        // Raw retained records are sync bookkeeping, not the user's edited history.
        db.execSQL("CREATE TABLE refuel_gauge_records AS SELECT * FROM refuel_intervals WHERE 0")
        db.execSQL("CREATE UNIQUE INDEX refuel_gauge_key ON refuel_gauge_records(device_id,interval_id)")
        db.execSQL("""CREATE TABLE refuel_sync_state (
            device_id TEXT PRIMARY KEY, revision INTEGER NOT NULL,
            oldest_id INTEGER NOT NULL, newest_id INTEGER NOT NULL, count INTEGER NOT NULL)""")
    }

    fun matchesSnapshot(deviceId: String, meta: TripBleProtocol.RefuelMeta): Boolean =
        readableDatabase.rawQuery("SELECT revision,oldest_id,newest_id,count FROM refuel_sync_state WHERE device_id=?",
            arrayOf(deviceId)).use { c -> c.moveToFirst() && c.getInt(0) == meta.historyRevision &&
                c.getLong(1) == meta.oldestId && c.getLong(2) == meta.newestId && c.getInt(3) == meta.count }

    /** Commit only a complete, metadata-verified gauge window. Records outside
     * the retained window are archives, not deletions inferred from absence. */
    fun reconcileSnapshot(deviceId: String, meta: TripBleProtocol.RefuelMeta,
                          incoming: List<RefuelInterval>, mergedNodeId: Long? = null,
                          discardedNodeId: Long? = null) {
        require(incoming.size == meta.count && meta.count in 0..20)
        require(incoming.all { it.deviceId == deviceId } &&
            incoming.zipWithNext().all { (a, b) -> a.id < b.id })
        require(if (incoming.isEmpty()) meta.oldestId == 0L && meta.newestId == 0L else
            incoming.first().id == meta.oldestId && incoming.last().id == meta.newestId)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val local = all(deviceId).associateBy { it.id }
            val raw = records("refuel_gauge_records", deviceId).associateBy { it.id }
            val ids = incoming.map { it.id }.toSet()
            val oldNewest = raw.keys.maxOrNull() ?: 0L
            val revisionChanged = db.rawQuery("SELECT revision FROM refuel_sync_state WHERE device_id=?",
                arrayOf(deviceId)).use { it.moveToFirst() && it.getInt(0) != meta.historyRevision }
            val removed = raw.values.filter { old -> old.id !in ids && (
                (meta.count > 0 && old.id >= meta.oldestId) ||
                (revisionChanged && (meta.count == 0 || meta.newestId <= oldNewest)) ||
                incoming.any { it.startEpochS > 0 && old.startEpochS >= it.startEpochS &&
                    old.endEpochS > it.startEpochS && old.endEpochS <= it.endEpochS }) }.map { it.id }.toMutableSet()
            // Upgrade/import may have no raw window yet. Only authoritative gaps
            // inside this complete window can be removed in that case.
            removed += local.keys.filter { meta.count > 0 && it in meta.oldestId..meta.newestId && it !in ids }
            listOfNotNull(mergedNodeId, discardedNodeId).filter { it !in ids }.forEach { removed += it }
            val carriedRevisions = mutableSetOf<Long>()
            for (record in incoming) {
                val contributions = raw.values.filter { old -> old.id == record.id ||
                    (old.id in removed && old.id != discardedNodeId && old.id < record.id &&
                        incoming.firstOrNull { it.id > old.id }?.id == record.id &&
                        (old.id == mergedNodeId || (record.startEpochS > 0 &&
                            old.startEpochS >= record.startEpochS && old.endEpochS <= record.endEpochS))) }
                val edited = contributions.mapNotNull { before ->
                    local[before.id]?.takeIf { it.dataRevised }?.let { before to it }
                }
                carriedRevisions += edited.map { it.first.id }
                val previous = local[record.id]
                val value = if (edited.isNotEmpty()) {
                    record.copy(
                        startEpochS = minOf(record.startEpochS, edited.minOf { it.second.startEpochS }),
                        durationS = (record.durationS + edited.sumOf { it.second.durationS - it.first.durationS }).coerceAtLeast(0),
                        distanceM = (record.distanceM + edited.sumOf { it.second.distanceM - it.first.distanceM }).coerceAtLeast(0),
                        fuelMl = (record.fuelMl + edited.sumOf { it.second.fuelMl - it.first.fuelMl }).coerceAtLeast(0),
                        dataRevised = true)
                } else if (previous?.dataRevised == true && record.id !in raw) {
                    // Imported/local revisions without a baseline remain authoritative.
                    record.copy(startEpochS = minOf(record.startEpochS, previous.startEpochS),
                        durationS = previous.durationS, distanceM = previous.distanceM,
                        fuelMl = previous.fuelMl, dataRevised = true)
                } else record
                check(db.insertWithOnConflict("refuel_intervals", null, values(value), SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            }
            // The newest deleted interval may now belong to the live gauge trip.
            // Do not erase a phone-only correction before a completed successor
            // proves where it belongs. Roll back the entire window and retry later.
            for (id in removed - carriedRevisions - setOfNotNull(discardedNodeId)) {
                val edited = local[id]?.takeIf { it.dataRevised } ?: continue
                val baseline = raw[id]
                if (baseline == null || edited.durationS != baseline.durationS ||
                    edited.distanceM != baseline.distanceM || edited.fuelMl != baseline.fuelMl ||
                    edited.startEpochS != baseline.startEpochS) throw UnresolvedLocalRevision()
            }
            for (id in removed) db.delete("refuel_intervals", "device_id=? AND interval_id=?", arrayOf(deviceId, id.toString()))
            db.delete("refuel_gauge_records", "device_id=?", arrayOf(deviceId))
            for (record in incoming) check(db.insertWithOnConflict("refuel_gauge_records", null,
                values(record), SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            check(db.insertWithOnConflict("refuel_sync_state", null, ContentValues().apply {
                put("device_id", deviceId); put("revision", meta.historyRevision)
                put("oldest_id", meta.oldestId); put("newest_id", meta.newestId); put("count", meta.count)
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun latestId(deviceId: String): Long = readableDatabase.rawQuery(
        "SELECT MAX(interval_id) FROM refuel_intervals WHERE device_id=?", arrayOf(deviceId)
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }

    fun upsert(record: RefuelInterval): Boolean {
        var value = record
        readableDatabase.rawQuery(
            "SELECT duration_s,distance_m,fuel_ml,data_revised,start_epoch_s FROM refuel_intervals WHERE device_id=? AND interval_id=?",
            arrayOf(record.deviceId, record.id.toString())
        ).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(3) != 0) {
                value = record.copy(startEpochS = minOf(record.startEpochS, cursor.getLong(4)),
                    durationS = cursor.getLong(0), distanceM = cursor.getLong(1),
                    fuelMl = cursor.getLong(2), dataRevised = true)
            }
        }
        return writableDatabase.insertWithOnConflict("refuel_intervals", null, values(value),
            SQLiteDatabase.CONFLICT_REPLACE) != -1L
    }

    fun revise(record: RefuelInterval): Boolean {
        if (record.durationS !in 0L..31_536_000L || record.distanceM !in 0L..50_000_000L ||
            record.fuelMl !in 0L..5_000_000L) return false
        val values = ContentValues().apply {
            put("duration_s", record.durationS)
            put("distance_m", record.distanceM)
            put("fuel_ml", record.fuelMl)
            put("data_revised", 1)
        }
        return writableDatabase.update("refuel_intervals", values,
            "device_id=? AND interval_id=?", arrayOf(record.deviceId, record.id.toString())) == 1
    }

    /** Mirror the gauge's node deletion so local test revisions survive the
     * revision-triggered resync. The deleted interval is folded into the next
     * chronological interval; deleting the newest node folds it into the live
     * gauge interval, so only the completed row is removed here. */
    fun mergeAfterDeletedNode(deviceId: String, intervalId: Long, archiveOnly: Boolean = false): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        var merged = false
        try {
            var removedStart = 0L
            var removedDuration = 0L
            var removedDistance = 0L
            var removedFuel = 0L
            var removedRevised = false
            val found = db.rawQuery(
                """SELECT start_epoch_s,duration_s,distance_m,fuel_ml,data_revised
                   FROM refuel_intervals WHERE device_id=? AND interval_id=?""".trimIndent(),
                arrayOf(deviceId, intervalId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) false else {
                    removedStart = cursor.getLong(0)
                    removedDuration = cursor.getLong(1)
                    removedDistance = cursor.getLong(2)
                    removedFuel = cursor.getLong(3)
                    removedRevised = cursor.getInt(4) != 0
                    true
                }
            }
            if (found) {
                var followingUpdated = !archiveOnly
                db.rawQuery(
                    """SELECT interval_id,duration_s,distance_m,fuel_ml,data_revised
                       FROM refuel_intervals WHERE device_id=? AND interval_id>?
                       ORDER BY interval_id ASC LIMIT 1""".trimIndent(),
                    arrayOf(deviceId, intervalId.toString())
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nextId = cursor.getLong(0)
                        val values = ContentValues().apply {
                            put("start_epoch_s", removedStart)
                            put("duration_s", removedDuration + cursor.getLong(1))
                            put("distance_m", removedDistance + cursor.getLong(2))
                            put("fuel_ml", removedFuel + cursor.getLong(3))
                            put("data_revised", if (archiveOnly || removedRevised || cursor.getInt(4) != 0) 1 else 0)
                        }
                        followingUpdated = db.update("refuel_intervals", values,
                            "device_id=? AND interval_id=?",
                            arrayOf(deviceId, nextId.toString())) == 1
                    }
                }
                if (followingUpdated && db.delete("refuel_intervals",
                        "device_id=? AND interval_id=?",
                        arrayOf(deviceId, intervalId.toString())) == 1) {
                    db.setTransactionSuccessful()
                    merged = true
                }
            }
        } finally {
            db.endTransaction()
        }
        return merged
    }

    fun discardBeforeFirstNode(deviceId: String, intervalId: Long): Boolean =
        writableDatabase.delete("refuel_intervals",
            "device_id=? AND interval_id=? AND interval_id=(SELECT MIN(interval_id) FROM refuel_intervals WHERE device_id=?)",
            arrayOf(deviceId, intervalId.toString(), deviceId)) == 1

    fun clearDevice(deviceId: String) {
        writableDatabase.delete("refuel_intervals", "device_id=?", arrayOf(deviceId))
    }

    fun all(deviceId: String): List<RefuelInterval> = records("refuel_intervals", deviceId)

    private fun records(table: String, deviceId: String): List<RefuelInterval> {
        val result = ArrayList<RefuelInterval>()
        readableDatabase.rawQuery(
            """SELECT device_id,interval_id,start_epoch_s,end_epoch_s,duration_s,distance_m,
                      fuel_ml,odometer_x10_km,detected_added_ml,flags,data_revised
               FROM $table WHERE device_id=? ORDER BY interval_id DESC""".trimIndent(),
            arrayOf(deviceId)
        ).use { cursor ->
            while (cursor.moveToNext()) result += RefuelInterval(
                cursor.getString(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3),
                cursor.getLong(4), cursor.getLong(5), cursor.getLong(6), cursor.getLong(7),
                cursor.getInt(8), cursor.getInt(9), cursor.getInt(10) != 0)
        }
        return result
    }

    private fun values(record: RefuelInterval) = ContentValues().apply {
        put("device_id", record.deviceId)
        put("interval_id", record.id)
        put("start_epoch_s", record.startEpochS)
        put("end_epoch_s", record.endEpochS)
        put("duration_s", record.durationS)
        put("distance_m", record.distanceM)
        put("fuel_ml", record.fuelMl)
        put("odometer_x10_km", record.odometerX10Km)
        put("detected_added_ml", record.detectedAddedMl)
        put("flags", record.flags)
        put("data_revised", if (record.dataRevised) 1 else 0)
    }
}
