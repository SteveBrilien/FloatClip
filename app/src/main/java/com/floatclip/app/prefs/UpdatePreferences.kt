package com.floatclip.app.prefs

import android.content.Context

class UpdatePreferences(context:Context) {
    private val prefs=context.getSharedPreferences("floatclip_update",Context.MODE_PRIVATE)
    fun checkOnLaunch()=prefs.getBoolean("check_on_launch",false)
    fun saveCheckOnLaunch(value:Boolean) { prefs.edit().putBoolean("check_on_launch",value).apply() }
    fun lastNotified()=prefs.getString("last_notified","").orEmpty().removePrefix("PREVIEW:")
    fun saveNotified(value:String) { prefs.edit().putString("last_notified",value).apply() }
    fun lastCheck(): String = prefs.getString("last_PREVIEW", "").orEmpty()
    fun saveLastCheck(version: String, build: Int) {
        val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        prefs.edit().putString("last_PREVIEW", "上次成功：$time · $version build $build").apply()
    }
}
