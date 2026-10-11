package com.brz.gauge.trips

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Offline 3D hero. Only bundled assets are reachable; native code owns preferences and plates. */
class Vehicle3DView(context: Context) : FrameLayout(context) {
    private val fallback = VehicleHeroView(context)
    private val surface = FrameLayout(context)
    private val controls = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private var render3D = true
    private val status = TextView(context).apply {
        text = "正在加载 3D 车辆…"
        textSize = 10f
        setTextColor(Color.rgb(190, 207, 227))
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }
    private val prefs = context.getSharedPreferences("vehicle_appearance", Context.MODE_PRIVATE)
    private var model = SupportedVehicleModel.ZD8
    private var web: WebView? = null
    private var ready = false
    private var loaded = false
    private var lastPayload = ""
    private var plateText = ""
    private var plateVisible = false
    private var plateRevision = 0
    @Volatile private var platePng: ByteArray? = null
    private var appearanceDialog: AlertDialog? = null
    private val timeout = Runnable { showFallback() }

    init {
        addView(surface, LayoutParams(-1, -1).apply { bottomMargin = dp(36) })
        surface.addView(fallback, LayoutParams(-1, -1))
        addView(status, LayoutParams(-1, dp(24), Gravity.TOP))
        status.setOnClickListener { if (!loaded) { releaseWeb(); startWeb() } }
        listOf("前侧" to "front", "后侧" to "rear", "车牌" to "plate", "车色" to "color").forEach { (title, action) ->
            controls.addView(TextView(context).apply {
                text = title; textSize = 11f; gravity = Gravity.CENTER
                setTextColor(Color.rgb(216, 229, 245))
                isClickable = true; isFocusable = true
                contentDescription = if (action == "color") "设置车身颜色与漆面" else "3D 车辆$title 视角"
                setOnClickListener {
                    if (action == "color") showAppearance()
                    else web?.evaluateJavascript("window.BrzVehicle?.view(${JSONObject.quote(action)})", null)
                }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        addView(controls, LayoutParams(-1, dp(36), Gravity.BOTTOM))
    }

    fun setVehicleArtwork(bitmap: Bitmap?, vehicleModel: SupportedVehicleModel) {
        model = vehicleModel
        fallback.setVehicleArtwork(bitmap, vehicleModel)
        updateDescription()
        sendState()
    }

    fun set3DEnabled(enabled: Boolean) {
        render3D = enabled
        updateDescription()
        controls.visibility = if (enabled) VISIBLE else GONE
        surface.layoutParams = (surface.layoutParams as LayoutParams).apply {
            bottomMargin = if (enabled) dp(36) else 0
        }
        web?.visibility = if (enabled) VISIBLE else GONE
        fallback.visibility = if (!enabled || !loaded) VISIBLE else GONE
        status.visibility = if (enabled && !loaded) VISIBLE else GONE
        removeCallbacks(timeout)
        if (enabled && isAttachedToWindow && web == null) startWeb()
        else if (enabled && web != null && !loaded) postDelayed(timeout, 45_000)
        if (enabled) sendState()
        setActive(enabled)
    }

    private fun updateDescription() {
        contentDescription = model.heroDescription + if (render3D) "，拖动旋转，双指缩放" else "，2D 车辆图片"
    }

    fun showInstalledPlate(value: GeneratedLicensePlate?, visible: Boolean) {
        val text = value?.displayText.orEmpty()
        val show = visible && value != null
        if (text == plateText && show == plateVisible) return
        fallback.showInstalledPlate(value, visible)
        if (text != plateText) {
            // Reuse the app's vector glyphs instead of substituting a browser font.
            platePng = value?.let {
                val bitmap = Bitmap.createBitmap(2640, 840, Bitmap.Config.ARGB_8888)
                try {
                    LicensePlateArtwork.draw(context, Canvas(bitmap), it, RectF(0f, 0f, 2640f, 840f))
                    ByteArrayOutputStream().use { bytes ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                        bytes.toByteArray()
                    }
                } finally { bitmap.recycle() }
            }
            plateRevision++
        }
        plateText = text; plateVisible = show
        sendState()
    }

    private fun key(suffix: String) = "${model.name}_$suffix"
    private fun sendState() {
        val target = web ?: return
        if (!ready || !render3D) return
        val payload = JSONObject().apply {
            put("model", model.name.lowercase(Locale.ROOT))
            put("color", prefs.getString(key("color"), "#155dcc"))
            put("finish", prefs.getString(key("finish"), "gloss"))
            put("plate", plateText); put("visible", plateVisible)
            put("plateUrl", if (platePng == null) "" else "$ORIGIN/plate.png?v=$plateRevision")
        }.toString()
        if (payload == lastPayload) return
        lastPayload = payload
        target.evaluateJavascript("window.BrzVehicle?.apply($payload)", null)
    }

    private fun showAppearance() {
        val names = arrayOf("拉力蓝", "珍珠白", "烈焰红", "石墨灰", "曜石黑", "冰银", "切换亮面 / 哑光", "模型来源与许可")
        val colors = arrayOf("#155dcc", "#e7e9e8", "#b91930", "#565d66", "#141a22", "#a4b1bf")
        appearanceDialog = AlertDialog.Builder(context).setTitle("${model.name} 车身外观")
            .setItems(names) { _, index ->
                when {
                    index < colors.size -> prefs.edit().putString(key("color"), colors[index]).apply()
                    index == 6 -> prefs.edit().putString(key("finish"),
                        if (prefs.getString(key("finish"), "gloss") == "gloss") "satin" else "gloss").apply()
                    else -> {
                        val id = if (model == SupportedVehicleModel.ZD8) "2e8aca0407ae44d2802bc34761054a69" else "321a9ece66bb4616872892c01d279542"
                        val credits = TextView(context).apply {
                            text = "Subaru BRZ (${model.name})\nMona x Supercars / GT Cars: Hyperspeed\nhttps://sketchfab.com/Car2022\n\nhttps://sketchfab.com/3d-models/$id\n\nCC BY 4.0\nhttps://creativecommons.org/licenses/by/4.0/\n\n修改：清理辅助几何、合并 GLB、替换车漆、添加前后车牌。\nThree.js 0.160.1 · MIT；原始许可随 App 内置。"
                            textSize = 13f; setPadding(dp(20), dp(12), dp(20), dp(12))
                            autoLinkMask = android.text.util.Linkify.WEB_URLS
                            movementMethod = android.text.method.LinkMovementMethod.getInstance()
                        }
                        appearanceDialog = AlertDialog.Builder(context).setTitle("3D 模型署名").setView(credits)
                            .setPositiveButton("关闭", null).show()
                    }
                }
                sendState()
            }.setNegativeButton("取消", null).show()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun startWeb() {
        if (!render3D || web != null || !isAttachedToWindow) return
        ready = false; loaded = false; lastPayload = ""
        status.text = "正在加载 3D 车辆…"; status.visibility = VISIBLE
        fallback.visibility = VISIBLE
        try {
            val target = WebView(context)
            web = target
            target.setBackgroundColor(Color.TRANSPARENT)
            target.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                // Assets are local; avoid retaining scripts from a previously installed APK.
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                blockNetworkLoads = true
                setSupportZoom(false)
            }
            target.isVerticalScrollBarEnabled = false
            target.isHorizontalScrollBarEnabled = false
            target.setOnTouchListener { _, event ->
                // A gesture beginning on the car rotates it; the rest of the page still scrolls.
                parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                false
            }
            target.addJavascriptInterface(object {
                @JavascriptInterface fun ready() { post {
                    if (web !== target) return@post
                    ready = true; sendState(); setActive(windowVisibility == VISIBLE)
                } }
                @JavascriptInterface fun loaded() { post {
                    if (web !== target) return@post
                    loaded = true; removeCallbacks(timeout)
                    fallback.visibility = if (render3D) GONE else VISIBLE; status.visibility = GONE
                } }
                @JavascriptInterface fun failed() { post { if (web === target) showFallback() } }
            }, "BrzHost")
            target.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val url = request.url
                    if (url.scheme != "https" || url.host != "appassets.androidplatform.net" || request.method != "GET") return blocked()
                    if (url.path == "/plate.png") {
                        val bytes = platePng ?: return blocked()
                        return WebResourceResponse("image/png", null, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
                    }
                    val path = url.path?.removePrefix("/") ?: return blocked()
                    if (!path.startsWith("vehicle-3d/") || path.split('/').any { it == ".." || it == "." } || path.contains('\\')) return blocked()
                    val mime = when (path.substringAfterLast('.')) {
                        "html" -> "text/html"; "js", "mjs" -> "text/javascript"; "glb" -> "model/gltf-binary"
                        "json" -> "application/json"; "txt" -> "text/plain"; else -> return blocked()
                    }
                    return try { WebResourceResponse(mime, if (mime == "model/gltf-binary") null else "UTF-8", context.assets.open(path)) }
                    catch (_: Exception) { blocked() }
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (web === view) showFallback()
                    return true
                }
            }
            surface.addView(target, LayoutParams(-1, -1))
            target.loadUrl("$ORIGIN/vehicle-3d/app.html")
            postDelayed(timeout, 45_000)
        } catch (_: RuntimeException) { showFallback() }
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun showFallback() {
        releaseWeb()
        fallback.visibility = VISIBLE
        status.text = "3D 暂不可用 · 点击重试"; status.visibility = if (render3D) VISIBLE else GONE
    }
    private var presentationActive = true
    private fun setActive(requested: Boolean) {
        val active = requested && isAttachedToWindow && presentationActive && render3D
        web?.let {
            if (active) it.onResume()
            if (ready) it.evaluateJavascript("window.BrzVehicle?.setActive($active)", null)
            if (!active) it.onPause()
        }
    }
    private fun releaseWeb() {
        removeCallbacks(timeout)
        val previous = web; web = null; ready = false; loaded = false; lastPayload = ""
        previous?.let { surface.removeView(it); it.removeJavascriptInterface("BrzHost"); it.stopLoading(); it.destroy() }
    }
    fun pausePresentation() { presentationActive = false; setActive(false) }
    fun resumePresentation() { presentationActive = true; if (isAttachedToWindow) setActive(true) }
    fun dispose() { appearanceDialog?.dismiss(); appearanceDialog = null; releaseWeb() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (render3D && web == null) startWeb() else setActive(render3D)
    }
    override fun onDetachedFromWindow() {
        appearanceDialog?.dismiss(); appearanceDialog = null
        // MainActivity retains this view across tabs: keep the decoded model and GPU resources.
        setActive(false); super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility); setActive(visibility == VISIBLE)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val ORIGIN = "https://appassets.androidplatform.net" }
}
