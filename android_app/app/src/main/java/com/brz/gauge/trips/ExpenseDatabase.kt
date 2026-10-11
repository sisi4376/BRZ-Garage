package com.brz.gauge.trips

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ExpenseDatabase(context: Context) :
    SQLiteOpenHelper(context, "brz_expenses.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                date_epoch_day INTEGER NOT NULL,
                category INTEGER NOT NULL,
                amount REAL NOT NULL,
                title TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                odometer_km REAL,
                maintenance_type INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX expenses_by_device_date ON expenses(device_id,date_epoch_day DESC,id DESC)")
        TransferMigrations.localIdentity(db, "expenses", "id")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) db.execSQL("ALTER TABLE expenses ADD COLUMN maintenance_type INTEGER")
        if (oldVersion < 3) TransferMigrations.localIdentity(db, "expenses", "id")
        if (oldVersion < 2) {
            db.execSQL(
                "UPDATE expenses SET title=? WHERE category=? AND title=?",
                arrayOf("配件用品", ExpenseCategory.DAILY.code, "车辆配件"),
            )
        }
    }

    fun save(record: ExpenseRecord): Long {
        val values = ContentValues().apply {
            put("device_id", record.deviceId)
            put("date_epoch_day", record.dateEpochDay)
            put("category", record.category.code)
            put("amount", record.amount)
            put("title", record.title.trim())
            put("note", record.note.trim())
            if (record.odometerKm == null) putNull("odometer_km")
            else put("odometer_km", record.odometerKm)
            if (record.category == ExpenseCategory.MAINTENANCE && record.maintenanceType != null)
                put("maintenance_type", record.maintenanceType.code)
            else putNull("maintenance_type")
        }
        return if (record.id == 0L) writableDatabase.insert("expenses", null, values)
        else {
            val changed = writableDatabase.update(
                "expenses", values, "id=? AND device_id=?",
                arrayOf(record.id.toString(), record.deviceId),
            )
            if (changed == 1) record.id else -1L
        }
    }

    fun delete(deviceId: String, id: Long): Boolean = writableDatabase.delete(
        "expenses", "id=? AND device_id=?", arrayOf(id.toString(), deviceId)
    ) == 1

    fun all(deviceId: String): List<ExpenseRecord> {
        return queryRecords("device_id=?", arrayOf(deviceId))
    }

    /** Include records created before the vehicle was bound, without mixing other vehicles. */
    fun allForVehicle(deviceId: String): List<ExpenseRecord> =
        if (deviceId.isBlank()) queryRecords("1=1", emptyArray())
        else queryRecords("(device_id=? OR device_id='local')", arrayOf(deviceId))

    private fun queryRecords(where: String, args: Array<String>): List<ExpenseRecord> {
        val result = ArrayList<ExpenseRecord>()
        readableDatabase.rawQuery(
            """SELECT id,device_id,date_epoch_day,category,amount,title,note,odometer_km,maintenance_type
               FROM expenses WHERE $where ORDER BY date_epoch_day DESC,id DESC""".trimIndent(),
            args,
        ).use { cursor ->
            while (cursor.moveToNext()) result += ExpenseRecord(
                id = cursor.getLong(0),
                deviceId = cursor.getString(1),
                dateEpochDay = cursor.getLong(2),
                category = ExpenseCategory.fromCode(cursor.getInt(3)),
                amount = cursor.getDouble(4),
                title = cursor.getString(5),
                note = cursor.getString(6),
                odometerKm = if (cursor.isNull(7)) null else cursor.getDouble(7),
                maintenanceType = if (cursor.isNull(8)) null else MaintenanceRecordType.fromCode(cursor.getInt(8)),
            )
        }
        return result
    }

}
