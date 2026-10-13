package com.brz.gauge.trips

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP entries are read in memory and never extracted to paths supplied by a package. */
internal object TransferArchive {
    const val MAX_BYTES = 32 * 1024 * 1024
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun jsonObject(values: Map<String, Any?>): JSONObject = JSONObject().also { json ->
        values.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
    }
    fun write(snapshot: TransferSnapshot, stream: OutputStream) {
        TransferSchema.validate(snapshot)
        val tables = JSONObject()
        snapshot.tables.forEach { (name, rows) -> tables.put(name, JSONArray().also { array -> rows.forEach { array.put(jsonObject(it)) } }) }
        val prefs = JSONObject()
        snapshot.preferences.forEach { (key, value) -> prefs.put(key, jsonObject(value)) }
        val devices = JSONObject()
        snapshot.devices.forEach { (gauge, value) -> devices.put(gauge, jsonObject(value)) }
        val bytes = JSONObject().put("tables", tables).put("preferences", prefs).put("devices", devices)
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "数据超过 32 MiB 上限" }
        val manifest = JSONObject().put("format", "brz-garage-transfer").put("version", 3)
            .put("appVersion", BuildConfig.VERSION_NAME).put("createdAt", snapshot.createdAt)
            .put("gauge", snapshot.gauge).put("bytes", bytes.size).put("sha256", digest(bytes))
        ZipOutputStream(stream).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json")); zip.write(bytes); zip.closeEntry()
        }
    }
    fun read(stream: InputStream): TransferSnapshot {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in setOf("manifest.json", "data.json") && entry.name !in entries && !entry.isDirectory) { "数据包包含异常文件" }
                val max = if (entry.name == "manifest.json") 8192 else MAX_BYTES
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    require(out.size().toLong() + read <= max) { "数据包文件过大" }
                    out.write(buffer, 0, read)
                }
                entries[entry.name] = out.toByteArray()
                zip.closeEntry()
            }
        }
        require(entries.keys == setOf("manifest.json", "data.json")) { "数据包不完整" }
        val manifest = parseObject(entries.getValue("manifest.json"))
        val version = manifest.getInt("version")
        require(manifest.getString("format") == "brz-garage-transfer" && version in 1..3) { "不支持的数据包版本，请升级 App" }
        val bytes = entries.getValue("data.json")
        require(manifest.getLong("bytes") == bytes.size.toLong() && manifest.getString("sha256") == digest(bytes)) { "数据包校验失败，文件可能损坏" }
        val data = parseObject(bytes)
        require(data.keySet() == if (version >= 3) setOf("tables", "preferences", "devices") else setOf("tables", "preferences")) { "数据内容不完整" }
        val tables = data.getJSONObject("tables")
        require(tables.keySet() == TransferSchema.byName.keys) { "数据表不完整或不支持" }
        var count = 0
        val rows = TransferSchema.tables.associate { table ->
            val array = tables.getJSONArray(table.name)
            count += array.length()
            require(count <= 100_000) { "记录数量超限" }
            table.name to List(array.length()) { index ->
                val obj = array.getJSONObject(index)
                // v1 backups predate maintenance headings. Preserve every existing field.
                if (version == 1 && table.name == "expenses") {
                    require(obj.keySet() == table.columns.keys - "maintenance_type") { "expenses 字段不完整" }
                    obj.put("maintenance_type", JSONObject.NULL)
                }
                require(obj.keySet() == table.columns.keys) { "${table.name} 字段不完整" }
                table.columns.mapValues { (key, col) ->
                    if (obj.isNull(key)) null else when (col.kind) {
                        'I' -> integer(obj.get(key))
                        'R' -> (obj.get(key) as? Number)?.toDouble() ?: error("$key 数值类型无效")
                        else -> obj.get(key) as? String ?: error("$key 文本类型无效")
                    }
                }
            }
        }
        val prefs = data.getJSONObject("preferences")
        val preferences = prefs.keySet().associateWith { key ->
            val entry = prefs.getJSONObject(key)
            require(entry.keySet() == setOf("type", "value")) { "配置字段无效" }
            val type = entry.getString("type")
            mapOf("type" to type, "value" to if (entry.isNull("value")) null else when (type) {
                "int", "long" -> integer(entry.get("value"))
                "boolean" -> entry.get("value") as? Boolean ?: error("配置布尔值无效")
                "string" -> entry.get("value") as? String ?: error("配置文本无效")
                else -> error("配置类型不支持")
            })
        }
        val devices = if (version >= 3) data.getJSONObject("devices").let { root -> root.keySet().associateWith { gauge ->
            val state=root.getJSONObject(gauge)
            state.keySet().associateWith { key -> if(state.isNull(key))null else when(TransferSchema.deviceStateTypes[key]) {
                "boolean" -> state.get(key) as? Boolean ?: error("仪表缓存布尔值无效")
                "real", "real?" -> (state.get(key) as? Number)?.toDouble() ?: error("仪表缓存数值无效")
                "int", "int?", "long", "long?", "signed?" -> integer(state.get(key))
                else -> state.get(key) as? String ?: error("仪表缓存文本无效")
            } }
        } } else emptyMap()
        val result = TransferSnapshot(rows, preferences, manifest.getString("gauge"), integer(manifest.get("createdAt")), devices)
        TransferSchema.validate(result)
        return result
    }
    private fun integer(value: Any): Long {
        require(value is Int || value is Long) { "整数类型无效" }
        return (value as Number).toLong()
    }
    private fun parseObject(bytes: ByteArray): JSONObject {
        val tokener = JSONTokener(bytes.toString(Charsets.UTF_8))
        val obj = tokener.nextValue() as? JSONObject ?: error("JSON 格式无效")
        require(tokener.nextClean() == '\u0000') { "JSON 包含多余内容" }
        return obj
    }
    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
}
