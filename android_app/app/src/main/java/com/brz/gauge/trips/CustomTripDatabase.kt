package com.brz.gauge.trips

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CustomTripDatabase(context: Context) :
    SQLiteOpenHelper(context, "brz_custom_trip_intervals.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
                db.execSQL(
                    """DELETE FROM custom_trip_intervals WHERE device_id=? AND interval_id NOT IN
                       (SELECT interval_id FROM custom_trip_intervals WHERE device_id=?
                        ORDER BY end_epoch_s DESC, interval_id DESC LIMIT 50)""".trimIndent(),
                    arrayOf(record.deviceId, record.deviceId)
                )
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
