package com.floatclip.app.sync

import android.content.Context
import android.util.AtomicFile
import com.floatclip.app.security.LocalVaultCrypto
import com.floatclip.app.security.VaultCipher
import com.floatclip.share.ClipboardSync
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Additional encrypted FIFO in front of the existing v1 single-entry sync outbox.
 * Does NOT migrate or rewrite clipboard_sync.v1.bin, preserving downgrade safety.
 * All callers are serialized by ClipboardSyncService.actor.
 */
internal class ClipboardTextOutbox(context: Context,private val cipher:VaultCipher=LocalVaultCrypto()) {
    data class Item(val stamp:String,val text:String,val queuedAt:Long,val sendGeneration:String,
        val dispatched:Boolean=false,val syncId:String="",val lastSentAt:Long=0L)
    private val file=AtomicFile(File(context.filesDir,"clipboard_text_queue_v1.bin"))
    @Synchronized fun load():List<Item> {
        if(!file.baseFile.exists())return emptyList()
        check(file.baseFile.length() in 1..MAX_FILE_BYTES) { "剪贴板发送队列文件大小异常" }
        val root=JSONObject(cipher.decrypt(file.readFully()).toString(Charsets.UTF_8))
        check(root.getInt("version")==1) { "发送队列版本不支持" }
        val items=root.getJSONArray("items")
        check(items.length()<=MAX_ITEMS){"待发送队列长度异常"}
        return (0 until items.length()).map { i ->
            val row=items.getJSONObject(i)
            val entry=Item(row.getString("stamp"),row.getString("text"),row.getLong("queuedAt"),
                row.getString("sendGeneration"),row.getBoolean("dispatched"),
                row.optString("syncId",""),row.optLong("lastSentAt",0L))
            validate(entry)
            entry
        }.also{values -> check(values.map{it.stamp}.distinct().size==values.size){"重复的发送事件"} }
    }
    @Synchronized fun save(items:List<Item>) {
        require(items.size<=MAX_ITEMS) { "待同步记录过多，已有记录已保留" }
        items.forEach(::validate)
        require(items.map{it.stamp}.distinct().size==items.size) { "不能重复保存同一事件" }
        if(items.isEmpty()){file.delete();return}
        val rows=JSONArray()
        for(item in items) rows.put(JSONObject().put("stamp",item.stamp).put("text",item.text)
            .put("queuedAt",item.queuedAt).put("sendGeneration",item.sendGeneration)
            .put("dispatched",item.dispatched).put("syncId",item.syncId)
            .put("lastSentAt",item.lastSentAt))
        val encoded=cipher.encrypt(JSONObject().put("version",1).put("items",rows).toString().toByteArray(Charsets.UTF_8))
        require(encoded.size<=MAX_FILE_BYTES){"待发送记录体积超过安全上限"}
        val output=file.startWrite()
        try {output.write(encoded);file.finishWrite(output)}
        catch(error:Exception){file.failWrite(output);throw error}
    }
    private fun validate(item:Item) {
        require(item.stamp.length in 1..160 && !item.stamp.contains('\u0000')) { "发送事件编号无效" }
        require(item.queuedAt>=0 && item.text.isNotBlank() &&
            item.text.toByteArray(Charsets.UTF_8).size<=ClipboardSync.MAX_BYTES){"发送记录内容无效"}
        require(item.sendGeneration.length in 1..96 && !item.sendGeneration.contains('\u0000')) {"发送授权已失效"}
        require(item.syncId.isEmpty() || runCatching {UUID.fromString(item.syncId)}.isSuccess) {"发送事件 UUID 无效"}
        require(item.lastSentAt>=0 && (!item.dispatched || item.syncId.isNotBlank() && item.lastSentAt>0)) {"发送确认状态异常"}
    }
    companion object {
        const val MAX_ITEMS=96
        private const val MAX_FILE_BYTES=8L*1024*1024
    }
}
