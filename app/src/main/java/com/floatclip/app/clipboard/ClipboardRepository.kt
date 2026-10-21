package com.floatclip.app.clipboard

import android.content.Context
import com.floatclip.app.accessibility.PasteAccessibilityService
import com.floatclip.app.integration.AndroidClipboardBridge

class ClipboardRepository(context: Context) {
    private val appContext = context.applicationContext
    private val bridge = AndroidClipboardBridge(context)

    fun readCurrentText(forCapture: Boolean = false): String? {
        if(forCapture) {
            // A denied focused read must never fall back to yesterday's accessibility cache.
            val clip=appContext.getSystemService(android.content.ClipboardManager::class.java).primaryClip?:return null
            com.floatclip.app.sync.SyncRuntime.observe(appContext,clip)
            if(ClipboardWrites.isOwnClip(appContext,clip.description)||
                clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true)return null
            return if(clip.itemCount>0)clip.getItemAt(0).text?.toString() else null
        }
        return bridge.readCurrent()?.text ?: PasteAccessibilityService.readClipboardText(false)
    }

    fun writeText(text: String) {
        ClipboardWrites.copyByUser(appContext,appContext.getSystemService(android.content.ClipboardManager::class.java),text)
    }
}
