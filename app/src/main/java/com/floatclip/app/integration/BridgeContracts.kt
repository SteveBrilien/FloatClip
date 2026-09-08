package com.floatclip.app.integration

import android.content.Context

/** Stable contracts between FloatClip core and optional privileged integrations. */
data class CapturedClipboardItem(
    val text: String? = null,
    val uri: String? = null,
    val mimeType: String? = null,
    val sourcePackage: String? = null,
    val capturedAt: Long = System.currentTimeMillis(),
)

interface ClipboardBridge {
    val modeName: String
    fun readCurrent(): CapturedClipboardItem?
    fun writeText(text: String): Boolean
}

interface PasteBridge {
    val modeName: String
    fun pasteText(text: String): Boolean
}

data class OriginOsThemeSnapshot(
    val sourcePackage: String,
    val themeName: String? = null,
    val bubbleBackgroundArgb: Int? = null,
    val panelBackgroundArgb: Int? = null,
    val primaryTextArgb: Int? = null,
    val secondaryTextArgb: Int? = null,
    val cornerRadiusDp: Float? = null,
    val elevationDp: Float? = null,
    val metadata: Map<String, String> = emptyMap(),
)

interface OriginOsSystemBridge {
    val protocolVersion: Int
    fun isAvailable(): Boolean
    fun currentTheme(): OriginOsThemeSnapshot?
    fun registerClipboardObserver(observer: (CapturedClipboardItem) -> Unit): AutoCloseable?

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}

object NoOpOriginOsSystemBridge : OriginOsSystemBridge {
    override val protocolVersion: Int = OriginOsSystemBridge.PROTOCOL_VERSION
    override fun isAvailable(): Boolean = false
    override fun currentTheme(): OriginOsThemeSnapshot? = null
    override fun registerClipboardObserver(observer: (CapturedClipboardItem) -> Unit): AutoCloseable? = null
}

/** Process-local integration switch. A future privileged IPC client can replace this bridge. */
object IntegrationRegistry {
    @Volatile
    var originOsSystemBridge: OriginOsSystemBridge = NoOpOriginOsSystemBridge

    @Synchronized
    fun initialize(context: Context) {
        if (originOsSystemBridge === NoOpOriginOsSystemBridge) {
            originOsSystemBridge = LockedOriginOsSystemBridge(context.applicationContext)
        }
    }

    fun romLockResult(): OriginOsRomLockResult? =
        (originOsSystemBridge as? LockedOriginOsSystemBridge)?.lockResult
}
