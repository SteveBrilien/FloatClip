package com.floatclip.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SyncBootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        val prefs=SyncPreferences(context)
        if(prefs.enabled()&&prefs.reconnect())runCatching{SyncRuntime.refresh(context)}
    }
}
