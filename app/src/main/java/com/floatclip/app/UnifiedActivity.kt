package com.floatclip.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.*
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.prefs.OverlayThemeMode

/** Shared 0.6 UI with a narrow, origin-checked bridge to the existing native vault. */
class UnifiedActivity : Activity() {
    private var web: WebView? = null
    private var bridge: NativeBridge? = null
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var trusted = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        applyBars(OverlayPreferences(this).themeMode())
        try { bridge = NativeBridge(this, ::openLegacy); openWeb() }
        catch (_: Throwable) { fallback("共享页面暂不可用，本机剪贴板仍可使用") }
    }
    fun applyBars(mode: OverlayThemeMode) {
        val dark = mode == OverlayThemeMode.DARK || mode == OverlayThemeMode.SYSTEM && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = Color.parseColor(if (dark) "#0C1422" else "#F4F7FC")
        window.navigationBarColor = Color.parseColor(if (dark) "#162235" else "#FFFFFF")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun openWeb() {
        web?.destroy()
        val view = WebView(this); view.setBackgroundColor(window.statusBarColor); web = view
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
            userAgentString = userAgentString + " FloatClipNative/1 Android/0.6.0"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) { trusted = isTrusted(url) }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (isTrusted(request.url.toString())) return false
                if (request.isForMainFrame && request.hasGesture() && request.url.scheme == "https") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                }
                return true
            }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { trusted = false; fallback("无法打开共享页面，请检查网络或先使用本机功能") }
            }
            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) { trusted = false; fallback("共享节点暂不可用，请稍后重试") }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onJsPrompt(v: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                if (!trusted || !isTrusted(v.url) || !isTrusted(url) || message?.startsWith(PREFIX) != true) { result.cancel(); return true }
                result.confirm(bridge?.handle(message.removePrefix(PREFIX)) ?: ""); return true
            }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                if (!trusted || !isTrusted(v.url)) { callback.onReceiveValue(null); return true }
                chooser?.onReceiveValue(null); chooser = callback
                return try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_FILE); true
                } catch (_: Exception) { chooser = null; callback.onReceiveValue(null); true }
            }
        }
        setContentView(view)
        view.loadUrl(ORIGIN + "/")
    }
    private fun fallback(message: String) {
        trusted = false
        val mode = OverlayPreferences(this).themeMode()
        val dark = mode == OverlayThemeMode.DARK || mode == OverlayThemeMode.SYSTEM && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(40, 40, 40, 40); setBackgroundColor(Color.parseColor(if (dark) "#0C1422" else "#F4F7FC")) }
        root.addView(TextView(this).apply { text = "FloatClip"; textSize = 27f; setTextColor(Color.parseColor(if (dark) "#EDF3FC" else "#19283F")) })
        root.addView(TextView(this).apply { text = message; textSize = 15f; setPadding(0, 25, 0, 25); setTextColor(Color.parseColor(if (dark) "#A9B8CD" else "#596C85")) })
        listOf("重新打开共享" to { openWeb() }, "悬浮球与本机剪贴板" to { openLegacy("clipboard") }, "局域网兼容模式" to { openLegacy("lan") }).forEach { (label, action) ->
            root.addView(Button(this).apply { text = label; setOnClickListener { runCatching { action() }.onFailure { Toast.makeText(this@UnifiedActivity, "暂时无法打开，请稍后重试", Toast.LENGTH_LONG).show() } } })
        }
        setContentView(root)
    }
    private fun openLegacy(page: String) {
        val target = if (page == "lan") com.floatclip.probe.ShareActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target))
    }
    @Deprecated("Fixed Android 11 activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE) { chooser?.onReceiveValue(if (resultCode == RESULT_OK && data?.data?.scheme == "content") arrayOf(data.data!!) else null); chooser = null }
        if (requestCode == NativeBridge.SAVE_FILE) {
            try { val message = bridge?.saveResult(if (resultCode == RESULT_OK) data?.data else null); Toast.makeText(this, message ?: "保存已结束", Toast.LENGTH_SHORT).show() }
            catch (_: Exception) { Toast.makeText(this, "文件未保存，请重新选择位置", Toast.LENGTH_LONG).show() }
        }
    }
    @Deprecated("Fixed Android 11 navigation")
    override fun onBackPressed() { if (web?.canGoBack() == true) web?.goBack() else super.onBackPressed() }
    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        applyBars(OverlayPreferences(this).themeMode())
        if (trusted && isTrusted(web?.url)) web?.evaluateJavascript("window.dispatchEvent(new Event('floatclip-host-changed'))", null)
    }
    override fun onPause() { runCatching { CookieManager.getInstance().flush() }; super.onPause() }
    override fun onResume() {
        super.onResume()
        if (trusted && isTrusted(web?.url)) web?.evaluateJavascript("window.dispatchEvent(new Event('floatclip-vault-changed'))", null)
    }
    override fun onDestroy() { chooser?.onReceiveValue(null); chooser = null; bridge?.close(); web?.destroy(); web = null; super.onDestroy() }
    companion object {
        const val ORIGIN = "https://clip.wmy-cloud.cn"
        const val PREFIX = "FloatClip/Native/v1:"
        private const val PICK_FILE = 6060
        fun isTrusted(value: String?): Boolean = runCatching {
            val uri = Uri.parse(value ?: "")
            uri.scheme == "https" && uri.host == "clip.wmy-cloud.cn" && uri.port in listOf(-1, 443) && uri.userInfo == null
        }.getOrDefault(false)
    }
}
