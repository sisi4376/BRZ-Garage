package com.brz.gauge.trips

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main() {
    val generatedPlate = checkNotNull(LicensePlateGenerator.parse(" 粤b·a1234 ").plate)
    check(generatedPlate.compactText == "粤BA1234" && generatedPlate.displayText == "粤B·A1234")
    check(LicensePlateGenerator.parse("粤B12345").plate?.serial == "12345")
    check(LicensePlateGenerator.parse("粤B1234").plate == null)
    check(LicensePlateGenerator.parse("鲁O12345").plate?.authority == 'O')
    check(LicensePlateGenerator.parse("粤B12I45").plate == null)
    check(LicensePlateGenerator.parse("粤BABC12").plate == null)
    check(LicensePlateGenerator.parse("港B12345").plate == null)
    check(LicensePlateGenerator.parse("粤BD12345").plate == null)
    println("PASS: GA 36-2018 small-car plate input is normalized and validated")

    val mileageTrips = listOf(
        TripRecord("AA:BB", 1, 0, 0, 60, 1200, 0, 0, 0),
        TripRecord("aa:bb", 2, 0, 0, 60, 2300, 0, 0, 0),
        TripRecord("CC:DD", 9, 0, 0, 60, 9900, 0, 0, 0),
    )
    val uncalibratedMileage = estimateMileage(mileageTrips, "AA:BB", null, 0)
    check(uncalibratedMileage.distanceM == 3500L && !uncalibratedMileage.calibrated)
    println("PASS: uncalibrated mileage sums only this gauge's saved trips")
    val calibratedMileage = estimateMileage(mileageTrips, "AA:BB", 100_000_000L, 1)
    check(calibratedMileage.distanceM == 100_002_300L && calibratedMileage.calibrated)
    println("PASS: calibrated mileage adds only trips newer than its anchor")
    check(newestTripId(mileageTrips, "AA:BB") == 2L &&
        estimateMileage(mileageTrips.drop(1), "AA:BB", 100_000_000L, 1).distanceM == 100_002_300L)
    println("PASS: deleted pre-calibration history cannot reduce calibrated mileage")
    check(homeMileageLabel(MileageEstimate(5_000_000L, true)) == "5000km")
    check(homeMileageLabel(MileageEstimate(5_000_000L, false)) == "5000km · 未校准")
    println("PASS: home mileage is compact and only marks uncalibrated values")

    check(normalizeVehicleDisplayName("") == DEFAULT_VEHICLE_DISPLAY_NAME)
    check(normalizeVehicleDisplayName("  我的\n小跑车  ") == "我的 小跑车")
    check(normalizeVehicleDisplayName("1234567890") == "123456789")
    println("PASS: vehicle display name is single-line, defaulted and length-limited")
    check(!isLowFuel(null) && !isLowFuel(Double.NaN) && !isLowFuel(25.0))
    check(isLowFuel(24.99) && isLowFuel(0.0))
    println("PASS: low-fuel threshold is strictly below 25 percent")
    val raw = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(1); put(15); putShort(64); putLong(1788537600); putInt(55)
        putShort(5000); putShort(800); putInt(12000); putInt(900); putInt(960)
        putLong(100000); putLong(7200); putLong(8000); putShort(2500); putShort(60)
        putShort(0); putShort(0)
    }.array()
    fun crc(bytes: ByteArray) {
        val sum = TripBleProtocol.crc16(bytes, 62)
        bytes[62] = sum.toByte(); bytes[63] = (sum shr 8).toByte()
    }
    crc(raw)
    val state = checkNotNull(VehicleState.parse(raw))
    check(state.fuelPercent == 50.0 && state.remainingLitres(50.0) == 25.0)
    check(state.estimatedRangeKm(50.0) == 312)
    check(state.totalDistanceM == 100000L && state.currentFuelMl == 960L && state.rpm == 2500)
    println("PASS: vehicle wire format and range calculation")
    val rawV2 = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(2); put(15); putShort(84); putLong(1788537600); putInt(55)
        putShort(5000); putShort(800); putInt(12000); putInt(900); putInt(960)
        putLong(100000); putLong(7200); putLong(8000); putShort(2500); putShort(60)
        putShort(138); putShort(6840); putShort(326); putShort(-714)
        putLong(1788536700); putInt(42); putShort(0)
    }.array()
    val vehicleV2Crc = TripBleProtocol.crc16(rawV2, 82)
    rawV2[82] = vehicleV2Crc.toByte(); rawV2[83] = (vehicleV2Crc shr 8).toByte()
    val stateV2 = checkNotNull(VehicleState.parse(rawV2))
    check(stateV2.maxSpeedKmh == 138 && stateV2.maxRpm == 6840 &&
        stateV2.maxAccelX100 == 326 && stateV2.maxDecelX100 == -714 &&
        stateV2.currentStartEpochS == 1788536700L && stateV2.fuelSampleSequence == 42L)
    println("PASS: v2 vehicle snapshot exposes current-trip detail and fresh-fuel sequence")
    check(state.copy(flags = 0).fuelPercent == null)
    check(state.copy(flags = 0).estimatedRangeKm(50.0) == null)
    check(state.copy(averageX100 = 0).estimatedRangeKm(50.0) == null)
    check(state.copy(totalDistanceM = 999).estimatedRangeKm(50.0) == null)
    check(state.copy(totalDistanceM = 1000).estimatedRangeKm(50.0) == 312)
    check(state.copy(flags = state.flags and 1.inv()).estimatedRangeKm(50.0, 40.0) == 250)
    check(state.copy(fuelPercentX100 = 10001).remainingLitres(50.0) == null)
    check(state.remainingLitres(Double.NaN) == null)
    check(state.remainingLitres(0.0) == null)
    check(state.copy(fuelPercentX100 = 0).estimatedRangeKm(50.0) == 0)
    println("PASS: ECU/manual fuel, valid empty tank, invalid capacity and short sample")
    check(VehicleState.parse(raw.copyOf(63)) == null)
    val corrupt = raw.clone(); corrupt[16] = 0; check(VehicleState.parse(corrupt) == null)
    val version = raw.clone(); version[0] = 2; crc(version); check(VehicleState.parse(version) == null)
    println("PASS: snapshot length, version and CRC rejection")
    val record = TripRecord("A", 4, 1788159428, 1788160215, 3982, 36750, 2888, 786, 1)
    check(record.hasValidTime && record.timeInconsistent)
    check(!record.copy(startEpochS = 0, endEpochS = 0, flags = 0).hasValidTime)
    check(!record.copy(endEpochS = 1788159000).hasValidTime)
    check(!record.copy(durationS = 787).timeInconsistent)
    println("PASS: real trip #4 timestamp anomaly and missing timestamps")
    val wire = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(1); put(1); putShort(40); putInt(4); putLong(1788159428); putLong(1788160215)
        putInt(3982); putInt(36750); putInt(2888); putShort(786)
    }.array()
    val sum = TripBleProtocol.crc16(wire, 38)
    wire[38] = sum.toByte(); wire[39] = (sum shr 8).toByte()
    check(TripBleProtocol.parseRecord("A", wire) == record)
    wire[8] = (wire[8].toInt() xor 1).toByte()
    check(TripBleProtocol.parseRecord("A", wire) == null)
    val detailed = record.copy(maxSpeedKmh = 138, maxRpm = 6840,
        maxAccelX100 = 326, maxDecelX100 = -714)
    val wireV2 = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(2); put(1); putShort(48); putInt(4); putLong(1788159428); putLong(1788160215)
        putInt(3982); putInt(36750); putInt(2888); putShort(786)
        putShort(138); putShort(6840); putShort(326); putShort(-714)
    }.array()
    val sumV2 = TripBleProtocol.crc16(wireV2, 46)
    wireV2[46] = sumV2.toByte(); wireV2[47] = (sumV2 shr 8).toByte()
    check(TripBleProtocol.parseRecord("A", wireV2) == detailed)
    check(detailed.averageSpeedKmh != null && kotlin.math.abs(detailed.averageSpeedKmh!! - 33.222) < 0.01)
    check(TripBleProtocol.timePacket(1788537600).size == 8)
    check(TripBleProtocol.controlPacket(2, 0xffff_ffff).size == 5)
    println("PASS: v1/v2 trip compatibility, driving details, corrupt record rejection and time packet")

    val brightness = TripBleProtocol.brightnessPacket(55)
    check(brightness.size == 6 && brightness[0] == 1.toByte() &&
        brightness[1] == 1.toByte() && brightness[2] == 55.toByte() && brightness[3] == 6.toByte())
    check(TripBleProtocol.crc16(brightness, 4) ==
        ((brightness[4].toInt() and 0xFF) or ((brightness[5].toInt() and 0xFF) shl 8)))
    check(runCatching { TripBleProtocol.brightnessPacket(9) }.isFailure)
    check(runCatching { TripBleProtocol.brightnessPacket(101) }.isFailure)
    println("PASS: brightness command range, version, length and CRC")

    check(SupportedVehicleModel.fromProfileIndex(4) == SupportedVehicleModel.ZD8)
    check(SupportedVehicleModel.fromProfileIndex(1) == SupportedVehicleModel.ZC6)
    check(SupportedVehicleModel.fromProfileIndex(2) == null)
    val zc6Profile = TripBleProtocol.vehicleProfilePacket(1)
    check(zc6Profile.size == 6 && zc6Profile[0] == 1.toByte() &&
        zc6Profile[1] == 1.toByte() && zc6Profile[2] == 1.toByte() && zc6Profile[3] == 6.toByte())
    check(TripBleProtocol.crc16(zc6Profile, 4) ==
        ((zc6Profile[4].toInt() and 0xFF) or ((zc6Profile[5].toInt() and 0xFF) shl 8)))
    check(TripBleProtocol.vehicleProfilePacket(4)[2] == 4.toByte())
    check(runCatching { TripBleProtocol.vehicleProfilePacket(2) }.isFailure)
    println("PASS: ZD8/ZC6 model mapping and vehicle-profile command CRC")

    val odometerDisplay = TripBleProtocol.odometerDisplayPacket(false)
    check(odometerDisplay.size == 12 && odometerDisplay[0] == 1.toByte() &&
        odometerDisplay[1] == 1.toByte() && odometerDisplay[2] == 0.toByte() &&
        odometerDisplay[3] == 12.toByte())
    val odometerCalibration = TripBleProtocol.odometerCalibrationPacket(5_000_050L)
    check(ByteBuffer.wrap(odometerCalibration).order(ByteOrder.LITTLE_ENDIAN).getInt(4) == 50_001)
    val odometerWire = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(1); put(3); putShort(12); putInt(50_001); putShort(0); putShort(0)
    }.array()
    val odometerCrc = TripBleProtocol.crc16(odometerWire, 10)
    odometerWire[10] = odometerCrc.toByte(); odometerWire[11] = (odometerCrc shr 8).toByte()
    val odometer = checkNotNull(TripBleProtocol.parseOdometerConfig(odometerWire))
    check(odometer.displayEnabled && odometer.calibrated && odometer.odometerX10Km == 50_001L)
    val badOdometer = odometerWire.clone(); badOdometer[4] = (badOdometer[4].toInt() xor 1).toByte()
    check(TripBleProtocol.parseOdometerConfig(badOdometer) == null)
    println("PASS: odometer display/calibration commands, snapshot and CRC rejection")

    val refuelMetaWire = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(2); put(2); putShort(36); putInt(3); putInt(4); putLong(1788530000)
        putInt(3600); putInt(50000); putInt(4200); putShort(7); putShort(0)
    }.array()
    val refuelMetaCrc = TripBleProtocol.crc16(refuelMetaWire, 34)
    refuelMetaWire[34] = refuelMetaCrc.toByte(); refuelMetaWire[35] = (refuelMetaCrc shr 8).toByte()
    val refuelMeta = checkNotNull(TripBleProtocol.parseRefuelMeta(refuelMetaWire))
    check(refuelMeta.newestId == 4L && refuelMeta.currentDistanceM == 50000L &&
        refuelMeta.historyRevision == 7)
    check(refuelMeta.protocolVersion == 2)
    val refuelWire = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(1); put(1); putShort(48); putInt(4); putLong(1788500000); putLong(1788530000)
        putInt(3600); putInt(50000); putInt(4200); putInt(50000); putShort(12000)
        putInt(0); putShort(0)
    }.array()
    val refuelCrc = TripBleProtocol.crc16(refuelWire, 46)
    refuelWire[46] = refuelCrc.toByte(); refuelWire[47] = (refuelCrc shr 8).toByte()
    val refuel = checkNotNull(TripBleProtocol.parseRefuelRecord("A", refuelWire))
    check(refuel.automatic && refuel.detectedAddedMl == 12000 && refuel.odometerX10Km == 50000L)
    val resetPacket = TripBleProtocol.refuelControlPacket(2)
    check(resetPacket.size == 10 && TripBleProtocol.crc16(resetPacket, 8) ==
        ((resetPacket[8].toInt() and 0xff) or ((resetPacket[9].toInt() and 0xff) shl 8)))
    val deletePacket = TripBleProtocol.refuelControlPacket(3, 4)
    check(deletePacket[1] == 3.toByte() &&
        ByteBuffer.wrap(deletePacket).order(ByteOrder.LITTLE_ENDIAN).getInt(4) == 4)
    val thresholdPacket = TripBleProtocol.refuelControlPacket(4, 10_000)
    check(thresholdPacket[1] == 4.toByte() &&
        ByteBuffer.wrap(thresholdPacket).order(ByteOrder.LITTLE_ENDIAN).getInt(4) == 10_000)
    val discardOldestPacket = TripBleProtocol.refuelControlPacket(5, 1)
    check(discardOldestPacket[1] == 5.toByte() &&
        ByteBuffer.wrap(discardOldestPacket).order(ByteOrder.LITTLE_ENDIAN).getInt(4) == 1)
    println("PASS: refuel meta/history, reset, delete, discard-oldest and threshold packets are fixed and CRC protected")

    val customBaseline = TripBleProtocol.customTripBaselinePacket(123456, 7890, 4567)
    check(customBaseline.size == 20 && customBaseline[0] == 1.toByte() &&
        customBaseline[1] == 1.toByte() &&
        ByteBuffer.wrap(customBaseline).order(ByteOrder.LITTLE_ENDIAN).getInt(4) == 123456 &&
        ByteBuffer.wrap(customBaseline).order(ByteOrder.LITTLE_ENDIAN).getInt(8) == 7890 &&
        ByteBuffer.wrap(customBaseline).order(ByteOrder.LITTLE_ENDIAN).getInt(12) == 4567 &&
        TripBleProtocol.crc16(customBaseline, 18) ==
        ((customBaseline[18].toInt() and 0xff) or ((customBaseline[19].toInt() and 0xff) shl 8)))
    println("PASS: custom trip baseline fits the default BLE MTU and is CRC protected")

    check(customTripTitle(null) == "自定义行程")
    check(customTripTitle("上次保养以来") == "自定义行程-上次保养以来")
    check(normalizeCustomTripName("  周末   旅行  ") == "周末 旅行")
    check(normalizeCustomTripName("1234567890") == "12345678")
    println("PASS: custom trip names are optional, normalized and length limited")

    val settingsWire = ByteBuffer.allocate(106).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(2); put(7); putShort(106)
        put(6); put(2); put(6); put(80); put(4); put(2); put(1); put(2)
        put(1); put(0); put(1); put(0)
        putShort(200); putShort(15); putShort(5000); putShort(15)
        put(byteArrayOf(0, 1, 2)); put(byteArrayOf(5, 6, 7, 8, 9))
        put(5); put(8)
        put(byteArrayOf(1, 2, 3, 4, 5, 6)); put(ByteArray(6))
        put("ELM327-Test".toByteArray().copyOf(32))
        listOf(110, 60, 130, 90, 80, 6500, 180, 14500, 40, 600, 15, 1470)
            .forEach { putShort(it.toShort()) }
        putShort(10_000)
    }.array()
    val settingsCrc = TripBleProtocol.crc16(settingsWire, 104)
    settingsWire[104] = settingsCrc.toByte(); settingsWire[105] = (settingsCrc shr 8).toByte()
    val gauge = checkNotNull(TripBleProtocol.parseGaugeSettings(settingsWire))
    check(gauge.readOnly && gauge.tripMergeLocked && gauge.refuelDiscardOldestSupported &&
        gauge.vehicleProfile == 4)
    check(gauge.defaultPage == 6 && gauge.brightness == 80 && gauge.obdName == "ELM327-Test")
    check(gauge.tempDisplayItems == listOf(0, 1, 2) && gauge.infoDisplayItems == listOf(5, 6, 7, 8, 9))
    check(gauge.chartAlarms[7] == 14500 && gauge.obdMac.contentEquals(byteArrayOf(1, 2, 3, 4, 5, 6)))
    check(gauge.refuelThresholdMl == 10_000)
    val legacySettings = settingsWire.copyOf(104)
    legacySettings[0] = 1; legacySettings[2] = 104; legacySettings[3] = 0
    val legacyCrc = TripBleProtocol.crc16(legacySettings, 102)
    legacySettings[102] = legacyCrc.toByte(); legacySettings[103] = (legacyCrc shr 8).toByte()
    check(TripBleProtocol.parseGaugeSettings(legacySettings)?.refuelThresholdMl == null)
    val writable = settingsWire.clone(); writable[1] = 0
    val writableCrc = TripBleProtocol.crc16(writable, 104)
    writable[104] = writableCrc.toByte(); writable[105] = (writableCrc shr 8).toByte()
    check(TripBleProtocol.parseGaugeSettings(writable) == null)
    val badSettings = settingsWire.clone(); badSettings[20] = (badSettings[20].toInt() xor 1).toByte()
    check(TripBleProtocol.parseGaugeSettings(badSettings) == null)
    println("PASS: read-only settings snapshot wire format, mappings and CRC rejection")

    val manifest = """{"device":{"board":"Waveshare ESP32-S3-Touch-AMOLED-1.75-B","variant":"obd_brz_gauge_amoled175","lcd":"CO5300","screen":{"w":466,"h":466,"bpp":16},"flash_mb":16,"ota_slots":2},"firmware":{"project":"obd_brz_gauge","version":"2.7.0","build_tag":"local"}}"""
    val firmware = checkNotNull(TripBleProtocol.parseFirmwareInfo(manifest.toByteArray()))
    check(firmware.version == "2.7.0" && firmware.buildTag == "local")
    check(firmware.variant == "obd_brz_gauge_amoled175" && firmware.screenWidth == 466)
    check(TripBleProtocol.parseFirmwareInfo("{}".toByteArray()) == null)
    val otaStart = TripBleProtocol.otaWifiStartPacket()
    check(otaStart.contentEquals(byteArrayOf(0x4f, 0x54, 0x41, 0x31, 0x04)))
    val wifiStatus = """{"state":"wifi-ready","message":"ready","ssid":"SkyGauge-1234","password":"88888888","ip":"192.168.4.1","token":"abc","port":80}"""
    val wifi = checkNotNull(TripBleProtocol.parseOtaWifiInfo(wifiStatus.toByteArray()))
    check(wifi.ssid == "SkyGauge-1234" && wifi.port == 80 && wifi.token == "abc")
    println("PASS: firmware identity and Wi-Fi OTA handshake parsing")

    val discoveryJson = """{"ssid":"OBD-Gauge-OTA-12AB","password":"88888888","ip":"192.168.4.1","token":"0123456789abcdef","port":80}"""
    val discovered = checkNotNull(TripBleProtocol.parseOtaWifiDiscovery(discoveryJson.toByteArray()))
    check(discovered.state == "wifi-ready" && discovered.ssid == "OBD-Gauge-OTA-12AB" &&
        discovered.password == "88888888" && discovered.token == "0123456789abcdef")
    check(TripBleProtocol.parseOtaWifiDiscovery(discoveryJson.replace("OBD-Gauge-OTA-", "OTHER-").toByteArray()) == null)
    println("PASS: BLE-independent OTA hotspot discovery validates bootstrap identity")

    val fuel = analyzeFuelRecords(listOf(
        FuelRecord(5, 5, 1000.0, 20.0, 160.0, true),
        FuelRecord(3, 3, 600.0, 10.0, 80.0, false),
        FuelRecord(1, 1, 100.0, 40.0, 320.0, true),
        FuelRecord(4, 4, 800.0, 35.0, 280.0, true),
        FuelRecord(2, 2, 400.0, 30.0, 240.0, true),
        FuelRecord(99, 99, 9999.0, 999.0, 999.0, true, draft = true),
    ))
    check(fuel.points.size == 3)
    check(kotlin.math.abs(fuel.points[0].litresPer100Km - 10.0) < 0.0001)
    check(kotlin.math.abs(fuel.points[1].litresPer100Km - 11.25) < 0.0001)
    check(kotlin.math.abs(fuel.points[2].litresPer100Km - 10.0) < 0.0001)
    check(kotlin.math.abs(fuel.measuredLitres - 95.0) < 0.0001)
    check(kotlin.math.abs(fuel.measuredDistanceKm - 900.0) < 0.0001)
    check(kotlin.math.abs(checkNotNull(fuel.historicalAverage) - (95.0 / 900.0 * 100.0)) < 0.0001)
    println("PASS: first fill excluded, newest full fill included, and partials accumulate")

    val recentTrips = (1L..6L).map { id ->
        TripRecord("A", id, 0, 0, 60, 10_000, id * 1_000, (id * 1_000).toInt(), 0)
    }
    val recentAverage = checkNotNull(recentTripAverage(recentTrips))
    check(kotlin.math.abs(recentAverage - 40.0) < 0.0001) // IDs 6..2, weighted by fuel/distance.
    val recentRange = checkNotNull(selectRangeConsumption(
        RangeConsumptionSource.RECENT_FIVE, 1.06, recentTrips, 8.0, 9.0))
    check(recentRange.displayedLitresPer100Km == 40.0)
    check(kotlin.math.abs(recentRange.calculationLitresPer100Km - 42.4) < 0.0001)
    val fuelRange = checkNotNull(selectRangeConsumption(
        RangeConsumptionSource.FUEL_RECORDS, 1.06, recentTrips, 8.0, 10.0))
    check(fuelRange.calculationLitresPer100Km == 10.0)
    check(estimateRangeKm(50.0, 50.0, fuelRange) == 250)
    println("PASS: selectable range sources, five-trip weighting and correction-factor scope")
}
