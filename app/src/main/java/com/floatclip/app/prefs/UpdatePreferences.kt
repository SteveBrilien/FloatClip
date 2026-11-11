package com.floatclip.app.prefs

import android.content.Context

class UpdatePreferences(context:Context) {
    private val prefs=context.getSharedPreferences("floatclip_update",Context.MODE_PRIVATE)
    fun claimLaunchCheck(now:Long=System.currentTimeMillis()):Boolean {
        val last=prefs.getLong("last_update_attempt",0L)
        if(last>0 && now>=last && now-last<12L*60*60*1000)return false
        recordCheckAttempt(now)
        return true
    }
    fun recordCheckAttempt(now:Long=System.currentTimeMillis()) {
        prefs.edit().putLong("last_update_attempt",now).apply()
    }
    fun lastNotified()=prefs.getString("last_notified","").orEmpty().removePrefix("PREVIEW:")
    fun saveNotified(value:String) { prefs.edit().putString("last_notified",value).apply() }
    fun lastCheck(): String = prefs.getString("last_PREVIEW", "").orEmpty()
    fun saveLastCheck(version: String, build: Int) {
        val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        prefs.edit().putString("last_PREVIEW", "上次成功：$time · $version build $build").apply()
    }
}
