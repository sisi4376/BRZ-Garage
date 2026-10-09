package android.content

import java.io.File

open class Context(val root: File = File(System.getProperty("java.io.tmpdir"), "transfer-test-unused")) {
    companion object { const val MODE_PRIVATE = 0 }
    val filesDir = File(root, "files").apply { mkdirs() }
    private val preferences = HashMap<String, SharedPreferences>()
    fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile.mkdirs() }
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences.getOrPut(name) {
        SharedPreferences(File(root, "preferences/$name.bin"))
    }
}

class SharedPreferences(private val file: File? = null) {
    val all = LinkedHashMap<String, Any?>()
    init {
        if (file?.isFile == true) java.io.ObjectInputStream(file.inputStream()).use {
            @Suppress("UNCHECKED_CAST")
            all.putAll(it.readObject() as Map<String, Any?>)
        }
    }
    fun contains(key: String) = all.containsKey(key)
    fun getString(key: String, fallback: String?): String? = if (contains(key)) all[key] as String? else fallback
    fun getLong(key: String, fallback: Long): Long = if (contains(key)) all[key] as Long else fallback
    fun getInt(key: String, fallback: Int): Int = if (contains(key)) all[key] as Int else fallback
    fun getBoolean(key: String, fallback: Boolean): Boolean = if (contains(key)) all[key] as Boolean else fallback
    var failCommit = false
    fun edit() = Editor(this)
    class Editor(private val prefs: SharedPreferences) {
        private val writes = LinkedHashMap<String, Any?>()
        fun putString(key: String, value: String?) = apply { writes[key] = value }
        fun putLong(key: String, value: Long) = apply { writes[key] = value }
        fun putInt(key: String, value: Int) = apply { writes[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { writes[key] = value }
        fun remove(key: String) = apply { writes[key] = null }
        fun commit(): Boolean {
            if (prefs.failCommit) return false
            writes.forEach { (key, value) -> if (value == null) prefs.all.remove(key) else prefs.all[key] = value }
            prefs.file?.let { file ->
                file.parentFile.mkdirs()
                java.io.ObjectOutputStream(file.outputStream()).use { it.writeObject(prefs.all) }
            }
            return true
        }
        fun apply() { check(commit()) }
    }
}

class ContentValues : LinkedHashMap<String, Any?>() {
    fun putNull(name: String) { put(name, null) }
}
