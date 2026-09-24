package com.brz.gauge.trips

import android.content.Context
import android.util.Base64

class AppState(context: Context) {
    val prefs = context.getSharedPreferences("vehicle_companion", Context.MODE_PRIVATE)

    /**
     * Some development builds stored newly introduced numeric preferences as
     * strings. SharedPreferences throws ClassCastException instead of returning
     * the supplied default in that situation, which can otherwise crash the app
     * while the home page is being created after an in-place update.
     */
    private fun compatibleInt(key: String, defaultValue: Int): Int {
        return try {
            prefs.getInt(key, defaultValue)
        } catch (_: ClassCastException) {
            val recovered = when (val raw = prefs.all[key]) {
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull() ?: defaultValue
                else -> defaultValue
            }
            prefs.edit().putInt(key, recovered).apply()
            recovered
        }
    }
    private fun compatibleString(key: String, defaultValue: String? = null): String? {
        return try {
            prefs.getString(key, defaultValue)
        } catch (_: ClassCastException) {
            val recovered = try {
                prefs.all[key]?.toString()?.takeIf { it.isNotEmpty() }
            } catch (_: RuntimeException) {
                null
            }
            try {
                val edit = prefs.edit()
                if (recovered == null) edit.remove(key) else edit.putString(key, recovered)
                edit.apply()
            } catch (_: RuntimeException) { }
            recovered ?: defaultValue
        }
    }
    private fun compatibleLongOrNull(key: String): Long? {
        if (!prefs.contains(key)) return null
        return try {
            prefs.getLong(key, 0L)
        } catch (_: ClassCastException) {
            val recovered = when (val raw = prefs.all[key]) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            }
            if (recovered == null) prefs.edit().remove(key).apply()
            else prefs.edit().putLong(key, recovered).apply()
            recovered
        }
    }
    private fun compatibleBoolean(key: String, defaultValue: Boolean): Boolean {
        return try {
            prefs.getBoolean(key, defaultValue)
        } catch (_: ClassCastException) {
            val recovered = when (val raw = prefs.all[key]) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                is String -> raw.equals("true", true) || raw == "1"
                else -> defaultValue
            }
            prefs.edit().putBoolean(key, recovered).apply()
            recovered
        }
    }
    private fun odometerKey(prefix: String, deviceId: String): String =
        "${prefix}_${deviceId.trim().uppercase()}"
    private fun lastFuelKey(deviceId: String): String =
        "last_gauge_fuel_percent_${deviceId.trim().uppercase()}"
    private fun deviceKey(prefix: String, deviceId: String = address): String =
        "${prefix}_${deviceId.trim().uppercase()}"
    var address: String
        get() = prefs.getString("address", "") ?: ""
        set(value) { prefs.edit().putString("address", value).apply() }
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }
    var vehicleDisplayName: String
        get() = normalizeVehicleDisplayName(
            prefs.getString("vehicle_display_name", DEFAULT_VEHICLE_DISPLAY_NAME)
                ?: DEFAULT_VEHICLE_DISPLAY_NAME)
        set(value) {
            prefs.edit().putString("vehicle_display_name", normalizeVehicleDisplayName(value)).apply()
        }
    var customLicensePlate: String?
        get() = compatibleString("custom_license_plate")
            ?.let { LicensePlateGenerator.parse(it).plate?.compactText }
        set(value) {
            val normalized = value?.let { LicensePlateGenerator.parse(it).plate?.compactText }
            val edit = prefs.edit()
            if (normalized == null) edit.remove("custom_license_plate")
            else edit.putString("custom_license_plate", normalized)
            edit.apply()
        }
    var showCustomLicensePlate: Boolean
        get() = compatibleBoolean("show_custom_license_plate", false) && customLicensePlate != null
        set(value) { prefs.edit().putBoolean("show_custom_license_plate", value).apply() }
    val selectedVehicleModel: SupportedVehicleModel
        get() = SupportedVehicleModel.fromProfileIndex(
            compatibleInt("selected_vehicle_profile", SupportedVehicleModel.DEFAULT_PROFILE_INDEX)
        ) ?: SupportedVehicleModel.ZD8
    val pendingVehicleProfile: Int?
        get() = compatibleInt("pending_vehicle_profile", -1).takeIf {
            SupportedVehicleModel.fromProfileIndex(it) != null
        }
    fun requestVehicleModel(model: SupportedVehicleModel) {
        prefs.edit().putInt("selected_vehicle_profile", model.profileIndex)
            .putInt("pending_vehicle_profile", model.profileIndex).apply()
    }
    fun confirmVehicleProfile(profileIndex: Int) {
        if (SupportedVehicleModel.fromProfileIndex(profileIndex) == null) return
        val pending = pendingVehicleProfile
        // A newer phone choice may arrive while an older command is being
        // verified. Keep the latest local image/selection stable; the service
        // will send that newer pending profile on the next idle slot.
        if (pending != null && pending != profileIndex) return
        val edit = prefs.edit().putInt("selected_vehicle_profile", profileIndex)
        if (pending == profileIndex) edit.remove("pending_vehicle_profile")
        edit.apply()
    }
    fun adoptVehicleProfileFromGauge(profileIndex: Int) {
        if (pendingVehicleProfile == null) confirmVehicleProfile(profileIndex)
    }
    var tankLitres: Double
        get() = (prefs.getString("tank", "50")?.toDoubleOrNull() ?: 50.0).coerceIn(1.0, 150.0)
        set(value) { prefs.edit().putString("tank", value.toString()).apply() }
    var rangeConsumptionSource: RangeConsumptionSource
        get() = RangeConsumptionSource.fromKey(prefs.getString("range_consumption_source", null))
        set(value) { prefs.edit().putString("range_consumption_source", value.key).apply() }
    var rangeCorrectionFactor: Double
        get() = prefs.getString("range_correction_factor", "1.06")?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.5..2.0 } ?: 1.06
        set(value) { prefs.edit().putString("range_correction_factor", value.coerceIn(0.5, 2.0).toString()).apply() }
    val manualFuelPercent: Double?
        get() = prefs.getString("manual_fuel_percent", null)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
    fun setManualFuel(percent: Double, vehicle: VehicleState?) {
        prefs.edit().putString("manual_fuel_percent", percent.coerceIn(0.0, 100.0).toString())
            .putLong("manual_fuel_baseline_ml", vehicle?.totalFuelMl ?: -1L).apply()
    }
    fun clearManualFuel() {
        prefs.edit().remove("manual_fuel_percent").remove("manual_fuel_baseline_ml").apply()
    }
    /**
     * Prefer the ECU tank-level PID. If the car does not expose it, decrease a
     * user-entered fuel level by the fuel consumed since calibration.
     */
    fun effectiveFuelPercent(vehicle: VehicleState?): Double? {
        vehicle?.fuelPercent?.let { return it }
        lastGaugeFuelPercent()?.let { return it }
        val initial = manualFuelPercent ?: return null
        if (vehicle == null) return initial
        var baseline = prefs.getLong("manual_fuel_baseline_ml", -1L)
        if (baseline < 0L || vehicle.totalFuelMl < baseline) {
            baseline = vehicle.totalFuelMl
            prefs.edit().putLong("manual_fuel_baseline_ml", baseline).apply()
        }
        val initialLitres = tankLitres * initial / 100.0
        val remaining = (initialLitres - (vehicle.totalFuelMl - baseline) / 1000.0).coerceAtLeast(0.0)
        return (remaining / tankLitres * 100.0).coerceIn(0.0, 100.0)
    }
    fun status(message: String, connected: Boolean) {
        prefs.edit().putString("status", message).putBoolean("connected", connected)
            .putLong("status_at", System.currentTimeMillis()).apply()
    }
    val connected: Boolean get() = prefs.getBoolean("connected", false)
    fun saveVehicle(bytes: ByteArray) {
        val deviceId = address
        val previousFuel = vehicle()?.fuelPercent
        val parsed = VehicleState.parse(bytes)
        val receivedFuel = parsed?.fuelPercent
        val rememberedFuel = receivedFuel ?: previousFuel ?: lastGaugeFuelPercent(deviceId)
        val edit = prefs.edit()
            .putString("vehicle_$deviceId", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putLong("vehicle_at_$deviceId", System.currentTimeMillis())
        if (rememberedFuel != null && rememberedFuel.isFinite() && rememberedFuel in 0.0..100.0) {
            edit.putString(lastFuelKey(deviceId), rememberedFuel.toString())
        }
        if (receivedFuel != null) {
            val sequenceKey = deviceKey("last_gauge_fuel_sequence", deviceId)
            val previousSequence = compatibleLongOrNull(sequenceKey)
            val sequence = parsed.fuelSampleSequence
            if (sequence == null || sequence != previousSequence) {
                edit.putLong(deviceKey("last_gauge_fuel_at", deviceId), System.currentTimeMillis())
                if (sequence != null) edit.putLong(sequenceKey, sequence)
            }
        }
        edit.apply()
    }
    fun vehicle(): VehicleState? = try {
        prefs.getString("vehicle_$address", null)?.let { VehicleState.parse(Base64.decode(it, Base64.NO_WRAP)) }
    } catch (_: RuntimeException) { null }
    val vehicleAt: Long get() = prefs.getLong("vehicle_at_$address", 0)
    fun lastGaugeFuelPercent(deviceId: String = address): Double? =
        deviceId.takeIf { it.isNotBlank() }
            ?.let { prefs.getString(lastFuelKey(it), null)?.toDoubleOrNull() }
            ?.takeIf { it.isFinite() && it in 0.0..100.0 }
    fun lastGaugeFuelAt(deviceId: String = address): Long =
        deviceId.takeIf { it.isNotBlank() }
            ?.let { compatibleLongOrNull(deviceKey("last_gauge_fuel_at", it)) } ?: 0L

    var showSinceRefuelTrip: Boolean
        get() = compatibleBoolean(deviceKey("show_since_refuel"), false)
        set(value) { prefs.edit().putBoolean(deviceKey("show_since_refuel"), value).apply() }
    var showCustomTrip: Boolean
        get() = compatibleBoolean(deviceKey("show_custom_trip"), false)
        set(value) { prefs.edit().putBoolean(deviceKey("show_custom_trip"), value).apply() }
    val customTripName: String
        get() = normalizeCustomTripName(compatibleString(deviceKey("custom_trip_name"), ""))
    val customTripResetAtMs: Long
        get() = compatibleLongOrNull(deviceKey("custom_reset_at")) ?: 0L
    fun setCustomTripName(name: String?) {
        prefs.edit().putString(deviceKey("custom_trip_name"), normalizeCustomTripName(name)).apply()
    }
    fun resetCustomTrip(vehicle: VehicleState?, name: String? = null) {
        val edit = prefs.edit()
        if (vehicle == null) {
            edit.remove(deviceKey("custom_base_distance"))
                .remove(deviceKey("custom_base_duration"))
                .remove(deviceKey("custom_base_fuel"))
                .remove(deviceKey("custom_sync_pending"))
        } else {
            edit.putLong(deviceKey("custom_base_distance"), vehicle.totalDistanceM)
                .putLong(deviceKey("custom_base_duration"), vehicle.totalDurationS)
                .putLong(deviceKey("custom_base_fuel"), vehicle.totalFuelMl)
                .putBoolean(deviceKey("custom_sync_pending"), true)
        }
        edit.putString(deviceKey("custom_trip_name"), normalizeCustomTripName(name))
            .putLong(deviceKey("custom_reset_at"), System.currentTimeMillis()).apply()
    }
    fun customTrip(vehicle: VehicleState?): Triple<Long, Long, Long>? {
        vehicle ?: return null
        val distance = compatibleLongOrNull(deviceKey("custom_base_distance")) ?: run {
            resetCustomTrip(vehicle)
            vehicle.totalDistanceM
        }
        val duration = compatibleLongOrNull(deviceKey("custom_base_duration")) ?: vehicle.totalDurationS
        val fuel = compatibleLongOrNull(deviceKey("custom_base_fuel")) ?: vehicle.totalFuelMl
        return Triple(
            (vehicle.totalDistanceM - distance).coerceAtLeast(0L),
            (vehicle.totalDurationS - duration).coerceAtLeast(0L),
            (vehicle.totalFuelMl - fuel).coerceAtLeast(0L),
        )
    }
    fun customTripBaseline(): Triple<Long, Long, Long>? {
        val distance = compatibleLongOrNull(deviceKey("custom_base_distance")) ?: return null
        val duration = compatibleLongOrNull(deviceKey("custom_base_duration")) ?: return null
        val fuel = compatibleLongOrNull(deviceKey("custom_base_fuel")) ?: return null
        return Triple(distance.coerceAtLeast(0L), duration.coerceAtLeast(0L), fuel.coerceAtLeast(0L))
    }
    val pendingCustomTripSync: Boolean
        get() = compatibleBoolean(deviceKey("custom_sync_pending"), false)
    fun confirmCustomTripSync() {
        prefs.edit().putBoolean(deviceKey("custom_sync_pending"), false).apply()
    }
    fun saveRefuelMeta(meta: TripBleProtocol.RefuelMeta) {
        prefs.edit()
            .putLong(deviceKey("refuel_start"), meta.currentStartEpochS)
            .putLong(deviceKey("refuel_duration"), meta.currentDurationS)
            .putLong(deviceKey("refuel_distance"), meta.currentDistanceM)
            .putLong(deviceKey("refuel_fuel"), meta.currentFuelMl)
            .putInt(deviceKey("refuel_protocol"), meta.protocolVersion)
            .putLong(deviceKey("refuel_meta_at"), System.currentTimeMillis())
            .apply()
    }
    val refuelHistoryRevision: Int?
        get() {
            val key = deviceKey("refuel_history_revision")
            return if (prefs.contains(key)) compatibleInt(key, 0) else null
        }
    fun confirmRefuelHistoryRevision(revision: Int) {
        prefs.edit().putInt(deviceKey("refuel_history_revision"), revision and 0xffff).apply()
    }
    fun currentRefuelMeta(): TripBleProtocol.RefuelMeta? {
        val at = compatibleLongOrNull(deviceKey("refuel_meta_at")) ?: return null
        if (at <= 0L) return null
        return TripBleProtocol.RefuelMeta(
            count = 0, oldestId = 0, newestId = 0,
            currentStartEpochS = compatibleLongOrNull(deviceKey("refuel_start")) ?: 0L,
            currentDurationS = compatibleLongOrNull(deviceKey("refuel_duration")) ?: 0L,
            currentDistanceM = compatibleLongOrNull(deviceKey("refuel_distance")) ?: 0L,
            currentFuelMl = compatibleLongOrNull(deviceKey("refuel_fuel")) ?: 0L,
            historyRevision = refuelHistoryRevision ?: 0,
            protocolVersion = compatibleInt(deviceKey("refuel_protocol"), 1),
        )
    }
    val pendingRefuelReset: Boolean
        get() = compatibleBoolean(deviceKey("pending_refuel_reset"), false)
    fun requestRefuelReset() {
        if (address.isNotBlank()) prefs.edit().putBoolean(deviceKey("pending_refuel_reset"), true).apply()
    }
    fun confirmRefuelReset() {
        prefs.edit().remove(deviceKey("pending_refuel_reset")).apply()
    }
    val pendingRefuelDeleteId: Long?
        get() = compatibleLongOrNull(deviceKey("pending_refuel_delete_id"))?.takeIf { it > 0L }
    fun requestRefuelDelete(id: Long) {
        if (address.isNotBlank() && id > 0L) {
            prefs.edit().putLong(deviceKey("pending_refuel_delete_id"), id).apply()
        }
    }
    fun confirmRefuelDelete() {
        prefs.edit().remove(deviceKey("pending_refuel_delete_id")).apply()
    }
    val pendingRefuelDiscardOldestId: Long?
        get() = compatibleLongOrNull(deviceKey("pending_refuel_discard_oldest_id"))?.takeIf { it > 0L }
    fun requestRefuelDiscardOldest(id: Long) {
        if (address.isNotBlank() && id > 0L) {
            prefs.edit().putLong(deviceKey("pending_refuel_discard_oldest_id"), id).apply()
        }
    }
    fun confirmRefuelDiscardOldest() {
        prefs.edit().remove(deviceKey("pending_refuel_discard_oldest_id")).apply()
    }
    fun saveGaugeSettings(bytes: ByteArray) {
        prefs.edit().putString("gauge_settings_$address", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putLong("gauge_settings_at_$address", System.currentTimeMillis()).apply()
    }
    fun gaugeSettings(): TripBleProtocol.GaugeSettings? = try {
        prefs.getString("gauge_settings_$address", null)?.let {
            TripBleProtocol.parseGaugeSettings(Base64.decode(it, Base64.NO_WRAP))
        }
    } catch (_: RuntimeException) { null }
    val gaugeSettingsAt: Long get() = prefs.getLong("gauge_settings_at_$address", 0)
    fun saveFirmwareInfo(info: TripBleProtocol.FirmwareInfo) {
        prefs.edit().putString("firmware_version_$address", info.version)
            .putString("firmware_build_$address", info.buildTag)
            .putString("firmware_project_$address", info.project)
            .putString("firmware_board_$address", info.board)
            .putString("firmware_variant_$address", info.variant)
            .putString("firmware_lcd_$address", info.lcd)
            .putInt("firmware_screen_w_$address", info.screenWidth)
            .putInt("firmware_screen_h_$address", info.screenHeight)
            .putInt("firmware_bpp_$address", info.colorBits)
            .putInt("firmware_flash_mb_$address", info.flashMb)
            .putInt("firmware_ota_slots_$address", info.otaSlots)
            .putLong("firmware_at_$address", System.currentTimeMillis()).apply()
    }
    fun firmwareInfo(): TripBleProtocol.FirmwareInfo? {
        val version = firmwareVersion ?: return null
        return TripBleProtocol.FirmwareInfo(
            version, firmwareBuildTag ?: "local",
            compatibleString("firmware_project_$address", "") ?: "",
            compatibleString("firmware_board_$address", "") ?: "",
            compatibleString("firmware_variant_$address", "") ?: "",
            compatibleString("firmware_lcd_$address", "") ?: "",
            compatibleInt("firmware_screen_w_$address", -1),
            compatibleInt("firmware_screen_h_$address", -1),
            compatibleInt("firmware_bpp_$address", -1),
            compatibleInt("firmware_flash_mb_$address", -1),
            compatibleInt("firmware_ota_slots_$address", -1),
        )
    }
    val firmwareVersion: String? get() = compatibleString("firmware_version_$address")
    val firmwareBuildTag: String? get() = compatibleString("firmware_build_$address")
    val firmwareAt: Long get() = prefs.getLong("firmware_at_$address", 0)
    fun firmwareUpdate(stage: String, message: String, progress: Int = 0, target: String? = null) {
        val edit = prefs.edit().putString("ota_stage", stage).putString("ota_message", message)
            .putInt("ota_progress", progress.coerceIn(0, 100)).putLong("ota_at", System.currentTimeMillis())
        if (target != null) edit.putString("ota_target", target)
        edit.apply()
    }
    val firmwareUpdateStage: String get() = compatibleString("ota_stage", "idle") ?: "idle"
    val firmwareUpdateMessage: String get() = compatibleString("ota_message", "尚未检查更新") ?: "尚未检查更新"
    val firmwareUpdateProgress: Int get() = compatibleInt("ota_progress", 0).coerceIn(0, 100)
    val firmwareUpdateTarget: String? get() = compatibleString("ota_target")
    val firmwareUpdateActive: Boolean get() = firmwareUpdateStage in setOf(
        "checking", "preparing", "wifi", "uploading", "installing", "verifying")
    fun odometerCalibrationM(deviceId: String = address): Long? =
        deviceId.takeIf { it.isNotBlank() }
            ?.let { compatibleLongOrNull(odometerKey("odometer_calibration_m", it)) }
            ?.coerceAtLeast(0L)
    fun odometerAnchorTripId(deviceId: String = address): Long =
        deviceId.takeIf { it.isNotBlank() }
            ?.let { compatibleLongOrNull(odometerKey("odometer_anchor_trip", it)) }
            ?.coerceAtLeast(0L) ?: 0L
    fun calibrateOdometer(deviceId: String, distanceM: Long, anchorTripId: Long) {
        if (deviceId.isBlank()) return
        prefs.edit()
            .putLong(odometerKey("odometer_calibration_m", deviceId), distanceM.coerceAtLeast(0L))
            .putLong(odometerKey("odometer_anchor_trip", deviceId), anchorTripId.coerceAtLeast(0L))
            .apply()
    }
    val odometerDisplayEnabled: Boolean
        get() = address.takeIf { it.isNotBlank() }?.let {
            compatibleBoolean(odometerKey("odometer_display", it), true)
        } ?: true
    val pendingOdometerDisplay: Boolean?
        get() {
            val deviceId = address.takeIf { it.isNotBlank() } ?: return null
            val key = odometerKey("pending_odometer_display", deviceId)
            return if (prefs.contains(key)) compatibleBoolean(key, true) else null
        }
    val pendingGaugeOdometerCalibrationM: Long?
        get() = address.takeIf { it.isNotBlank() }?.let {
            compatibleLongOrNull(odometerKey("pending_gauge_odometer_m", it))
        }?.coerceIn(0L, 999_999_900L)
    fun requestOdometerDisplay(enabled: Boolean) {
        val deviceId = address.takeIf { it.isNotBlank() } ?: return
        prefs.edit()
            .putBoolean(odometerKey("odometer_display", deviceId), enabled)
            .putBoolean(odometerKey("pending_odometer_display", deviceId), enabled)
            .apply()
    }
    fun confirmOdometerDisplay(enabled: Boolean) {
        val deviceId = address.takeIf { it.isNotBlank() } ?: return
        val pending = pendingOdometerDisplay
        if (pending != null && pending != enabled) return
        val edit = prefs.edit().putBoolean(odometerKey("odometer_display", deviceId), enabled)
        if (pending == enabled) edit.remove(odometerKey("pending_odometer_display", deviceId))
        edit.apply()
    }
    fun adoptOdometerDisplayFromGauge(enabled: Boolean) {
        if (pendingOdometerDisplay == null) confirmOdometerDisplay(enabled)
    }
    fun requestGaugeOdometerCalibration(distanceM: Long) {
        val deviceId = address.takeIf { it.isNotBlank() } ?: return
        prefs.edit().putLong(
            odometerKey("pending_gauge_odometer_m", deviceId),
            distanceM.coerceIn(0L, 999_999_900L),
        ).apply()
    }
    fun confirmGaugeOdometerCalibration(distanceM: Long) {
        val deviceId = address.takeIf { it.isNotBlank() } ?: return
        val pending = pendingGaugeOdometerCalibrationM ?: return
        if (kotlin.math.abs(pending - distanceM) <= 100L) {
            prefs.edit().remove(odometerKey("pending_gauge_odometer_m", deviceId)).apply()
        }
    }
    fun recordTimeSync(verified: Boolean) {
        prefs.edit().putLong("time_at_$address", System.currentTimeMillis())
            .putBoolean("time_verified_$address", verified).apply()
    }
    val timeAt: Long get() = prefs.getLong("time_at_$address", 0)
    val timeVerified: Boolean get() = prefs.getBoolean("time_verified_$address", false)
}
