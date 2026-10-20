package com.floatclip.app.sync

import android.content.Context
import java.util.UUID

/** Desired policy is committed before the asynchronous service sees it. Trust lives separately. */
class SyncPreferences(context: Context) {
    private val prefs=context.getSharedPreferences("floatclip_sync",Context.MODE_PRIVATE)
    data class Policy(val enabled: Boolean,val reconnect: Boolean,val sending: Boolean,val receiving: Boolean,val applying: Boolean,
        val sendGeneration: String,val receiveGeneration: String,val applyGeneration: String,
        val appliedSend: String,val appliedReceive: String,val appliedApply: String)
    fun enabled()=prefs.getBoolean("enabled",false)
    fun reconnect()=prefs.getBoolean("reconnect",true)
    fun sending()=prefs.getBoolean("send",true)
    fun receiving()=prefs.getBoolean("receive",true)
    fun applying()=prefs.getBoolean("apply",true)
    fun policy(): Policy = synchronized(prefs) {
        Policy(enabled(),reconnect(),sending(),receiving(),applying(),
            prefs.getString("send_generation","initial")!!,prefs.getString("receive_generation","initial")!!,prefs.getString("apply_generation","initial")!!,
            prefs.getString("applied_send","")!!,prefs.getString("applied_receive","")!!,prefs.getString("applied_apply","")!!)
    }
    fun applied(value: Policy)=synchronized(prefs) {
        check(prefs.edit().putString("applied_send",value.sendGeneration).putString("applied_receive",value.receiveGeneration).putString("applied_apply",value.applyGeneration).commit()){ "共享权限批次未保存" }
    }
    fun save(enabled: Boolean=enabled(),reconnect: Boolean=reconnect(),sending: Boolean=sending(),receiving: Boolean=receiving(),applying: Boolean=applying()) = synchronized(prefs) {
        val editor=prefs.edit().putBoolean("enabled",enabled).putBoolean("reconnect",reconnect).putBoolean("send",sending).putBoolean("receive",receiving).putBoolean("apply",applying)
        if(enabled!=this.enabled()||sending!=this.sending())editor.putString("send_generation",UUID.randomUUID().toString())
        if(enabled!=this.enabled()||receiving!=this.receiving())editor.putString("receive_generation",UUID.randomUUID().toString())
        if(enabled!=this.enabled()||receiving!=this.receiving()||applying!=this.applying())editor.putString("apply_generation",UUID.randomUUID().toString())
        check(editor.commit()){ "无法保存共享开关" }
    }
}
