package com.floatclip.app.integration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.floatclip.app.accessibility.PasteAccessibilityService

class AndroidClipboardBridge(context: Context) : ClipboardBridge {
    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)

    override val modeName: String = "android-public"

    override fun readCurrent(): CapturedClipboardItem? {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val description = clip.description
        val item = clip.getItemAt(0)
        val text = item.coerceToText(appContext)?.toString()?.takeIf { it.isNotBlank() }
        val uri = item.uri?.toString()
        if (text == null && uri == null) return null
        val mimeType = if (description != null && description.mimeTypeCount > 0) {
            description.getMimeType(0)
        } else {
            null
        }
        return CapturedClipboardItem(
            text = text,
            uri = uri,
            mimeType = mimeType,
        )
    }

    override fun writeText(text: String): Boolean = runCatching {
        clipboard.setPrimaryClip(ClipData.newPlainText("FloatClip", text))
        true
    }.getOrDefault(false)
}

object AccessibilityPasteBridge : PasteBridge {
    override val modeName: String = "accessibility"
    override fun pasteText(text: String): Boolean = PasteAccessibilityService.paste(text)
}
