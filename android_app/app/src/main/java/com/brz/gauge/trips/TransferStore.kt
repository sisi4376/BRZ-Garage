package com.brz.gauge.trips

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID

/** Replay committed preference changes before any Activity, receiver or BLE service starts. */
class BrzApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TransferStore.recoverPreferences(this)
    }
}

internal class TransferStore(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("vehicle_companion", Context.MODE_PRIVATE)
    val backupDirectory get() = File(context.filesDir, "transfer-backups")
    fun backups(): List<File> = backupDirectory.listFiles()?.filter { it.extension == "zip" }?.sortedByDescending { it.name }.orEmpty()

    private fun initialize() {
        val helpers = listOf<SQLiteOpenHelper>(TripDatabase(context), FuelDatabase(context), ExpenseDatabase(context),
            RefuelIntervalDatabase(context), CustomTripDatabase(context))
        helpers.forEach { helper -> helper.use { it.writableDatabase } }
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        initialize()
        SQLiteDatabase.openDatabase(context.getDatabasePath("brz_trip_history.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // An on-disk main DB + rollback journals provide one crash-atomic commit across ATTACHed DBs.
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { require(it.moveToFirst() && it.getString(0).equals("delete", true)) }
            listOf("fuel" to "brz_fuel_records.db", "expense" to "brz_expenses.db",
                "refuel" to "brz_refuel_intervals.db", "custom" to "brz_custom_trip_intervals.db").forEach { (alias, file) ->
                db.execSQL("ATTACH DATABASE ? AS $alias", arrayOf(context.getDatabasePath(file).path))
                db.rawQuery("PRAGMA $alias.journal_mode=DELETE", null).use { require(it.moveToFirst() && it.getString(0).equals("delete", true)) }
            }
            listOf("main", "fuel", "expense", "refuel", "custom").forEach { db.execSQL("PRAGMA $it.synchronous=FULL") }
            db.beginTransaction()
            try {
                val result = block(db)
                db.setTransactionSuccessful()
                return result
            } finally { db.endTransaction() }
        }
    }

    fun snapshot(): TransferSnapshot = transaction(::read)

    private fun long(values: Map<String, *>, key: String, fallback: Long = 0L): Long = when (val value = values[key]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: fallback
        else -> fallback
    }.coerceAtLeast(0L)

    private fun int(values: Map<String, *>, key: String, fallback: Long = 0L): Long =
        long(values, key, fallback).coerceAtMost(Int.MAX_VALUE.toLong())

    private fun text(values: Map<String, *>, key: String): String? =
        values[key]?.toString()?.takeIf { it.isNotEmpty() }

    private fun portableDevice(gauge: String, values: Map<String, *>): TransferRow {
        fun key(prefix: String) = "${prefix}_$gauge"
        val raw = text(values, key("vehicle"))
        val vehicle = runCatching { raw?.let { VehicleState.parse(Base64.getDecoder().decode(it)) } }.getOrNull()
        val settings = text(values, key("gauge_settings"))?.takeIf {
            runCatching { Base64.getDecoder().decode(it) }.isSuccess
        }
        val lastFuel = text(values, key("last_gauge_fuel_percent"))?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.0..100.0 }
        return linkedMapOf(
            "vehicle_data" to raw.takeIf { vehicle != null },
            "vehicle_epoch" to (vehicle?.epochS ?: 0L),
            "vehicle_uptime" to (vehicle?.uptimeS ?: 0L),
            "vehicle_fuel_percent" to vehicle?.fuelPercent,
            "vehicle_current_distance" to (vehicle?.currentDistanceM ?: 0L),
            "vehicle_current_duration" to (vehicle?.currentDurationS ?: 0L),
            "vehicle_current_fuel" to (vehicle?.currentFuelMl ?: 0L),
            "vehicle_total_distance" to (vehicle?.totalDistanceM ?: 0L),
            "vehicle_total_duration" to (vehicle?.totalDurationS ?: 0L),
            "vehicle_total_fuel" to (vehicle?.totalFuelMl ?: 0L),
            "vehicle_rpm" to vehicle?.rpm?.toLong(),
            "vehicle_speed" to vehicle?.speed?.toLong(),
            "vehicle_average" to vehicle?.average,
            "vehicle_max_speed" to vehicle?.maxSpeedKmh?.toLong(),
            "vehicle_max_rpm" to vehicle?.maxRpm?.toLong(),
            "vehicle_max_accel" to vehicle?.maxAccelX100?.toLong(),
            "vehicle_max_decel" to vehicle?.maxDecelX100?.toLong(),
            "vehicle_current_start" to (vehicle?.currentStartEpochS ?: 0L),
            "vehicle_fuel_sequence" to vehicle?.fuelSampleSequence,
            "vehicle_at" to long(values, key("vehicle_at")),
            "last_fuel_percent" to lastFuel,
            "last_fuel_at" to long(values, key("last_gauge_fuel_at")),
            "last_fuel_sequence" to values[key("last_gauge_fuel_sequence")]?.let {
                long(values, key("last_gauge_fuel_sequence"))
            },
            "refuel_start" to long(values, key("refuel_start")),
            "refuel_duration" to long(values, key("refuel_duration")),
            "refuel_distance" to long(values, key("refuel_distance")),
            "refuel_fuel" to long(values, key("refuel_fuel")),
            "refuel_protocol" to int(values, key("refuel_protocol"), 1L),
            "refuel_meta_at" to long(values, key("refuel_meta_at")),
            "gauge_settings_data" to settings,
            "gauge_settings_at" to long(values, key("gauge_settings_at")),
            "firmware_version" to text(values, key("firmware_version")),
            "firmware_build" to text(values, key("firmware_build")),
            "firmware_project" to text(values, key("firmware_project")),
            "firmware_board" to text(values, key("firmware_board")),
            "firmware_variant" to text(values, key("firmware_variant")),
            "firmware_lcd" to text(values, key("firmware_lcd")),
            "firmware_screen_w" to values[key("firmware_screen_w")]?.let { int(values, key("firmware_screen_w")) },
            "firmware_screen_h" to values[key("firmware_screen_h")]?.let { int(values, key("firmware_screen_h")) },
            "firmware_bpp" to values[key("firmware_bpp")]?.let { int(values, key("firmware_bpp")) },
            "firmware_flash_mb" to values[key("firmware_flash_mb")]?.let { int(values, key("firmware_flash_mb")) },
            "firmware_ota_slots" to values[key("firmware_ota_slots")]?.let { int(values, key("firmware_ota_slots")) },
            "firmware_at" to long(values, key("firmware_at")),
            "time_at" to long(values, key("time_at")),
            "time_verified" to (values[key("time_verified")] as? Boolean ?: false),
        )
    }

    private fun read(db: SQLiteDatabase): TransferSnapshot {
        val tables = TransferSchema.tables.associate { table ->
            val rows = ArrayList<TransferRow>()
            val names = table.columns.keys.toList()
            db.rawQuery("SELECT ${names.joinToString(",")} FROM ${table.database}.${table.sqlTable}", null).use { cursor ->
                while (cursor.moveToNext()) rows += names.mapIndexed { index, name ->
                    name to if (cursor.isNull(index)) null else when (table.columns.getValue(name).kind) {
                        'I' -> cursor.getLong(index)
                        'R' -> cursor.getDouble(index)
                        else -> cursor.getString(index)
                    }
                }.toMap()
            }
            table.name to rows
        }
        val allPrefs = prefs.all
        val appearancePrefs = context.getSharedPreferences("vehicle_appearance", Context.MODE_PRIVATE).all
        val selectedGauge = ((allPrefs["address"] as? String).orEmpty().ifEmpty {
            (allPrefs["archive_gauge"] as? String).orEmpty()
        }).uppercase()
        val suffix = Regex("([0-9A-F]{2}(?::[0-9A-F]{2}){5})$")
        val gauges = (tables.values.flatten().mapNotNull { it["device_id"] as? String } +
            allPrefs.keys.mapNotNull { suffix.find(it.uppercase())?.groupValues?.get(1) } +
            setOf(selectedGauge)).filter { TransferSchema.gaugePattern.matches(it) }.toSet()
        val gauge = selectedGauge.ifEmpty { gauges.singleOrNull().orEmpty() }
        val keys = TransferSchema.preferenceKeys(gauges) + allPrefs.keys.filter { TransferSchema.preferenceType(it) != null }
        val preferences = keys.associateWith { key ->
            val type = TransferSchema.preferenceType(key)!!
            val value = allPrefs[key]
            // Normalize historical numeric-string preferences into the typed portable representation.
            val normalized: Any? = when (type) {
                "long", "int" -> when (value) { is Number -> value.toLong(); is String -> value.toLongOrNull(); else -> null }
                "boolean" -> when (value) { is Boolean -> value; is String -> value == "true" || value == "1"; is Number -> value.toInt() != 0; else -> null }
                else -> value?.toString()
            }
            mapOf("type" to type, "value" to normalized)
        }.toMutableMap()
        listOf("ZD8_color", "ZD8_finish", "ZC6_color", "ZC6_finish").forEach { key ->
            if (appearancePrefs.containsKey(key)) preferences["appearance.$key"] =
                mapOf("type" to "string", "value" to appearancePrefs[key]?.toString())
        }
        val devices = gauges.associateWith { portableDevice(it, allPrefs) }
        return TransferSnapshot(tables, preferences, gauge, devices = devices).also(TransferSchema::validate)
    }

    fun preview(incoming: TransferSnapshot, preferIncoming: Boolean): TransferMergeResult =
        TransferMerge.merge(snapshot(), incoming, preferIncoming)

    data class Imported(val result: TransferMergeResult, val backup: File)
    fun import(incoming: TransferSnapshot, preferIncoming: Boolean): Imported {
        TransferSchema.validate(incoming)
        val imported = transaction { db ->
            val old = read(db)
            val merged = TransferMerge.merge(old, incoming, preferIncoming)
            require(backupDirectory.isDirectory || backupDirectory.mkdirs()) { "无法创建导入前备份目录" }
            val backup = File(backupDirectory, "before-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip")
            backup.outputStream().use { TransferArchive.write(old, it) }
            backup.inputStream().use { TransferArchive.read(it) } // Verify the recovery package before touching data.
            // Live rows first, then tombstones: local DELETE triggers must not overwrite the selected merge outcome.
            TransferSchema.tables.forEach { table ->
                val before = old.tables.getValue(table.name).associateBy(table::key)
                val after = merged.snapshot.tables.getValue(table.name).associateBy(table::key)
                val sql = "${table.database}.${table.sqlTable}"
                val where = table.keys.joinToString(" AND ") { "$it=?" }
                // Read actual DB contents, since live-row triggers may have added tombstones in this transaction.
                val actual = ArrayList<List<String>>()
                db.rawQuery("SELECT ${table.keys.joinToString(",")} FROM $sql", null).use { cursor ->
                    while (cursor.moveToNext()) actual += table.keys.indices.map { cursor.getString(it) }
                }
                actual.filter { it.joinToString("|") !in after }.forEach { db.delete(sql, where, it.toTypedArray()) }
                after.forEach { (key, row) ->
                    if (before[key] == row && table.deletedTable != null) return@forEach
                    val values = ContentValues().apply {
                        row.forEach { (name, value) -> when (value) {
                            null -> putNull(name)
                            is String -> put(name, value)
                            is Long -> put(name, value)
                            is Double -> put(name, value)
                        } }
                        if (table.name == "trips") put("synced_at_s", System.currentTimeMillis() / 1000)
                    }
                    val args = table.keys.map { row[it].toString() }.toTypedArray()
                    if (db.update(sql, values, where, args) == 0) db.insertOrThrow(sql, null, values)
                }
            }
            // Journal the display vehicle with the data commit; Bluetooth binding stays local.
            db.execSQL("INSERT OR REPLACE INTO transfer_preferences(key,value) VALUES(?,?)",
                arrayOf(ARCHIVE_GAUGE_KEY, JSONObject().put("value", merged.snapshot.gauge).toString()))
            merged.snapshot.preferences.forEach { (key, value) ->
                db.execSQL("INSERT OR REPLACE INTO transfer_preferences(key,value) VALUES(?,?)",
                    arrayOf(key, TransferArchive.jsonObject(value).toString()))
            }
            merged.snapshot.devices.forEach { (gauge, value) ->
                db.execSQL("INSERT OR REPLACE INTO transfer_preferences(key,value) VALUES(?,?)",
                    arrayOf(DEVICE_PREFIX + gauge, TransferArchive.jsonObject(value).toString()))
            }
            // Re-read the retained gauge window once, instead of trusting MAX(id) after a merge with gaps.
            (merged.snapshot.tables.getValue("trips") + merged.snapshot.tables.getValue("deleted_trips"))
                .map { it.getValue("device_id") as String }.toSet().forEach { gauge ->
                    db.execSQL("INSERT OR IGNORE INTO transfer_reconcile(device_id) VALUES(?)", arrayOf(gauge))
                }
            merged.snapshot.tables.getValue("refuel").map { it.getValue("device_id") as String }.toSet().forEach { gauge ->
                db.execSQL("INSERT OR IGNORE INTO transfer_refuel_reconcile(device_id) VALUES(?)", arrayOf(gauge))
            }
            Imported(merged, backup)
        }
        recoverPreferences(context)
        return imported
    }

    companion object {
        private const val DEVICE_PREFIX = "__device_state__"
        private const val ARCHIVE_GAUGE_KEY = "__archive_gauge__"

        private fun encodedVehicle(value: JSONObject): String? {
            if (value.getLong("vehicle_at") <= 0L) return null
            val bytes = ByteArray(84)
            val fuel = if (value.isNull("vehicle_fuel_percent")) null else value.getDouble("vehicle_fuel_percent")
            val average = if (value.isNull("vehicle_average")) null else value.getDouble("vehicle_average")
            val flags = (if (fuel != null) 1 else 0) or
                (if (!value.isNull("vehicle_rpm") || !value.isNull("vehicle_speed")) 2 else 0) or
                (if (average != null) 8 else 0)
            fun u16(field: String): Short = if (value.isNull(field)) 0 else value.getInt(field).coerceIn(0, 65535).toShort()
            fun signed16(field: String): Short = if (value.isNull(field)) 0 else value.getInt(field).coerceIn(-32768, 32767).toShort()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(2).put(flags.toByte()).putShort(84).putLong(value.getLong("vehicle_epoch"))
                .putInt(value.getLong("vehicle_uptime").toInt())
                .putShort(kotlin.math.round((fuel ?: 0.0) * 100.0).toInt().coerceIn(0, 10000).toShort())
                .putShort(kotlin.math.round((average ?: 0.0) * 100.0).toInt().coerceIn(0, 6000).toShort())
                .putInt(value.getLong("vehicle_current_distance").toInt())
                .putInt(value.getLong("vehicle_current_duration").toInt())
                .putInt(value.getLong("vehicle_current_fuel").toInt())
                .putLong(value.getLong("vehicle_total_distance")).putLong(value.getLong("vehicle_total_duration"))
                .putLong(value.getLong("vehicle_total_fuel")).putShort(u16("vehicle_rpm")).putShort(u16("vehicle_speed"))
                .putShort(u16("vehicle_max_speed")).putShort(u16("vehicle_max_rpm"))
                .putShort(signed16("vehicle_max_accel")).putShort(signed16("vehicle_max_decel"))
                .putLong(value.getLong("vehicle_current_start"))
                .putInt(if (value.isNull("vehicle_fuel_sequence")) 0 else value.getLong("vehicle_fuel_sequence").toInt())
            buffer.putShort(82, TripBleProtocol.crc16(bytes, 82).toShort())
            return Base64.getEncoder().encodeToString(bytes)
        }

        private fun restoreDevice(edit: SharedPreferences.Editor, gauge: String, value: JSONObject) {
            require(TransferSchema.gaugePattern.matches(gauge) && value.keys().asSequence().toSet() == TransferSchema.deviceStateTypes.keys)
            fun key(prefix: String) = "${prefix}_$gauge"
            fun string(field: String, prefix: String = field) {
                if (value.isNull(field)) edit.remove(key(prefix)) else edit.putString(key(prefix), value.getString(field))
            }
            fun long(field: String, prefix: String = field) = edit.putLong(key(prefix), value.getLong(field))
            fun int(field: String, prefix: String = field) = edit.putInt(key(prefix), value.getInt(field))
            val rawVehicle = if (value.isNull("vehicle_data")) encodedVehicle(value) else value.getString("vehicle_data")
            if (rawVehicle == null) edit.remove(key("vehicle")) else edit.putString(key("vehicle"), rawVehicle)
            long("vehicle_at")
            if (value.isNull("last_fuel_percent")) edit.remove(key("last_gauge_fuel_percent"))
            else edit.putString(key("last_gauge_fuel_percent"), value.getDouble("last_fuel_percent").toString())
            long("last_fuel_at", "last_gauge_fuel_at")
            if (value.isNull("last_fuel_sequence")) edit.remove(key("last_gauge_fuel_sequence"))
            else edit.putLong(key("last_gauge_fuel_sequence"), value.getLong("last_fuel_sequence"))
            listOf("refuel_start", "refuel_duration", "refuel_distance", "refuel_fuel", "refuel_meta_at",
                "gauge_settings_at", "firmware_at", "time_at").forEach { long(it) }
            int("refuel_protocol")
            string("gauge_settings_data", "gauge_settings")
            listOf("firmware_version", "firmware_build", "firmware_project", "firmware_board",
                "firmware_variant", "firmware_lcd").forEach { string(it) }
            listOf("firmware_screen_w", "firmware_screen_h", "firmware_bpp", "firmware_flash_mb",
                "firmware_ota_slots").forEach { field ->
                if (value.isNull(field)) edit.remove(key(field)) else int(field)
            }
            edit.putBoolean(key("time_verified"), value.getBoolean("time_verified"))
        }

        @Synchronized fun recoverPreferences(context: Context) {
            val file = context.getDatabasePath("brz_trip_history.db")
            if (!file.exists()) return
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='transfer_preferences'", null)
                    .use { it.moveToFirst() }
                if (!exists) return
                db.beginTransaction()
                try {
                    val edit = context.getSharedPreferences("vehicle_companion", Context.MODE_PRIVATE).edit()
                    val appearanceEdit = context.getSharedPreferences("vehicle_appearance", Context.MODE_PRIVATE).edit()
                    var count = 0
                    var appearanceCount = 0
                    db.rawQuery("SELECT key,value FROM transfer_preferences", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val key = cursor.getString(0)
                            val value = JSONObject(cursor.getString(1))
                            if (key == ARCHIVE_GAUGE_KEY) {
                                val gauge = value.getString("value")
                                require(gauge.isEmpty() || TransferSchema.gaugePattern.matches(gauge))
                                edit.putString("archive_gauge", gauge)
                            } else if (key.startsWith(DEVICE_PREFIX)) {
                                restoreDevice(edit, key.removePrefix(DEVICE_PREFIX), value)
                            } else if (key.startsWith("appearance.")) {
                                require(TransferSchema.preferenceType(key) == "string" && value.getString("type") == "string")
                                val target = key.removePrefix("appearance.")
                                if (value.isNull("value")) appearanceEdit.remove(target)
                                else appearanceEdit.putString(target, value.getString("value"))
                                appearanceCount++
                            } else {
                                require(TransferSchema.preferenceType(key) == value.getString("type"))
                                if (value.isNull("value")) edit.remove(key) else when (value.getString("type")) {
                                    "string" -> edit.putString(key, value.getString("value"))
                                    "long" -> edit.putLong(key, value.getLong("value"))
                                    "int" -> edit.putInt(key, value.getInt("value"))
                                    "boolean" -> edit.putBoolean(key, value.getBoolean("value"))
                                }
                            }
                            count++
                        }
                    }
                    if (count > 0) {
                        DataTransferSession.recoveryRequired = true
                        check(edit.commit()) { "配置尚未完成恢复，请重新打开 App" }
                        if (appearanceCount > 0) check(appearanceEdit.commit()) { "车辆外观尚未完成恢复，请重新打开 App" }
                        db.delete("transfer_preferences", null, null)
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                DataTransferSession.recoveryRequired = false
            }
        }
    }
}
