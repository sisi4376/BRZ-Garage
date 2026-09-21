package com.brz.gauge.trips

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

class FuelDatabase(context: Context) : SQLiteOpenHelper(context, "brz_fuel_records.db", null, 3) {
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
        insertInitialRecords(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE fuel_records ADD COLUMN full_tank INTEGER NOT NULL DEFAULT 1")
            insertInitialRecords(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE fuel_records ADD COLUMN draft INTEGER NOT NULL DEFAULT 0")
        }
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

    private fun insertInitialRecords(db: SQLiteDatabase) {
        val records = listOf(
            FuelRecord(dateEpochDay = day("2026-01-06"), odometerKm = 27.0, litres = 47.87, cost = 339.87),
            FuelRecord(dateEpochDay = day("2026-01-11"), odometerKm = 394.0, litres = 34.36, cost = 243.94),
            FuelRecord(dateEpochDay = day("2026-01-28"), odometerKm = 863.0, litres = 41.85, cost = 300.00),
            FuelRecord(dateEpochDay = day("2026-02-04"), odometerKm = 1171.0, litres = 25.36, cost = 186.39),
            FuelRecord(dateEpochDay = day("2026-02-06"), odometerKm = 1709.0, litres = 40.97, cost = 270.68),
            FuelRecord(dateEpochDay = day("2026-03-16"), odometerKm = 2150.0, litres = 40.08, cost = 324.24),
            FuelRecord(dateEpochDay = day("2026-03-20"), odometerKm = 2356.0, litres = 20.86, cost = 162.49),
            FuelRecord(dateEpochDay = day("2026-04-06"), odometerKm = 2750.0, litres = 37.55, cost = 340.57),
            FuelRecord(dateEpochDay = day("2026-05-05"), odometerKm = 3207.0, litres = 44.66, cost = 400.00, fullTank = false),
            FuelRecord(dateEpochDay = day("2026-05-17"), odometerKm = 3702.0, litres = 21.67, cost = 200.00, fullTank = false),
            FuelRecord(dateEpochDay = day("2026-05-19"), odometerKm = 4019.0, litres = 44.32, cost = 409.07),
            FuelRecord(dateEpochDay = day("2026-07-04"), odometerKm = 4260.0, litres = 28.85, cost = 219.26),
            FuelRecord(dateEpochDay = day("2026-09-06"), odometerKm = 4660.0, litres = 37.77, cost = 323.31),
        )
        records.forEach { record ->
            db.execSQL(
                """
                INSERT INTO fuel_records(date_epoch_day,odometer_km,litres,cost,full_tank)
                SELECT ?,?,?,?,? WHERE NOT EXISTS(
                    SELECT 1 FROM fuel_records WHERE date_epoch_day=? AND ABS(odometer_km-?)<0.001
                )
                """.trimIndent(),
                arrayOf<Any>(
                    record.dateEpochDay, record.odometerKm, record.litres, record.cost,
                    if (record.fullTank) 1 else 0, record.dateEpochDay, record.odometerKm
                )
            )
        }
    }

    private fun day(value: String) = LocalDate.parse(value).toEpochDay()
}
