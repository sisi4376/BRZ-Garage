package android.database.sqlite

import android.content.ContentValues
import android.content.Context
import java.sql.Connection
import java.sql.DriverManager

/** Host adapter only: SQL, triggers, ATTACH and rollback run on real SQLite via JDBC. */
class SQLiteDatabase private constructor(private val connection: Connection) : AutoCloseable {
    companion object {
        const val OPEN_READWRITE = 0
        const val CONFLICT_REPLACE = 5
        var failSqlContaining: String? = null
        fun openDatabase(path: String, factory: Any?, flags: Int): SQLiteDatabase =
            SQLiteDatabase(DriverManager.getConnection("jdbc:sqlite:$path"))
    }
    private var success = false
    fun beginTransaction() { execSQL("BEGIN IMMEDIATE"); success = false }
    fun setTransactionSuccessful() { success = true }
    fun endTransaction() { execSQL(if (success) "COMMIT" else "ROLLBACK") }
    fun execSQL(sql: String, args: Array<out Any?> = emptyArray()) {
        inject(sql)
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, v -> statement.setObject(i + 1, v) }
            statement.execute()
        }
    }
    fun rawQuery(sql: String, args: Array<String>?): Cursor = connection.prepareStatement(sql).use { statement ->
        args?.forEachIndexed { i, v -> statement.setObject(i + 1, v) }
        statement.executeQuery().use { result ->
            val rows = ArrayList<List<Any?>>()
            while (result.next()) rows += (1..result.metaData.columnCount).map { result.getObject(it) }
            Cursor(rows)
        }
    }
    fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues): Long {
        val sql = "INSERT INTO $table(${values.keys.joinToString(",")}) VALUES(${values.keys.joinToString(",") { "?" }})"
        execSQL(sql, values.values.toTypedArray())
        return rawQuery("SELECT last_insert_rowid()", null).use { it.moveToFirst(); it.getLong(0) }
    }
    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long = insertOrThrow(table, nullColumnHack, values)
    fun insertWithOnConflict(table: String, nullColumnHack: String?, values: ContentValues, conflict: Int): Long {
        val sql = "INSERT OR REPLACE INTO $table(${values.keys.joinToString(",")}) VALUES(${values.keys.joinToString(",") { "?" }})"
        execSQL(sql, values.values.toTypedArray())
        return rawQuery("SELECT last_insert_rowid()", null).use { it.moveToFirst(); it.getLong(0) }
    }
    fun update(table: String, values: ContentValues, where: String, args: Array<String>): Int = change(
        "UPDATE $table SET ${values.keys.joinToString(",") { "$it=?" }} WHERE $where", values.values.toList() + args)
    fun delete(table: String, where: String?, args: Array<String>?): Int = change(
        "DELETE FROM $table" + (where?.let { " WHERE $it" } ?: ""), args?.toList().orEmpty())
    private fun change(sql: String, values: List<Any?>): Int {
        inject(sql)
        return connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { i, v -> statement.setObject(i + 1, v) }
            statement.executeUpdate()
        }
    }
    private fun inject(sql: String) {
        failSqlContaining?.let { if (sql.contains(it)) error("Injected write failure") }
    }
    override fun close() { connection.close() }
}
class Cursor(private val rows: List<List<Any?>>) : AutoCloseable {
    private var index = -1
    fun moveToFirst(): Boolean { index = 0; return rows.isNotEmpty() }
    fun moveToNext(): Boolean { index++; return index < rows.size }
    fun isNull(column: Int) = rows[index][column] == null
    fun getLong(column: Int) = (rows[index][column] as Number).toLong()
    fun getInt(column: Int) = getLong(column).toInt()
    fun getDouble(column: Int) = (rows[index][column] as Number).toDouble()
    fun getString(column: Int) = rows[index][column].toString()
    override fun close() {}
}
abstract class SQLiteOpenHelper(private val context: Context, private val name: String, factory: Any?, private val version: Int) : AutoCloseable {
    private var opened: SQLiteDatabase? = null
    val writableDatabase: SQLiteDatabase get() {
        opened?.let { return it }
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0)
        val old = db.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
        if (old != version) {
            db.beginTransaction()
            try {
                if (old == 0) onCreate(db) else onUpgrade(db, old, version)
                db.execSQL("PRAGMA user_version=$version")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        opened = db
        return db
    }
    val readableDatabase get() = writableDatabase
    abstract fun onCreate(db: SQLiteDatabase)
    abstract fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int)
    override fun close() { opened?.close(); opened = null }
}
