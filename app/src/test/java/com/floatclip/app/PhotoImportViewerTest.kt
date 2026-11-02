package com.floatclip.app

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.floatclip.app.clipboard.*
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.security.VaultCipher
import com.floatclip.app.ui.CopyFeedbackButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhotoImportViewerTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun png():ByteArray {
        val bitmap=Bitmap.createBitmap(12,8,Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle()}.toByteArray()
    }
    private fun store():ClipStore {
        java.io.File(context.filesDir,"floatclip_vault.bin").delete()
        return ClipStore(context,object:VaultCipher {
            override fun encrypt(value:ByteArray)=value
            override fun decrypt(value:ByteArray)=value
        })
    }
    private fun views(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList())
    @Test fun validPngKeepsExactBytesAndLargeCameraDecodeHasABoundedPixelBudget() {
        val source=png();val prepared=ClipboardPhotoImporter.prepare(source)
        assertArrayEquals(source,prepared.png);assertFalse(prepared.resized)
        val sample=ClipboardPhotoImporter.sample(12000,16000)
        assertTrue(12000L/sample*(16000/sample)<=4_000_000L)
        assertTrue(16000/sample<=4096)
    }
    @Test fun selectedJpegAboveOldEightMiBLimitIsAcceptedWithoutChangingSourceBytes() {
        val jpeg=javaClass.getResourceAsStream("/photos/camera-orientation6.jpg")!!.use{it.readBytes()}
        val source=jpeg.copyOf(8*1024*1024+1024)
        val prefix=source.copyOfRange(0,jpeg.size)
        val prepared=ClipboardPhotoImporter.prepare(source)
        assertTrue(prepared.png.size<=8*1024*1024)
        assertArrayEquals(prefix,source.copyOfRange(0,jpeg.size))
        val info=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(prepared.png,0,prepared.png.size,info)
        assertEquals(200,info.outWidth);assertEquals(400,info.outHeight)
    }
    @Test fun oemGalleryClipDataAndDataUriAreAcceptedButMultipleSelectionsAreExplicit() {
        val uri=Uri.parse("content://gallery/photo/40")
        val incoming=Intent(Intent.ACTION_SEND).setType("image/jpeg").apply{clipData=ClipData.newRawUri("photo",uri)}
        assertEquals(uri,ImageImportActivity.incomingUri(incoming))
        assertEquals(uri,ImageImportActivity.incomingUri(Intent(Intent.ACTION_SEND).setData(uri)))
        incoming.clipData!!.addItem(ClipData.Item(Uri.parse("content://gallery/photo/41")))
        assertTrue(runCatching{ImageImportActivity.incomingUri(incoming)}.isFailure)
    }
    @Test fun malformedImageDoesNotReplaceExistingProtectedHistory() {
        val store=store();val row=store.addText("keep")!!;store.togglePinned(row.id)
        val before=store.exportJson()
        // Legacy graphics otherwise fabricates a bitmap for arbitrary bytes.
        org.robolectric.shadows.ShadowBitmapFactory.setAllowInvalidImageData(false)
        try { assertTrue(runCatching {ClipboardPhotoImporter.prepare(byteArrayOf(1,2,3))}.isFailure) }
        finally { org.robolectric.shadows.ShadowBitmapFactory.setAllowInvalidImageData(true) }
        assertEquals(before,store.exportJson())
    }
    @Test fun fullscreenViewerShowsSelectableUntruncatedTextAndCopySuccess() {
        val store=store();val row=store.addText("查看全文😀\n".repeat(3000))!!
        val intent=Intent(context,ClipViewerActivity::class.java).putExtra(ClipViewerActivity.EXTRA_ID,row.id)
        val owner=Robolectric.buildActivity(ClipViewerActivity::class.java,intent).create()
        val activity=owner.get()
        ClipViewerActivity::class.java.getDeclaredField("store").apply{isAccessible=true}.set(activity,store)
        try {
            owner.start().resume().visible()
            val all=views(activity.findViewById(android.R.id.content))
            assertTrue(all.filterIsInstance<TextView>().any { it.text.toString()==row.text && it.isTextSelectable })
            val copy=all.filterIsInstance<CopyFeedbackButton>().single()
            copy.performClick();assertEquals("已复制全文",copy.text.toString())
            assertEquals(row.text,context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        } finally {owner.pause().stop().destroy()}
    }
    @Test fun imageViewerUsesTheWholeWindowAndKeepsCopyControlsAboveImage() {
        val store=store();val row=store.addImage(png())!!
        val owner=Robolectric.buildActivity(ClipViewerActivity::class.java,Intent(context,ClipViewerActivity::class.java).putExtra(ClipViewerActivity.EXTRA_ID,row.id)).create()
        val activity=owner.get()
        ClipViewerActivity::class.java.getDeclaredField("store").apply{isAccessible=true}.set(activity,store)
        try {
            owner.start().resume().visible()
            val content=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            assertTrue(content is android.widget.FrameLayout)
            assertTrue(activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN!=0)
            assertEquals(1,views(content).filterIsInstance<CopyFeedbackButton>().size)
        } finally {owner.pause().stop().destroy()}
    }
    @Test fun copyFeedbackStaysBusyUntilCompletionAndFailureNeverSaysCopied() {
        var complete:((Result<Unit>)->Unit)?=null;var calls=0
        val button=CopyFeedbackButton(context,"复制图片","已复制图片"){calls++;complete=it}
        button.performClick();button.performClick()
        assertEquals(1,calls);assertFalse(button.isEnabled);assertEquals("正在复制…",button.text.toString())
        complete!!(Result.failure(java.io.IOException("image missing")))
        assertTrue(button.isEnabled);assertEquals("复制未完成",button.text.toString())
    }
    private fun awaitCopy(done:()->Boolean) {
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4)
        while(!done()&&System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        assertTrue("copy callback must finish",done())
    }
    @Test fun imageCopyValidatesOffMainAndOnlyThenChangesClipboard() {
        java.io.File(context.filesDir,"floatclip_vault.bin").delete()
        val thread=java.util.concurrent.atomic.AtomicReference<String>()
        val store=ClipStore(context,object:VaultCipher {
            override fun encrypt(value:ByteArray)=value
            override fun decrypt(value:ByteArray):ByteArray {thread.set(Thread.currentThread().name);return value}
        })
        val row=store.addImage(png())!!
        val manager=context.getSystemService(android.content.ClipboardManager::class.java)
        manager.setPrimaryClip(ClipData.newPlainText("before","unchanged"))
        var result:Result<Unit>?=null
        val request=ClipboardImages.copyAsync(context,store,row){result=it}
        assertEquals("unchanged",manager.primaryClip!!.getItemAt(0).text.toString())
        awaitCopy{result!=null}
        assertTrue(result!!.isSuccess);assertEquals("FloatClip image copy",thread.get())
        assertEquals(row.imageRef,ClipboardImageProvider.reference(manager.primaryClip!!.getItemAt(0).uri,context.packageName))
        request.close()
    }
    @Test fun corruptImageCopyLeavesSystemClipboardAndProtectedRecordUntouched() {
        val store=store();val keep=store.addText("protected")!!;store.togglePinned(keep.id)
        val row=store.addImage(png())!!
        java.io.File(context.filesDir,"clipboard-images/"+row.imageRef+".bin").writeBytes(byteArrayOf())
        val manager=context.getSystemService(android.content.ClipboardManager::class.java)
        manager.setPrimaryClip(ClipData.newPlainText("before","unchanged"))
        var result:Result<Unit>?=null
        val request=ClipboardImages.copyAsync(context,store,row){result=it}
        awaitCopy{result!=null}
        assertTrue(result!!.isFailure);assertEquals("unchanged",manager.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(store.entries().first{it.id==keep.id}.pinned);request.close()
    }

}
