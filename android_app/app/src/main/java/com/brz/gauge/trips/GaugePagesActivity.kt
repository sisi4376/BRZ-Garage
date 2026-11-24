package com.brz.gauge.trips

import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class GaugePagesActivity : Activity() {
    private lateinit var state: AppState
    private lateinit var device: String
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AppState(this)
        device = state.dataDeviceId
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface fun initial() = state.editorLayout(device).encode()
            @JavascriptInterface fun status(): String = when {
                state.pendingDisplay(4, device) != null -> "已保存在手机 · 待支持页面编排的仪表回读"
                state.prefs.contains("page_layout_" + device.uppercase()) -> "已读取仪表页面"
                else -> "初始编排 · 连接新版仪表后读取"
            }
            @JavascriptInterface fun save(value: String) {
                val layout = GaugeLayout.decode(value) ?: return
                runOnUiThread {
                    if (state.dataDeviceId != device) { finish(); return@runOnUiThread }
                    state.queueDisplay(4, layout.encode(), device)
                    TripSyncService.wakeDisplayQueue(this@GaugePagesActivity, state)
                }
            }
            @JavascriptInterface fun back() = runOnUiThread { finish() }
        }, "GaugePhone")
        web.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/gauge-pages/index.html")
    }
    override fun onDestroy() {
        web.removeJavascriptInterface("GaugePhone")
        web.destroy()
        super.onDestroy()
    }
}
