package com.brz.gauge.trips

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TripDatabase(context: Context) :
    SQLiteOpenHelper(context, "brz_trip_history.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE trips (
                device_id TEXT NOT NULL,
                trip_id INTEGER NOT NULL,
                start_epoch_s INTEGER NOT NULL,
                end_epoch_s INTEGER NOT NULL,
                duration_s INTEGER NOT NULL,
                distance_m INTEGER NOT NULL,
                fuel_ml INTEGER NOT NULL,
                avg_l100_x100 INTEGER NOT NULL,
                flags INTEGER NOT NULL,
                synced_at_s INTEGER NOT NULL,
                time_revised INTEGER NOT NULL DEFAULT 0,
                max_speed_kmh INTEGER,
                max_rpm INTEGER,
                max_accel_x100 INTEGER,
                max_decel_x100 INTEGER,
                data_revised INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(device_id, trip_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX trips_by_start ON trips(start_epoch_s DESC, trip_id DESC)")
        createDeletedTripsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE trips ADD COLUMN time_revised INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) createDeletedTripsTable(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE trips ADD COLUMN max_speed_kmh INTEGER")
            db.execSQL("ALTER TABLE trips ADD COLUMN max_rpm INTEGER")
            db.execSQL("ALTER TABLE trips ADD COLUMN max_accel_x100 INTEGER")
            db.execSQL("ALTER TABLE trips ADD COLUMN max_decel_x100 INTEGER")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE trips ADD COLUMN data_revised INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun createDeletedTripsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS deleted_trips (
                device_id TEXT NOT NULL,
                trip_id INTEGER NOT NULL,
                deleted_at_s INTEGER NOT NULL,
                PRIMARY KEY(device_id, trip_id)
            )
            """.trimIndent()
        )
    }

    fun upsert(record: TripRecord): Boolean {
        val db = writableDatabase
        db.rawQuery(
            "SELECT 1 FROM deleted_trips WHERE device_id=? AND trip_id=? LIMIT 1",
            arrayOf(record.deviceId, record.tripId.toString())
        ).use { cursor ->
            // Treat a deleted record as successfully stored so the gauge can advance
            // its ACK cursor without restoring the row on the phone.
            if (cursor.moveToFirst()) return true
        }
        var startEpochS = record.startEpochS
        var endEpochS = record.endEpochS
        var flags = record.flags
        var revised = record.timeRevised
        var durationS = record.durationS
        var distanceM = record.distanceM
        var fuelMl = record.fuelMl
        var avgL100X100 = record.avgL100X100
        var maxSpeedKmh = record.maxSpeedKmh
        var maxRpm = record.maxRpm
        var maxAccelX100 = record.maxAccelX100
        var maxDecelX100 = record.maxDecelX100
        var dataRevised = record.dataRevised
        db.rawQuery(
            """SELECT start_epoch_s,end_epoch_s,flags,time_revised,
                       duration_s,distance_m,fuel_ml,avg_l100_x100,
                       max_speed_kmh,max_rpm,max_accel_x100,max_decel_x100,data_revised
                FROM trips WHERE device_id=? AND trip_id=?""".trimIndent(),
            arrayOf(record.deviceId, record.tripId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                if (cursor.getInt(12) != 0) {
                    durationS = cursor.getLong(4)
                    distanceM = cursor.getLong(5)
                    fuelMl = cursor.getLong(6)
                    avgL100X100 = cursor.getInt(7)
                    maxSpeedKmh = if (cursor.isNull(8)) null else cursor.getInt(8)
                    maxRpm = if (cursor.isNull(9)) null else cursor.getInt(9)
                    maxAccelX100 = if (cursor.isNull(10)) null else cursor.getInt(10)
                    maxDecelX100 = if (cursor.isNull(11)) null else cursor.getInt(11)
                    dataRevised = true
                }
                if (cursor.getInt(3) != 0) {
                    startEpochS = cursor.getLong(0)
                    endEpochS = cursor.getLong(1)
                    flags = record.flags or cursor.getInt(2) or 1
                    revised = true
                }
            }
        }
        val values = ContentValues().apply {
            put("device_id", record.deviceId)
            put("trip_id", record.tripId)
            put("start_epoch_s", startEpochS)
            put("end_epoch_s", endEpochS)
            put("duration_s", durationS)
            put("distance_m", distanceM)
            put("fuel_ml", fuelMl)
            put("avg_l100_x100", avgL100X100)
            put("flags", flags)
            put("synced_at_s", System.currentTimeMillis() / 1000L)
            put("time_revised", if (revised) 1 else 0)
            put("data_revised", if (dataRevised) 1 else 0)
            if (maxSpeedKmh == null) putNull("max_speed_kmh") else put("max_speed_kmh", maxSpeedKmh)
            if (maxRpm == null) putNull("max_rpm") else put("max_rpm", maxRpm)
            if (maxAccelX100 == null) putNull("max_accel_x100") else put("max_accel_x100", maxAccelX100)
            if (maxDecelX100 == null) putNull("max_decel_x100") else put("max_decel_x100", maxDecelX100)
        }
        return db.insertWithOnConflict(
            "trips", null, values, SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }

    fun reviseTime(deviceId: String, tripId: Long, startEpochS: Long, endEpochS: Long): Boolean {
        if (startEpochS !in 1704067200L..4102444800L ||
            endEpochS !in startEpochS..4102444800L) return false
        val db = writableDatabase
        var durationS: Long? = null
        var flags = 0
        db.rawQuery(
            "SELECT duration_s,flags FROM trips WHERE device_id=? AND trip_id=?",
            arrayOf(deviceId, tripId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                durationS = cursor.getLong(0)
                flags = cursor.getInt(1)
            }
        }
        val duration = durationS ?: return false
        /* The Android picker edits whole minutes. Allow only its sub-minute
         * rounding loss; never accept a materially shorter wall interval. */
        if (endEpochS - startEpochS + 59L < duration) return false
        val values = ContentValues().apply {
            put("start_epoch_s", startEpochS)
            put("end_epoch_s", endEpochS)
            put("flags", flags or 1)
            put("time_revised", 1)
        }
        return db.update(
            "trips", values, "device_id=? AND trip_id=?",
            arrayOf(deviceId, tripId.toString())
        ) == 1
    }

    fun reviseData(record: TripRecord): Boolean {
        if (record.durationS !in 1L..604_800L ||
            record.distanceM !in 0L..10_000_000L ||
            record.fuelMl !in 0L..1_000_000L ||
            record.avgL100X100 !in 0..10_000 ||
            (record.maxSpeedKmh != null && record.maxSpeedKmh !in 0..500) ||
            (record.maxRpm != null && record.maxRpm !in 0..20_000) ||
            (record.maxAccelX100 != null && record.maxAccelX100 !in 0..3_000) ||
            (record.maxDecelX100 != null && record.maxDecelX100 !in -3_000..0)) return false
        val values = ContentValues().apply {
            put("duration_s", record.durationS)
            put("distance_m", record.distanceM)
            put("fuel_ml", record.fuelMl)
            put("avg_l100_x100", record.avgL100X100)
            if (record.maxSpeedKmh == null) putNull("max_speed_kmh") else put("max_speed_kmh", record.maxSpeedKmh)
            if (record.maxRpm == null) putNull("max_rpm") else put("max_rpm", record.maxRpm)
            if (record.maxAccelX100 == null) putNull("max_accel_x100") else put("max_accel_x100", record.maxAccelX100)
            if (record.maxDecelX100 == null) putNull("max_decel_x100") else put("max_decel_x100", record.maxDecelX100)
            put("data_revised", 1)
        }
        return writableDatabase.update(
            "trips", values, "device_id=? AND trip_id=?",
            arrayOf(record.deviceId, record.tripId.toString())
        ) == 1
    }

    fun deleteTrip(deviceId: String, tripId: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val marker = ContentValues().apply {
                put("device_id", deviceId)
                put("trip_id", tripId)
                put("deleted_at_s", System.currentTimeMillis() / 1000L)
            }
            if (db.insertWithOnConflict(
                    "deleted_trips", null, marker, SQLiteDatabase.CONFLICT_REPLACE
                ) == -1L
            ) return false
            db.delete(
                "trips", "device_id=? AND trip_id=?",
                arrayOf(deviceId, tripId.toString())
            )
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Returns this phone's durable sync cursor for one gauge.  Deleted rows are
     * included because their tombstones deliberately prevent a retained gauge
     * record from being restored on the next connection.
     */
    fun latestKnownId(deviceId: String): Long {
        readableDatabase.rawQuery(
            """
            SELECT MAX(trip_id) FROM (
                SELECT trip_id FROM trips WHERE device_id=?
                UNION ALL
                SELECT trip_id FROM deleted_trips WHERE device_id=?
            )
            """.trimIndent(),
            arrayOf(deviceId, deviceId)
        ).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        }
    }

    fun allTrips(): List<TripRecord> {
        val result = ArrayList<TripRecord>()
        readableDatabase.rawQuery(
            """
            SELECT device_id, trip_id, start_epoch_s, end_epoch_s,
                   duration_s, distance_m, fuel_ml, avg_l100_x100, flags, time_revised,
                   max_speed_kmh, max_rpm, max_accel_x100, max_decel_x100, data_revised
            FROM trips
            -- trip_id is assigned by the gauge when the trip occurs, so it remains
            -- a stable occurrence sequence even when the absolute clock was unknown.
            -- Keep newest trips first and do not reorder a trip after its time is revised.
            ORDER BY trip_id DESC, device_id DESC
            """.trimIndent(), null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TripRecord(
                    deviceId = cursor.getString(0),
                    tripId = cursor.getLong(1),
                    startEpochS = cursor.getLong(2),
                    endEpochS = cursor.getLong(3),
                    durationS = cursor.getLong(4),
                    distanceM = cursor.getLong(5),
                    fuelMl = cursor.getLong(6),
                    avgL100X100 = cursor.getInt(7),
                    flags = cursor.getInt(8),
                    timeRevised = cursor.getInt(9) != 0,
                    maxSpeedKmh = if (cursor.isNull(10)) null else cursor.getInt(10),
                    maxRpm = if (cursor.isNull(11)) null else cursor.getInt(11),
                    maxAccelX100 = if (cursor.isNull(12)) null else cursor.getInt(12),
                    maxDecelX100 = if (cursor.isNull(13)) null else cursor.getInt(13),
                    dataRevised = cursor.getInt(14) != 0,
                )
            }
        }
        return result
    }
}
