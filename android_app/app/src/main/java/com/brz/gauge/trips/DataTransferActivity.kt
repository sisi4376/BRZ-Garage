package com.brz.gauge.trips

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class DataTransferActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: TransferStore
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val actions = ArrayList<Button>()
    private var working = false
    private var ownsSession = false
    @Volatile private var ioActive = false
    private var backupToExport: String? = null
    private var imported = false
    private var preparedExport: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TransferStore(applicationContext)
        backupToExport = savedInstanceState?.getString("backup")
        imported = savedInstanceState?.getBoolean("imported") ?: false
        preparedExport = savedInstanceState?.getString("preparedExport")
        if (imported) setResult(RESULT_OK)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(24))
            setBackgroundColor(Color.rgb(244, 245, 247))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        button(body, "返回设置") { finish() }
        text(body, "数据迁移", 28f)
        text(body, "切换设备前，先让旧手机与仪表同步，再导出数据包。新手机导入后，绑定同一仪表并补齐后续记录。", 15f)
        text(body, "包含行程与修订、删除标记、加油记录、保养及日常费用、加油区间、自定义行程和车辆参数。系统权限、蓝牙配对与待执行仪表命令需要在新手机重新设置。", 14f)
        button(body, "导出完整数据包") {
            backupToExport = null
            prepareExport(null)
        }
        button(body, "导入并合并数据包") {
            openImportPicker()
        }
        button(body, "导入前备份") { showBackups() }
        text(body, "导入前会自动保留本机数据包。冲突可选择以数据包为准或保留本机；不会仅因数据包缺少某条记录就删除本机记录。手动拆分和加油历史版本冲突按整组处理。", 13f)
        text(body, "操作期间暂时断开手机与仪表的数据连接，完成后恢复。仪表继续自行记录。仪表仅保留最近 64 条行程，已被覆盖且未导出的记录无法补回。", 13f)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        body.addView(progress)
        status = text(body, savedInstanceState?.getString("status") ?: "尚未进行数据迁移。数据包未加密，请妥善保存。", 14f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(body: LinearLayout, value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(30, 36, 46))
        setPadding(0, dp(12), 0, dp(12))
        body.addView(this)
    }
    private fun button(body: LinearLayout, title: String, action: () -> Unit) {
        val button = Button(this).apply { text = title; isAllCaps = false; setOnClickListener { if (!working) action() } }
        body.addView(button, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(10) })
        actions += button
    }
    private fun timestamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private fun createDocument(name: String) {
        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, name)
        }, 101) } catch (_: RuntimeException) { showError("无法打开文件保存窗口") }
    }

    private fun openImportPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        try { startActivityForResult(picker, 102) }
        catch (_: android.content.ActivityNotFoundException) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, 102) } catch (_: RuntimeException) { showError("无法打开文件选择器，请检查系统文件应用") }
        } catch (_: RuntimeException) { showError("无法打开文件选择器") }
    }
    private fun prepareExport(backup: File?) = pauseSync {
        ioActive = true
        worker.execute {
            val result = runCatching {
                val file = File.createTempFile("BRZ-Garage-", ".zip", cacheDir)
                try {
                    file.outputStream().use { out ->
                        if (backup == null) TransferArchive.write(store.snapshot(), out)
                        else backup.inputStream().use { it.copyTo(out) }
                    }
                    file.inputStream().use(TransferArchive::read)
                    file.path
                } catch (failure: Exception) { file.delete(); throw failure }
            }
            ioActive = false
            handler.post {
                endSession()
                if (!isDestroyed) result.fold({ path ->
                    preparedExport?.let { File(it).delete() }
                    preparedExport = path
                    createDocument(backup?.name ?: "BRZ-Garage-${timestamp()}.zip")
                }, { showError(it.message ?: "无法生成数据包") })
                else result.getOrNull()?.let { File(it).delete() }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == 101) { preparedExport?.let { File(it).delete() }; preparedExport = null }
            return
        }
        val uri = data?.data ?: return
        if (requestCode == 101) pauseSync {
            val prepared = preparedExport ?: run { endSession(); showError("数据包已过期，请重新导出"); return@pauseSync }
            runWork {
                contentResolver.openOutputStream(uri, "w")?.use { out ->
                    File(prepared).inputStream().use { it.copyTo(out) }
                    out.flush()
                } ?: error("无法写入所选位置")
                File(prepared).delete()
                preparedExport = null
                "数据包已导出。请将此文件传到新设备，再选择“导入并合并数据包”。"
            }
        } else if (requestCode == 102) readImport { contentResolver.openInputStream(uri) ?: error("无法读取数据包") }
    }

    private fun pauseSync(next: () -> Unit) {
        if (working) return
        if (DataTransferSession.busy) { showError("另一项数据迁移尚未完成，请稍后重试"); return }
        if (AppState(this).firmwareUpdateActive) { showError("请等待仪表固件更新完成后再迁移数据"); return }
        ownsSession = true
        DataTransferSession.busy = true
        setWorking(true, "正在暂停仪表连接…")
        stopService(Intent(this, TripSyncService::class.java))
        val deadline = SystemClock.elapsedRealtime() + 10_000
        fun waitForStop() {
            if (!TripSyncService.runningForTransfer) next()
            else if (SystemClock.elapsedRealtime() >= deadline) {
                endSession(); showError("仪表连接尚未停止，请稍后重试")
            } else handler.postDelayed({ waitForStop() }, 100)
        }
        handler.post { waitForStop() }
    }
    private fun setWorking(value: Boolean, message: String? = null) {
        working = value
        actions.forEach { it.isEnabled = !value }
        progress.visibility = if (value) View.VISIBLE else View.GONE
        if (message != null) status.text = message
    }
    private fun endSession() {
        if (ownsSession) {
            ownsSession = false
            DataTransferSession.busy = false
            TripSyncService.start(applicationContext)
        }
        if (!isDestroyed) setWorking(false)
    }
    private fun runWork(job: () -> String) {
        ioActive = true
        worker.execute {
            val result = runCatching(job)
            ioActive = false
            handler.post {
                endSession()
                if (!isDestroyed) result.fold({ status.text = it }, { showError(it.message ?: "数据操作失败") })
            }
        }
    }
    private fun readImport(open: () -> java.io.InputStream) = pauseSync {
        status.text = "正在校验数据包并计算合并结果…"
        ioActive = true
        worker.execute {
            val result = runCatching {
                val snapshot = open().use(TransferArchive::read)
                val local = store.snapshot()
                Triple(snapshot, TransferMerge.merge(local, snapshot, true), TransferMerge.merge(local, snapshot, false))
            }
            ioActive = false
            handler.post {
                if (isDestroyed) { endSession(); return@post }
                result.fold({ (snapshot, incoming, local) -> confirmImport(snapshot, incoming, local) }, {
                    endSession(); showError("未导入任何记录：${it.message ?: "文件无效"}")
                })
            }
        }
    }
    private fun confirmImport(snapshot: TransferSnapshot, incoming: TransferMergeResult, local: TransferMergeResult) {
        var preferIncoming = true
        progress.visibility = View.GONE
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val info = text(body, incoming.summary(), 15f)
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(snapshot.createdAt))
        text(body, "导出时间：$exportedAt\n来源仪表：${snapshot.gauge.ifEmpty { "未绑定" }}\n冲突包括记录编辑、删除与拆分，以及车辆配置。导入前会自动备份本机数据。", 13f)
        val choices = android.widget.RadioGroup(this)
        listOf("以数据包为准（切换到此设备）", "保留本机冲突内容").forEachIndexed { index, label ->
            choices.addView(android.widget.RadioButton(this).apply { id = 300 + index; text = label })
        }
        choices.check(300)
        choices.setOnCheckedChangeListener { _, id ->
            preferIncoming = id == 300
            info.text = (if (preferIncoming) incoming else local).summary()
        }
        body.addView(choices)
        AlertDialog.Builder(this).setTitle("确认合并数据").setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("备份并导入") { _, _ ->
                setWorking(true, "正在备份并合并，请稍候…")
                runWork {
                    val result = store.import(snapshot, preferIncoming)
                    handler.post { imported = true; setResult(RESULT_OK) }
                    "导入完成\n${result.result.summary()}\n\n导入前备份已保存，可在“导入前备份”中导出或再次合并。\n请在设置中绑定来源仪表 ${snapshot.gauge}，随后会重新核对仪表保留的行程。"
                }
            }
            .setNegativeButton("取消") { _, _ -> endSession(); status.text = "已取消导入，本机数据未更改。" }
            .setOnCancelListener { endSession() }.show()
    }
    private fun showBackups() {
        val backups = store.backups()
        if (backups.isEmpty()) { status.text = "暂无导入前备份。首次导入时会自动创建。"; return }
        val names = backups.map { file ->
            val time = file.name.removePrefix("before-").substringBefore('-').toLongOrNull() ?: file.lastModified()
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("导入前备份").setItems(names) { _, which ->
            val file = backups[which]
            AlertDialog.Builder(this).setTitle(names[which])
                .setMessage("可以保存备份到文件，或按相同合并规则导入。合并备份不会清除之后新增的记录。")
                .setPositiveButton("导出备份") { _, _ -> backupToExport = file.path; prepareExport(file) }
                .setNeutralButton("合并此备份") { _, _ -> readImport { file.inputStream() } }
                .setNegativeButton("取消", null).show()
        }.setNegativeButton("关闭", null).show()
    }
    private fun showError(message: String) {
        status.text = message
        AlertDialog.Builder(this).setTitle("数据迁移未完成").setMessage(message).setPositiveButton("知道了", null).show()
    }
    override fun onBackPressed() { if (!working) super.onBackPressed() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("backup", backupToExport)
        outState.putString("preparedExport", preparedExport)
        outState.putString("status", status.text.toString())
        outState.putBoolean("imported", imported)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        // Queued IO keeps ownership until it finishes; premature reconnect could race an import.
        if (!ioActive) {
            handler.removeCallbacksAndMessages(null)
            endSession()
        }
        worker.shutdown()
        super.onDestroy()
    }
}
