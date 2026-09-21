package com.brz.gauge.trips

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object TripBleProtocol {
    val INFO_SERVICE: UUID = uuid16(0x1FFA)
    val MANIFEST: UUID = uuid16(0x0001)
    val TIME_SYNC: UUID = uuid16(0x0002)
    val TRIP_META: UUID = uuid16(0x0003)
    val TRIP_CONTROL: UUID = uuid16(0x0004)
    val TRIP_DATA: UUID = uuid16(0x0005)
    val VEHICLE_STATE: UUID = uuid16(0x0006)
    val GAUGE_SETTINGS: UUID = uuid16(0x0007)
    val BRIGHTNESS_CONTROL: UUID = uuid16(0x0008)
    val VEHICLE_PROFILE_CONTROL: UUID = uuid16(0x0009)
    val ODOMETER_CONFIG: UUID = uuid16(0x000A)
    val REFUEL_META: UUID = uuid16(0x000B)
    val REFUEL_CONTROL: UUID = uuid16(0x000C)
    val REFUEL_DATA: UUID = uuid16(0x000D)
    val CUSTOM_TRIP_CONTROL: UUID = uuid16(0x000E)
    val OTA_SERVICE: UUID = uuid16(0x1FFB)
    val OTA_CONTROL: UUID = uuid16(0x0001)
    val OTA_STATUS: UUID = uuid16(0x0003)

    const val COMMAND_CURSOR: Byte = 1
    const val COMMAND_ACK: Byte = 2

    data class Meta(
        val version: Int,
        val pendingCount: Int,
        val overflowed: Boolean,
        val oldestId: Long,
        val newestId: Long,
        val lastAckedId: Long,
        val capacity: Int,
        val recordSize: Int,
    )

    data class GaugeSettings(
        val version: Int,
        val flags: Int,
        val protocol: Int,
        val theme: Int,
        val defaultPage: Int,
        val brightness: Int,
        val vehicleProfile: Int,
        val deviceRole: Int,
        val introMode: Int,
        val devicePosition: Int,
        val rpmWarnAnimation: Boolean,
        val rpmWarnLinked: Boolean,
        val raceChronoEnabled: Boolean,
        val bootMode: Int,
        val brakeTempWarningC: Int,
        val oilPressureWarningX10: Int,
        val rpmWarning: Int,
        val tripMergeMinutes: Int,
        val tempDisplayItems: List<Int>,
        val infoDisplayItems: List<Int>,
        val needleItem: Int,
        val chartItem: Int,
        val obdMac: ByteArray,
        val masterMac: ByteArray,
        val obdName: String,
        val chartAlarms: List<Int>,
        val refuelThresholdMl: Int?,
    ) {
        val readOnly: Boolean get() = flags and 0x01 != 0
        val tripMergeLocked: Boolean get() = flags and 0x02 != 0
        val refuelDiscardOldestSupported: Boolean get() = flags and 0x04 != 0
    }

    data class FirmwareInfo(
        val version: String,
        val buildTag: String,
        val project: String,
        val board: String,
        val variant: String,
        val lcd: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val colorBits: Int,
        val flashMb: Int,
        val otaSlots: Int,
    )

    data class OdometerConfig(
        val displayEnabled: Boolean,
        val calibrated: Boolean,
        val odometerX10Km: Long,
    )

    data class RefuelMeta(
        val count: Int,
        val oldestId: Long,
        val newestId: Long,
        val currentStartEpochS: Long,
        val currentDurationS: Long,
        val currentDistanceM: Long,
        val currentFuelMl: Long,
        val historyRevision: Int = 0,
        val protocolVersion: Int = 1,
    )

    data class OtaWifiInfo(
        val state: String,
        val message: String,
        val ssid: String,
        val password: String,
        val ip: String,
        val token: String,
        val port: Int,
    )

    fun timePacket(epochSeconds: Long): ByteArray = ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(epochSeconds)
        .array()

    fun controlPacket(command: Byte, tripId: Long): ByteArray = ByteBuffer.allocate(5)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(command)
        .putInt(tripId.toInt())
        .array()

    /** Six-byte, versioned SET command for brightness. */
    fun brightnessPacket(percent: Int): ByteArray {
        require(percent in 10..100) { "brightness must be 10..100" }
        val packet = byteArrayOf(1, 1, percent.toByte(), 6, 0, 0)
        val crc = crc16(packet, 4)
        packet[4] = crc.toByte()
        packet[5] = (crc shr 8).toByte()
        return packet
    }

    /** Only the two BRZ 6MT profiles exposed by the app may be selected. */
    fun vehicleProfilePacket(profileIndex: Int): ByteArray {
        require(SupportedVehicleModel.fromProfileIndex(profileIndex) != null) {
            "unsupported vehicle profile"
        }
        val packet = byteArrayOf(1, 1, profileIndex.toByte(), 6, 0, 0)
        val crc = crc16(packet, 4)
        packet[4] = crc.toByte()
        packet[5] = (crc shr 8).toByte()
        return packet
    }

    private fun odometerPacket(command: Int, displayEnabled: Boolean, odometerX10Km: Long): ByteArray {
        require(command == 1 || command == 2)
        require(odometerX10Km in 0L..9_999_999L)
        val packet = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(1.toByte()).put(command.toByte())
            .put(if (displayEnabled) 1.toByte() else 0.toByte()).put(12.toByte())
            .putInt(odometerX10Km.toInt()).putShort(0).putShort(0).array()
        val crc = crc16(packet, 10)
        packet[10] = crc.toByte()
        packet[11] = (crc shr 8).toByte()
        return packet
    }

    fun odometerDisplayPacket(enabled: Boolean): ByteArray =
        odometerPacket(1, enabled, 0)

    fun odometerCalibrationPacket(distanceM: Long): ByteArray {
        val x10Km = ((distanceM.coerceAtLeast(0L) + 50L) / 100L).coerceAtMost(9_999_999L)
        return odometerPacket(2, false, x10Km)
    }

    fun refuelControlPacket(command: Int, argument: Long = 0): ByteArray {
        require(command in 1..5)
        require(argument in 0L..0xffff_ffffL)
        require(command == 1 || (command == 2 && argument == 0L) ||
            (command == 3 && argument > 0L) ||
            (command == 4 && argument in 5_000L..20_000L && argument % 1_000L == 0L) ||
            (command == 5 && argument > 0L))
        val packet = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .put(1.toByte()).put(command.toByte()).putShort(10.toShort())
            .putInt(argument.toInt()).putShort(0.toShort()).array()
        val crc = crc16(packet, 8)
        packet[8] = crc.toByte()
        packet[9] = (crc shr 8).toByte()
        return packet
    }

    /** Default-MTU-safe command that mirrors the App's persisted lifetime baseline. */
    fun customTripBaselinePacket(distanceM: Long, durationS: Long, fuelMl: Long): ByteArray {
        require(distanceM in 0L..0xffff_ffffL)
        require(durationS in 0L..0xffff_ffffL)
        require(fuelMl in 0L..0xffff_ffffL)
        val packet = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put(1.toByte()).put(1.toByte()).putShort(20.toShort())
            .putInt(distanceM.toInt()).putInt(durationS.toInt()).putInt(fuelMl.toInt())
            .putShort(0.toShort()).putShort(0.toShort()).array()
        val crc = crc16(packet, 18)
        packet[18] = crc.toByte()
        packet[19] = (crc shr 8).toByte()
        return packet
    }

    /** Read a named object from the gauge's fixed JSON manifest without regex. */
    private fun jsonObject(json: String, name: String): String? {
        val keyAt = json.indexOf("\"$name\"")
        if (keyAt < 0) return null
        val colonAt = json.indexOf(':', keyAt + name.length + 2)
        val openAt = if (colonAt >= 0) json.indexOf('{', colonAt + 1) else -1
        if (openAt < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in openAt until json.length) {
            val char = json[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(openAt + 1, index)
                }
            }
        }
        return null
    }

    private fun jsonValueStart(json: String, name: String): Int {
        val keyAt = json.indexOf("\"$name\"")
        if (keyAt < 0) return -1
        val colonAt = json.indexOf(':', keyAt + name.length + 2)
        if (colonAt < 0) return -1
        var valueAt = colonAt + 1
        while (valueAt < json.length && json[valueAt].isWhitespace()) valueAt++
        return valueAt
    }

    private fun jsonString(json: String, name: String): String? {
        val openAt = jsonValueStart(json, name)
        if (openAt < 0 || openAt >= json.length || json[openAt] != '"') return null
        var escaped = false
        for (index in openAt + 1 until json.length) {
            val char = json[index]
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') return json.substring(openAt + 1, index)
        }
        return null
    }

    private fun jsonInt(json: String, name: String): Int {
        val start = jsonValueStart(json, name)
        if (start < 0) return -1
        var end = start
        while (end < json.length && json[end].isDigit()) end++
        return json.substring(start, end).toIntOrNull() ?: -1
    }

    /** Parse the gauge's read-only manifest by its fixed object and field names. */
    fun parseFirmwareInfo(value: ByteArray): FirmwareInfo? {
        val root = value.toString(Charsets.UTF_8).trimEnd('\u0000')
        val device = jsonObject(root, "device") ?: return null
        val firmware = jsonObject(root, "firmware") ?: return null
        val screen = jsonObject(device, "screen") ?: return null
        val version = jsonString(firmware, "version")
            ?.takeIf { it.isNotBlank() && it.length <= 31 } ?: return null
        return FirmwareInfo(
            version = version,
            buildTag = jsonString(firmware, "build_tag")
                ?.takeIf { it.isNotBlank() && it.length <= 63 } ?: "local",
            project = jsonString(firmware, "project").orEmpty(),
            board = jsonString(device, "board").orEmpty(),
            variant = jsonString(device, "variant").orEmpty(),
            lcd = jsonString(device, "lcd").orEmpty(),
            screenWidth = jsonInt(screen, "w"),
            screenHeight = jsonInt(screen, "h"),
            colorBits = jsonInt(screen, "bpp"),
            flashMb = jsonInt(device, "flash_mb"),
            otaSlots = jsonInt(device, "ota_slots"),
        )
    }

    fun otaWifiStartPacket(): ByteArray = byteArrayOf('O'.code.toByte(), 'T'.code.toByte(),
        'A'.code.toByte(), '1'.code.toByte(), 4)

    fun parseOtaWifiInfo(value: ByteArray): OtaWifiInfo? {
        val json = value.toString(Charsets.UTF_8).trimEnd('\u0000')
        fun field(name: String): String =
            Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
                .find(json)?.groupValues?.get(1).orEmpty()
        fun number(name: String): Int =
            Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(\\d+)")
                .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val state = field("state")
        if (state.isEmpty()) return null
        return OtaWifiInfo(state, field("message"), field("ssid"), field("password"),
            field("ip"), field("token"), number("port"))
    }

    /** Parse the BLE-independent /ota/discover bootstrap response. */
    fun parseOtaWifiDiscovery(value: ByteArray): OtaWifiInfo? {
        val json = value.toString(Charsets.UTF_8).trimEnd('\u0000')
        fun field(name: String): String =
            Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
                .find(json)?.groupValues?.get(1).orEmpty()
        fun number(name: String): Int =
            Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(\\d+)")
                .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val ssid = field("ssid")
        val password = field("password")
        val ip = field("ip")
        val token = field("token")
        val port = number("port")
        if (!ssid.startsWith("OBD-Gauge-OTA-") || password.length < 8 ||
            ip.isBlank() || token.isBlank() || port !in 1..65535) return null
        return OtaWifiInfo("wifi-ready", "discovered", ssid, password, ip, token, port)
    }

    fun parseMeta(value: ByteArray): Meta? {
        if (value.size < 20) return null
        val data = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val version = data.get().toInt() and 0xFF
        val pending = data.get().toInt() and 0xFF
        val overflow = data.get().toInt() != 0
        data.get()
        val meta = Meta(
            version = version,
            pendingCount = pending,
            overflowed = overflow,
            oldestId = data.int.toLong() and 0xFFFF_FFFFL,
            newestId = data.int.toLong() and 0xFFFF_FFFFL,
            lastAckedId = data.int.toLong() and 0xFFFF_FFFFL,
            capacity = data.short.toInt() and 0xFFFF,
            recordSize = data.short.toInt() and 0xFFFF,
        )
        return meta.takeIf {
            ((it.version == 1 && it.recordSize == 40) ||
                (it.version == 2 && it.recordSize == 48)) &&
                it.pendingCount <= it.capacity &&
                (it.pendingCount == 0 || it.oldestId in 1..it.newestId)
        }
    }

    fun parseRecord(deviceId: String, value: ByteArray): TripRecord? {
        if (value.size < 4) return null
        val version = value[0].toInt() and 0xFF
        val encodedSize = (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)
        val expectedSize = when (version) { 1 -> 40; 2 -> 48; else -> return null }
        if (encodedSize != expectedSize || value.size != expectedSize) return null
        val crcOffset = expectedSize - 2
        val expectedCrc = (value[crcOffset].toInt() and 0xFF) or
            ((value[crcOffset + 1].toInt() and 0xFF) shl 8)
        if (crc16(value, crcOffset) != expectedCrc) return null
        val data = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        data.get()
        val flags = data.get().toInt() and 0xFF
        val size = data.short.toInt() and 0xFFFF
        if (size != expectedSize) return null
        val tripId = data.int.toLong() and 0xFFFF_FFFFL
        val startEpochS = data.long
        val endEpochS = data.long
        val durationS = data.int.toLong() and 0xFFFF_FFFFL
        val distanceM = data.int.toLong() and 0xFFFF_FFFFL
        val fuelMl = data.int.toLong() and 0xFFFF_FFFFL
        val avgL100X100 = data.short.toInt() and 0xFFFF
        val detail = if (version >= 2) {
            intArrayOf(data.short.toInt() and 0xFFFF, data.short.toInt() and 0xFFFF,
                data.short.toInt(), data.short.toInt())
        } else null
        val detailAvailable = detail?.any { it != 0 } == true
        return TripRecord(
            deviceId = deviceId,
            tripId = tripId,
            startEpochS = startEpochS,
            endEpochS = endEpochS,
            durationS = durationS,
            distanceM = distanceM,
            fuelMl = fuelMl,
            avgL100X100 = avgL100X100,
            flags = flags,
            maxSpeedKmh = detail?.get(0)?.takeIf { detailAvailable },
            maxRpm = detail?.get(1)?.takeIf { detailAvailable },
            maxAccelX100 = detail?.get(2)?.takeIf { detailAvailable },
            maxDecelX100 = detail?.get(3)?.takeIf { detailAvailable },
        )
    }

    fun parseGaugeSettings(value: ByteArray): GaugeSettings? {
        if (value.size !in setOf(104, 106)) return null
        val version = value[0].toInt() and 0xFF
        if ((version == 1 && value.size != 104) || (version == 2 && value.size != 106)) return null
        val encodedSize = (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)
        val crcOffset = value.size - 2
        val expectedCrc = (value[crcOffset].toInt() and 0xFF) or
            ((value[crcOffset + 1].toInt() and 0xFF) shl 8)
        if (encodedSize != value.size || crc16(value, crcOffset) != expectedCrc) return null
        fun u8(offset: Int) = value[offset].toInt() and 0xFF
        fun u16(offset: Int) = u8(offset) or (u8(offset + 1) shl 8)
        fun s16(offset: Int): Int = u16(offset).let { if (it >= 0x8000) it - 0x10000 else it }
        val nameBytes = value.copyOfRange(46, 78)
        val nameEnd = nameBytes.indexOf(0.toByte()).let { if (it < 0) nameBytes.size else it }
        return GaugeSettings(
            version = u8(0),
            flags = u8(1),
            protocol = u8(4),
            theme = u8(5),
            defaultPage = u8(6),
            brightness = u8(7),
            vehicleProfile = u8(8),
            deviceRole = u8(9),
            introMode = u8(10),
            devicePosition = u8(11),
            rpmWarnAnimation = u8(12) != 0,
            rpmWarnLinked = u8(13) != 0,
            raceChronoEnabled = u8(14) != 0,
            bootMode = u8(15),
            brakeTempWarningC = u16(16),
            oilPressureWarningX10 = u16(18),
            rpmWarning = u16(20),
            tripMergeMinutes = u16(22),
            tempDisplayItems = (24..26).map(::u8),
            infoDisplayItems = (27..31).map(::u8),
            needleItem = u8(32),
            chartItem = u8(33),
            obdMac = value.copyOfRange(34, 40),
            masterMac = value.copyOfRange(40, 46),
            obdName = nameBytes.copyOf(nameEnd).toString(Charsets.UTF_8),
            chartAlarms = (0 until 12).map { s16(78 + it * 2) },
            refuelThresholdMl = if (version >= 2) u16(102) else null,
        ).takeIf { it.readOnly }
    }

    fun parseOdometerConfig(value: ByteArray): OdometerConfig? {
        if (value.size != 12 || (value[0].toInt() and 0xFF) != 1) return null
        val data = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        data.get()
        val flags = data.get().toInt() and 0xFF
        val size = data.short.toInt() and 0xFFFF
        val odometer = data.int.toLong() and 0xFFFF_FFFFL
        val reserved = data.short.toInt() and 0xFFFF
        val expectedCrc = data.short.toInt() and 0xFFFF
        if (size != 12 || reserved != 0 || flags and 0xFC != 0 ||
            crc16(value, 10) != expectedCrc || odometer > 9_999_999L) return null
        return OdometerConfig(
            displayEnabled = flags and 0x01 != 0,
            calibrated = flags and 0x02 != 0,
            odometerX10Km = odometer,
        )
    }

    fun parseRefuelMeta(value: ByteArray): RefuelMeta? {
        if (value.size != 36) return null
        val data = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val version = data.get().toInt() and 0xff
        val count = data.get().toInt() and 0xff
        val size = data.short.toInt() and 0xffff
        fun u32() = data.int.toLong() and 0xffff_ffffL
        val oldest = u32()
        val newest = u32()
        val start = data.long
        val duration = u32()
        val distance = u32()
        val fuel = u32()
        val historyRevision = data.short.toInt() and 0xffff
        val expected = data.short.toInt() and 0xffff
        if (version !in 1..2 || size != 36 || count > 20 || crc16(value, 34) != expected ||
            (count > 0 && oldest !in 1..newest)) return null
        return RefuelMeta(count, oldest, newest, start, duration, distance, fuel,
            historyRevision, version)
    }

    fun parseRefuelRecord(deviceId: String, value: ByteArray): RefuelInterval? {
        if (value.size != 48 || (value[0].toInt() and 0xff) != 1) return null
        val data = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        data.get()
        val flags = data.get().toInt() and 0xff
        val size = data.short.toInt() and 0xffff
        fun u32() = data.int.toLong() and 0xffff_ffffL
        val id = u32()
        val start = data.long
        val end = data.long
        val duration = u32()
        val distance = u32()
        val fuel = u32()
        val odometerX10 = u32()
        val addedMl = data.short.toInt() and 0xffff
        data.position(46)
        val expected = data.short.toInt() and 0xffff
        if (size != 48 || id == 0L || crc16(value, 46) != expected) return null
        return RefuelInterval(deviceId, id, start, end, duration, distance, fuel,
            odometerX10, addedMl, flags)
    }

    fun crc16(data: ByteArray, length: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    private fun uuid16(value: Int): UUID = UUID.fromString(
        String.format("0000%04x-0000-1000-8000-00805f9b34fb", value)
    )
}
