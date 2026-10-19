package com.floatclip.app

import android.content.Context
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.security.VaultCipher
import com.floatclip.app.security.PortableVaultCrypto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UnifiedVaultTest {
    // Storage/migration tests inject a codec; Android Keystore is verified separately on hardware.
    private val codec = object : VaultCipher {
        override fun encrypt(plain: ByteArray) = byteArrayOf(70, 67) + plain
        override fun decrypt(payload: ByteArray): ByteArray { require(payload.size >= 2 && payload[0] == 70.toByte() && payload[1] == 67.toByte()); return payload.copyOfRange(2, payload.size) }
    }
    private fun context(): Context = RuntimeEnvironment.getApplication().also {
        File(it.filesDir,"floatclip_vault.bin").delete()
        it.getSharedPreferences("floatclip_store",Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun shareIdentityCanCoexistWithOriginalClipboardApp() {
        val ctx=context()
        assertEquals("com.floatclip.probe",ctx.packageName)
        val info=ctx.packageManager.getPackageInfo(ctx.packageName,android.content.pm.PackageManager.GET_PERMISSIONS)
        val permissions=info.permissions?.map{it.name}.orEmpty()
        assertTrue(permissions.contains("${ctx.packageName}.permission.INTERNAL"))
        assertFalse(permissions.contains("com.floatclip.app.permission.INTERNAL"))
    }
    @Test fun portableLegacyBackupRestoresIntoCurrentShareVault() {
        // Frozen v1 fixture uses the old app's PBKDF2/AES-GCM format; PIN is test data.
        val backup="""{"format":"floatclip-portable-vault","version":1,"kdf":"PBKDF2-HMAC-SHA256","iterations":210000,"salt":"AAECAwQFBgcICQoLDA0ODw==","iv":"AAECAwQFBgcICQoL","ciphertext":"rUuTMwMYNPZj33MZhNuISZ00DE7bbn4lyttbV+39nP6QpFgVCov3lgZ06dnvz6s+pP92QJ60FXWyRAE4R4B8rLLgo5VTCXq2sVXgn5kAJSw+vct45F80PfP8SQ0Tkg=="}"""
        val store=ClipStore(context(),codec)
        assertTrue(store.importJson(PortableVaultCrypto.decrypt(backup,"000123")))
        val entry=store.entries().single()
        assertEquals("old text",entry.text);assertTrue(entry.pinned);assertEquals("工作",entry.category)
        assertEquals("local",entry.sourceDeviceId)
    }
    @Test fun legacyVaultMigratesWithoutLosingPinOrCategory() {
        val ctx=context();val raw="""[{"id":5,"text":"old text","pinned":true,"category":"工作","createdAt":100}]"""
        ctx.getSharedPreferences("floatclip_store",Context.MODE_PRIVATE).edit().putString("items",raw).commit()
        val store=ClipStore(ctx,codec);val old=store.entries().single()
        assertEquals(5L,old.id);assertEquals("old text",old.text);assertTrue(old.pinned);assertEquals("工作",old.category)
        val snapshot=store.unifiedSnapshot();val entry=snapshot.getJSONArray("entries").getJSONObject(0)
        assertEquals("5",entry.getString("id"));assertEquals("android",entry.getJSONObject("source").getString("platform"))
        store.commitUnified(snapshot.getString("revision"),snapshot.getJSONArray("entries"))
        assertEquals(old.id,store.entries().single().id);assertTrue(store.entries().single().pinned)
    }
    @Test fun staleWebSnapshotCannotOverwriteOverlayChanges() {
        val ctx=context();val store=ClipStore(ctx,codec);store.addText("first");val snapshot=store.unifiedSnapshot()
        ClipStore(ctx,codec).addText("from overlay")
        try { store.commitUnified(snapshot.getString("revision"),snapshot.getJSONArray("entries"));fail("stale snapshot accepted") } catch(e:IllegalStateException){assertEquals("VAULT_CONFLICT",e.message)}
        assertEquals(setOf("first","from overlay"),store.entries().map{it.text}.toSet())
    }
    @Test fun matchingTextFromDifferentDevicesIsNotDeduplicated() {
        val store=ClipStore(context(),codec);store.addText("same");val snap=store.unifiedSnapshot();val entries=snap.getJSONArray("entries")
        val copy=JSONObject(entries.getJSONObject(0).toString()).put("id","remote-record").put("source",JSONObject().put("id","desktop-1").put("name","电脑").put("platform","windows"))
        entries.put(copy);store.commitUnified(snap.getString("revision"),entries);store.addText("same")
        assertEquals(2,store.entries().size);assertEquals(setOf("local","desktop-1"),store.entries().map{it.sourceDeviceId}.toSet())
    }
    @Test fun damagedVaultIsPreservedUntilExplicitBackupRecovery() {
        val ctx=context();val file=File(ctx.filesDir,"floatclip_vault.bin");file.writeText("corrupt")
        val store=ClipStore(ctx,codec);try{store.addText("replacement");fail("damaged vault overwritten")}catch(_:Exception){}
        assertEquals("corrupt",file.readText());assertTrue(store.importJson("""[{"id":7,"text":"restored","createdAt":1}]"""));assertEquals("restored",store.entries().single().text)
    }
    @Test fun bridgeRejectsForeignAndAmbiguousOrigins() {
        assertTrue(UnifiedActivity.isTrusted("https://clip.wmy-cloud.cn/"));assertTrue(UnifiedActivity.isTrusted("https://clip.wmy-cloud.cn:443/path"))
        listOf("http://clip.wmy-cloud.cn","https://clip.wmy-cloud.cn:444/","https://clip.wmy-cloud.cn.evil.example/","https://clip.wmy-cloud.cn@evil.example/","file:///clip.wmy-cloud.cn").forEach { assertFalse(it,UnifiedActivity.isTrusted(it)) }
    }
}
