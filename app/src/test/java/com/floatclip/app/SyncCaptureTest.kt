package com.floatclip.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.clipboard.ClipboardWrites
import com.floatclip.app.sync.ClipboardSyncService
import com.floatclip.app.sync.SyncPreferences
import com.floatclip.app.sync.SyncRuntime
import com.floatclip.share.ClipboardSync
import com.floatclip.share.ShareWire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncCaptureTest {
    private fun set(target:Any,name:String,value:Any) { target.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(target,value) }
    @Test fun deliberateHistoryCopySendsOnceWithoutRelabelingRemoteHistory() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("floatclip_sync",Context.MODE_PRIVATE).edit().clear().commit()
        val prefs=SyncPreferences(context).apply{save(enabled=true,reconnect=false)}
        val service=Robolectric.buildService(ClipboardSyncService::class.java).get()
        val actor=service.javaClass.getDeclaredField("actor").apply{isAccessible=true}.get(service) as ScheduledExecutorService
        fun storage()=object:ClipboardSync.Storage {
            override fun load():ByteArray?=null
            override fun save(bytes:ByteArray)=Unit
        }
        val frames=mutableListOf<ShareWire.Frame>()
        val key="b".repeat(64)
        fun events(target:MutableList<ShareWire.Frame>)=object:ClipboardSync.Events {
            override fun send(peer:String,frame:ShareWire.Frame){target.add(frame)}
            override fun history(entry:ClipboardSync.Entry)=Unit
            override fun apply(entry:ClipboardSync.Entry)=Unit
        }
        val local=ClipboardSync(ClipboardSync.Device(UUID.randomUUID(),"phone","android"),storage(),events(frames))
        val greetings=mutableListOf<ShareWire.Frame>()
        val remote=ClipboardSync(ClipboardSync.Device(UUID.randomUUID(),"pc","windows"),storage(),events(greetings))
        local.configure(true,true,true,true);remote.configure(true,true,true,true)
        local.connected(key);remote.connected("a".repeat(64))
        local.accept(key,greetings.single(),System.currentTimeMillis());frames.clear()
        java.io.File(context.filesDir,"floatclip_vault.bin").delete()
        context.getSharedPreferences("floatclip_store",Context.MODE_PRIVATE).edit().clear().commit()
        val codec=object:com.floatclip.app.security.VaultCipher {
            override fun encrypt(plain:ByteArray)=plain.copyOf()
            override fun decrypt(payload:ByteArray)=payload.copyOf()
        }
        val library=ClipStore(context,codec)
        val original=ClipboardSync.Entry(UUID.randomUUID(),ClipboardSync.Device(UUID.randomUUID(),"pc history","windows"),1,System.currentTimeMillis(),"remote row")
        library.receiveShared(original)
        set(service,"prefs",prefs);set(service,"engine",local);set(service,"library",library)
        set(service,"trust",context.getSharedPreferences("share-pairs",Context.MODE_PRIVATE))
        SyncRuntime.attach(service)
        try {
            val manager=context.getSystemService(ClipboardManager::class.java)
            ClipboardWrites.copyByUser(context,manager,"remote row")
            actor.submit{}.get(3,TimeUnit.SECONDS)
            assertEquals(setOf(key),local.pendingPeers(System.currentTimeMillis()))
            assertEquals(1,frames.count{it.type==ClipboardSync.CLIP})
            assertEquals(original.source.id.toString(),library.entries().first{it.text=="remote row"}.sourceDeviceId)
            SyncRuntime.observe(context,manager.primaryClip!!)
            actor.submit{}.get(3,TimeUnit.SECONDS)
            assertEquals("own listener must not echo",1,frames.count{it.type==ClipboardSync.CLIP})
            manager.setPrimaryClip(ClipData.newPlainText("Other app","remote row"))
            SyncRuntime.observe(context,manager.primaryClip!!)
            actor.submit{}.get(3,TimeUnit.SECONDS)
            assertEquals("external copy of identical text is a new event",2,frames.count{it.type==ClipboardSync.CLIP})
        } finally {
            SyncRuntime.detach(service);set(service,"closing",true);actor.shutdownNow()
        }
    }
}
