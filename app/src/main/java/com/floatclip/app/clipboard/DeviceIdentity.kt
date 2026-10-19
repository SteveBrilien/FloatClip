package com.floatclip.app.clipboard

import android.content.Context
import android.os.Build
import java.util.UUID
import org.json.JSONObject

object DeviceIdentity {
    @Synchronized fun id(context: Context): String {
        val prefs = context.getSharedPreferences("floatclip_device", Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("id", it).commit()) { "无法保存设备标识" }
        }
    }
    fun json(context: Context) = JSONObject().put("id", id(context))
        .put("name", Build.MODEL.take(40).ifBlank { "我的手机" }).put("platform", "android")
}
