package com.brz.gauge.trips

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

data class AppRelease(
    val version: String,
    val tag: String,
    val name: String,
    val apkName: String?,
    val apkUrl: String?,
    val apkSize: Long,
    val apkSha256: String?,
    val htmlUrl: String,
    val prerelease: Boolean
)

data class AppUpdateCheck(val release: AppRelease?, val message: String)

enum class AppDownloadStatus { NONE, DOWNLOADING, READY, FAILED, INSTALLED }

data class AppDownloadSnapshot(
    val status: AppDownloadStatus,
    val message: String,
    val progress: Int = 0,
    val downloadId: Long = -1L,
    val release: AppRelease? = null
)

enum class AppInstallResult { STARTED, NEED_PERMISSION }

object AppUpdater {
    private const val API_URL =
        "https://api.github.com/repos/sisi4376/BRZ-Garage/releases?per_page=10"
    private const val MIRROR_MANIFEST_URL =
        "https://ghfast.top/https://raw.githubusercontent.com/sisi4376/BRZ-Garage/main/android_app/update-manifest.json"
    private const val MIRROR_PREFIX = "https://ghfast.top/"
    private const val MIRROR_HOST = "ghfast.top"
    private const val RELEASE_HOST = "github.com"
    private const val RELEASE_PATH_PREFIX = "/sisi4376/BRZ-Garage/releases/download/"
    private const val TRUSTED_SIGNER_SHA256 =
        "08bab8a9c07f8e5b0efcfb39f6c9ee56bea08021c7e5b231be7efff0aaa43f50"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val PREFS = "app_updater"
    private const val MAX_RESPONSE_CHARS = 2_000_000
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun checkAsync(context: Context, callback: (Result<AppUpdateCheck>) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { fetchLatest(app) }
            main.post { callback(result) }
        }
    }

    private fun fetchLatest(@Suppress("UNUSED_PARAMETER") context: Context): AppUpdateCheck {
        val official = runCatching { fetchLatestFromGitHub() }
        if (official.isSuccess) return describeRelease(official.getOrThrow(), false)
        val mirror = runCatching { fetchLatestFromMirrorManifest() }
        if (mirror.isSuccess) return describeRelease(mirror.getOrThrow(), true)
        val officialMessage = official.exceptionOrNull()?.message ?: "连接失败"
        val mirrorMessage = mirror.exceptionOrNull()?.message ?: "连接失败"
        throw IllegalStateException(
            "GitHub 官方不可用（$officialMessage），国内镜像也不可用（$mirrorMessage）"
        )
    }

    private fun fetchLatestFromGitHub(): AppRelease? {
        val releases = JSONArray(readText(API_URL, true))
        var newest: AppRelease? = null
        for (index in 0 until releases.length()) {
            val item = releases.getJSONObject(index)
            if (item.optBoolean("draft", false)) continue
            val tag = item.optString("tag_name")
            val version = extractVersion(tag) ?: continue
            val assets = item.optJSONArray("assets") ?: JSONArray()
            var apkName: String? = null
            var apkUrl: String? = null
            var apkSize = 0L
            var apkSha256: String? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                val candidateName = asset.optString("name")
                if (!candidateName.endsWith(".apk", ignoreCase = true)) continue
                val candidateUrl = asset.optString("browser_download_url")
                if (!isTrustedReleaseUrl(candidateUrl)) continue
                apkName = candidateName
                apkUrl = candidateUrl
                apkSize = asset.optLong("size", 0L)
                apkSha256 = normalizeSha256(asset.optString("digest"))
                break
            }
            // Older releases may contain a direct release-download link in the notes.
            if (apkUrl == null) {
                val direct = APK_URL.find(item.optString("body"))?.value
                if (direct != null && isTrustedReleaseUrl(direct)) {
                    apkUrl = direct
                    apkName = Uri.parse(direct).lastPathSegment
                }
            }
            val candidate = AppRelease(
                version = version,
                tag = tag,
                name = item.optString("name").ifBlank { tag },
                apkName = apkName,
                apkUrl = apkUrl,
                apkSize = apkSize,
                apkSha256 = apkSha256,
                htmlUrl = item.optString("html_url"),
                prerelease = item.optBoolean("prerelease", false)
            )
            if (newest == null || compareVersions(candidate.version, newest.version) > 0)
                newest = candidate
        }
        return newest
    }

    private fun fetchLatestFromMirrorManifest(): AppRelease {
        val manifest = JSONObject(readText(MIRROR_MANIFEST_URL, false))
        val version = extractVersion(manifest.optString("version"))
            ?: throw IllegalStateException("镜像版本清单格式错误")
        val tag = "v$version"
        val apkUrl = manifest.optString("apk_url")
        if (!isTrustedReleaseUrl(apkUrl))
            throw IllegalStateException("镜像版本清单中的安装包地址无效")
        val apkSize = manifest.optLong("apk_size", 0L)
        if (apkSize !in 1..MAX_APK_BYTES)
            throw IllegalStateException("镜像版本清单中的安装包大小无效")
        val apkSha256 = normalizeSha256(manifest.optString("sha256"))
            ?: throw IllegalStateException("镜像版本清单缺少有效的 SHA-256")
        return AppRelease(
            version = version,
            tag = tag,
            name = manifest.optString("name").ifBlank { "BRZ Garage $tag" },
            apkName = manifest.optString("apk_name").ifBlank { Uri.parse(apkUrl).lastPathSegment },
            apkUrl = apkUrl,
            apkSize = apkSize,
            apkSha256 = apkSha256,
            htmlUrl = "https://github.com/sisi4376/BRZ-Garage/releases/tag/$tag",
            prerelease = manifest.optBoolean("prerelease", false)
        )
    }

    private fun readText(url: String, githubApi: Boolean): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            if (githubApi) {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            } else {
                setRequestProperty("Accept", "application/json")
            }
            setRequestProperty("User-Agent", "BRZ-Garage/${BuildConfig.VERSION_NAME}")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > MAX_RESPONSE_CHARS)
                        throw IllegalStateException("版本信息过大")
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun describeRelease(release: AppRelease?, fromMirror: Boolean): AppUpdateCheck {
        val source = if (fromMirror) "（通过国内镜像获取版本信息）" else ""
        release ?: return AppUpdateCheck(null, "暂无可识别的发布版本$source")
        if (compareVersions(release.version, BuildConfig.VERSION_NAME) <= 0)
            return AppUpdateCheck(null, "当前已是最新版本 v${BuildConfig.VERSION_NAME}$source")
        if (release.apkUrl == null)
            return AppUpdateCheck(release, "发现 v${release.version}，但该 Release 尚未附带可安装 APK$source")
        if (release.apkSize !in 0..MAX_APK_BYTES)
            throw IllegalStateException("发布的 APK 大小异常")
        return AppUpdateCheck(
            release,
            "发现新版本 v${release.version}${if (release.prerelease) "（测试版）" else ""}$source"
        )
    }

    fun startDownload(context: Context, release: AppRelease): Long {
        val url = release.apkUrl ?: throw IllegalArgumentException("该版本没有 APK")
        require(isTrustedReleaseUrl(url)) { "下载地址不属于本项目的 GitHub Release" }
        require(release.apkSize in 0..MAX_APK_BYTES) { "APK 大小异常" }
        val safeName = (release.apkName ?: "BRZ-Garage-v${release.version}.apk")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(safeName.endsWith(".apk", ignoreCase = true)) { "安装包扩展名错误" }
        val relativePath = "app-updates/$safeName"
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("手机存储当前不可用")
        val target = File(root, relativePath)
        target.parentFile?.mkdirs()
        if (target.exists() && !target.delete()) throw IllegalStateException("无法替换旧的下载文件")
        val manager = context.getSystemService(DownloadManager::class.java)
        val id = enqueueDownload(context, manager, url, release, relativePath, "GitHub 官方")
        prefs(context).edit()
            .putLong("download_id", id)
            .putString("version", release.version)
            .putString("tag", release.tag)
            .putString("name", release.name)
            .putString("apk_name", safeName)
            .putString("apk_url", url)
            .putLong("apk_size", release.apkSize)
            .putString("apk_sha256", release.apkSha256)
            .putString("html_url", release.htmlUrl)
            .putBoolean("prerelease", release.prerelease)
            .putString("apk_path", target.absolutePath)
            .putString("download_source", "official")
            .putBoolean("mirror_attempted", false)
            .apply()
        return id
    }

    private fun enqueueDownload(
        context: Context,
        manager: DownloadManager,
        url: String,
        release: AppRelease,
        relativePath: String,
        sourceName: String
    ): Long {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("BRZ Garage v${release.version}")
            .setDescription("正在从${sourceName}下载安装包")
            .setMimeType(APK_MIME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, relativePath)
        return manager.enqueue(request)
    }

    fun downloadState(context: Context): AppDownloadSnapshot {
        val stored = prefs(context)
        val id = stored.getLong("download_id", -1L)
        if (id < 0L) return AppDownloadSnapshot(AppDownloadStatus.NONE, "尚未下载更新")
        val release = storedRelease(context)
        if (release != null && compareVersions(release.version, BuildConfig.VERSION_NAME) <= 0) {
            return AppDownloadSnapshot(
                AppDownloadStatus.INSTALLED,
                "v${release.version} 已安装",
                100,
                id,
                release
            )
        }
        val manager = context.getSystemService(DownloadManager::class.java)
        val cursor = manager.query(DownloadManager.Query().setFilterById(id))
        cursor.use {
            if (!it.moveToFirst()) return fallbackToMirror(
                context, manager, stored, id, release, "系统找不到 GitHub 下载任务")
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val progress = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
            val mirrorSource = stored.getString("download_source", "official") == "mirror"
            val sourceName = if (mirrorSource) "国内镜像" else "GitHub 官方"
            return when (status) {
                DownloadManager.STATUS_PENDING -> AppDownloadSnapshot(
                    AppDownloadStatus.DOWNLOADING, "等待${sourceName}开始下载", progress, id, release)
                DownloadManager.STATUS_RUNNING -> AppDownloadSnapshot(
                    AppDownloadStatus.DOWNLOADING, "正在从${sourceName}下载 v${release?.version ?: ""}", progress, id, release)
                DownloadManager.STATUS_PAUSED -> if (mirrorSource) AppDownloadSnapshot(
                    AppDownloadStatus.DOWNLOADING, "$sourceName 下载已暂停，等待网络恢复", progress, id, release)
                else fallbackToMirror(
                    context, manager, stored, id, release, "GitHub 官方下载已暂停")
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val error = verifyDownloadedApk(context, release)
                    if (error == null) AppDownloadSnapshot(
                        AppDownloadStatus.READY, "下载完成，签名与版本校验通过", 100, id, release)
                    else AppDownloadSnapshot(AppDownloadStatus.FAILED, error, progress, id, release)
                }
                else -> fallbackToMirror(
                    context, manager, stored, id, release, "$sourceName 下载失败")
            }
        }
    }

    private fun fallbackToMirror(
        context: Context,
        manager: DownloadManager,
        stored: android.content.SharedPreferences,
        failedId: Long,
        release: AppRelease?,
        reason: String
    ): AppDownloadSnapshot {
        if (stored.getBoolean("mirror_attempted", false) ||
            stored.getString("download_source", "official") == "mirror") {
            return AppDownloadSnapshot(
                AppDownloadStatus.FAILED,
                "$reason；GitHub 官方和国内镜像均未完成下载，请稍后重试",
                downloadId = failedId,
                release = release
            )
        }
        val officialUrl = release?.apkUrl
        if (release == null || officialUrl == null || !isTrustedReleaseUrl(officialUrl)) {
            return AppDownloadSnapshot(
                AppDownloadStatus.FAILED, "$reason，请重新检查更新", downloadId = failedId, release = release)
        }
        val mirrorUrl = mirrorUrlFor(officialUrl)
        if (!isTrustedMirrorUrl(mirrorUrl, officialUrl)) {
            return AppDownloadSnapshot(
                AppDownloadStatus.FAILED, "$reason，镜像地址校验失败", downloadId = failedId, release = release)
        }
        manager.remove(failedId)
        val target = File(stored.getString("apk_path", "") ?: "")
        if (target.exists() && !target.delete()) {
            return AppDownloadSnapshot(
                AppDownloadStatus.FAILED, "$reason，无法清理未完成文件", downloadId = failedId, release = release)
        }
        val relativePath = "app-updates/${stored.getString("apk_name", release.apkName ?: "BRZ-Garage-v${release.version}.apk")}"
        return try {
            val mirrorId = enqueueDownload(context, manager, mirrorUrl, release, relativePath, "国内镜像")
            stored.edit()
                .putLong("download_id", mirrorId)
                .putString("download_source", "mirror")
                .putBoolean("mirror_attempted", true)
                .apply()
            AppDownloadSnapshot(
                AppDownloadStatus.DOWNLOADING,
                "$reason，已自动切换国内镜像",
                downloadId = mirrorId,
                release = release
            )
        } catch (error: RuntimeException) {
            stored.edit().putBoolean("mirror_attempted", true).apply()
            AppDownloadSnapshot(
                AppDownloadStatus.FAILED,
                "$reason，国内镜像也无法开始：${error.message ?: "系统错误"}",
                downloadId = failedId,
                release = release
            )
        }
    }

    fun installDownloaded(context: Context): AppInstallResult {
        val snapshot = downloadState(context)
        if (snapshot.status != AppDownloadStatus.READY)
            throw IllegalStateException(snapshot.message)
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls())
            return AppInstallResult.NEED_PERMISSION
        val manager = context.getSystemService(DownloadManager::class.java)
        val contentUri = manager.getUriForDownloadedFile(snapshot.downloadId)
            ?: throw IllegalStateException("系统无法读取已下载的安装包")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return AppInstallResult.STARTED
    }

    private fun verifyDownloadedApk(context: Context, release: AppRelease?): String? {
        if (release == null) return "缺少下载版本信息，请重新检查并下载"
        val stored = prefs(context)
        val apk = File(stored.getString("apk_path", "") ?: "")
        if (!apk.isFile || apk.length() <= 0L) return "安装包文件不存在，请重新下载"
        if (release.apkSize > 0L && apk.length() != release.apkSize) return "安装包大小校验失败"
        if (apk.length() > MAX_APK_BYTES) return "安装包大小异常"
        release.apkSha256?.let { expected ->
            val actual = apk.inputStream().buffered().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (!actual.equals(expected, ignoreCase = true)) return "安装包 SHA-256 校验失败，已拒绝安装"
        }
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return "下载内容不是有效的 Android 安装包"
        if (archive.packageName != context.packageName) return "安装包应用标识不匹配，已拒绝安装"
        if (archive.versionName?.let(::extractVersion) != release.version)
            return "安装包版本与 GitHub Release 不一致，已拒绝安装"
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        if (archive.longVersionCode <= installed.longVersionCode)
            return "下载的版本不高于当前版本"
        val installedSigners = signerDigests(installed)
        val archiveSigners = signerDigests(archive)
        if (installedSigners.isEmpty() || archiveSigners != installedSigners)
            return "安装包签名与当前 App 不一致，已拒绝安装"
        if (archiveSigners != setOf(TRUSTED_SIGNER_SHA256))
            return "安装包签名不是 BRZ Garage 固定证书，已拒绝安装"
        return null
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun storedRelease(context: Context): AppRelease? {
        val stored = prefs(context)
        val version = stored.getString("version", null) ?: return null
        return AppRelease(
            version,
            stored.getString("tag", "v$version") ?: "v$version",
            stored.getString("name", "BRZ Garage v$version") ?: "BRZ Garage v$version",
            stored.getString("apk_name", null),
            stored.getString("apk_url", null),
            stored.getLong("apk_size", 0L),
            stored.getString("apk_sha256", null),
            stored.getString("html_url", "") ?: "",
            stored.getBoolean("prerelease", false)
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isTrustedReleaseUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme == "https" && uri.host.equals(RELEASE_HOST, ignoreCase = true) &&
            uri.path?.startsWith(RELEASE_PATH_PREFIX) == true &&
            uri.path?.endsWith(".apk", ignoreCase = true) == true
    }.getOrDefault(false)

    private fun mirrorUrlFor(officialUrl: String): String = "$MIRROR_PREFIX$officialUrl"

    private fun isTrustedMirrorUrl(value: String, officialUrl: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme == "https" && uri.host.equals(MIRROR_HOST, ignoreCase = true) &&
            value == mirrorUrlFor(officialUrl) && isTrustedReleaseUrl(officialUrl)
    }.getOrDefault(false)

    private fun normalizeSha256(value: String): String? =
        SHA256.matchEntire(value.trim())?.groupValues?.get(1)?.lowercase()

    internal fun compareVersions(left: String, right: String): Int {
        val a = versionParts(left)
        val b = versionParts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    private fun versionParts(value: String): List<Int> =
        (extractVersion(value) ?: "0").split('.').map { it.toIntOrNull() ?: 0 }

    private fun extractVersion(value: String): String? = VERSION.find(value)?.groupValues?.get(1)

    private val VERSION = Regex("(?:^|[^0-9])(\\d+(?:\\.\\d+){1,3})(?:[^0-9]|$)")
    private val APK_URL = Regex(
        "https://github\\.com/sisi4376/BRZ-Garage/releases/download/[^\\s)\\]\\\"]+\\.apk",
        RegexOption.IGNORE_CASE
    )
    private val SHA256 = Regex("^(?:sha256:)?([0-9a-fA-F]{64})$")
}
