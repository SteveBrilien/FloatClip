package com.floatclip.app

import android.content.Context
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.security.VaultCipher
import com.floatclip.app.sync.SyncPreferences
import com.floatclip.app.sync.SyncPolicy
import com.floatclip.app.sync.TrustedPeerStore
import com.floatclip.share.ShareWire
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
        File(it.filesDir,"trusted_peers.v1.bin").delete()
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
    @Test fun verifiedTransportCredentialSurvivesRestartAndBindsDeviceIdentity(){
        val context=context();val token=ByteArray(32){(it+1).toByte()};val key=TrustedPeerStore.hash(token)
        val store=TrustedPeerStore(context,codec);store.putVerified(key,token)
        assertArrayEquals(token,TrustedPeerStore(context,codec).get(key)!!.token)
        store.rememberDevice(key,remote);val remembered=TrustedPeerStore(context,codec).get(key)!!
        assertEquals(remote.id,remembered.deviceId);assertEquals("电脑",remembered.name);assertEquals("windows",remembered.platform)
        store.rememberBluetooth(key,"aa:bb:cc:dd:ee:ff");assertEquals("AA:BB:CC:DD:EE:FF",TrustedPeerStore(context,codec).get(key)!!.bluetoothAddress)
        assertThrows(IllegalArgumentException::class.java){store.putVerified("a".repeat(64),token)}
        val changed=ClipboardSync.Device(UUID.randomUUID(),"另一台电脑","windows")
        assertThrows(java.io.IOException::class.java){store.rememberDevice(key,changed)}
        store.clear();assertNull(TrustedPeerStore(context,codec).get(key))
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
    private class PolicyFixture {
        var saved: ByteArray?=null
        val frames=mutableListOf<ShareWire.Frame>()
        val local=ClipboardSync.Device(UUID.fromString("00000000-0000-0000-0000-000000000001"),"手机","android")
        fun engine()=ClipboardSync(local,object:ClipboardSync.Storage{
            override fun load():ByteArray?=saved
            override fun save(bytes:ByteArray){saved=bytes.copyOf()}
        },object:ClipboardSync.Events{
            override fun send(peer:String,frame:ShareWire.Frame){frames.add(frame)}
            override fun history(entry:ClipboardSync.Entry)=Unit
            override fun apply(entry:ClipboardSync.Entry)=Unit
        })
        fun epoch(engine:ClipboardSync):ByteArray{frames.clear();engine.connected("a".repeat(64));return frames.last{it.type==ClipboardSync.HELLO}.data.copyOfRange(2,18)}
    }
    @Test fun policySurvivesInterruptedServiceAndCoalescedToggles(){
        val prefs=SyncPreferences(context());prefs.save(enabled=true);val fixture=PolicyFixture();val first=fixture.engine();SyncPolicy.apply(prefs,first);val original=fixture.epoch(first)
        val unchanged=prefs.policy();prefs.save(reconnect=false);assertEquals(unchanged.receiveGeneration,prefs.policy().receiveGeneration)
        val restart=fixture.engine();SyncPolicy.apply(prefs,restart);assertArrayEquals(original,fixture.epoch(restart))
        // The service never processed either preference event before its process was killed.
        prefs.save(enabled=false);prefs.save(enabled=true);assertNotEquals(prefs.policy().receiveGeneration,prefs.policy().appliedReceive)
        val resumed=fixture.engine();SyncPolicy.apply(prefs,resumed);assertFalse(original.contentEquals(fixture.epoch(resumed)));assertEquals(1,resumed.remembered())
        assertEquals(prefs.policy().receiveGeneration,prefs.policy().appliedReceive)
        val beforeFastToggle=fixture.epoch(resumed);prefs.save(receiving=false);prefs.save(receiving=true);SyncPolicy.apply(prefs,resumed);assertFalse(beforeFastToggle.contentEquals(fixture.epoch(resumed)))
    }
    @Test fun queuedCopiesKeepTheirCaptureConsentAfterSendingIsResumed(){
        val prefs=SyncPreferences(context());prefs.save(enabled=true);val fixture=PolicyFixture();val engine=fixture.engine();SyncPolicy.apply(prefs,engine)
        val previous=prefs.policy().sendGeneration;prefs.save(sending=false);val privateCapture=prefs.policy().sendGeneration.takeIf{prefs.sending()};prefs.save(sending=true);val current=SyncPolicy.apply(prefs,engine)
        assertFalse(SyncPolicy.mayShare(current,previous));assertFalse(SyncPolicy.mayShare(current,privateCapture));assertTrue(SyncPolicy.mayShare(current,current.sendGeneration))
        assertNull(engine.copied("private queued content",100,!SyncPolicy.mayShare(current,privateCapture)))
        assertFalse(fixture.saved!!.toString(Charsets.UTF_8).contains("private queued content"))
    }

}
