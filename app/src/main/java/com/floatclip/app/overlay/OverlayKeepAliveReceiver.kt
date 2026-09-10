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
        return prefs.overlayEnabled() && prefs.keepAliveEnabled() && Settings.canDrawOverlays(context)
    }

    fun startIfNeeded(context: Context) {
        if (!shouldRun(context)) return
        if (PasteAccessibilityService.ensureOverlayHosted()) return
        runCatching {
            context.startForegroundService(Intent(context, ClipboardOverlayService::class.java))
        }
    }

    fun schedule(context: Context) {
        if (!shouldRun(context)) return
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, OverlayRestartReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarm.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
            pending,
        )
    }
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
