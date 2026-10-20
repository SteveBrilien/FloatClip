package com.floatclip.app

import android.content.Context
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.security.VaultCipher
import com.floatclip.app.sync.SyncPreferences
import com.floatclip.share.ClipboardSync
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClipboardSyncStorageTest {
    private val codec=object:VaultCipher{override fun encrypt(plain:ByteArray)=byteArrayOf(70,67)+plain;override fun decrypt(payload:ByteArray)=payload.copyOfRange(2,payload.size)}
    private fun context():Context=RuntimeEnvironment.getApplication().also{
        File(it.filesDir,"floatclip_vault.bin").delete()
        it.getSharedPreferences("floatclip_store",Context.MODE_PRIVATE).edit().clear().commit()
        it.getSharedPreferences("floatclip_sync",Context.MODE_PRIVATE).edit().clear().commit()
        it.getSharedPreferences("share-pairs",Context.MODE_PRIVATE).edit().clear().commit()
    }
    private val remote=ClipboardSync.Device(UUID.fromString("00000000-0000-0000-0000-000000000002"),"电脑","windows")
    @Test fun preferencesSurviveRestartAndPauseRetainsAuthorization(){
        val context=context();val prefs=SyncPreferences(context);assertFalse(prefs.enabled())
        context.getSharedPreferences("share-pairs",Context.MODE_PRIVATE).edit().putBoolean("peer-fixture",true).commit()
        prefs.save(enabled=true,sending=false,receiving=true,applying=false,reconnect=false)
        val restarted=SyncPreferences(context);assertTrue(restarted.enabled());assertFalse(restarted.sending());assertTrue(restarted.receiving());assertFalse(restarted.applying());assertFalse(restarted.reconnect())
        restarted.save(enabled=false);assertTrue(context.getSharedPreferences("share-pairs",Context.MODE_PRIVATE).getBoolean("peer-fixture",false))
        assertFalse(SyncPreferences(context).enabled())
    }
    @Test fun repeatedDeliveryKeepsStableIdPinAndCategory(){
        val context=context();val store=ClipStore(context,codec);val first=ClipboardSync.Entry(UUID.randomUUID(),remote,1,100,"remote text")
        store.receiveShared(first);val row=store.entries().single();store.togglePinned(row.id);store.updateCategory(row.id,"工作")
        ClipStore(context,codec).receiveShared(first);assertEquals(1,store.entries().size);assertEquals(first.id.toString(),store.entries().single().key)
        val later=ClipboardSync.Entry(UUID.randomUUID(),remote,2,200,"remote text");store.receiveShared(later)
        val newest=store.entries().single();assertEquals(later.id.toString(),newest.key);assertTrue(newest.pinned);assertEquals("工作",newest.category);assertEquals(remote.id.toString(),newest.sourceDeviceId)
    }
    @Test fun localAndRemoteEqualTextRemainSeparate(){
        val store=ClipStore(context(),codec);store.addText("same");store.receiveShared(ClipboardSync.Entry(UUID.randomUUID(),remote,1,100,"same"))
        assertEquals(2,store.entries().size);assertEquals(2,store.entries().map{it.sourceDeviceId}.distinct().size)
    }
}
