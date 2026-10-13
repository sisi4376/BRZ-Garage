package com.brz.gauge.trips

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import java.security.SecureRandom

class CustomTripDatabase(context: Context) :
    SQLiteOpenHelper(context, "brz_custom_trip_intervals.db", null, 3) {

    companion object {
        val lock = Any()
        @Volatile var recoveryRequired = false
            private set
        fun newToken(): Long = (SecureRandom().nextInt().toLong() and 0xffff_ffffL).coerceAtLeast(1)

        /** Replay before UI/BLE use. The row is cleared only after a durable preferences commit. */
        fun recover(context: Context) = synchronized(lock) {
            CustomTripDatabase(context).use { helper ->
                val db = helper.writableDatabase
                db.rawQuery("SELECT device_id,generation,token,name,reset_at,distance,duration,fuel,pending,status FROM custom_reset_state WHERE published=0", null).use { c ->
                    while (c.moveToNext()) {
                        recoveryRequired = true
                        val device = c.getString(0)
                        fun key(prefix: String) = "${prefix}_$device"
                        val edit = context.getSharedPreferences("vehicle_companion", Context.MODE_PRIVATE).edit()
                            .putString(key("custom_generation"), c.getString(1))
                            .putLong(key("custom_wire_token"), c.getLong(2))
                            .putString(key("custom_trip_name"), c.getString(3))
                            .putLong(key("custom_reset_at"), c.getLong(4))
                            .putLong(key("custom_base_distance"), c.getLong(5))
                            .putLong(key("custom_base_duration"), c.getLong(6))
                            .putLong(key("custom_base_fuel"), c.getLong(7))
                            .putBoolean(key("custom_sync_pending"), c.getInt(8) != 0)
                            .putString(key("custom_sync_status"), c.getString(9))
                        check(edit.commit()) { "自定义行程已保存，配置等待恢复" }
                        db.execSQL("UPDATE custom_reset_state SET published=1 WHERE device_id=? AND generation=?",
                            arrayOf(device, c.getString(1)))
                    }
                }
            }
            recoveryRequired = false
        }
    }

    private fun createResetState(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE custom_reset_state (
            device_id TEXT PRIMARY KEY, generation TEXT NOT NULL, token INTEGER NOT NULL,
            name TEXT NOT NULL, reset_at INTEGER NOT NULL, distance INTEGER NOT NULL,
            duration INTEGER NOT NULL, fuel INTEGER NOT NULL, pending INTEGER NOT NULL,
            status TEXT NOT NULL, published INTEGER NOT NULL DEFAULT 0)""")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE custom_trip_intervals (
                interval_id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                name TEXT NOT NULL,
                start_epoch_s INTEGER NOT NULL,
                end_epoch_s INTEGER NOT NULL,
                duration_s INTEGER NOT NULL,
                distance_m INTEGER NOT NULL,
                fuel_ml INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX custom_trip_device_end ON custom_trip_intervals(device_id,end_epoch_s DESC)")
        TransferMigrations.localIdentity(db, "custom_trip_intervals", "interval_id")
        createResetState(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) TransferMigrations.localIdentity(db, "custom_trip_intervals", "interval_id")
        if (oldVersion < 3) createResetState(db)
    }

    /** History and the recoverable new baseline/pending command commit together. */
    fun archiveAndReset(device: String, expected: String, current: String,
                        record: CustomTripInterval?, name: String, baseline: Triple<Long,Long,Long>,
                        resetAt: Long): Boolean = synchronized(lock) {
        require(device.isNotBlank() && baseline.toList().all { it in 0..0xffff_ffffL })
        val db = writableDatabase
        db.beginTransaction()
        try {
            val persisted = db.rawQuery("SELECT generation FROM custom_reset_state WHERE device_id=?", arrayOf(device))
                .use { if (it.moveToFirst()) it.getString(0) else current }
            if (persisted != expected || current != expected) return@synchronized false
            if (record != null) {
                require(record.deviceId == device && record.endEpochS >= record.startEpochS &&
                    record.startEpochS >= 0 && record.durationS >= 0 && record.distanceM >= 0 && record.fuelMl >= 0)
                check(db.insert("custom_trip_intervals", null, ContentValues().apply {
                    put("device_id", device); put("name", normalizeCustomTripName(record.name))
                    put("start_epoch_s", record.startEpochS); put("end_epoch_s", record.endEpochS)
                    put("duration_s", record.durationS); put("distance_m", record.distanceM); put("fuel_ml", record.fuelMl)
                }) != -1L)
            }
            check(db.insertWithOnConflict("custom_reset_state", null, ContentValues().apply {
                put("device_id", device); put("generation", UUID.randomUUID().toString()); put("token", newToken())
                put("name", normalizeCustomTripName(name)); put("reset_at", resetAt)
                put("distance", baseline.first); put("duration", baseline.second); put("fuel", baseline.third)
                put("pending", 1); put("status", "pending"); put("published", 0)
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            recoveryRequired = true
            db.setTransactionSuccessful()
            true
        } finally { db.endTransaction() }
    }

    fun updateSync(device: String, request: CustomTripSyncRequest, actual: Triple<Long,Long,Long>?, status: String): Boolean {
        val values = ContentValues().apply {
            put("status", status); put("pending", if (actual == null) 1 else 0); put("published", 0)
            if (actual != null) { put("distance", actual.first); put("duration", actual.second); put("fuel", actual.third) }
        }
        val updated = writableDatabase.update("custom_reset_state", values,
            "device_id=? AND generation=? AND token=? AND distance=? AND duration=? AND fuel=?",
            arrayOf(device, request.generation, request.token.toString(), request.distanceM.toString(),
                request.durationS.toString(), request.fuelMl.toString())) == 1
        if (updated) recoveryRequired = true
        return updated
    }

    fun renameCurrent(device: String, name: String) {
        if (writableDatabase.update("custom_reset_state", ContentValues().apply {
            put("name", normalizeCustomTripName(name)); put("published", 0)
        }, "device_id=?", arrayOf(device)) > 0) recoveryRequired = true
    }

    fun hasCurrent(device: String, generation: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM custom_reset_state WHERE device_id=? AND generation=?", arrayOf(device, generation)
    ).use { it.moveToFirst() }

    fun insert(record: CustomTripInterval): Boolean {
        if (record.deviceId.isBlank() || record.startEpochS < 0 ||
            record.endEpochS < record.startEpochS || record.durationS < 0 ||
            record.distanceM < 0 || record.fuelMl < 0) return false
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val inserted = db.insert("custom_trip_intervals", null, ContentValues().apply {
                put("device_id", record.deviceId)
                put("name", normalizeCustomTripName(record.name))
                put("start_epoch_s", record.startEpochS)
                put("end_epoch_s", record.endEpochS)
                put("duration_s", record.durationS)
                put("distance_m", record.distanceM)
                put("fuel_ml", record.fuelMl)
            }) != -1L
            if (inserted) {
                db.setTransactionSuccessful()
            }
            inserted
        } finally {
            db.endTransaction()
        }
    }

    fun all(deviceId: String): List<CustomTripInterval> {
        val result = ArrayList<CustomTripInterval>()
        readableDatabase.rawQuery(
            """SELECT interval_id,device_id,name,start_epoch_s,end_epoch_s,duration_s,distance_m,fuel_ml
               FROM custom_trip_intervals WHERE device_id=?
               ORDER BY end_epoch_s DESC, interval_id DESC""".trimIndent(),
            arrayOf(deviceId)
        ).use { cursor ->
            while (cursor.moveToNext()) result += CustomTripInterval(
                id = cursor.getLong(0), deviceId = cursor.getString(1), name = cursor.getString(2),
                startEpochS = cursor.getLong(3), endEpochS = cursor.getLong(4),
                durationS = cursor.getLong(5), distanceM = cursor.getLong(6), fuelMl = cursor.getLong(7)
            )
        }
        return result
    }
}
