package com.brz.gauge.trips

import android.content.Context
import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION", "MissingPermission")
class FirmwareWifiUploader(
    context: Context,
    private val firmware: EmbeddedFirmwarePackage,
    private val wifi: TripBleProtocol.OtaWifiInfo,
    private val callback: Callback,
) {
    companion object {
        private const val CHUNK_BYTES = 64 * 1024
        private const val MAX_TRANSFER_RETRIES = 5
        private const val MAX_NETWORK_RECONNECTS = 5
        private const val INITIAL_NETWORK_TIMEOUT_MS = 45_000
        private const val RECOVERY_NETWORK_TIMEOUT_MS = 20_000
        private const val NETWORK_RECONNECT_DELAY_MS = 1_200L
        private const val UPDATE_WAKE_TIMEOUT_MS = 5 * 60 * 1000L
        private const val OTA_SSID_PREFIX = "OBD-Gauge-OTA-"
        private const val OTA_BOOTSTRAP_PASSWORD = "88888888"

        fun recoveryInfo() = TripBleProtocol.OtaWifiInfo(
            state = "wifi-recovery", message = "BLE handshake recovery",
            ssid = "", password = OTA_BOOTSTRAP_PASSWORD,
            ip = "192.168.4.1", token = "", port = 80,
        )
    }
    interface Callback {
        fun onStatus(message: String)
        fun onProgress(percent: Int)
        fun onComplete()
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    private val networkGeneration = AtomicInteger(0)
    private val resumeOffset = AtomicInteger(0)
    private val networkStateLock = Any()
    private val discoveryMode = wifi.ssid.isBlank() || wifi.token.isBlank()
    @Volatile private var activeWifi = wifi
    @Volatile private var activeNetwork: Network? = null
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var reconnectAttempts = 0
    private var legacyNetworkId = -1
    private val wifiLock = wifiManager.createWifiLock(
        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BRZGarage:FirmwareUpdate"
    ).apply { setReferenceCounted(false) }
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK, "BRZGarage:FirmwareUpdate"
    ).apply { setReferenceCounted(false) }

    private val reconnect = Runnable {
        if (finished.get()) return@Runnable
        reconnectAttempts++
        if (reconnectAttempts > MAX_NETWORK_RECONNECTS) {
            fail("仪表更新热点连续断开；原固件未被改写，请靠近仪表后重试")
            return@Runnable
        }
        callback.onStatus("更新热点已断开 · 正在重新连接 $reconnectAttempts/$MAX_NETWORK_RECONNECTS")
        unregisterNetworkCallback()
        try {
            if (Build.VERSION.SDK_INT >= 29) connectModern(RECOVERY_NETWORK_TIMEOUT_MS)
            else connectLegacy(RECOVERY_NETWORK_TIMEOUT_MS)
        } catch (error: RuntimeException) {
            scheduleReconnect("重新连接更新热点失败")
        }
    }

    fun start() {
        require(wifi.password.length >= 8 && wifi.ip.isNotBlank() && wifi.port in 1..65535 &&
            (discoveryMode || wifi.ssid.isNotBlank() && wifi.token.isNotBlank())) {
            "仪表未返回完整的更新热点信息"
        }
        callback.onStatus(if (discoveryMode) "BLE 握手已中断 · 正在寻找已启动的仪表更新热点…"
            else "正在连接仪表更新热点 ${wifi.ssid}…")
        holdUpdateResources()
        try {
            if (Build.VERSION.SDK_INT >= 29) connectModern(INITIAL_NETWORK_TIMEOUT_MS)
            else connectLegacy(INITIAL_NETWORK_TIMEOUT_MS)
        } catch (error: RuntimeException) {
            fail("无法连接仪表更新热点：${error.message ?: "系统拒绝连接"}")
        }
    }

    fun cancel() {
        finished.set(true)
        finishNetwork()
    }

    @TargetApi(29)
    private fun connectModern(timeoutMs: Int) {
        val target = activeWifi
        val builder = WifiNetworkSpecifier.Builder().setWpa2Passphrase(target.password)
        if (target.ssid.isBlank()) builder.setSsidPattern(PatternMatcher(OTA_SSID_PREFIX,
            PatternMatcher.PATTERN_PREFIX))
        else builder.setSsid(target.ssid)
        val specifier = builder.build()
        requestNetwork(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier).build(), timeoutMs)
    }

    private fun connectLegacy(timeoutMs: Int) {
        if (!wifiManager.isWifiEnabled) wifiManager.isWifiEnabled = true
        val target = activeWifi
        if (target.ssid.isBlank()) {
            executor.execute {
                val deadline = System.currentTimeMillis() + 15_000L
                var ssid: String? = null
                while (ssid == null && System.currentTimeMillis() < deadline && !finished.get()) {
                    runCatching { wifiManager.startScan() }
                    ssid = runCatching { wifiManager.scanResults
                        .filter { it.SSID.startsWith(OTA_SSID_PREFIX) }
                        .maxByOrNull { it.level }?.SSID }.getOrNull()
                    if (ssid == null) Thread.sleep(1000)
                }
                if (ssid == null) scheduleReconnect("未发现仪表更新热点")
                else runCatching { connectLegacySsid(ssid, timeoutMs) }
                    .onFailure { scheduleReconnect("无法连接仪表更新热点") }
            }
            return
        }
        connectLegacySsid(target.ssid, timeoutMs)
    }

    private fun connectLegacySsid(ssid: String, timeoutMs: Int) {
        val config = WifiConfiguration().apply {
            SSID = quote(ssid)
            preSharedKey = quote(activeWifi.password)
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        legacyNetworkId = wifiManager.addNetwork(config)
        require(legacyNetworkId >= 0 && wifiManager.enableNetwork(legacyNetworkId, true) && wifiManager.reconnect()) {
            "系统无法加入仪表更新热点"
        }
        requestNetwork(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), timeoutMs)
    }

    private fun requestNetwork(request: NetworkRequest, timeoutMs: Int) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (finished.get() || networkCallback !== this) return
                val generation: Int
                synchronized(networkStateLock) {
                    if (finished.get() || networkCallback !== this || activeNetwork == network) return
                    activeNetwork = network
                    generation = networkGeneration.incrementAndGet()
                }
                mainHandler.removeCallbacks(reconnect)
                runCatching { executor.execute { upload(network, generation) } }
            }
            override fun onUnavailable() {
                if (!finished.get() && networkCallback === this) {
                    invalidateNetwork(null)
                    scheduleReconnect("连接仪表更新热点超时")
                }
            }
            override fun onLost(network: Network) {
                if (!finished.get() && networkCallback === this && invalidateNetwork(network)) {
                    /* A no-internet SoftAP can be dropped briefly by the phone.
                     * Invalidate this Android Network immediately: retrying HTTP
                     * on it can never recover. Re-request the hotspot, then read
                     * the gauge's authenticated offset and continue from there. */
                    scheduleReconnect("更新热点连接短暂中断")
                }
            }
        }
        networkCallback = cb
        connectivity.requestNetwork(request, cb, timeoutMs)
    }

    private fun upload(network: Network, generation: Int) {
        try {
            ensureCurrentNetwork(network, generation)
            if (activeWifi.ssid.isBlank() || activeWifi.token.isBlank()) {
                callback.onStatus("已连接更新热点 · 正在恢复安全更新会话")
                val bootstrap = get(network, "/ota/discover", authenticated = false)
                activeWifi = TripBleProtocol.parseOtaWifiDiscovery(bootstrap.toByteArray())
                    ?: error("更新热点未返回有效的安全会话")
            }
            ensureCurrentNetwork(network, generation)
            callback.onStatus("已连接更新热点 · 正在复核仪表型号")
            val remoteManifest = get(network, "/ota/info", authenticated = false)
            val remote = TripBleProtocol.parseFirmwareInfo(remoteManifest.toByteArray())
                ?: error("无法识别热点返回的仪表信息")
            if (!EmbeddedFirmware.compatible(remote, firmware.metadata)) {
                error("热点对应的仪表型号不匹配，已停止更新")
            }

            val bytes = firmware.bytes
            ensureCurrentNetwork(network, generation)
            var offset = readResumeOffset(network, bytes.size)
            resumeOffset.set(offset)
            if (offset > 0) {
                callback.onProgress((offset.toLong() * 100L / bytes.size).toInt())
                callback.onStatus("更新热点已恢复 · 从 ${offset * 100L / bytes.size}% 继续传输")
            }
            var failures = 0
            while (offset < bytes.size) {
                try {
                    ensureCurrentNetwork(network, generation)
                    val length = minOf(CHUNK_BYTES, bytes.size - offset)
                    offset = postChunk(network, bytes, offset, length)
                    resumeOffset.set(offset)
                    reconnectAttempts = 0
                    failures = 0
                    callback.onProgress((offset.toLong() * 100L / bytes.size).toInt())
                } catch (error: Exception) {
                    if (!isCurrentNetwork(network, generation)) return
                    failures++
                    if (failures > MAX_TRANSFER_RETRIES) throw error
                    callback.onStatus("传输短暂中断 · 正在恢复 $failures/$MAX_TRANSFER_RETRIES")
                    Thread.sleep((500L * failures).coerceAtMost(2500L))
                    /* The response may have been lost after the gauge accepted
                     * some or all of the chunk.  Trust its authenticated offset
                     * instead of blindly retransmitting or aborting.  Older
                     * firmware reports zero after resetting; restarting from
                     * zero remains safe because flash is still untouched. */
                    queryResumeOffset(network, bytes.size)?.let { offset = it }
                }
            }
            if (finished.compareAndSet(false, true)) {
                callback.onStatus("固件已完整送达，仪表正在校验并写入备用分区")
                callback.onComplete()
                finishNetwork()
            }
        } catch (error: Exception) {
            if (finished.get() || !isCurrentNetwork(network, generation)) return
            requestGaugeNormalMode(network)
            fail(error.message ?: "固件上传失败")
        }
    }

    private fun isCurrentNetwork(network: Network, generation: Int): Boolean =
        !finished.get() && activeNetwork == network && networkGeneration.get() == generation

    private fun ensureCurrentNetwork(network: Network, generation: Int) {
        if (!isCurrentNetwork(network, generation)) error("更新热点连接已失效")
    }

    private fun invalidateNetwork(network: Network?): Boolean = synchronized(networkStateLock) {
        if (network != null && activeNetwork != network) return@synchronized false
        if (activeNetwork == null && network != null) return@synchronized false
        activeNetwork = null
        networkGeneration.incrementAndGet()
        activeConnection?.let { runCatching { it.disconnect() } }
        activeConnection = null
        true
    }

    private fun scheduleReconnect(reason: String) {
        if (finished.get()) return
        callback.onStatus("$reason · 将自动续传")
        mainHandler.removeCallbacks(reconnect)
        mainHandler.postDelayed(reconnect, NETWORK_RECONNECT_DELAY_MS)
    }

    private fun postChunk(network: Network, bytes: ByteArray, offset: Int, length: Int): Int {
        val last = offset + length == bytes.size
        val connection = open(network, "/ota/firmware").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-OTA-Token", activeWifi.token)
            setRequestProperty("X-OTA-SHA256", firmware.metadata.sha256)
            setRequestProperty("X-OTA-Size", bytes.size.toString())
            setRequestProperty("X-Offset", offset.toString())
            setRequestProperty("X-Last", if (last) "1" else "0")
            setFixedLengthStreamingMode(length)
        }
        return try {
            connection.outputStream.use { it.write(bytes, offset, length) }
            val code = connection.responseCode
            val response = readResponse(connection, code)
            if (code != HttpURLConnection.HTTP_OK)
                error("仪表拒绝固件分块：HTTP $code $response")
            val next = if (last) bytes.size else
                Regex("\\\"nextOffset\\\"\\s*:\\s*(\\d+)").find(response)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: error("仪表未确认分块偏移")
            if (next !in (offset + 1)..(offset + length))
                error("仪表确认偏移异常：$next")
            next
        } finally {
            closeHttp(connection)
        }
    }

    private fun readResumeOffset(network: Network, totalSize: Int): Int {
        val response = get(network, "/ota/status", authenticated = true)
        val state = Regex("\\\"state\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(response)?.groupValues?.get(1).orEmpty()
        val received = Regex("\\\"received\\\"\\s*:\\s*(\\d+)")
            .find(response)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("仪表未返回续传位置")
        val expected = Regex("\\\"expected\\\"\\s*:\\s*(\\d+)")
            .find(response)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (state == "error" && (received != 0 || expected != 0))
            error("仪表已停止接收固件")
        if (expected != 0 && expected != totalSize) error("仪表更新会话与内置固件不一致")
        return received.takeIf { it in 0..totalSize } ?: error("仪表续传位置无效")
    }

    private fun queryResumeOffset(network: Network, totalSize: Int): Int? =
        runCatching { readResumeOffset(network, totalSize) }.getOrNull()

    private fun requestGaugeNormalMode(network: Network) {
        runCatching {
            val connection = open(network, "/ota/cancel").apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("X-OTA-Token", activeWifi.token)
                setFixedLengthStreamingMode(0)
            }
            try {
                connection.outputStream.use { }
                connection.responseCode
            } finally {
                closeHttp(connection)
            }
        }
    }

    private fun get(network: Network, path: String, authenticated: Boolean): String {
        val connection = open(network, path).apply {
            requestMethod = "GET"
            if (authenticated) setRequestProperty("X-OTA-Token", activeWifi.token)
        }
        return try {
            val code = connection.responseCode
            val response = readResponse(connection, code)
            if (code != HttpURLConnection.HTTP_OK) error("仪表身份复核失败：HTTP $code")
            response
        } finally {
            closeHttp(connection)
        }
    }

    private fun open(network: Network, path: String): HttpURLConnection =
        network.openConnection(URL("http://${activeWifi.ip}:${activeWifi.port}$path")).let { it as HttpURLConnection }
            .apply {
                connectTimeout = 12_000
                readTimeout = 45_000
                useCaches = false
                setRequestProperty("Connection", "close")
            }
            .also { activeConnection = it }

    private fun closeHttp(connection: HttpURLConnection) {
        if (activeConnection === connection) activeConnection = null
        connection.disconnect()
    }

    private fun readResponse(connection: HttpURLConnection, code: Int): String =
        (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()

    private fun fail(message: String) {
        if (finished.compareAndSet(false, true)) callback.onError(message)
        finishNetwork()
    }

    private fun holdUpdateResources() {
        runCatching { if (!wifiLock.isHeld) wifiLock.acquire() }
        runCatching { if (!wakeLock.isHeld) wakeLock.acquire(UPDATE_WAKE_TIMEOUT_MS) }
    }

    private fun unregisterNetworkCallback() {
        val old = networkCallback
        networkCallback = null
        old?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        invalidateNetwork(null)
    }

    private fun finishNetwork() {
        mainHandler.removeCallbacks(reconnect)
        unregisterNetworkCallback()
        if (legacyNetworkId >= 0) {
            runCatching { wifiManager.removeNetwork(legacyNetworkId) }
            runCatching { wifiManager.reconnect() }
            legacyNetworkId = -1
        }
        runCatching { if (wifiLock.isHeld) wifiLock.release() }
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        executor.shutdown()
    }

    private fun quote(value: String) = "\"${value.replace("\"", "") }\""
}
