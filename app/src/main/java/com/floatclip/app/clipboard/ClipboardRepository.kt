package com.floatclip.app.clipboard

import android.content.Context
import com.floatclip.app.accessibility.PasteAccessibilityService
import com.floatclip.app.integration.AndroidClipboardBridge

class ClipboardRepository(context: Context) {
    private val appContext = context.applicationContext
    private val bridge = AndroidClipboardBridge(context)

    fun readCurrentText(forCapture: Boolean = false): String? {
        val current = bridge.readCurrent()?.text
        if (forCapture && ClipboardWrites.isCurrentOwn(appContext)) return null
        return current ?: PasteAccessibilityService.readClipboardText(forCapture)
    }

    fun writeText(text: String) {
        bridge.writeText(text)
    }
}
