package com.brz.gauge.trips

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val GAUGE = "AA:BB:CC:DD:EE:01"
private var passed = 0
private fun passed(name: String) { passed++; println("PASS: $name") }
private fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) }
private fun trip(id: Long) = TripRecord(GAUGE, id, 1780000000, 1780000060, 60, 1000, 100, 1000, 1)
private fun normalized(snapshot: TransferSnapshot) = snapshot.tables.mapValues { it.value.toSet() }
private fun archive(snapshot: TransferSnapshot): ByteArray = ByteArrayOutputStream().also { TransferArchive.write(snapshot, it) }.toByteArray()
private fun decode(bytes: ByteArray) = TransferArchive.read(ByteArrayInputStream(bytes))
private fun vehicleBytes(): ByteArray {
    val bytes = ByteArray(84)
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    b.put(2).put(0x0b).putShort(84).putLong(1_780_000_000L).putInt(3600)
        .putShort(6250).putShort(820).putInt(12_345).putInt(900).putInt(1_250)
        .putLong(456_789).putLong(32_100).putLong(38_500).putShort(2_500).putShort(88)
        .putShort(165).putShort(7_200).putShort(135).putShort((-92).toShort())
        .putLong(1_779_999_100L).putInt(77)
    b.putShort(82, TripBleProtocol.crc16(bytes, 82).toShort())
    check(VehicleState.parse(bytes) != null)
    return bytes
}
private fun rewrite(bytes: ByteArray, alter: (String, ByteArray) -> ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { output -> ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            output.putNextEntry(ZipEntry(entry.name)); output.write(alter(entry.name, input.readBytes())); output.closeEntry()
        }
    } }
    return out.toByteArray()
}

fun main(args: Array<String>) {
    val root = File(args[0], "devices")
    val a = Context(File(root, "a")); val b = Context(File(root, "b")); val c = Context(File(root, "c"))
    val storeA = TransferStore(a); val storeB = TransferStore(b)
    check(storeA.snapshot().tables.values.all { it.isEmpty() })
    passed("new devices contain no seeded personal fuel records")
    val aPrefs = a.getSharedPreferences("vehicle_companion", 0)
    aPrefs.edit().putString("address", GAUGE).putString("tank", "45")
        .putLong("custom_base_distance_$GAUGE", 10000).putInt("refuel_history_revision_$GAUGE", 1)
        .putString("vehicle_$GAUGE", Base64.getEncoder().encodeToString(vehicleBytes())).putLong("vehicle_at_$GAUGE", 1_800_000_001_000L)
        .putString("last_gauge_fuel_percent_$GAUGE", "62.5").putLong("last_gauge_fuel_at_$GAUGE", 1_800_000_001_100L)
        .putLong("last_gauge_fuel_sequence_$GAUGE", 77).putLong("refuel_start_$GAUGE", 1_779_999_100L)
        .putLong("refuel_duration_$GAUGE", 900).putLong("refuel_distance_$GAUGE", 12_345)
        .putLong("refuel_fuel_$GAUGE", 1_250).putInt("refuel_protocol_$GAUGE", 2)
        .putLong("refuel_meta_at_$GAUGE", 1_800_000_001_200L)
        .putString("gauge_settings_$GAUGE", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)))
        .putLong("gauge_settings_at_$GAUGE", 1_800_000_001_300L)
        .putString("firmware_version_$GAUGE", "2.6.0").putString("firmware_build_$GAUGE", "release")
        .putString("firmware_project_$GAUGE", "BRZ").putString("firmware_board_$GAUGE", "ESP32")
        .putString("firmware_variant_$GAUGE", "ZD8").putString("firmware_lcd_$GAUGE", "ST7789")
        .putInt("firmware_screen_w_$GAUGE", 240).putInt("firmware_screen_h_$GAUGE", 240)
        .putInt("firmware_bpp_$GAUGE", 16).putInt("firmware_flash_mb_$GAUGE", 16)
        .putInt("firmware_ota_slots_$GAUGE", 2).putLong("firmware_at_$GAUGE", 1_800_000_001_400L)
        .putLong("time_at_$GAUGE", 1_800_000_001_500L).putBoolean("time_verified_$GAUGE", true)
        .putBoolean("refined_home_ui", false).putBoolean("grouped_settings_ui", false)
        .putBoolean("automatic", true).putInt("accepted_user_notice_version", 999)
        .putLong("pending_gauge_odometer_m_$GAUGE", 50000).commit()
    a.getSharedPreferences("vehicle_appearance", 0).edit().putString("ZD8_color", "#155dcc")
        .putString("ZD8_finish", "satin").commit()
    TripDatabase(a).use { check(it.upsert(trip(1))); check(it.upsert(trip(2).copy(dataRevised = true, distanceM = 2000))) }
    val fuelA = FuelDatabase(a).use { it.save(FuelRecord(dateEpochDay = 20500, odometerKm = 100.0, litres = 30.0, cost = 200.0)) }
    val expenseA = ExpenseDatabase(a).use { it.save(ExpenseRecord(deviceId = GAUGE, dateEpochDay = 20500,
        category = ExpenseCategory.MAINTENANCE, amount = 300.0, title = "机油", note = "中文备注",
        maintenanceType = MaintenanceRecordType.A)) }
    RefuelIntervalDatabase(a).use { it.upsert(RefuelInterval(GAUGE, 1, 1780000000, 1780000060, 60, 1000, 100, 1000, 10000, 1)) }
    CustomTripDatabase(a).use { db -> repeat(55) { i -> check(db.insert(CustomTripInterval(deviceId = GAUGE,
        name = "区间$i", startEpochS = 1780000000L + i * 60, endEpochS = 1780000060L + i * 60,
        durationS = 60, distanceM = 1000, fuelMl = 100))) } }
    val initial = storeA.snapshot()
    check(initial.tables.getValue("custom").size == 55)
    check(initial.devices.getValue(GAUGE)["vehicle_total_distance"] == 456_789L)
    check(initial.devices.getValue(GAUGE)["vehicle_max_decel"] == -92L)
    check(initial.devices.getValue(GAUGE)["firmware_version"] == "2.6.0")
    check(initial.devices.getValue(GAUGE)["time_verified"] == true)
    check(initial.preferences["appearance.ZD8_finish"]?.get("value") == "satin")
    check(initial.preferences["refined_home_ui"]?.get("value") == false && initial.preferences["grouped_settings_ui"]?.get("value") == false)
    check(initial.preferences.keys.none { it.startsWith("pending_") || it == "automatic" || it.startsWith("accepted_") })
    passed("all datasets, Chinese text and calibration export; connection/consent commands excluded")
    val bytes = archive(initial)
    val portable = decode(bytes)
    check(normalized(initial) == normalized(portable) && initial.preferences == portable.preferences && initial.devices == portable.devices)
    passed("ZIP/JSON/SHA-256 roundtrip")
    // A genuinely unbound new phone must be usable without pairing, including after process death.
    aPrefs.edit().putLong("odometer_calibration_m_$GAUGE", 42_000_000)
        .putLong("odometer_anchor_trip_$GAUGE", 2)
        .putBoolean("show_since_refuel_$GAUGE", true).commit()
    ExpenseDatabase(a).use { it.save(ExpenseRecord(deviceId = "local", dateEpochDay = 20501,
        category = ExpenseCategory.DAILY, amount = 25.0, title = "停车")) }
    val relocation = decode(archive(storeA.snapshot()))
    args.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { path ->
        File(path).also { it.parentFile.mkdirs() }.writeBytes(archive(relocation))
    }
    val freshPath = File(root, "fresh-offline")
    val fresh = Context(freshPath)
    TransferStore(fresh).import(relocation, false)
    val reboot = Context(freshPath)
    TransferStore.recoverPreferences(reboot)
    val offline = AppState(reboot)
    check(offline.address.isEmpty() && !offline.hasAcceptedCurrentUserNotice)
    check(offline.dataDeviceId == GAUGE && offline.vehicle()?.totalDistanceM == 456_789L)
    check(offline.vehicle()?.currentDistanceM == 12_345L && offline.effectiveFuelPercent(offline.vehicle()) == 62.5)
    check(offline.showSinceRefuelTrip && offline.currentRefuelMeta()?.currentDistanceM == 12_345L)
    check(offline.firmwareInfo()?.version == "2.6.0" && offline.timeVerified && offline.timeAt > 0)
    TripDatabase(reboot).use { db ->
        check(estimateMileage(db.allTrips(), offline.dataDeviceId, offline.odometerCalibrationM(),
            offline.odometerAnchorTripId()).distanceM == 42_000_000L)
    }
    ExpenseDatabase(reboot).use { db ->
        val rows = db.allForVehicle(offline.dataDeviceId)
        check(rows.size == 2 && rows.single { it.category == ExpenseCategory.MAINTENANCE }.amount == 300.0)
        val local = rows.single { it.category == ExpenseCategory.DAILY }
        check(local.amount == 25.0 && db.save(local.copy(amount = 30.0)) == local.id)
        check(db.delete(local.deviceId, local.id))
    }
    val exportedAgain = decode(archive(TransferStore(reboot).snapshot()))
    check(exportedAgain.gauge == GAUGE && exportedAgain.devices.getValue(GAUGE)["vehicle_total_distance"] == 456_789L)
    offline.address = "AA:BB:CC:DD:EE:02"
    check(offline.dataDeviceId == offline.address && offline.vehicle() == null && offline.odometerCalibrationM() == null)
    offline.address = ""
    check(offline.dataDeviceId == GAUGE && offline.vehicle() != null)
    passed("fresh unbound phone restores dashboard, odometer, maintenance and local daily costs after process restart; export retains vehicle; other binding stays separate")
    // Reproduce old exports with missing manifest gauge and distinguish empty target settings from conflicts.
    val missingGauge = relocation.copy(gauge = "")
    val freshLegacy = Context(File(root, "fresh-missing-gauge"))
    TransferStore(freshLegacy).import(missingGauge, false)
    check(AppState(freshLegacy).dataDeviceId == GAUGE && AppState(freshLegacy).odometerCalibrationM() == 42_000_000L)
    check(TransferStore(freshLegacy).snapshot().gauge == GAUGE)
    passed("empty target preferences accept incoming values even when keeping local conflicts; single-vehicle legacy exports recover selection")
    val localOnly = Context(File(root, "local-only"))
    ExpenseDatabase(localOnly).use { db ->
        db.save(ExpenseRecord(deviceId = "local", dateEpochDay = 20501,
            category = ExpenseCategory.MAINTENANCE, amount = 100.0, title = "机油"))
        db.save(ExpenseRecord(deviceId = "AA:BB:CC:DD:EE:02", dateEpochDay = 20501,
            category = ExpenseCategory.DAILY, amount = 999.0, title = "停车"))
        check(db.allForVehicle(GAUGE).single().amount == 100.0)
    }
    check(decode(archive(TransferStore(localOnly).snapshot())).tables.getValue("expenses").size == 2)
    rejects { TransferSchema.validate(relocation.copy(tables = relocation.tables +
        ("trips" to relocation.tables.getValue("trips").map { it + ("device_id" to "local") }))) }
    passed("local maintenance exports successfully and remains visible without including another vehicle's daily expenses; trips cannot use local identity")
    args.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { path ->
        val fromHarmony = File(path).inputStream().use(TransferArchive::read)
        val migrated = Context(File(root, "from-harmony-store"))
        TransferStore(migrated).import(fromHarmony, false)
        val coldAndroid = Context(migrated.root)
        val state = AppState(coldAndroid)
        check(state.address.isEmpty() && state.dataDeviceId == GAUGE)
        check(state.vehicle()?.totalDistanceM == 456_789L && state.odometerCalibrationM() == 42_000_000L)
        check(state.vehicle()?.maxDecelX100 == -92 && state.lastGaugeFuelPercent() == 62.5)
        check(!state.prefs.getBoolean("refined_home_ui", true) && !state.prefs.getBoolean("grouped_settings_ui", true))
        ExpenseDatabase(coldAndroid).use { db ->
            check(db.allForVehicle(state.dataDeviceId).size == 2)
            check(db.allForVehicle(state.dataDeviceId).single { it.category == ExpenseCategory.DAILY }.amount == 25.0)
        }
        CustomTripDatabase(coldAndroid).use { check(it.all(GAUGE).size == 55) }
        check(TransferStore(coldAndroid).snapshot().gauge == GAUGE)
        passed("Android production store -> Harmony production repository -> new Android phone preserves visible home, maintenance, local daily costs, odometer, peaks and custom history")
    }
    // Restore the original source fixture for the remaining deletion/merge scenarios.
    ExpenseDatabase(a).use { db -> db.all("local").forEach { check(db.delete(it.deviceId, it.id)) } }
    aPrefs.edit().remove("odometer_calibration_m_$GAUGE").remove("odometer_anchor_trip_$GAUGE")
        .remove("show_since_refuel_$GAUGE").commit()
    val harmonyUpdated = portable.copy(devices = portable.devices + (GAUGE to portable.devices.getValue(GAUGE).toMutableMap().apply {
        this["vehicle_data"] = null; this["vehicle_total_distance"] = 987_654L; this["vehicle_at"] = 1_800_000_009_000L
    }))
    val fromHarmony = Context(File(root, "from-harmony")); TransferStore(fromHarmony).import(harmonyUpdated, true)
    val restoredVehicle = fromHarmony.getSharedPreferences("vehicle_companion", 0).all["vehicle_$GAUGE"] as String
    check(VehicleState.parse(Base64.getDecoder().decode(restoredVehicle))?.totalDistanceM == 987_654L)
    passed("canonical Harmony dashboard state reconstructs an Android vehicle snapshot")
    check(portable.tables.getValue("expenses").single()["maintenance_type"] == 1L)
    val v1Data = JSONObject().put("tables", JSONObject().also { tables ->
        initial.tables.forEach { (name, rows) ->
            tables.put(name, org.json.JSONArray().also { array -> rows.forEach { row ->
                array.put(TransferArchive.jsonObject(if (name == "expenses") row - "maintenance_type" else row))
            } })
        }
    }).put("preferences", JSONObject().also { prefs ->
        initial.preferences.forEach { (key, value) -> prefs.put(key, TransferArchive.jsonObject(value)) }
    }).toString().toByteArray()
    val v1Bytes = rewrite(bytes) { name, content ->
        if (name == "data.json") v1Data else JSONObject(content.toString(Charsets.UTF_8))
            .put("version", 1).put("bytes", v1Data.size).put("sha256", TransferArchive.digest(v1Data))
            .toString().toByteArray()
    }
    val legacyPortable = decode(v1Bytes)
    val legacyExpense = legacyPortable.tables.getValue("expenses").single()
    check(legacyExpense["maintenance_type"] == null && legacyExpense["title"] == "机油")
    check(legacyExpense["note"] == "中文备注" && legacyExpense["amount"] == 300.0)
    rejects { decode(rewrite(v1Bytes) { name, content -> if (name == "manifest.json")
        JSONObject(content.toString(Charsets.UTF_8)).put("version", 2).toString().toByteArray() else content }) }
    passed("v2 headings roundtrip, v1 archives upgrade without losing details, malformed v2 rejected")
    rejects { decode(bytes.copyOf(bytes.size / 2)) }
    rejects { decode(rewrite(bytes) { name, content -> if (name == "data.json") content + ' '.code.toByte() else content }) }
    rejects { decode(rewrite(bytes) { name, content -> if (name == "manifest.json")
        JSONObject(content.toString(Charsets.UTF_8)).put("version", 999).toString().toByteArray() else content }) }
    val traversal = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use {
        it.putNextEntry(ZipEntry("../data.json")); it.write("{}".toByteArray()); it.closeEntry()
    } }.toByteArray()
    rejects { decode(traversal) }
    passed("truncated, tampered, future-version and unexpected-path archives rejected")
    val bomb = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use {
        it.putNextEntry(ZipEntry("data.json")); val chunk = ByteArray(8192); repeat(4097) { _ -> it.write(chunk) }; it.closeEntry()
    } }.toByteArray()
    rejects { decode(bomb) }
    passed("decompression limit prevents oversized package")
    val duplicate = initial.copy(tables = initial.tables + ("trips" to listOf(initial.tables.getValue("trips")[0], initial.tables.getValue("trips")[0])))
    rejects { TransferSchema.validate(duplicate) }
    rejects { TransferSchema.validate(initial.copy(preferences = initial.preferences + ("automatic" to mapOf("type" to "boolean", "value" to true)))) }
    passed("duplicate identities and forbidden settings rejected before import")

    storeB.snapshot()
    TripDatabase(b).use { it.upsert(trip(100)) }
    FuelDatabase(b).use { it.save(FuelRecord(dateEpochDay = 20501, odometerKm = 300.0, litres = 20.0, cost = 150.0)) }
    val bPrefs = b.getSharedPreferences("vehicle_companion", 0)
    bPrefs.edit().putBoolean("automatic", false).putInt("accepted_user_notice_version", 2).commit()
    val imported = storeB.import(portable, true)
    check(imported.backup.exists())
    val before = imported.backup.inputStream().use(TransferArchive::read)
    check(before.tables.getValue("trips").size == 1 && before.tables.getValue("fuel").size == 1)
    check(storeB.snapshot().tables.getValue("fuel").size == 2)
    check(bPrefs.all["tank"] == "45" && bPrefs.all["automatic"] == false && bPrefs.all["accepted_user_notice_version"] == 2)
    check(bPrefs.all["vehicle_at_$GAUGE"] == 1_800_000_001_000L)
    check(VehicleState.parse(Base64.getDecoder().decode(bPrefs.all["vehicle_$GAUGE"] as String))?.totalDistanceM == 456_789L)
    check(bPrefs.all["firmware_version_$GAUGE"] == "2.6.0" && bPrefs.all["time_verified_$GAUGE"] == true)
    check(b.getSharedPreferences("vehicle_appearance", 0).all["ZD8_finish"] == "satin")
    check("address" !in bPrefs.all)
    TripDatabase(b).use {
        check(it.needsTransferReconcile(GAUGE)); check(it.latestKnownId(GAUGE) == 100L)
        check(it.needsRefuelTransferReconcile(GAUGE))
        it.completeRefuelTransferReconcile(GAUGE); check(!it.needsRefuelTransferReconcile(GAUGE))
    }
    passed("nonempty-device merge preserves independent local rows and creates pre-import backup/reconcile marker")
    val firstResult = normalized(storeB.snapshot())
    val repeated = storeB.import(portable, true)
    check(normalized(storeB.snapshot()) == firstResult && repeated.result.added == 0 && repeated.result.removed == 0 && repeated.result.updated == 0)
    passed("repeated package import is idempotent despite colliding local integer IDs")
    TransferStore(c).import(decode(archive(storeB.snapshot())), true)
    check(normalized(TransferStore(c).snapshot()) == normalized(storeB.snapshot()))
    check(TransferStore(c).snapshot().devices == storeB.snapshot().devices)
    passed("A to B to C preserves stable record identities")

    ExpenseDatabase(a).use { it.save(it.all(GAUGE).single().copy(amount = 450.0)) }
    val changed = storeA.snapshot()
    check(storeB.preview(changed, false).conflicts > 0)
    storeB.import(changed, false)
    ExpenseDatabase(b).use { check(it.all(GAUGE).single().amount == 300.0) }
    storeB.import(changed, true)
    ExpenseDatabase(b).use { check(it.all(GAUGE).single().amount == 450.0) }
    passed("conflict policy retains local or applies incoming edited row without duplication")
    FuelDatabase(a).use { it.delete(fuelA) }
    ExpenseDatabase(a).use { it.delete(GAUGE, expenseA) }
    storeB.import(storeA.snapshot(), true)
    FuelDatabase(b).use { check(it.allRecords().size == 1 && it.allRecords().single().odometerKm == 300.0) }
    ExpenseDatabase(b).use { check(it.all(GAUGE).isEmpty()) }
    storeB.import(portable, false)
    ExpenseDatabase(b).use { check(it.all(GAUGE).isEmpty()) }
    passed("local-record deletion propagates and keep-local policy prevents stale resurrection")

    TripDatabase(a).use { db ->
        val original = trip(1)
        check(db.splitTrip(original,
            original.copy(tripId = -3, endEpochS = original.startEpochS + 30, durationS = 30, distanceM = 400, fuelMl = 40, dataRevised = true),
            original.copy(tripId = -4, startEpochS = original.startEpochS + 30, durationS = 30, distanceM = 600, fuelMl = 60, dataRevised = true)))
    }
    storeB.import(storeA.snapshot(), true)
    TripDatabase(b).use { db ->
        check(db.allTrips().none { it.tripId == 1L }); check(db.allTrips().count { it.tripId < 0 } == 2)
        check(db.upsert(trip(1))); check(db.allTrips().none { it.tripId == 1L })
        check(db.upsert(trip(2))); check(db.allTrips().single { it.tripId == 2L }.distanceM == 2000L)
        db.completeTransferReconcile(GAUGE); check(!db.needsTransferReconcile(GAUGE))
    }
    storeB.import(portable, false)
    TripDatabase(b).use { check(it.allTrips().count { row -> row.tripId < 0 } == 2) }
    storeB.import(portable, true)
    TripDatabase(b).use { check(it.allTrips().none { row -> row.tripId < 0 }); check(it.allTrips().any { row -> row.tripId == 1L }) }
    passed("split families merge atomically; gauge replay preserves deletion/revision; explicit parent restore avoids double count")

    val beforeFailure = storeB.snapshot()
    TripDatabase(a).use { it.upsert(trip(300)) }
    FuelDatabase(a).use { it.save(FuelRecord(dateEpochDay = 20503, odometerKm = 500.0, litres = 40.0, cost = 250.0)) }
    ExpenseDatabase(a).use { it.save(ExpenseRecord(deviceId = GAUGE, dateEpochDay = 20503,
        category = ExpenseCategory.DAILY, amount = 20.0, title = "停车")) }
    SQLiteDatabase.failSqlContaining = "INSERT INTO expense.expenses"
    rejects { storeB.import(storeA.snapshot(), true) }
    SQLiteDatabase.failSqlContaining = null
    check(normalized(beforeFailure) == normalized(storeB.snapshot()))
    check(beforeFailure.preferences == storeB.snapshot().preferences)
    passed("injected mid-import write failure rolls back all five attached databases")

    val d = Context(File(root, "d")); val dPrefs = d.getSharedPreferences("vehicle_companion", 0)
    dPrefs.failCommit = true
    rejects { TransferStore(d).import(portable, true) }
    check(DataTransferSession.recoveryRequired)
    dPrefs.failCommit = false
    TransferStore.recoverPreferences(d)
    check(dPrefs.all["tank"] == "45" && !DataTransferSession.recoveryRequired)
    check(dPrefs.all["firmware_version_$GAUGE"] == "2.6.0")
    check(normalized(TransferStore(d).snapshot()) == normalized(portable))
    passed("committed preference journal recovers after preference-write failure")

    val legacy = Context(File(root, "legacy"))
    SQLiteDatabase.openDatabase(legacy.getDatabasePath("brz_fuel_records.db").path, null, 0).use { db ->
        db.execSQL("CREATE TABLE fuel_records(id INTEGER PRIMARY KEY AUTOINCREMENT,date_epoch_day INTEGER NOT NULL,odometer_km REAL NOT NULL,litres REAL NOT NULL,cost REAL NOT NULL,full_tank INTEGER NOT NULL DEFAULT 1,draft INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO fuel_records(date_epoch_day,odometer_km,litres,cost) VALUES(20500,100,20,120)")
        db.execSQL("PRAGMA user_version=3")
    }
    val legacyFirst = TransferStore(legacy).snapshot().tables.getValue("fuel")
    check(legacyFirst.size == 1 && (legacyFirst.single()["transfer_id"] as String).length == 32)
    check(TransferStore(legacy).snapshot().tables.getValue("fuel") == legacyFirst)
    passed("existing v3 fuel DB upgrades without losing rows or changing stable IDs on reopen")

    val e = Context(File(root, "e")); val storeE = TransferStore(e)
    storeE.import(portable, true)
    RefuelIntervalDatabase(e).use { it.upsert(RefuelInterval(GAUGE, 2, 1780000060, 1780000120, 60, 1200, 150, 2000, 10000, 1)) }
    e.getSharedPreferences("vehicle_companion", 0).edit().putInt("refuel_history_revision_$GAUGE", 2).commit()
    storeE.import(portable, false)
    RefuelIntervalDatabase(e).use { check(it.all(GAUGE).size == 2) }
    storeE.import(portable, true)
    RefuelIntervalDatabase(e).use { check(it.all(GAUGE).size == 1) }
    check(e.getSharedPreferences("vehicle_companion", 0).all["refuel_history_revision_$GAUGE"] == 1)
    passed("refuel history and its revision resolve as one vehicle-level generation")

    val f = Context(File(root, "f")); val storeF = TransferStore(f)
    val emptyF = storeF.snapshot()
    storeF.backupDirectory.writeText("not a directory")
    rejects { storeF.import(portable, true) }
    check(normalized(storeF.snapshot()) == normalized(emptyF))
    passed("backup creation failure leaves every dataset unchanged")

    val g = Context(File(root, "g")); val storeG = TransferStore(g)
    storeG.import(portable, true)
    TripDatabase(g).use { it.deleteTrip(GAUGE, 2) }
    storeB.import(storeG.snapshot(), true)
    TripDatabase(b).use { check(it.allTrips().none { row -> row.tripId == 2L }); it.upsert(trip(2))
        check(it.allTrips().none { row -> row.tripId == 2L }) }
    passed("ordinary trip tombstone transfers and survives subsequent gauge replay")
    val oldExpenses = Context(File(root, "old-expenses"))
    SQLiteDatabase.openDatabase(oldExpenses.getDatabasePath("brz_expenses.db").path, null, 0).use { db ->
        db.execSQL("CREATE TABLE expenses(id INTEGER PRIMARY KEY AUTOINCREMENT,device_id TEXT NOT NULL,date_epoch_day INTEGER NOT NULL,category INTEGER NOT NULL,amount REAL NOT NULL,title TEXT NOT NULL,note TEXT NOT NULL DEFAULT '',odometer_km REAL)")
        db.execSQL("INSERT INTO expenses(device_id,date_epoch_day,category,amount,title,note,odometer_km) VALUES('$GAUGE',20500,1,300,'机油机滤','原始备注',5000)")
        TransferMigrations.localIdentity(db, "expenses", "id")
        db.execSQL("PRAGMA user_version=3")
    }
    ExpenseDatabase(oldExpenses).use { db ->
        val old = db.all(GAUGE).single()
        check(old.maintenanceType == null && old.title == "机油机滤" && old.note == "原始备注")
        check(old.odometerKm == 5000.0 && old.amount == 300.0)
        check(old.resolvedMaintenanceType() == MaintenanceRecordType.A)
        for (type in MaintenanceRecordType.entries) {
            check(db.save(old.copy(maintenanceType = type)) == old.id)
            check(db.all(GAUGE).single().maintenanceType == type)
            check(db.all(GAUGE).single().title == old.title)
        }
        check(db.save(old.copy(category = ExpenseCategory.DAILY, maintenanceType = MaintenanceRecordType.B)) == old.id)
        check(db.all(GAUGE).single().maintenanceType == null)
    }
    passed("v3 expense database upgrades safely; all headings persist on edit; daily expenses remain independent")
    val maintenance = ExpenseRecord(deviceId = GAUGE, dateEpochDay = 20500,
        category = ExpenseCategory.MAINTENANCE, amount = 500.0, title = "更换发动机油、更换机油滤清器")
    check(maintenance.resolvedMaintenanceType() == MaintenanceRecordType.A)
    check(maintenance.copy(title = "更换制动液").resolvedMaintenanceType() == MaintenanceRecordType.B)
    check(maintenance.copy(title = "轮胎更换").resolvedMaintenanceType() == MaintenanceRecordType.TIRES)
    check(maintenance.copy(title = "维修异响").resolvedMaintenanceType() == MaintenanceRecordType.REPAIR)
    check(maintenance.copy(maintenanceType = MaintenanceRecordType.B).resolvedMaintenanceType() == MaintenanceRecordType.B)
    check(MaintenanceServiceKind.TIRE_REPLACEMENT.matches("更换轮胎"))
    check(!MaintenanceServiceKind.TIRE_ROTATION.matches("更换轮胎"))
    check(MaintenanceServiceKind.TIRE_REPLACEMENT !in recommendedMaintenanceKinds(40000.0))
    val due = calculateMaintenanceDueStates(listOf(maintenance.copy(maintenanceType = MaintenanceRecordType.B)), 6000.0, java.time.LocalDate.ofEpochDay(20501))
    check(due.first { it.kind == MaintenanceServiceKind.ENGINE_OIL }.dueOdometerKm == null)
    check(due.first { it.kind == MaintenanceServiceKind.ENGINE_OIL }.lastRecord != null)
    check(due.single { it.kind == MaintenanceServiceKind.TIRE_REPLACEMENT }.status == MaintenanceDueStatus.OK)
    check(MaintenanceServiceKind.TIRE_REPLACEMENT in recommendedMaintenanceKinds(50000.0))
    passed("heading inference and manual override preserve project-based reminders; tire replacement is distinct from rotation")
    val tireOld = maintenance.copy(id = 10, dateEpochDay = 20000, title = "更换轮胎", odometerKm = 10000.0)
    val tireNew = tireOld.copy(id = 11, dateEpochDay = 20500, odometerKm = 30000.0)
    val rotation = tireNew.copy(id = 12, dateEpochDay = 20510, title = "执行轮胎换位", odometerKm = 31000.0)
    fun tireState(records: List<ExpenseRecord>, currentKm: Double? = 35000.0, day: Long = 20530) =
        calculateMaintenanceDueStates(records, currentKm, java.time.LocalDate.ofEpochDay(day))
            .single { it.kind == MaintenanceServiceKind.TIRE_REPLACEMENT }
    val trackedTire = tireState(listOf(tireOld, tireNew, rotation))
    check(trackedTire.lastRecord == tireNew && trackedTire.status == MaintenanceDueStatus.OK)
    check(trackedTire.daysSinceService == 30L && trackedTire.kmSinceService == 5000.0)
    check(trackedTire.dueDate == java.time.LocalDate.ofEpochDay(20500).plusYears(8))
    check(trackedTire.dueOdometerKm == 80000.0 && trackedTire.kmRemaining == 45000.0)
    check(tireState(listOf(tireOld, rotation)).lastRecord == tireOld)
    check(tireState(listOf(tireNew.copy(odometerKm = null))).kmSinceService == null)
    check(tireState(listOf(tireNew), null).daysSinceService == 30L)
    check(tireState(listOf(tireNew), null).status == MaintenanceDueStatus.OK)
    check(tireState(listOf(tireNew), 29000.0, 20499).kmSinceService == -1000.0)
    check(tireState(listOf(tireNew), 29000.0, 20499).daysSinceService == -1L)
    check(tireState(listOf(tireNew.copy(category = ExpenseCategory.DAILY))).lastRecord == null)
    val originalTire = tireState(listOf(rotation, tireNew.copy(category = ExpenseCategory.DAILY)), 6000.0)
    check(originalTire.lastRecord == null && originalTire.dueOdometerKm == 50000.0)
    check(originalTire.kmRemaining == 44000.0 && originalTire.kmSinceService == 6000.0)
    check(originalTire.dueDate == null && originalTire.daysSinceService == null)
    check(tireState(emptyList(), 0.0).kmRemaining == 50000.0)
    check(tireState(emptyList(), 49000.0).status == MaintenanceDueStatus.DUE_SOON)
    check(tireState(emptyList(), 50000.0).status == MaintenanceDueStatus.OVERDUE)
    check(tireState(emptyList(), null).kmSinceService == null)
    check(tireState(emptyList(), null).status == MaintenanceDueStatus.UNTRACKED)
    check(tireState(listOf(tireNew.copy(odometerKm = null))).dueOdometerKm == null)
    check(tireState(listOf(tireNew), 81000.0).status == MaintenanceDueStatus.OVERDUE)
    val tireDate = java.time.LocalDate.ofEpochDay(20500).plusYears(8)
    check(tireState(listOf(tireNew), 35000.0, tireDate.minusDays(30).toEpochDay()).status == MaintenanceDueStatus.DUE_SOON)
    check(tireState(listOf(tireNew), 35000.0, tireDate.toEpochDay()).daysRemaining == 0L)
    check(tireState(listOf(tireNew), 35000.0, tireDate.plusDays(1).toEpochDay()).status == MaintenanceDueStatus.OVERDUE)
    val leapTire = tireNew.copy(dateEpochDay = java.time.LocalDate.parse("2024-02-29").toEpochDay())
    check(tireState(listOf(leapTire)).dueDate == java.time.LocalDate.parse("2032-02-29"))
    check(MaintenanceServiceKind.TIRE_ROTATION.intervalKm == 10000 && MaintenanceServiceKind.TIRE_ROTATION.intervalMonths == null)
    passed("tire starts at zero without a date; latest replacement resets 50000 km / 8 year reminders, whichever comes first")
    println("$passed transfer scenarios passed")
}
