package com.floatclip.app

import android.net.Uri
import com.floatclip.app.prefs.OverlayThemeMode

/** Preserve the installed launcher name while opening the offline native clipboard. */
class UnifiedActivity : MainActivity() {
    // Retained for old bridge origin validation tests; no WebView is created by this launcher.
    fun applyBars(mode: OverlayThemeMode) = Unit
    companion object {
        const val ORIGIN = "https://clip.wmy-cloud.cn"
        fun isTrusted(value: String?): Boolean = runCatching {
            val uri=Uri.parse(value ?: "")
            uri.scheme=="https" && uri.host=="clip.wmy-cloud.cn" && uri.port in listOf(-1,443) && uri.userInfo==null
        }.getOrDefault(false)
    }
}
