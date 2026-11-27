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
            @JavascriptInterface fun status(): String {
                val pending = state.pendingDisplay(4, device)
                val layout = GaugeLayout.decode(pending)
                val settings = state.gaugeSettings()
                return when {
                    layout?.usesThreeSlotGps == true && settings?.threeSlotGpsSupported == false ->
                        "已保存在手机 · 三槽位 GPS 需新版固件"
                    layout?.usesFourSlots == true && settings?.fourSlotLayoutSupported == false ->
                        "已保存在手机 · 四槽位需新版固件"
                    pending != null -> "已保存在手机 · 待支持页面编排的仪表回读"
                    state.prefs.contains("page_layout_" + device.uppercase()) -> "已读取仪表页面"
                    else -> "初始编排 · 连接新版仪表后读取"
                }
            }
            @JavascriptInterface fun boot(): String {
                val settings = state.gaugeSettings()
                val startup = state.pendingDisplay(2, device)?.toIntOrNull() ?: settings?.defaultPage ?: -1
                val orientation = state.pendingDisplay(3, device)?.toIntOrNull() ?: settings?.defaultOrientation ?: -1
                return "$startup,$orientation"
            }
            @JavascriptInterface fun bootStatus(): String {
                val settings = state.gaugeSettings()
                val pendingPage = state.pendingDisplay(2, device)?.toIntOrNull()
                val pendingOrientation = state.pendingDisplay(3, device) != null
                return when {
                    pendingPage != null && pendingPage > 8 && settings?.anyStartupPageSupported == false ->
                        "已保存在手机 · 此启动页需新版仪表固件。"
                    settings?.bootSettingsSupported == false ->
                        "请升级仪表固件以同步启动页和朝向。"
                    pendingPage != null || pendingOrientation ->
                        "已保存在手机 · 待仪表回读确认，下次上电生效。"
                    else -> ""
                }
            }
            @JavascriptInterface fun setBoot(command: Int, value: Int) {
                if (command !in 2..3 || !TripBleProtocol.DisplaySetting(command, value).valid) return
                update {
                    if (command == 3 || value == GaugeLayout.SYSTEM_PAGE || value in state.editorLayout(device).pages) {
                        state.queueDisplay(command, value.toString(), device)
                    }
                }
            }
            @JavascriptInterface fun save(value: String, replacementStartup: Int) {
                val layout = GaugeLayout.decode(value) ?: return
                val startup = replacementStartup.takeIf { it == GaugeLayout.SYSTEM_PAGE || it in layout.pages }
                update { state.queueGaugeLayout(layout, startup, device) }
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
    private fun update(save: () -> Unit) = runOnUiThread {
        if (state.dataDeviceId != device) {
            finish()
            return@runOnUiThread
        }
        save()
        TripSyncService.wakeDisplayQueue(this, state)
    }

    override fun onDestroy() {
        web.removeJavascriptInterface("GaugePhone")
        web.destroy()
        super.onDestroy()
    }
}
