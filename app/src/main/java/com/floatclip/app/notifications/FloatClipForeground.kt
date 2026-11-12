package com.floatclip.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import com.floatclip.app.R
import com.floatclip.app.UnifiedActivity
import com.floatclip.app.overlay.ClipboardOverlayService
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.sync.ClipboardSyncService
import com.floatclip.app.sync.SyncPreferences

/**
 * Two long-lived services, but exactly one owner of one visible foreground notification.
 * Every startForegroundService entrant is promoted immediately (Android 8+ contract);
 * Both services remain true foreground services using the SAME package and notification ID.
 * The notification manager displays a single card for that (package, id) pair. When one
 * service exits, it detaches its notification association before the survivor republishes.
 *
 * All lifecycle calls run on the Android service main thread; no static Service is retained
 * after its onDestroy.
 */
object FloatClipForeground {
    private const val ID = 7080
    private const val OLD_OVERLAY_ID = 2001
    const val CHANNEL = "floatclip-main-service-v1"
    private var overlay: ClipboardOverlayService? = null
    private var sync: ClipboardSyncService? = null

    fun enter(service: Service) {
        when(service) {
            is ClipboardOverlayService -> overlay = service
            is ClipboardSyncService -> sync = service
            else -> return
        }
        val manager = service.getSystemService(NotificationManager::class.java)
        createChannel(manager)
        service.startForeground(ID, notification(service))
        // Do not stopForeground on an active secondary service: Android could reclaim it.
        // Both run with one shared id, so the notification is replaced rather than duplicated.
        manager.cancel(OLD_OVERLAY_ID) // Old installations displayed a second overlay card.
        refresh(service)
    }

    fun leave(service: Service) {
        val manager=service.getSystemService(NotificationManager::class.java)
        if(service===overlay) overlay=null
        if(service===sync) sync=null

        val survivor: Service? = sync ?: overlay
        // Detach rather than REMOVE while another service uses the same notification id.
        if(survivor != null) {
            service.stopForeground(Service.STOP_FOREGROUND_DETACH)
            survivor.startForeground(ID, notification(survivor))
            refresh(service)
        } else {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            manager.cancel(ID)
        }
    }

    fun refresh(context: Context) {
        if(sync==null && overlay==null) return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.notify(ID, notification(context))
    }

    private fun createChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL,"FloatClip 后台服务",NotificationManager.IMPORTANCE_LOW).apply {
                description = "悬浮剪贴板与设备共享共用一条通知"
                setShowBadge(false)
                setSound(null,null)
                enableVibration(false)
                lockscreenVisibility=Notification.VISIBILITY_SECRET
            }
        )
    }

    private fun notification(context: Context): Notification {
        val detailed=OverlayPreferences(context).detailedNotification()
        val overlayRunning=overlay!=null
        val syncRunning=sync!=null && SyncPreferences(context).enabled()
        val content=when {
            overlayRunning && syncRunning -> "悬浮剪贴板 · "+(sync?.status ?: "设备共享运行中")
            overlayRunning -> "悬浮剪贴板正在运行"
            syncRunning -> sync?.status ?: "设备共享正在运行"
            else -> "后台服务正在运行"
        }
        val open=PendingIntent.getActivity(
            context,7080,Intent(context,UnifiedActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(context,CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FloatClip")
            .setContentText(if(detailed) content else "")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }
}
