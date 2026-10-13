package com.brz.gauge.trips
import java.io.File
internal object BuildConfig { const val VERSION_NAME = "android-reference" }
fun main(args: Array<String>) {
  val root = File(args[0])
  if (args.getOrNull(1) == "verify") {
    val original = TransferArchive.read(File(root, "android.zip").inputStream())
    val roundtrip = TransferArchive.read(File(root, "harmony.zip").inputStream())
    check(original.tables == roundtrip.tables)
    check(original.preferences == roundtrip.preferences)
    check(original.devices == roundtrip.devices)
    check(original.gauge == roundtrip.gauge)
    println("PASS Android -> Harmony -> Android roundtrip, all nine datasets, preferences and device state")
    return
  }
  val gauge = "AA:BB:CC:DD:EE:01"
  var uid = 1
  val tables = TransferSchema.tables.associate { table ->
    val row = table.columns.mapValues { (name, column) ->
      if (column.nullable) null else when (column.kind) {
        'T' -> when(name) { "device_id" -> gauge; "transfer_id" -> (uid++).toString(16).padStart(32,'0'); else -> "中文记录 🏎" }
        'R' -> 123.45
        else -> when(name) { "trip_id", "interval_id" -> if(table.name=="deleted_trips") 99L else 1L; "category" -> 2L; else -> 0L }
      }
    }.toMutableMap()
    if(table.name=="expenses") row["maintenance_type"]=3L
    table.name to listOf(row.toMap())
  }
  val prefs = mapOf(
    "vehicle_display_name" to mapOf("type" to "string", "value" to "BRZ 测试"),
    "refined_home_ui" to mapOf("type" to "boolean", "value" to false),
    "appearance.ZD8_finish" to mapOf("type" to "string", "value" to "satin"),
    "custom_base_distance_$gauge" to mapOf("type" to "long", "value" to 4000000000L),
    "refuel_history_revision_$gauge" to mapOf("type" to "int", "value" to 7L))
  val device = TransferSchema.deviceStateTypes.mapValues { (_, type) ->
    when { type.endsWith("?") -> null; type == "boolean" -> false; else -> 0L }
  }.toMutableMap()
  device["vehicle_data"] = "AQIDBA=="; device["vehicle_fuel_percent"] = 62.5
  device["vehicle_total_distance"] = 456789L; device["vehicle_average"] = 8.2
  device["vehicle_max_decel"] = -92L; device["vehicle_at"] = 1780000001000L
  device["last_fuel_percent"] = 62.5; device["last_fuel_at"] = 1780000001100L
  device["gauge_settings_data"] = "AQIDBA=="; device["gauge_settings_at"] = 1780000001200L
  device["firmware_version"] = "2.6.0"; device["firmware_at"] = 1780000001300L
  device["time_at"] = 1780000001400L; device["time_verified"] = true
  TransferArchive.write(TransferSnapshot(tables,prefs,gauge,1780000000000, mapOf(gauge to device)),File(root,"android.zip").outputStream())
  println("PASS Android generated deflated ZIP fixture with descriptors")
}
