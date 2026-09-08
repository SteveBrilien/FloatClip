package com.floatclip.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

class PasteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    private fun pasteInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!target.isEditable) return false

        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("FloatClip", text))
        return target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    companion object {
        private var instance: WeakReference<PasteAccessibilityService>? = null

        fun paste(text: String): Boolean = instance?.get()?.pasteInternal(text) == true

        fun isConnected(): Boolean = instance?.get() != null
    }
}
