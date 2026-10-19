package com.floatclip.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.clipboard.ClipboardRepository
import com.floatclip.app.clipboard.DeviceIdentity
import com.floatclip.app.overlay.ClipboardOverlayService
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.prefs.OverlayThemeMode
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** No generic host objects, file paths, or command execution are exposed to web content. */
class NativeBridge(private val activity: Activity, private val legacy: (String) -> Unit) {
    private val store = ClipStore(activity)
    private val clipboard = ClipboardRepository(activity)
    private var transfer: Transfer? = null
    private var waitingDestination: Transfer? = null
    private data class Transfer(val token: String, val name: String, val size: Long, val file: File, var bytes: Long = 0)

    fun handle(raw: String): String {
        var id = ""
        return try {
            require(raw.toByteArray(Charsets.UTF_8).size <= 18 * 1024 * 1024) { "本机请求过大" }
            val request = JSONObject(raw)
            id = request.getString("id")
            require(id.matches(Regex("[-a-zA-Z0-9]{1,64}")))
            val result = invoke(request.getString("op"), request.optJSONObject("args") ?: JSONObject())
            JSONObject().put("id", id).put("ok", true).put("data", result ?: JSONObject.NULL).toString()
        } catch (e: Exception) {
            val message = if (e.message == "VAULT_CONFLICT") "VAULT_CONFLICT" else when (e) {
                is IllegalArgumentException, is IllegalStateException -> e.message?.take(160) ?: "本机请求无效"
                else -> "本机操作未完成，请检查剪贴板或存储权限"
            }
            JSONObject().put("id", id).put("ok", false).put("error", message).toString()
        }
    }

    private fun invoke(op: String, args: JSONObject): Any? = when (op) {
        "info" -> JSONObject().put("bridge", 1).put("version", activity.packageManager.getPackageInfo(activity.packageName, 0).versionName)
            .put("platform", "android").put("device", DeviceIdentity.json(activity)).put("legacy", true)
            .put("theme", OverlayPreferences(activity).themeMode().name.lowercase())
            .put("systemDark", activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        "vault.load" -> store.unifiedSnapshot()
        "vault.commit" -> store.commitUnified(args.getString("revision"), args.getJSONArray("entries"))
        "clipboard.read" -> clipboard.readCurrentText().orEmpty().also { require(it.length <= 1000000) { "剪贴板文字过长" } }
        "clipboard.write" -> args.getString("text").let { require(it.length <= 1000000); clipboard.writeText(it); true }
        "theme.set" -> {
            val mode = when (args.getString("theme")) { "dark" -> OverlayThemeMode.DARK; "light" -> OverlayThemeMode.LIGHT; "system" -> OverlayThemeMode.SYSTEM; else -> error("主题无效") }
            OverlayPreferences(activity).saveThemeMode(mode)
            activity.sendBroadcast(Intent(ClipboardOverlayService.ACTION_REFRESH_APPEARANCE).setPackage(activity.packageName), "com.floatclip.app.permission.INTERNAL")
            (activity as? UnifiedActivity)?.applyBars(mode)
            true
        }
        "legacy.open" -> { val page = args.getString("page"); require(page in listOf("clipboard", "lan")); legacy(page); true }
        "file.begin" -> {
            check(transfer == null && waitingDestination == null) { "请先完成当前文件保存" }
            val name = args.getString("name").replace(Regex("[\\\\/\\p{Cntrl}\\u202a-\\u202e\\u2066-\\u2069]"), "_").take(180)
            val size = args.getLong("size")
            require(name.isNotBlank() && name !in listOf(".", "..") && size in 0..64L*1024*1024) { "文件信息无效" }
            val token = UUID.randomUUID().toString()
            val dir = File(activity.cacheDir, "unified-save").apply { mkdirs() }
            check(dir.usableSpace > size + 1024*1024) { "可用空间不足" }
            transfer = Transfer(token, name, size, File(dir, "$token.part").apply { createNewFile() })
            JSONObject().put("token", token)
        }
        "file.chunk" -> {
            val t = transfer ?: error("文件保存已结束")
            require(args.getString("token") == t.token && args.getLong("offset") == t.bytes)
            val encoded = args.getString("base64"); require(encoded.length <= 22000)
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size in 1..16384 && t.bytes + bytes.size <= t.size)
            FileOutputStream(t.file, true).use { it.write(bytes) }; t.bytes += bytes.size
            true
        }
        "file.finish" -> {
            val t = transfer ?: error("文件保存已结束")
            require(args.getString("token") == t.token && t.bytes == t.size)
            waitingDestination = t; transfer = null
            try {
                activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(if (t.name.endsWith(".json")) "application/json" else "application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, t.name), SAVE_FILE)
            } catch (e: Exception) { waitingDestination = null; t.file.delete(); throw e }
            JSONObject().put("pending", true)
        }
        "file.cancel" -> {
            if (args.optString("token") == transfer?.token) { transfer?.file?.delete(); transfer = null }
            true
        }
        else -> error("不支持的本机操作")
    }

    fun saveResult(uri: Uri?): String {
        val t = waitingDestination ?: return "保存已结束"
        waitingDestination = null
        if (uri == null) { t.file.delete(); return "已取消保存" }
        require(uri.scheme == "content") { "保存位置无效" }
        return try {
            activity.contentResolver.openOutputStream(uri, "wt").use { output ->
                check(output != null) { "无法打开保存位置" }
                t.file.inputStream().use { it.copyTo(output) }
                output.flush()
            }
            "文件已保存"
        } finally { t.file.delete() }
    }
    fun close() { transfer?.file?.delete(); waitingDestination?.file?.delete(); transfer = null; waitingDestination = null }
    companion object { const val SAVE_FILE = 6061 }
}
