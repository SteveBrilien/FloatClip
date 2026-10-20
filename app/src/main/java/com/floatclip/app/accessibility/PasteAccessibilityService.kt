package com.floatclip.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.floatclip.app.overlay.ClipboardOverlayService
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.clipboard.ClipboardWrites
import java.lang.ref.WeakReference

class PasteAccessibilityService : AccessibilityService() {
    private var clipboard: ClipboardManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }
    private var overlayBindingRegistered = false
    private var overlayConnected = false

    private val overlayConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            overlayConnected = true
            (service as? ClipboardOverlayService.LocalBinder)?.useAccessibilityHost()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            overlayConnected = false
        }

        override fun onBindingDied(name: ComponentName?) {
            overlayConnected = false
            overlayBindingRegistered = false
            if (shouldHostOverlay()) handler.postDelayed({ ensureOverlayBinding() }, 900L)
        }
    }

    override fun onServiceConnected() {
        instance = WeakReference(this)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(clipboardListener)
        captureClipboard()
        ensureOverlayBinding()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) captureClipboard()
        if (!overlayBindingRegistered && shouldHostOverlay()) ensureOverlayBinding()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        clipboard?.removePrimaryClipChangedListener(clipboardListener)
        clipboard = null
        releaseOverlayBinding()
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    private fun shouldHostOverlay(): Boolean {
        val prefs = OverlayPreferences(this)
        return prefs.overlayEnabled() && Settings.canDrawOverlays(this)
    }

    private fun ensureOverlayBinding(): Boolean {
        if (!shouldHostOverlay()) return false
        if (overlayBindingRegistered) return true
        val intent = Intent(this, ClipboardOverlayService::class.java)
        overlayBindingRegistered = runCatching {
            bindService(intent, overlayConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        return overlayBindingRegistered
    }

    private fun releaseOverlayBinding() {
        if (!overlayBindingRegistered) return
        runCatching { unbindService(overlayConnection) }
        overlayBindingRegistered = false
        overlayConnected = false
    }

    private fun captureClipboard() {
        val clip = runCatching { clipboard?.primaryClip }.getOrNull() ?: return
        latestOwnWrite = ClipboardWrites.isOwnClip(this, clip.description)
        val item = if (clip.itemCount > 0) clip.getItemAt(0) else return
        val text = item.coerceToText(this)?.toString()?.trim().orEmpty()
        latestText = text.takeIf { it.isNotEmpty() }
    }

    private fun pasteInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!target.isEditable) return false
        val manager = clipboard ?: getSystemService(ClipboardManager::class.java)
        ClipboardWrites.write(this, manager, text)
        latestOwnWrite = true
        latestText = text
        return target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun readClipboardInternal(forCapture: Boolean): String? {
        captureClipboard()
        return if (forCapture && latestOwnWrite) null else latestText
    }

    companion object {
        private var instance: WeakReference<PasteAccessibilityService>? = null
        @Volatile private var latestText: String? = null
        @Volatile private var latestOwnWrite = false

        fun paste(text: String): Boolean = instance?.get()?.pasteInternal(text) == true
        fun readClipboardText(forCapture: Boolean = false): String? {
            val service = instance?.get()
            return if (service != null) service.readClipboardInternal(forCapture)
                else if (forCapture && latestOwnWrite) null else latestText
        }
        fun isConnected(): Boolean = instance?.get() != null
        fun ensureOverlayHosted(): Boolean = instance?.get()?.ensureOverlayBinding() == true
        fun releaseOverlayHosted() { instance?.get()?.releaseOverlayBinding() }
    }
}
