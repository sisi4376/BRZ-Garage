package com.brz.gauge.trips

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** A bounded local crash record. No exception messages, device addresses or app records. */
object AppCrashDiagnostics {
    private const val LIMIT = 12_000
    private const val MAX_STEPS = 24
    private var installed = false
    private var file: File? = null
    private var metadata = ""
    private val steps = ArrayDeque<String>()

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        file = File(context.applicationContext.filesDir, "last-app-crash.txt")
        metadata = "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Saving diagnostics must never replace Android's normal crash handling.
            try { save(thread, error) } catch (_: Throwable) { }
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        installed = true
    }

    @Synchronized
    fun step(label: String) {
        // Callers supply fixed stage names, never device names, addresses or extras.
        if (!label.matches(Regex("[A-Za-z0-9_.:= -]{1,100}"))) return
        steps.addLast("${System.currentTimeMillis()}: $label")
        while (steps.size > MAX_STEPS) steps.removeFirst()
        // Retain stages even if Android kills the process without a Java exception.
        try {
            file?.parentFile?.resolve("binding-steps.txt")?.writeText(
                metadata + "\n" + steps.joinToString("\n"), Charsets.UTF_8)
        } catch (_: Throwable) { }
    }

    @Synchronized
    private fun save(thread: Thread, error: Throwable) {
        val report = buildString {
            appendLine("BRZ local crash diagnostic v1")
            appendLine(metadata)
            appendLine("Time: ${System.currentTimeMillis()}")
            appendLine("Thread ID: ${thread.id}")
            appendLine("Binding steps:")
            steps.forEach { appendLine(it) }
            appendLine("Exception types and frames (messages omitted):")
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < 6 && seen.add(cause)) {
                appendLine("Cause $depth: ${cause.javaClass.name}")
                cause.stackTrace.take(20).forEach { frame ->
                    appendLine("  at $frame")
                }
                cause = cause.cause
                depth++
            }
        }
        // One private file replaces the previous record; it is never uploaded automatically.
        file?.writeText(report.take(LIMIT), Charsets.UTF_8)
    }

    fun summary(context: Context): String {
        fun read(name: String): String? = try {
            val target = File(context.applicationContext.filesDir, name)
            if (!target.isFile) null else target.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(LIMIT)
                val size = reader.read(buffer)
                if (size <= 0) null else String(buffer, 0, size)
            }
        } catch (_: Exception) { null }
        val report = read("last-app-crash.txt") ?: "未记录到 Java/Kotlin 崩溃。"
        val binding = read("binding-steps.txt") ?: "尚无绑定步骤记录。"
        return "最近一次 App 异常：\n$report\n最近绑定步骤：\n$binding" + exitReason(context)
    }

    private fun exitReason(context: Context): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        return try {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return ""
            val last = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull() ?: return ""
            "\n最近系统退出记录：reason=${last.reason}, status=${last.status}, time=${last.timestamp}"
        } catch (_: RuntimeException) { "" }
    }
}
