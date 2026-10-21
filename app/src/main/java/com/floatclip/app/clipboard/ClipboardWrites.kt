package com.floatclip.app.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import java.util.UUID

/** Identifies our write event, rather than suppressing any later copy of the same text. */
object ClipboardWrites {
    private const val PREFS = "clipboard_write_events"
    private const val TOKEN = "last_write"
    private const val EXTRA = "com.floatclip.app.OWN_WRITE"
    @Volatile private var lastObservedOwn = false

    fun write(context: Context, manager: ClipboardManager, text: String) {
        val token = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText("FloatClip", text)
        clip.description.extras = PersistableBundle().apply { putString(EXTRA, token) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN, token).apply()
        lastObservedOwn = true
        manager.setPrimaryClip(clip)
    }

    fun copyByUser(context: Context,manager: ClipboardManager,text: String) {
        write(context,manager,text)
        com.floatclip.app.sync.SyncRuntime.userCopied(context,text)
    }

    fun isOwnClip(context: Context, description: ClipDescription): Boolean {
        val marker = description.extras?.getString(EXTRA)
        val expected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN, null)
        return (marker != null && marker == expected).also { lastObservedOwn = it }
    }

    fun isCurrentOwn(context: Context): Boolean {
        val description = runCatching {
            context.getSystemService(ClipboardManager::class.java).primaryClipDescription
        }.getOrNull()
        return if (description != null) isOwnClip(context, description) else lastObservedOwn
    }
}
