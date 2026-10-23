package com.floatclip.app.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Best-effort recovery when the launcher task is removed or the process is reclaimed.
 * This does not and cannot bypass Android's explicit Force stop state.
 */
object SyncKeepAliveScheduler {
    private const val REQUEST_CODE = 7082
    private const val RESTART_DELAY_MS = 1_500L

    fun shouldRun(context: Context): Boolean {
        val prefs=SyncPreferences(context)
        return prefs.enabled() && prefs.keepAlive()
    }

    fun startIfNeeded(context: Context) {
        if(!shouldRun(context))return
        runCatching {
            context.startForegroundService(Intent(context, ClipboardSyncService::class.java))
        }
    }

    fun schedule(context: Context) {
        if(!shouldRun(context))return
        val alarm=context.getSystemService(AlarmManager::class.java) ?: return
        val intent=Intent(context,SyncRestartReceiver::class.java)
        val pending=PendingIntent.getBroadcast(
            context,REQUEST_CODE,intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarm.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime()+RESTART_DELAY_MS,
            pending,
        )
    }
}

class SyncRestartReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent?) {
        SyncKeepAliveScheduler.startIfNeeded(context)
    }
}
