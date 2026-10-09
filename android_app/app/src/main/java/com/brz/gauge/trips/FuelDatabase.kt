package com.brz.gauge.trips

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class FuelDatabase(context: Context) : SQLiteOpenHelper(context, "brz_fuel_records.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE fuel_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date_epoch_day INTEGER NOT NULL,
                odometer_km REAL NOT NULL,
                litres REAL NOT NULL,
                cost REAL NOT NULL,
                full_tank INTEGER NOT NULL DEFAULT 1,
                draft INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX fuel_by_date ON fuel_records(date_epoch_day DESC, id DESC)")
        // A new device starts empty; personal history arrives through an explicit import.
        TransferMigrations.localIdentity(db, "fuel_records", "id")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE fuel_records ADD COLUMN full_tank INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE fuel_records ADD COLUMN draft INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) TransferMigrations.localIdentity(db, "fuel_records", "id")
    }

    fun save(record: FuelRecord): Long {
        val values = ContentValues().apply {
            put("date_epoch_day", record.dateEpochDay)
            put("odometer_km", record.odometerKm)
            put("litres", record.litres)
            put("cost", record.cost)
            put("full_tank", if (record.fullTank) 1 else 0)
            put("draft", if (record.draft) 1 else 0)
        }
        return if (record.id == 0L) writableDatabase.insert("fuel_records", null, values)
        else {
            writableDatabase.update("fuel_records", values, "id=?", arrayOf(record.id.toString()))
            record.id
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete("fuel_records", "id=?", arrayOf(id.toString()))
    }

    fun allRecords(): List<FuelRecord> {
        val result = ArrayList<FuelRecord>()
        readableDatabase.rawQuery(
            "SELECT id,date_epoch_day,odometer_km,litres,cost,full_tank,draft FROM fuel_records ORDER BY date_epoch_day DESC,id DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) result += FuelRecord(
                id = cursor.getLong(0),
                dateEpochDay = cursor.getLong(1),
                odometerKm = cursor.getDouble(2),
                litres = cursor.getDouble(3),
                cost = cursor.getDouble(4),
                fullTank = cursor.getInt(5) != 0,
                draft = cursor.getInt(6) != 0,
            )
        }
        return result
    }

}
