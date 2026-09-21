package com.brz.gauge.trips

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

data class EmbeddedFirmwareMetadata(
    val packageId: String,
    val displayName: String,
    val manifestAsset: String,
    val isRollback: Boolean,
    val device: TripBleProtocol.FirmwareInfo,
    val assetPath: String,
    val size: Int,
    val sha256: String,
)

data class EmbeddedFirmwareCatalogEntry(
    val id: String,
    val label: String,
    val manifestAsset: String,
    val isRollback: Boolean,
)

data class EmbeddedFirmwarePackage(val metadata: EmbeddedFirmwareMetadata, val bytes: ByteArray)

data class FirmwareAssessment(val canUpdate: Boolean, val updateAvailable: Boolean, val message: String)

object EmbeddedFirmware {
    const val LATEST_PACKAGE_ID = "latest"
    private const val CATALOG_ASSET = "firmware/catalog.json"

    fun catalog(context: Context): List<EmbeddedFirmwareCatalogEntry> =
        context.assets.open(CATALOG_ASSET).use { input ->
            val packages = JSONObject(input.bufferedReader().readText()).getJSONArray("packages")
            buildList {
                for (index in 0 until packages.length()) {
                    val item = packages.getJSONObject(index)
                    add(EmbeddedFirmwareCatalogEntry(
                        id = item.getString("id"),
                        label = item.getString("label"),
                        manifestAsset = item.getString("manifest"),
                        isRollback = item.optBoolean("rollback", false),
                    ))
                }
            }
        }

    fun metadata(context: Context): EmbeddedFirmwareMetadata =
        metadata(context, LATEST_PACKAGE_ID)

    fun metadata(context: Context, packageId: String): EmbeddedFirmwareMetadata {
        val entry = catalog(context).firstOrNull { it.id == packageId }
            ?: error("找不到所选固件包")
        return readMetadata(context, entry)
    }

    fun historical(context: Context): List<EmbeddedFirmwareMetadata> =
        catalog(context).filter { it.isRollback }.map { readMetadata(context, it) }

    private fun readMetadata(
        context: Context,
        entry: EmbeddedFirmwareCatalogEntry,
    ): EmbeddedFirmwareMetadata = context.assets.open(entry.manifestAsset).use { input ->
        val root = JSONObject(input.bufferedReader().readText())
        val device = root.getJSONObject("device")
        val screen = device.getJSONObject("screen")
        val firmware = root.getJSONObject("firmware")
        val file = root.getJSONObject("files").getJSONObject("firmware")
        val manifestDirectory = entry.manifestAsset.substringBeforeLast('/', "")
        EmbeddedFirmwareMetadata(
            packageId = entry.id,
            displayName = entry.label,
            manifestAsset = entry.manifestAsset,
            isRollback = entry.isRollback,
            device = TripBleProtocol.FirmwareInfo(
                version = firmware.getString("version"),
                buildTag = firmware.optString("build_tag", "embedded"),
                project = firmware.getString("project"),
                board = device.getString("board"),
                variant = device.getString("variant"),
                lcd = device.getString("lcd"),
                screenWidth = screen.getInt("w"),
                screenHeight = screen.getInt("h"),
                colorBits = screen.getInt("bpp"),
                flashMb = device.getInt("flash_mb"),
                otaSlots = device.getInt("ota_slots"),
            ),
            assetPath = if (manifestDirectory.isEmpty()) file.getString("path")
                else "$manifestDirectory/${file.getString("path")}",
            size = file.getInt("size"),
            sha256 = file.getString("sha256").lowercase(),
        )
    }

    fun loadVerified(context: Context): EmbeddedFirmwarePackage =
        loadVerified(context, metadata(context))

    fun loadVerified(context: Context, metadata: EmbeddedFirmwareMetadata): EmbeddedFirmwarePackage {
        val bytes = context.assets.open(metadata.assetPath).use { it.readBytes() }
        require(bytes.size == metadata.size) { "内置固件长度校验失败" }
        require(bytes.isNotEmpty() && bytes[0] == 0xE9.toByte()) { "内置文件不是 ESP32 固件镜像" }
        require(bytes.size <= 0x300000) { "内置固件超过 OTA 分区容量" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(digest == metadata.sha256) { "内置固件 SHA-256 校验失败" }
        return EmbeddedFirmwarePackage(metadata, bytes)
    }

    fun assess(device: TripBleProtocol.FirmwareInfo?, embedded: EmbeddedFirmwareMetadata): FirmwareAssessment {
        if (device == null) return FirmwareAssessment(false, false, "请先连接仪表并读取当前固件信息")
        val target = embedded.device
        if (!sameHardware(device, target)) return FirmwareAssessment(false, false,
            "仪表硬件或分区信息与内置固件不匹配，已禁止更新")
        val comparison = compareVersions(target.version, device.version)
        return when {
            comparison > 0 -> FirmwareAssessment(true, true,
                "发现新固件 v${target.version}，可安全更新")
            comparison == 0 -> FirmwareAssessment(false, false,
                "仪表已是最新版本 v${device.version}")
            else -> FirmwareAssessment(false, false,
                "仪表版本 v${device.version} 高于 App 内置版本，禁止降级")
        }
    }

    fun assessRollback(
        device: TripBleProtocol.FirmwareInfo?,
        historical: EmbeddedFirmwareMetadata,
    ): FirmwareAssessment {
        if (!historical.isRollback) return FirmwareAssessment(false, false, "所选固件不是历史回滚包")
        if (device == null) return FirmwareAssessment(false, false, "请先连接仪表并读取当前固件信息")
        if (!sameHardware(device, historical.device)) return FirmwareAssessment(false, false,
            "仪表硬件或分区信息与历史固件不匹配，已禁止回滚")
        val comparison = compareVersions(historical.device.version, device.version)
        return when {
            comparison < 0 -> FirmwareAssessment(true, true,
                "可回滚至 v${historical.device.version}（数据安全版）")
            comparison == 0 -> FirmwareAssessment(false, false,
                "仪表当前已运行 v${device.version}，无需回滚")
            else -> FirmwareAssessment(false, false,
                "所选 v${historical.device.version} 高于仪表 v${device.version}，请使用正常更新")
        }
    }

    fun compatible(device: TripBleProtocol.FirmwareInfo, embedded: EmbeddedFirmwareMetadata): Boolean =
        sameHardware(device, embedded.device)

    private fun sameHardware(device: TripBleProtocol.FirmwareInfo, target: TripBleProtocol.FirmwareInfo) =
        device.project == target.project && device.board == target.board &&
            device.variant == target.variant && device.lcd == target.lcd &&
            device.screenWidth == target.screenWidth && device.screenHeight == target.screenHeight &&
            device.colorBits == target.colorBits && device.flashMb == target.flashMb &&
            device.otaSlots == target.otaSlots && device.otaSlots >= 2

    internal fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = right.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val value = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (value != 0) return value
        }
        return 0
    }
}
