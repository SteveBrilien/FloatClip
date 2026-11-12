package com.floatclip.app.overlay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.floatclip.app.accessibility.PasteAccessibilityService
import com.floatclip.app.prefs.OverlayPreferences

/** Best-effort service recovery for normal task removal, reboot and package replacement. */
object OverlayKeepAliveScheduler {
    private const val REQUEST_CODE = 2205
    private const val RESTART_DELAY_MS = 1_500L

    fun shouldRun(context: Context): Boolean {
        val prefs = OverlayPreferences(context)
        return prefs.overlayEnabled() && Settings.canDrawOverlays(context)
    }

    fun startIfNeeded(context: Context) {
        if (!shouldRun(context)) return
        PasteAccessibilityService.ensureOverlayHosted()
        runCatching {
            context.startForegroundService(Intent(context, ClipboardOverlayService::class.java))
        }
    }

    fun schedule(context: Context) {
        if (!shouldRun(context)) return
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = restartIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        alarm.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
            pending,
        )
    }

    fun cancel(context: Context) {
        val pending = restartIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }

    private fun restartIntent(context: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, OverlayRestartReceiver::class.java),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )
}

class OverlayRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        OverlayKeepAliveScheduler.startIfNeeded(context)
    }
}

class OverlayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> OverlayKeepAliveScheduler.startIfNeeded(context)
        }
    }
}
