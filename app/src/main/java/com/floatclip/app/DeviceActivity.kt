package com.floatclip.app

import com.floatclip.app.clipboard.ClipStore
import com.floatclip.probe.ShareActivity
import java.util.UUID
import org.json.JSONObject

/** The device route writes into the same native vault as the clipboard and floating panel. */
class DeviceActivity : ShareActivity() {
    override fun copyText(value: String) {
        com.floatclip.app.clipboard.ClipboardWrites.write(this,getSystemService(android.content.ClipboardManager::class.java),value)
    }
    override fun openWebDevices(): Boolean {
        startActivity(android.content.Intent(this,NodeActivity::class.java));return true
    }
    override fun sharedText(id: UUID,value: String,outgoing: Boolean,peer: String) {
        val store=ClipStore(this)
        runCatching {
            if(outgoing) store.addText(value)
            else if(peer.isNotEmpty()) {
                val snapshot=store.unifiedSnapshot();val entries=snapshot.getJSONArray("entries")
                if((0 until entries.length()).none { entries.getJSONObject(it).getString("id")==id.toString() }) {
                    val now=System.currentTimeMillis()
                    if(entries.length()>=500) {
                        val removable=(0 until entries.length()).filter { !entries.getJSONObject(it).getBoolean("pinned") }.minByOrNull { entries.getJSONObject(it).getLong("createdAt") }
                        check(removable!=null) { "置顶记录已满" };entries.remove(removable)
                    }
                    entries.put(JSONObject().put("id",id.toString()).put("text",value).put("pinned",false).put("category","未分类")
                        .put("createdAt",now).put("updatedAt",now).put("source",JSONObject().put("id","lan-"+peer).put("name","电脑 "+peer.takeLast(4)).put("platform","windows")))
                    store.commitUnified(snapshot.getString("revision"),entries)
                }
            }
        }.onFailure { android.widget.Toast.makeText(this,"文字已接收，但保存到历史未完成",android.widget.Toast.LENGTH_LONG).show() }
    }
}
