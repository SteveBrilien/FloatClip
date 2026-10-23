package com.floatclip.app.sync

import android.content.Context
import android.util.AtomicFile
import com.floatclip.app.security.LocalVaultCrypto
import com.floatclip.app.security.VaultCipher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Encrypted latest-only image outbox metadata. Image bytes stay in ClipStore's encrypted blob vault. */
class ClipboardImageSyncStorage(
    context: Context,
    private val cipher: VaultCipher = LocalVaultCrypto(),
) {
    data class Pending(
        val id: UUID,
        val imageRef: String,
        val sha256: String,
        val width: Int,
        val height: Int,
        val createdAt: Long,
        val queuedAt: Long,
        val peers: Set<String>,
    )

    private val file=AtomicFile(File(context.filesDir,"clipboard_image_sync.v1.bin"))

    @Synchronized fun load(): Pending? {
        if(!file.baseFile.exists())return null
        check(file.baseFile.length()<=128*1024){"图片同步状态过大"}
        val root=JSONObject(cipher.decrypt(file.readFully()).toString(Charsets.UTF_8))
        check(root.getInt("version")==1){"图片同步状态版本不支持"}
        if(root.isNull("pending"))return null
        val o=root.getJSONObject("pending")
        val id=UUID.fromString(o.getString("id"))
        val ref=o.getString("imageRef")
        val hash=o.getString("sha256")
        val width=o.getInt("width");val height=o.getInt("height")
        val created=o.getLong("createdAt");val queued=o.getLong("queuedAt")
        val peersArray=o.getJSONArray("peers")
        check(id!=UUID(0,0)&&ref.matches(Regex("[0-9a-f]{32}"))&&hash.matches(Regex("[0-9a-f]{64}"))){"图片同步状态无效"}
        check(width in 1..16384&&height in 1..16384&&width.toLong()*height<=64_000_000L&&created>=0&&queued>=0&&peersArray.length()<=8){"图片同步状态无效"}
        val peers=buildSet {
            for(i in 0 until peersArray.length()) {
                val key=peersArray.getString(i);check(key.matches(Regex("[a-f0-9]{64}"))){"图片同步设备无效"};add(key)
            }
        }
        return Pending(id,ref,hash,width,height,created,queued,peers)
    }

    @Synchronized fun save(pending: Pending?) {
        val root=JSONObject().put("version",1)
        if(pending==null)root.put("pending",JSONObject.NULL) else {
            check(pending.peers.size<=8)
            root.put("pending",JSONObject()
                .put("id",pending.id.toString()).put("imageRef",pending.imageRef).put("sha256",pending.sha256)
                .put("width",pending.width).put("height",pending.height).put("createdAt",pending.createdAt)
                .put("queuedAt",pending.queuedAt).put("peers",JSONArray(pending.peers.toList().sorted())))
        }
        val output=file.startWrite()
        try{output.write(cipher.encrypt(root.toString().toByteArray(Charsets.UTF_8)));file.finishWrite(output)}
        catch(t:Throwable){file.failWrite(output);throw t}
    }
}
