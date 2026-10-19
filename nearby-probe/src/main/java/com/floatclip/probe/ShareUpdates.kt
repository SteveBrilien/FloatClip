package com.floatclip.probe

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Explicit user initiated checks. Downloads open in the system browser. */
internal object ShareUpdates {
    const val BUILD = 8
    const val VERSION = "0.4.0"
    const val FEED = "https://raw.githubusercontent.com/SteveBrilien/FloatClip/main/updates/share-preview.json"
    data class Release(val build: Int, val version: String, val notes: String, val url: String)
    fun check(): Release {
        val connection = URL(FEED).openConnection() as HttpsURLConnection
        connection.connectTimeout = 10000; connection.readTimeout = 10000
        connection.useCaches = false; connection.setRequestProperty("User-Agent", "FloatClip-Share/$VERSION")
        try {
            check(connection.responseCode == 200) { "更新服务暂不可用" }
            val bytes = connection.inputStream.use { input ->
                val data=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                var n=input.read(buffer)
                while(n>=0) { check(data.size()+n<=65536); data.write(buffer,0,n); n=input.read(buffer) }
                data.toByteArray()
            }
            check(bytes.size <= 65536) { "更新信息过大" }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            check(json.getInt("schema") == 1 && json.getString("channel") == "share-preview")
            val platform = json.getJSONObject("android")
            val url = platform.getString("url"); val target = URL(url)
            check(target.protocol == "https" && target.userInfo == null && target.port == -1 && target.host in listOf("mcp.wmy-cloud.cn", "github.com"))
            val version = json.getString("version"); check(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            val build = platform.getInt("build"); check(build > 0)
            return Release(build, version, json.optString("notes").take(1200), url)
        } finally { connection.disconnect() }
    }
}
