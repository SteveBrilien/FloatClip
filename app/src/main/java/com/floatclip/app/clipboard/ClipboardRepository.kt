package com.floatclip.app.clipboard

import android.content.Context
import com.floatclip.app.integration.AndroidClipboardBridge

class ClipboardRepository(context: Context) {
    private val bridge = AndroidClipboardBridge(context)

    fun readCurrentText(): String? = bridge.readCurrent()?.text

    fun writeText(text: String) {
        bridge.writeText(text)
    }
}
