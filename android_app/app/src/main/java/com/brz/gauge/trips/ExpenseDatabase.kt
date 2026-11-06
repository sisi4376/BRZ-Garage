package com.brz.gauge.trips

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ExpenseDatabase(context: Context) :
    SQLiteOpenHelper(context, "brz_expenses.db", null, 6) {

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
                maintenance_type INTEGER,
                ledger_kind TEXT NOT NULL DEFAULT '', income INTEGER NOT NULL DEFAULT 0,
                due_epoch_day INTEGER, start_epoch_day INTEGER,
                party TEXT NOT NULL DEFAULT '', item_name TEXT NOT NULL DEFAULT '', asset_ref TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX expenses_by_device_date ON expenses(device_id,date_epoch_day DESC,id DESC)")
        TransferMigrations.localIdentity(db, "expenses", "id")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 6) db.execSQL("ALTER TABLE expenses ADD COLUMN asset_ref TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN ledger_kind TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE expenses ADD COLUMN income INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE expenses ADD COLUMN due_epoch_day INTEGER")
            db.execSQL("ALTER TABLE expenses ADD COLUMN start_epoch_day INTEGER")
            db.execSQL("ALTER TABLE expenses ADD COLUMN party TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE expenses ADD COLUMN item_name TEXT NOT NULL DEFAULT ''")
        }
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
        val db=writableDatabase
        db.beginTransaction()
        try {
            val rows=queryRecords("device_id=?",arrayOf(record.deviceId))
            val active=effectiveAssetLedger(rows)
            if(record.id != 0L) require(rows.none { it.id==record.id && it.isAssetEvent }) { "资产历史不可覆盖，请撤销后重新记录" }
            if(record.isAssetCancellation) {
                require(record.id==0L && record.amount==0.0 && !record.income)
                val target=active.singleOrNull { it.transferId==record.assetRef && (it.isAsset || it.isAssetExit) }
                require(target != null) { "原事件不存在或已撤销" }
                require(record.dateEpochDay>=target.dateEpochDay) { "撤销日期不能早于原事件" }
                if(target.isAsset) require(active.none { it.isAssetExit && it.assetRef==target.transferId }) { "请先撤销处置，再撤销登记" }
            }
            if(record.isAssetExit) {
                val root=active.singleOrNull { it.transferId==record.assetRef && it.isAsset }
                require(root != null) { "资产不存在或登记已撤销" }
                require(record.dateEpochDay>=root.dateEpochDay) { "处置日期不能早于登记日期" }
                require(active.none { it.isAssetExit && it.assetRef==record.assetRef }) { "资产已处置" }
                require(record.income==(record.ledgerKind=="asset_sale") && (record.income || record.amount==0.0))
            }
            val result=saveRow(record)
            if(result != -1L) db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun saveRow(record: ExpenseRecord): Long {
        val values = ContentValues().apply {
            put("device_id", record.deviceId)
            put("date_epoch_day", record.dateEpochDay)
            put("category", record.category.code)
            put("amount", record.amount)
            put("title", record.title.trim())
            put("note", record.note.trim())
            put("ledger_kind", record.ledgerKind)
            put("income", if (record.income) 1 else 0)
            put("party", record.party.trim())
            put("item_name", record.itemName.trim())
            put("asset_ref",record.assetRef)
            if (record.dueEpochDay == null) putNull("due_epoch_day") else put("due_epoch_day", record.dueEpochDay)
            if (record.startEpochDay == null) putNull("start_epoch_day") else put("start_epoch_day", record.startEpochDay)
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

    fun delete(deviceId: String, id: Long): Boolean {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val root=queryRecords("device_id=? AND id=?",arrayOf(deviceId,id.toString())).singleOrNull() ?: return false
            if(root.isAssetCancellation) return false
            if(root.isAsset && queryRecords("asset_ref=?",arrayOf(root.transferId)).any { it.isAssetExit }) return false
            if(root.isAssetEvent) db.delete("expenses","device_id=? AND ledger_kind=? AND asset_ref=?",
                arrayOf(deviceId,"asset_cancel",root.transferId))
            val removed=db.delete("expenses","id=? AND device_id=?",arrayOf(id.toString(),deviceId))==1
            if(removed) db.setTransactionSuccessful()
            return removed
        } finally { db.endTransaction() }
    }

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
            """SELECT id,device_id,date_epoch_day,category,amount,title,note,odometer_km,maintenance_type,ledger_kind,income,due_epoch_day,start_epoch_day,party,item_name,asset_ref,transfer_id
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
                ledgerKind = cursor.getString(9), income = cursor.getInt(10) == 1,
                dueEpochDay = if (cursor.isNull(11)) null else cursor.getLong(11),
                startEpochDay = if (cursor.isNull(12)) null else cursor.getLong(12),
                party = cursor.getString(13), itemName = cursor.getString(14), assetRef=cursor.getString(15),
            ).also { it.transferId=cursor.getString(16) }
        }
        return result
    }

}
