package com.brz.gauge.trips

/** Portable v2 data contract. Local SQLite row numbers are deliberately excluded. */
internal typealias TransferRow = Map<String, Any?>

internal data class TransferColumn(val kind: Char, val nullable: Boolean = false,
    val min: Long = 0, val max: Long = Long.MAX_VALUE)

internal data class TransferTable(val name: String, val database: String, val sqlTable: String,
    val keys: List<String>, val columns: Map<String, TransferColumn>, val localId: String? = null,
    val deletedTable: String? = null) {
    fun key(row: TransferRow): String = keys.joinToString("|") { row[it].toString() }
}

internal data class TransferSnapshot(
    val tables: Map<String, List<TransferRow>>,
    val preferences: Map<String, TransferRow>,
    val gauge: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val devices: Map<String, TransferRow> = emptyMap(),
)

internal object TransferSchema {
    private val n = TransferColumn('I', max = 0xffff_ffffL)
    private val signed = TransferColumn('I', min = -8_589_934_592L)
    private val bit = TransferColumn('I', max = 1)
    private val text = TransferColumn('T')
    private val real = TransferColumn('R')
    private val optionalReal = TransferColumn('R', true)
    private val optionalInt = TransferColumn('I', true, max = 65535)
    private val optionalSigned = TransferColumn('I', true, -32768, 32767)
    private fun fields(vararg names: String) = names.associateWith { n }
    private val interval = fields("start_epoch_s", "end_epoch_s", "duration_s", "distance_m", "fuel_ml")
    val tables = listOf(
        TransferTable("trips", "main", "trips", listOf("device_id", "trip_id"),
            interval + fields("avg_l100_x100", "flags", "poll_requested", "poll_received") + mapOf(
                "device_id" to text, "trip_id" to signed, "time_revised" to bit, "data_revised" to bit,
                "max_speed_kmh" to optionalInt, "max_rpm" to optionalInt,
                "max_accel_x100" to optionalSigned, "max_decel_x100" to optionalSigned),
            deletedTable = "deleted_trips"),
        TransferTable("deleted_trips", "main", "deleted_trips", listOf("device_id", "trip_id"),
            mapOf("device_id" to text, "trip_id" to signed, "deleted_at_s" to n)),
        TransferTable("fuel", "fuel", "fuel_records", listOf("transfer_id"), mapOf(
            "transfer_id" to text, "date_epoch_day" to TransferColumn('I', min = -36525, max = 47482),
            "odometer_km" to real, "litres" to real, "cost" to real, "full_tank" to bit, "draft" to bit),
            "id", "fuel_deleted"),
        TransferTable("expenses", "expense", "expenses", listOf("transfer_id"), mapOf(
            "transfer_id" to text, "device_id" to text,
            "date_epoch_day" to TransferColumn('I', min = -36525, max = 47482),
            "category" to TransferColumn('I', min = 1, max = 2), "amount" to real,
            "title" to text, "note" to text, "odometer_km" to optionalReal,
            "maintenance_type" to TransferColumn('I', true, min = 1, max = 4)), "id", "expenses_deleted"),
        TransferTable("refuel", "refuel", "refuel_intervals", listOf("device_id", "interval_id"),
            interval + fields("interval_id", "odometer_x10_km", "detected_added_ml", "flags") +
                mapOf("device_id" to text, "data_revised" to bit)),
        TransferTable("custom", "custom", "custom_trip_intervals", listOf("transfer_id"),
            interval + mapOf("transfer_id" to text, "device_id" to text, "name" to text),
            "interval_id", "custom_deleted"),
    ).let { base -> base + base.filter { it.localId != null }.map {
        TransferTable(it.deletedTable!!, it.database, "transfer_deleted", listOf("transfer_id"),
            mapOf("transfer_id" to text, "deleted_at_s" to n))
    } }
    val byName = tables.associateBy { it.name }
    val gaugePattern = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
    private val uuidPattern = Regex("[0-9a-f]{32}")

    // Explicit allowlist: never transport consent, permissions, commands, pairing or OTA state.
    private val globalPrefs = mapOf(
        "vehicle_display_name" to "string", "custom_license_plate" to "string",
        "show_custom_license_plate" to "boolean", "show_3d_vehicle" to "boolean",
        "selected_vehicle_profile" to "int", "tank" to "string",
        "range_consumption_source" to "string", "range_correction_factor" to "string",
        "manual_fuel_percent" to "string", "manual_fuel_baseline_ml" to "long",
        "maintenance_severe_conditions" to "boolean",
        "refined_home_ui" to "boolean", "grouped_settings_ui" to "boolean",
        "appearance.ZD8_color" to "string", "appearance.ZD8_finish" to "string",
        "appearance.ZC6_color" to "string", "appearance.ZC6_finish" to "string",
    )
    private val devicePrefs = mapOf(
        "show_since_refuel" to "boolean", "show_custom_trip" to "boolean",
        "custom_trip_name" to "string", "custom_reset_at" to "long",
        "custom_base_distance" to "long", "custom_base_duration" to "long", "custom_base_fuel" to "long",
        "refuel_history_revision" to "int", "odometer_calibration_m" to "long",
        "odometer_anchor_trip" to "long", "odometer_display" to "boolean", "overflow" to "boolean",
    )
    val deviceStateTypes = linkedMapOf(
        "vehicle_data" to "base64?", "vehicle_epoch" to "long", "vehicle_uptime" to "long",
        "vehicle_fuel_percent" to "real?", "vehicle_current_distance" to "long", "vehicle_current_duration" to "long",
        "vehicle_current_fuel" to "long", "vehicle_total_distance" to "long", "vehicle_total_duration" to "long",
        "vehicle_total_fuel" to "long", "vehicle_rpm" to "int?", "vehicle_speed" to "int?", "vehicle_average" to "real?",
        "vehicle_max_speed" to "int?", "vehicle_max_rpm" to "int?", "vehicle_max_accel" to "signed?",
        "vehicle_max_decel" to "signed?", "vehicle_current_start" to "long", "vehicle_fuel_sequence" to "long?",
        "vehicle_at" to "long", "last_fuel_percent" to "real?", "last_fuel_at" to "long",
        "last_fuel_sequence" to "long?", "refuel_start" to "long", "refuel_duration" to "long",
        "refuel_distance" to "long", "refuel_fuel" to "long", "refuel_protocol" to "int",
        "refuel_meta_at" to "long", "gauge_settings_data" to "base64?", "gauge_settings_at" to "long",
        "firmware_version" to "text?", "firmware_build" to "text?", "firmware_project" to "text?",
        "firmware_board" to "text?", "firmware_variant" to "text?", "firmware_lcd" to "text?",
        "firmware_screen_w" to "int?", "firmware_screen_h" to "int?", "firmware_bpp" to "int?",
        "firmware_flash_mb" to "int?", "firmware_ota_slots" to "int?", "firmware_at" to "long",
        "time_at" to "long", "time_verified" to "boolean",
    )
    fun preferenceType(key: String): String? = globalPrefs[key] ?: devicePrefs.entries.firstOrNull {
        key.startsWith(it.key + "_") && gaugePattern.matches(key.removePrefix(it.key + "_"))
    }?.value

    fun validate(snapshot: TransferSnapshot) {
        require(snapshot.createdAt in 1L..253402300799999L) { "导出时间无效" }
        require(snapshot.tables.keys == byName.keys) { "数据包的数据表不完整或版本不支持" }
        require(snapshot.gauge.isEmpty() || gaugePattern.matches(snapshot.gauge)) { "仪表标识无效" }
        require(snapshot.tables.values.sumOf { it.size } <= 100_000) { "数据包超过 100000 条记录上限" }
        tables.forEach { table ->
            val seen = HashSet<String>()
            snapshot.tables.getValue(table.name).forEach { row ->
                require(row.keys == table.columns.keys) { "${table.name} 字段不完整" }
                table.columns.forEach { (name, column) ->
                    val value = row[name]
                    require(value != null || column.nullable) { "$name 不允许为空" }
                    if (value != null) when (column.kind) {
                        'T' -> require(value is String && value.length <= 8192) { "$name 文本无效" }
                        'I' -> require(value is Long && value in column.min..column.max) { "$name 整数无效" }
                        'R' -> require(value is Double && value.isFinite() && value in 0.0..1.0e12) { "$name 数值无效" }
                    }
                }
                row["device_id"]?.let { require(gaugePattern.matches(it as String) ||
                    table.name == "expenses" && it == "local") { "仪表地址无效" } }
                row["transfer_id"]?.let { require(uuidPattern.matches(it as String)) { "记录标识无效" } }
                row["trip_id"]?.let { require(it as Long != 0L && it !in -2L..-1L && it <= 0xffff_ffffL) { "行程编号无效" } }
                row["interval_id"]?.let { require(it as Long in 1..0xffff_ffffL) { "区间编号无效" } }
                require(seen.add(table.key(row))) { "数据包内有重复记录标识" }
            }
        }
        tables.filter { it.deletedTable != null }.forEach { table ->
            val deleted = snapshot.tables.getValue(table.deletedTable!!).map { table.key(it) }.toSet()
            require(snapshot.tables.getValue(table.name).none { table.key(it) in deleted }) { "记录与删除标记矛盾" }
        }
        val tripDeletions = snapshot.tables.getValue("deleted_trips").map { byName.getValue("trips").key(it) }.toSet()
        snapshot.tables.getValue("trips").filter { (it.getValue("trip_id") as Long) < 0 }.forEach { row ->
            val parent = (-(row.getValue("trip_id") as Long) - 1) / 2
            require("${row["device_id"]}|$parent" in tripDeletions) { "拆分行程缺少原行程删除标记" }
        }
        require(snapshot.preferences.size <= 2048) { "配置数量超限" }
        snapshot.preferences.forEach { (key, entry) ->
            val type = preferenceType(key)
            require(type != null && entry.keys == setOf("type", "value") && entry["type"] == type) { "不支持的配置：$key" }
            val value = entry["value"]
            require(value == null || when (type) {
                "string" -> value is String && value.length <= 8192
                "boolean" -> value is Boolean
                "int" -> value is Long && value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                "long" -> value is Long && value >= -1
                else -> false
            }) { "配置类型无效：$key" }
        }
        require(snapshot.devices.size <= 128) { "仪表缓存数量超限" }
        snapshot.devices.forEach { (gauge, state) ->
            require(gaugePattern.matches(gauge) && state.keys == deviceStateTypes.keys) { "仪表缓存字段不完整" }
            deviceStateTypes.forEach { (key, type) ->
                val value = state[key]
                require(value != null || type.endsWith("?")) { "$key 不允许为空" }
                if (value != null) require(when {
                    type.startsWith("text") -> value is String && value.length <= 256
                    type.startsWith("base64") -> value is String && value.length <= 16384 &&
                        Regex("[A-Za-z0-9+/]*={0,2}").matches(value)
                    type == "boolean" -> value is Boolean
                    type.startsWith("real") -> value is Double && value.isFinite() && value in 0.0..1.0e12
                    type.startsWith("signed") -> value is Long && value in -32768L..32767L
                    else -> value is Long && value >= 0
                }) { "仪表缓存字段无效：$key" }
            }
        }
    }

    fun preferenceKeys(gauges: Set<String>): Set<String> = globalPrefs.keys +
        gauges.flatMap { gauge -> devicePrefs.keys.map { "${it}_$gauge" } }
}

/** Process-local gate; a killed process cannot leave automatic connection permanently disabled. */
internal object DataTransferSession {
    @Volatile var busy = false
    @Volatile var recoveryRequired = false
}
