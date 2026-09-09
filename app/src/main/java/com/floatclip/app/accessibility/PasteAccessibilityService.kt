package com.floatclip.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

class PasteAccessibilityService : AccessibilityService() {
    private var clipboard: ClipboardManager? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    override fun onServiceConnected() {
        instance = WeakReference(this)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(clipboardListener)
        captureClipboard()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) captureClipboard()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipboardListener)
        clipboard = null
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    private fun captureClipboard() {
        val clip = runCatching { clipboard?.primaryClip }.getOrNull() ?: return
        val item = if (clip.itemCount > 0) clip.getItemAt(0) else return
        val text = item.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) latestText = text
    }

    private fun pasteInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!target.isEditable) return false
        val manager = clipboard ?: getSystemService(ClipboardManager::class.java)
        manager.setPrimaryClip(ClipData.newPlainText("FloatClip", text))
        latestText = text
        return target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun readClipboardInternal(): String? {
        captureClipboard()
        return latestText
    }

    companion object {
        private var instance: WeakReference<PasteAccessibilityService>? = null
        @Volatile private var latestText: String? = null

        fun paste(text: String): Boolean = instance?.get()?.pasteInternal(text) == true
        fun readClipboardText(): String? = instance?.get()?.readClipboardInternal() ?: latestText
        fun isConnected(): Boolean = instance?.get() != null
    }
}
