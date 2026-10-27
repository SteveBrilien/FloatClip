package com.floatclip.app.prefs

import android.content.Context

enum class UpdateChannel(val label:String) {
    PREVIEW("预览"),
    DEV("开发测试"),
}

class UpdatePreferences(context:Context) {
    private val prefs=context.getSharedPreferences("floatclip_update",Context.MODE_PRIVATE)
    fun channel():UpdateChannel = runCatching {
        UpdateChannel.valueOf(prefs.getString("channel",UpdateChannel.PREVIEW.name) ?: UpdateChannel.PREVIEW.name)
    }.getOrDefault(UpdateChannel.PREVIEW)
    fun lastCheck(channel:UpdateChannel):String = prefs.getString("last_"+channel.name,"")?:""
    fun saveLastCheck(channel:UpdateChannel,version:String,build:Int) {
        val time=java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.getDefault()).format(java.util.Date())
        prefs.edit().putString("last_"+channel.name,"上次成功："+time+" · "+version+" build "+build).apply()
    }
    fun saveChannel(value:UpdateChannel) {
        check(prefs.edit().putString("channel",value.name).commit()) { "无法保存更新通道" }
    }
}
