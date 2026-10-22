package com.floatclip.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.clipboard.ClipboardImageProvider
import com.floatclip.app.clipboard.ClipboardImages
import com.floatclip.app.clipboard.ClipboardWrites
import com.floatclip.app.security.VaultCipher
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClipboardImageTest {
    private fun fixture(red:Int):ByteArray {
        val output=java.io.ByteArrayOutputStream()
        val stream=java.io.DataOutputStream(output)
        stream.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type:String,data:ByteArray) {
            val name=type.toByteArray(Charsets.US_ASCII)
            stream.writeInt(data.size);stream.write(name);stream.write(data)
            stream.writeInt(java.util.zip.CRC32().apply { update(name);update(data) }.value.toInt())
        }
        chunk("IHDR",ByteBuffer.allocate(13).putInt(1).putInt(1).put(8).put(6).put(0).put(0).put(0).array())
        val pixels=java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(pixels).use { it.write(byteArrayOf(0,red.toByte(),0,0,127)) }
        chunk("IDAT",pixels.toByteArray());chunk("IEND",byteArrayOf())
        return output.toByteArray()
    }
    private val png=fixture(255)
    private val cipher=object:VaultCipher {
        override fun encrypt(plain:ByteArray)=byteArrayOf(70,67)+plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(payload:ByteArray):ByteArray {
            require(payload[0]==70.toByte() && payload[1]==67.toByte())
            return payload.copyOfRange(2,payload.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }
    private fun context():Context=RuntimeEnvironment.getApplication().also {
        File(it.filesDir,"floatclip_vault.bin").delete()
        File(it.filesDir,"clipboard-images").deleteRecursively()
        it.getSharedPreferences("floatclip_store",Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun imageBlobIsEncryptedAndSurvivesReloadProtectionClearAndUndo() {
        val ctx=context();val store=ClipStore(ctx,cipher)
        val image=store.addImage(png)!!
        store.togglePinned(image.id)
        val fixed=store.addText("fixed")!!;store.toggleLocked(fixed.id)
        store.addText("ordinary")
        val removed=store.clearAll()
        val reloaded=ClipStore(ctx,cipher)
        assertEquals(2,reloaded.entries().size)
        val retained=reloaded.entries().first { it.kind=="image" }
        assertTrue(retained.pinned);assertEquals(1,retained.imageWidth);assertEquals(1,retained.imageHeight)
        assertArrayEquals(png,reloaded.readImage(retained))
        val encrypted=File(ctx.filesDir,"clipboard-images/${image.imageRef}.bin").readBytes()
        assertFalse(encrypted.take(png.size).toByteArray().contentEquals(png))
        assertTrue(store.restoreAll(removed).isEmpty());assertEquals(3,store.entries().size)
        assertEquals(image.imageRef,store.addImage(png)!!.imageRef)
    }

    @Test fun portableBackupCarriesImageBytesAndRejectsMissingOrTamperedAttachments() {
        val ctx=context();val store=ClipStore(ctx,cipher)
        store.addText("old text");store.addImage(png)
        val exported=store.exportJson();val oldRef=store.entries().first { it.kind=="image" }.imageRef
        assertTrue(store.importJson(exported))
        val imported=store.entries().first { it.kind=="image" }
        assertNotEquals(oldRef,imported.imageRef);assertArrayEquals(png,store.readImage(imported))
        val before=store.exportJson()
        val missing=JSONArray(exported)
        (0 until missing.length()).map { missing.getJSONObject(it) }.first { it.optString("kind")=="image" }.remove("imageData")
        assertFalse(store.importJson(missing.toString()));assertEquals(before,store.exportJson())
        val tampered=JSONArray(exported)
        (0 until tampered.length()).map { tampered.getJSONObject(it) }.first { it.optString("kind")=="image" }.put("imageSha256","0".repeat(64))
        assertFalse(store.importJson(tampered.toString()));assertEquals(before,store.exportJson())
    }

    @Test fun textOnlyWebCommitAndTextSyncKeepLocalImages() {
        val ctx=context();val store=ClipStore(ctx,cipher)
        val image=store.addImage(png)!!;store.toggleLocked(image.id);store.addText("web text")
        val snapshot=store.textSnapshot()
        assertEquals(1,snapshot.getJSONArray("entries").length())
        store.commitTextUnified(snapshot.getString("revision"),JSONArray())
        assertEquals("image",store.entries().single().kind)
        val remote=com.floatclip.share.ClipboardSync.Device(java.util.UUID.randomUUID(),"电脑","windows")
        store.receiveShared(com.floatclip.share.ClipboardSync.Entry(java.util.UUID.randomUUID(),remote,1,1,"图片"))
        assertEquals(setOf("text","image"),store.entries().map { it.kind }.toSet())
        assertTrue(store.entries().first { it.kind=="image" }.locked)
        assertArrayEquals(png,store.readImage(image))
    }

    @Test fun rejectedDimensionsAndInvalidPngNeverReplaceExistingHistory() {
        val store=ClipStore(context(),cipher);store.addText("keep")
        val before=store.exportJson()
        val huge=png.clone();ByteBuffer.wrap(huge,16,4).putInt(20000)
        for(bytes in listOf(byteArrayOf(1,2,3),huge,ByteArray(8*1024*1024+1))) {
            assertTrue(runCatching { store.addImage(bytes) }.isFailure)
            assertEquals(before,store.exportJson())
        }
    }

    @Test fun currentClipboardUriAndUndoLeaseSurviveOrphanCleanup() {
        val ctx=context();val store=ClipStore(ctx,cipher)
        val first=store.addImage(png)!!
        val removed=store.clearAll();store.retainUndoImages("test",removed)
        val other=fixture(0)
        store.addImage(other)
        assertArrayEquals(png,store.readImage(first))
        assertTrue(store.restoreAll(removed).isEmpty());store.retainUndoImages("test",emptyList())
    }

    @Test fun copiedImageHasReadOnlyScopedUriAndOwnWriteMarker() {
        val ctx=context();val manager=ctx.getSystemService(ClipboardManager::class.java)
        val uri=ClipboardImageProvider.uri(ctx.packageName,"a".repeat(32))
        ClipboardWrites.writeImage(ctx,manager,uri)
        assertEquals(uri,manager.primaryClip!!.getItemAt(0).uri)
        assertTrue(ClipboardWrites.isCurrentOwn(ctx))
        assertEquals("a".repeat(32),ClipboardImageProvider.reference(uri,ctx.packageName))
        assertNull(ClipboardImageProvider.reference(Uri.parse("content://${ctx.packageName}.clipboard-images/image/../vault"),ctx.packageName))
        assertNull(ClipboardImageProvider.reference(Uri.parse("content://other.clipboard-images/image/"+"a".repeat(32)),ctx.packageName))
        val info=ctx.packageManager.resolveContentProvider("${ctx.packageName}.clipboard-images",0)!!
        assertFalse(info.exported);assertTrue(info.grantUriPermissions)
    }

    @Test fun imageUriGrantFailureDoesNotCreateTextFallbackOrReplaceHistory() {
        val ctx=context();val store=ClipStore(ctx,cipher);store.addText("keep")
        val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="image/png"
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor=throw SecurityException("No read grant")
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
            override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
        }
        ShadowContentResolver.registerProviderInternal("test.image",provider)
        val clip=ClipData("image",arrayOf("image/png"),ClipData.Item(Uri.parse("content://test.image/item")))
        assertTrue(ClipboardImages.isImage(ctx,clip))
        assertTrue(runCatching { ClipboardImages.capture(ctx,clip,store) }.isFailure)
        assertEquals("keep",store.entries().single().text)
    }
}
