package com.floatclip.app

import com.floatclip.app.clipboard.ClipStore
import com.floatclip.probe.ShareActivity
import java.util.UUID
import org.json.JSONObject
import com.floatclip.app.sync.SyncRuntime
import com.floatclip.app.sync.SyncPreferences
import com.floatclip.app.sync.ClipboardSyncService
import com.floatclip.app.sync.TrustedPeerStore
import com.floatclip.share.ShareWire
import java.io.File
import java.util.function.Consumer

/** The device route writes into the same native vault as the clipboard and floating panel. */
class DeviceActivity : ShareActivity(),SyncRuntime.Observer {
    private var syncDialog: android.app.AlertDialog?=null
    // This activity always belongs to the managed clipboard-sync architecture.
    // The master switch controls the service; it must never make this screen fall back
    // to the legacy foreground-only ShareActivity connection lifecycle.
    override fun managedConnections()=true
    override fun hostTheme()=com.floatclip.app.prefs.OverlayPreferences(this).themeMode().name.lowercase()
    override fun beginManagedPairing(): Boolean {
        if(!SyncPreferences(this).enabled()) {
            managedState(null,"共享已关闭，请先在剪贴板设置中开启",false)
            android.widget.Toast.makeText(this,"请先开启剪贴板共享",android.widget.Toast.LENGTH_SHORT).show()
            return true
        }
        SyncRuntime.pair(this);return true
    }
    override fun stopManagedPairing(): Boolean {SyncRuntime.current()?.closePairing();return true}
    override fun pauseManagedConnections(): Boolean {SyncPreferences(this).save(enabled=false);SyncRuntime.refresh(this);return true}
    override fun clearManagedTrust(): Boolean {
        val cleared=getSharedPreferences("share-pairs",MODE_PRIVATE).edit().clear().commit()
        if(cleared)runCatching{TrustedPeerStore(this).clear()}.onSuccess{SyncRuntime.current()?.refreshUi()}.onFailure{
            android.widget.Toast.makeText(this,"设备授权已撤销，但加密凭据清理未完成",android.widget.Toast.LENGTH_LONG).show()
        }
        else android.widget.Toast.makeText(this,"设备授权未移除，请检查本机存储",android.widget.Toast.LENGTH_SHORT).show()
        // Returning true means the managed sync layer handled the request. A storage failure
        // must never fall back to disconnect(), because that would also disable the master switch.
        return true
    }
    override fun onResume(){
        super.onResume()
        SyncRuntime.setObserver(this)
        val enabled=SyncPreferences(this).enabled()
        if(!enabled)managedState(null,"共享已关闭，请在剪贴板设置中开启",false)
        SyncRuntime.refresh(this)
    }
    override fun onPause(){SyncRuntime.clearObserver(this);syncDialog?.dismiss();syncDialog=null;super.onPause()}
    override fun changed(service: ClipboardSyncService){managedState(service.firstSession(),service.status,service.pairingOpen())}
    override fun verify(code: String,answer: Consumer<Boolean>) {
        syncDialog?.dismiss()
        syncDialog=android.app.AlertDialog.Builder(this).setTitle("允许此设备共享剪贴板？")
            .setMessage("核对两端识别码：\n\n$code\n\n确认后记住授权，后续可自动重连。共享与接收写入可在设置中关闭。")
            .setNegativeButton("拒绝"){_,_->answer.accept(false)}.setPositiveButton("相同，允许"){_,_->answer.accept(true)}
            .setOnCancelListener{answer.accept(false)}.show()
    }
    override fun offer(peer: String,offer: ShareWire.Offer){managedOffer(offer,SyncRuntime.current()?.session(peer))}
    override fun progress(name: String,done: Long,total: Long,outgoing: Boolean){managedProgress(name,done,total,outgoing)}
    override fun file(offer: ShareWire.Offer,file: File,outgoing: Boolean){managedFile(offer,file,outgoing)}
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
