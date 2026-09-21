package com.brz.gauge.trips

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class RefuelIntervalDatabase(context: Context) :
    SQLiteOpenHelper(context, "brz_refuel_intervals.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun latestId(deviceId: String): Long = readableDatabase.rawQuery(
        "SELECT MAX(interval_id) FROM refuel_intervals WHERE device_id=?", arrayOf(deviceId)
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }

    fun upsert(record: RefuelInterval): Boolean {
        var value = record
        readableDatabase.rawQuery(
            "SELECT duration_s,distance_m,fuel_ml,data_revised FROM refuel_intervals WHERE device_id=? AND interval_id=?",
            arrayOf(record.deviceId, record.id.toString())
        ).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(3) != 0) {
                value = record.copy(durationS = cursor.getLong(0), distanceM = cursor.getLong(1),
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
    fun mergeAfterDeletedNode(deviceId: String, intervalId: Long): Boolean {
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
                var followingUpdated = true
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
                            put("data_revised", if (removedRevised || cursor.getInt(4) != 0) 1 else 0)
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
            "device_id=? AND interval_id=?",
            arrayOf(deviceId, intervalId.toString())) == 1

    fun clearDevice(deviceId: String) {
        writableDatabase.delete("refuel_intervals", "device_id=?", arrayOf(deviceId))
    }

    fun all(deviceId: String): List<RefuelInterval> {
        val result = ArrayList<RefuelInterval>()
        readableDatabase.rawQuery(
            """SELECT device_id,interval_id,start_epoch_s,end_epoch_s,duration_s,distance_m,
                      fuel_ml,odometer_x10_km,detected_added_ml,flags,data_revised
               FROM refuel_intervals WHERE device_id=? ORDER BY interval_id DESC""".trimIndent(),
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
