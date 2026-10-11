package com.brz.gauge.trips

import android.database.sqlite.SQLiteDatabase

internal object TransferMigrations {
    fun tripMetadata(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS transfer_reconcile(device_id TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS transfer_refuel_reconcile(device_id TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS transfer_preferences(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    /** The trigger also covers existing save paths without changing their local row-number APIs. */
    fun localIdentity(db: SQLiteDatabase, table: String, id: String) {
        require(table in setOf("fuel_records", "expenses", "custom_trip_intervals"))
        require(id in setOf("id", "interval_id"))
        db.execSQL("ALTER TABLE $table ADD COLUMN transfer_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE $table SET transfer_id=lower(hex(randomblob(16))) WHERE transfer_id=''")
        db.execSQL("CREATE UNIQUE INDEX ${table}_transfer_id ON $table(transfer_id)")
        db.execSQL("CREATE TABLE transfer_deleted(transfer_id TEXT PRIMARY KEY NOT NULL, deleted_at_s INTEGER NOT NULL)")
        db.execSQL("""CREATE TRIGGER ${table}_transfer_insert AFTER INSERT ON $table
            WHEN NEW.transfer_id='' BEGIN
            UPDATE $table SET transfer_id=lower(hex(randomblob(16))) WHERE $id=NEW.$id;
            END""")
        db.execSQL("""CREATE TRIGGER ${table}_transfer_delete AFTER DELETE ON $table
            WHEN OLD.transfer_id!='' BEGIN
            INSERT OR REPLACE INTO transfer_deleted(transfer_id,deleted_at_s)
            VALUES(OLD.transfer_id,CAST(strftime('%s','now') AS INTEGER));
            END""")
    }
}
