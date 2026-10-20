package com.floatclip.app.sync

import android.content.Context

/** Master is off until explicitly enabled; pairing trust survives pauses. */
class SyncPreferences(context: Context) {
    private val prefs=context.getSharedPreferences("floatclip_sync",Context.MODE_PRIVATE)
    fun enabled()=prefs.getBoolean("enabled",false)
    fun reconnect()=prefs.getBoolean("reconnect",true)
    fun sending()=prefs.getBoolean("send",true)
    fun receiving()=prefs.getBoolean("receive",true)
    fun applying()=prefs.getBoolean("apply",true)
    fun save(enabled: Boolean=enabled(),reconnect: Boolean=reconnect(),sending: Boolean=sending(),receiving: Boolean=receiving(),applying: Boolean=applying()) {
        check(prefs.edit().putBoolean("enabled",enabled).putBoolean("reconnect",reconnect).putBoolean("send",sending).putBoolean("receive",receiving).putBoolean("apply",applying).commit()) { "无法保存共享开关" }
    }
}
