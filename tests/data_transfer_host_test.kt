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
                array.put(TransferArchive.jsonObject(if (name == "expenses") row - ledgerDefaults.keys - "maintenance_type" else row))
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
    val oldRefuelMeta = RefuelIntervalDatabase(e).use { db ->
        val rows = db.all(GAUGE).sortedBy { it.id }
        val metadata = TripBleProtocol.RefuelMeta(rows.size, rows.first().id, rows.last().id, 0, 0, 0, 0, 2, 2)
        db.reconcileSnapshot(GAUGE, metadata, rows)
        check(db.matchesSnapshot(GAUGE, metadata))
        metadata
    }
    storeE.import(portable, true)
    RefuelIntervalDatabase(e).use {
        check(it.all(GAUGE).size == 1 && !it.matchesSnapshot(GAUGE, oldRefuelMeta))
        it.readableDatabase.rawQuery("SELECT COUNT(*) FROM refuel_gauge_records", null).use { c ->
            check(c.moveToFirst() && c.getInt(0) == 0)
        }
    }
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
    val ledgerContext = Context(File(root, "refined-ledger"))
    val ledgerDb = ExpenseDatabase(ledgerContext)
    val insurance = ExpenseRecord(deviceId = GAUGE, dateEpochDay = 21000, category = ExpenseCategory.DAILY,
        amount = 2500.0, title = "保险", ledgerKind = "insurance", dueEpochDay = 21365,
        startEpochDay = 21000, party = "保险公司", itemName = "商业险", note = "原备注")
    val insuranceId = ledgerDb.save(insurance)
    val storedInsurance = ledgerDb.all(GAUGE).single()
    check(storedInsurance.id == insuranceId && storedInsurance.copy(id=0) == insurance)
    ledgerDb.save(storedInsurance.copy(amount=2600.0,note="精简版编辑备注"))
    val editedInsurance = ledgerDb.all(GAUGE).single()
    check(editedInsurance.dueEpochDay == 21365L && editedInsurance.startEpochDay == 21000L)
    check(editedInsurance.itemName == "商业险" && editedInsurance.party == "保险公司")
    ledgerDb.save(insurance.copy(title="保险赔付", ledgerKind="income", income=true, amount=900.0))
    val ledgerStore = TransferStore(ledgerContext)
    val ledgerSnapshot = ledgerStore.snapshot()
    val ledgerRestored = Context(File(root,"refined-restored"))
    val restoredStore = TransferStore(ledgerRestored)
    restoredStore.import(decode(archive(ledgerSnapshot)), true)
    restoredStore.import(decode(archive(ledgerSnapshot)), true)
    check(normalized(restoredStore.snapshot()) == normalized(ledgerSnapshot))
    ExpenseDatabase(ledgerRestored).use { db ->
        val rows = db.all(GAUGE)
        check(rows.size == 2 && rows.filter { it.income }.sumOf { it.amount } == 900.0)
        check(rows.filter { !it.income }.sumOf { it.amount } == 2600.0)
    }
    check(insurance.section == "insurance" && insurance.isFixed)
    check(insurance.copy(title="维修",ledgerKind="").isMaintenance)
    check(insurance.copy(title="车辆配件",ledgerKind="").section == "asset")
    check(insurance.copy(title="证件手续",ledgerKind="").section == "paperwork")
    passed("refined ledger fields survive basic edits, archive/import and repeated merge; income remains separate")

    // v3 archives contain neither refined fields nor separate income metadata.
    val v3Data = JSONObject().put("tables", JSONObject().also { tables ->
        ledgerSnapshot.tables.forEach { (name,rows) -> tables.put(name,org.json.JSONArray().also { array ->
            rows.forEach { row -> array.put(TransferArchive.jsonObject(if(name=="expenses") row-ledgerDefaults.keys else row)) }
        }) }
    }).put("preferences",JSONObject()).put("devices",JSONObject()).toString().toByteArray()
    val v3Archive = rewrite(archive(ledgerSnapshot)) { name,content -> if(name=="data.json") v3Data else
        JSONObject(content.toString(Charsets.UTF_8)).put("version",3).put("bytes",v3Data.size)
            .put("sha256",TransferArchive.digest(v3Data)).toString().toByteArray() }
    check(decode(v3Archive).tables.getValue("expenses").all { it["income"] == 0L && it["due_epoch_day"] == null })
    passed("v3 archives upgrade to complete v4 defaults without changing original amounts or identities")

    val deadline = java.time.LocalDate.ofEpochDay(21365)
    for(offset in listOf(30L,7L,3L,0L)) {
        val day=deadline.minusDays(offset)
        val reminders=ledgerReminders(listOf(storedInsurance),day)
        check(reminders.single().offset(day) in reminderOffsets)
        check(reminders.single().deliveryKey(day).endsWith("|$offset"))
    }
    for(offset in listOf(31L,29L,10L,6L,2L,-1L)) {
        val day=deadline.minusDays(offset)
        check(ledgerReminders(listOf(storedInsurance),day).none { it.offset(day) in reminderOffsets })
    }
    val renewed=storedInsurance.copy(id=insuranceId+10,dateEpochDay=21355,startEpochDay=21366,dueEpochDay=21730)
    val day7=deadline.minusDays(7)
    check(ledgerReminders(listOf(storedInsurance,renewed),day7).none { it.offset(day7) in reminderOffsets })
    val separate=storedInsurance.copy(id=insuranceId+11,itemName="交强险")
    check(ledgerReminders(listOf(storedInsurance,renewed,separate),day7).single { it.offset(day7) in reminderOffsets }.title.contains("交强险"))
    val service = ExpenseRecord(id=1,deviceId=GAUGE,dateEpochDay=21000,category=ExpenseCategory.MAINTENANCE,
        amount=300.0,title="更换发动机油",maintenanceType=MaintenanceRecordType.A)
    val serviceDue=java.time.LocalDate.ofEpochDay(21000).plusMonths(6)
    val serviceDay=serviceDue.minusDays(7)
    check(ledgerReminders(listOf(service),serviceDay).single().offset(serviceDay)==7L)
    val repair=service.copy(id=2,dateEpochDay=serviceDay.minusDays(1).toEpochDay(),title="维修",ledgerKind="repair",itemName="机油渗漏处理",maintenanceType=MaintenanceRecordType.REPAIR)
    check(ledgerReminders(listOf(service,repair),serviceDay).single().offset(serviceDay)==7L)
    val serviced=service.copy(id=3,dateEpochDay=serviceDay.minusDays(1).toEpochDay())
    check(ledgerReminders(listOf(service,serviced),serviceDay).none { it.offset(serviceDay) in reminderOffsets })
    ledgerDb.close()
    passed("30/7/3/0 notifications use latest cycles, early renewal cancels old reminders, separate policies and non-service repairs stay independent")
    deadlineTrackingChecks()
    vehicleRegistrationChecks(File(args[0]))

    val assetContext=Context(File(root,"asset-lifecycle"))
    val assetDb=ExpenseDatabase(assetContext)
    val rootId=assetDb.save(ExpenseRecord(deviceId=GAUGE,dateEpochDay=22000,category=ExpenseCategory.DAILY,amount=500.0,
        title="配件用品",ledgerKind="asset",itemName="备用轮毂"))
    val heldAsset=assetDb.all(GAUGE).single()
    check(heldAsset.isAsset && heldAsset.transferId.length==32)
    val rootSnapshot=TransferStore(assetContext).snapshot()
    val sale=ExpenseRecord(deviceId=GAUGE,dateEpochDay=22001,category=ExpenseCategory.DAILY,amount=350.0,
        title="配件出售",ledgerKind="asset_sale",income=true,itemName="备用轮毂",assetRef=heldAsset.transferId)
    rejects { assetDb.save(sale.copy(dateEpochDay=21999)) }
    val saleId=assetDb.save(sale)
    rejects { assetDb.save(sale) }
    check(!assetDb.delete(GAUGE,rootId))
    rejects { assetDb.save(heldAsset.copy(dateEpochDay=22002)) }
    rejects { assetDb.save(heldAsset.copy(amount=520.0,note="历史不可覆盖")) }
    val originalSale=assetDb.all(GAUGE).single { it.isAssetExit }
    rejects { assetDb.save(originalSale.copy(amount=360.0)) }
    val soldRows=assetDb.all(GAUGE)
    check(soldRows.count { it.isAssetExit }==1 && soldRows.filter { it.income }.sumOf { it.amount }==350.0)
    check(soldRows.filter { it.isAsset }.all { a -> soldRows.any { it.isAssetExit && it.assetRef==a.transferId } })
    val soldSnapshot=TransferStore(assetContext).snapshot()
    val assetTarget=Context(File(root,"asset-lifecycle-target")); val targetStore=TransferStore(assetTarget)
    targetStore.import(decode(archive(soldSnapshot)),true)
    targetStore.import(decode(archive(soldSnapshot)),true)
    check(normalized(targetStore.snapshot())==normalized(soldSnapshot))
    ExpenseDatabase(assetTarget).use { db ->
        val rows=db.all(GAUGE); val a=rows.single { it.isAsset }
        check(rows.single { it.isAssetExit }.assetRef==a.transferId)
    }
    fun cancelAsset(event: ExpenseRecord) = assetDb.save(ExpenseRecord(deviceId=event.deviceId,dateEpochDay=22003,
        category=ExpenseCategory.DAILY,amount=0.0,title="资产记录撤销",ledgerKind="asset_cancel",assetRef=event.transferId,itemName=event.itemName))
    // Legacy cancellation logs remain readable until explicitly deleted.
    cancelAsset(originalSale)
    check(assetDb.all(GAUGE).size==3 && effectiveAssetLedger(assetDb.all(GAUGE)).none { it.income })
    rejects { cancelAsset(originalSale) }
    val retirement=assetDb.save(sale.copy(ledgerKind="asset_retire",title="资产移除",income=false,amount=0.0))
    val retired=assetDb.all(GAUGE).single { it.id==retirement }
    rejects { cancelAsset(heldAsset) }
    cancelAsset(retired)
    cancelAsset(heldAsset)
    val permanent=assetDb.all(GAUGE)
    check(permanent.size==6 && permanent.count { it.isAssetCancellation }==3)
    check(permanent.any { it.id==rootId } && permanent.any { it.id==saleId } && permanent.any { it.id==retirement })
    check(effectiveAssetLedger(permanent).isEmpty())
    check(summarizeExpenseMonth(java.time.YearMonth.from(java.time.LocalDate.ofEpochDay(22000)),0.0,permanent,true).total==0.0)
    check(!assetDb.delete(GAUGE,rootId))
    val auditSnapshot=TransferStore(assetContext).snapshot()
    val auditTarget=Context(File(root,"asset-audit-target")); val auditStore=TransferStore(auditTarget)
    auditStore.import(decode(archive(auditSnapshot)),true)
    auditStore.import(decode(archive(auditSnapshot)),true)
    check(normalized(auditStore.snapshot())==normalized(auditSnapshot))
    ExpenseDatabase(auditTarget).use { check(it.all(GAUGE).size==6 && effectiveAssetLedger(it.all(GAUGE)).isEmpty()) }
    check(assetDb.delete(GAUGE,retirement))
    check(assetDb.all(GAUGE).none { it.id==retirement || (it.isAssetCancellation && it.assetRef==retired.transferId) })
    check(assetDb.delete(GAUGE,saleId))
    check(assetDb.all(GAUGE).none { it.id==saleId || (it.isAssetCancellation && it.assetRef==originalSale.transferId) })
    check(assetDb.delete(GAUGE,rootId) && assetDb.all(GAUGE).isEmpty())
    auditStore.import(decode(archive(TransferStore(assetContext).snapshot())),true)
    check(auditStore.snapshot().tables.getValue("expenses").isEmpty())
    auditStore.import(decode(archive(auditSnapshot)),false)
    check(auditStore.snapshot().tables.getValue("expenses").isEmpty())
    ExpenseDatabase(assetTarget).use { db ->
        val before=db.all(GAUGE); val exit=before.single { it.isAssetExit }
        check(db.delete(GAUGE,exit.id))
        val after=db.all(GAUGE)
        check(after.size==1 && after.single().isAsset && after.none { it.income })
        check(after.single().transferId==heldAsset.transferId)
    }

    val v4Data=JSONObject().put("tables",JSONObject().also { tables ->
        rootSnapshot.tables.forEach { (name,rows) -> tables.put(name,org.json.JSONArray().also { array ->
            rows.forEach { row -> array.put(TransferArchive.jsonObject(if(name=="expenses") row-"asset_ref" else row)) }
        }) }
    }).put("preferences",JSONObject()).put("devices",JSONObject()).toString().toByteArray()
    val v4Archive=rewrite(archive(rootSnapshot)) { name,content -> if(name=="data.json") v4Data else
        JSONObject(content.toString(Charsets.UTF_8)).put("version",4).put("bytes",v4Data.size)
            .put("sha256",TransferArchive.digest(v4Data)).toString().toByteArray() }
    val upgraded=decode(v4Archive).tables.getValue("expenses").single()
    check(upgraded["asset_ref"]=="" && upgraded["transfer_id"]==heldAsset.transferId && upgraded["amount"]==500.0)
    val duplicates=soldSnapshot.tables.toMutableMap()
    duplicates["expenses"]=soldSnapshot.tables.getValue("expenses") + soldSnapshot.tables.getValue("expenses").single { it["ledger_kind"]=="asset_sale" }.toMutableMap().also { it["transfer_id"]="f".repeat(32) }
    rejects { TransferSchema.validate(soldSnapshot.copy(tables=duplicates)) }
    assetDb.close()
    passed("asset registration/sale/removal/undo preserve stable links and one income; repeated exits and duplicate import conflicts rejected; v4 archives upgrade")

    val reportMonth=java.time.YearMonth.of(2030,1)
    val reportDay=reportMonth.atDay(1).toEpochDay()
    val reportAsset=ExpenseRecord(deviceId=GAUGE,dateEpochDay=reportDay,category=ExpenseCategory.DAILY,amount=500.0,title="配件用品",ledgerKind="asset")
    val reportRows=listOf(reportAsset,
        reportAsset.copy(amount=200.0,title="保险",ledgerKind="insurance"),
        reportAsset.copy(amount=20.0,title="停车",ledgerKind="daily"),
        reportAsset.copy(amount=300.0,title="配件出售",ledgerKind="asset_sale",income=true),
        reportAsset.copy(amount=0.0,title="资产移除",ledgerKind="asset_retire"),
        reportAsset.copy(dateEpochDay=reportMonth.minusYears(1).atDay(1).toEpochDay(),amount=9000.0))
    val refinedReport=summarizeExpenseMonth(reportMonth,100.0,reportRows,true)
    check(refinedReport.maintenance==200.0 && refinedReport.assets==500.0 && refinedReport.daily==20.0)
    check(refinedReport.total==820.0 && refinedReport.income==300.0 && refinedReport.netExpense==520.0)
    val basicReport=summarizeExpenseMonth(reportMonth,100.0,reportRows,false)
    check(basicReport.assets==0.0 && basicReport.daily==720.0 && basicReport.total==refinedReport.total && basicReport.netExpense==520.0)
    val incomeOnly=summarizeExpenseMonth(reportMonth,0.0,listOf(reportAsset.copy(amount=1000.0,income=true,ledgerKind="income")),true)
    check(incomeOnly.total==0.0 && incomeOnly.netExpense== -1000.0)
    passed("statistics separate fixed and asset spending, include sale income once, preserve basic totals, ignore other years and allow negative net expense")

    check((0..3).map { accountingSectionAfterSwitch(it,false) }==listOf(0,1,3,4))
    check((0..4).map { accountingSectionAfterSwitch(it,true) }==listOf(0,1,2,2,3))
    for(section in 0..3) check(accountingSectionAfterSwitch(accountingSectionAfterSwitch(section,false),true)==section)
    check(accountingSectionAfterSwitch(1,true,"insurance")==2)
    check(accountingSectionAfterSwitch(1,true,"inspection")==2)
    check(accountingSectionAfterSwitch(1,true,"paperwork")==2)
    check(accountingSectionAfterSwitch(1,true,"maintenance")==1)
    val interopContext=Context(File(root,"bookkeeping-mode-interop"))
    val interopDay=java.time.LocalDate.of(2026,6,1).toEpochDay()
    val interopDb=ExpenseDatabase(interopContext)
    val sampleInsurance=ExpenseRecord(deviceId=GAUGE,dateEpochDay=interopDay,category=ExpenseCategory.DAILY,amount=2500.0,
        title="保险",ledgerKind="insurance",itemName="交强险",party="保险公司",startEpochDay=interopDay,dueEpochDay=interopDay+364)
    val insuranceKey=interopDb.save(sampleInsurance)
    listOf(sampleInsurance.copy(title="证件手续",ledgerKind="paperwork",amount=40.0),
        sampleInsurance.copy(title="年审",ledgerKind="",amount=150.0),
        sampleInsurance.copy(title="车辆配件",ledgerKind="",amount=500.0),
        sampleInsurance.copy(title="停车",ledgerKind="daily",amount=20.0),
        sampleInsurance.copy(title="他人付款",ledgerKind="income",income=true,amount=5000.0),
        sampleInsurance.copy(title="更换发动机油",ledgerKind="",category=ExpenseCategory.MAINTENANCE,amount=300.0,odometerKm=20000.0)
    ).forEach { interopDb.save(it) }
    val interopRows=interopDb.all(GAUGE)
    check(interopRows.size==7 && interopRows.single { it.title=="年审" }.section=="inspection")
    check(DailyExpenseKind.fromTitle("年审")==DailyExpenseKind.INSPECTION)
    val interopSnapshot=TransferStore(interopContext).snapshot()
    repeat(20) { pass ->
        interopContext.getSharedPreferences("vehicle_companion",0).edit().putBoolean("refined_accounting_ui",pass%2==0).commit()
        val rows=interopDb.all(GAUGE)
        val month=java.time.YearMonth.of(2026,6)
        val basic=summarizeExpenseMonth(month,200.0,rows,false)
        val refined=summarizeExpenseMonth(month,200.0,rows,true)
        check(basic.total==refined.total && basic.income==refined.income && basic.netExpense==refined.netExpense)
        check(basic.total==3710.0 && basic.income==5000.0 && basic.netExpense== -1290.0)
        check(normalized(TransferStore(interopContext).snapshot())==normalized(interopSnapshot))
    }
    val oldInsurance=interopDb.all(GAUGE).single { it.id==insuranceKey }
    interopDb.save(oldInsurance.copy(amount=2600.0,note="精简版编辑备注"))
    val updatedInsurance=interopDb.all(GAUGE).single { it.id==insuranceKey }
    check(updatedInsurance.transferId==oldInsurance.transferId && updatedInsurance.itemName=="交强险")
    check(updatedInsurance.startEpochDay==interopDay && updatedInsurance.dueEpochDay==interopDay+364 && updatedInsurance.party=="保险公司")
    val interopTarget=Context(File(root,"bookkeeping-mode-restored"))
    val interopStore=TransferStore(interopTarget)
    val interopPackage=decode(archive(TransferStore(interopContext).snapshot()))
    interopStore.import(interopPackage,true);interopStore.import(interopPackage,true)
    ExpenseDatabase(interopTarget).use { db -> check(db.all(GAUGE).size==7 && db.all(GAUGE).single { it.title=="保险" }.amount==2600.0) }
    check(normalized(interopStore.snapshot())==normalized(TransferStore(interopContext).snapshot()))
    interopDb.close()
    passed("semantic mode routes roundtrip; 20 basic/refined toggles preserve IDs, rich fields, counts and totals; backup restores without duplication")

    refuelHistoryChecks(File(args[0]))
    refuelServiceChecks(File(args[0]))
    customTripChecks(File(args[0]))
    currentDrivingChecks(File(args[0]))
    run {
        val tables=TransferSchema.tables.associate { it.name to emptyList<TransferRow>() }
        val key="refuel_history_revision_$GAUGE"
        val a=TransferSnapshot(tables,mapOf(key to mapOf("type" to "int","value" to 1L)),GAUGE)
        val b=a.copy(preferences=mapOf(key to mapOf("type" to "int","value" to 2L)))
        for (prefer in listOf(false,true)) {
            val result=TransferMerge.merge(a,b,prefer)
            check(result.conflicts==1 && result.recordConflicts==1 && result.preferenceConflicts==0)
            check(result.snapshot.preferences.getValue(key)["value"]==if(prefer) 2L else 1L)
        }
        val reg="vehicle_registration_$GAUGE"
        val x=a.copy(preferences=mapOf(reg to mapOf("type" to "string","value" to """{"registeredDay":18000,"estimated":true}""")))
        val y=a.copy(preferences=mapOf(reg to mapOf("type" to "string","value" to """{"estimated":true,"registeredDay":18000}""")))
        check(TransferMerge.merge(x,y,false).conflicts==0)
        passed("migration counts revision once and ignores registration JSON field order")
    }
    println("$passed transfer scenarios passed")
}
