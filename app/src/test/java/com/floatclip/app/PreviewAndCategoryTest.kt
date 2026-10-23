package com.floatclip.app

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.files.FilePreviewContent
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.security.VaultCipher
import com.floatclip.app.ui.ClipboardLayout
import com.floatclip.app.ui.ZoomImageView
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PreviewAndCategoryTest {
    private val context get()=RuntimeEnvironment.getApplication() as Context
    private fun file()=File(context.cacheDir,"preview-${UUID.randomUUID()}")
    @Test fun renameAndDeleteKeepContentProtectionOrderAndAppearance() {
        val ctx=context; ctx.getSharedPreferences("floatclip_categories",Context.MODE_PRIVATE).edit().clear().commit()
        File(ctx.filesDir,"floatclip_vault.bin").delete()
        val categories=CategoryStore(ctx);categories.add("研究");categories.saveIcon("研究","book");categories.saveColor("研究","purple")
        val codec=object:VaultCipher { override fun encrypt(plain:ByteArray)=plain;override fun decrypt(payload:ByteArray)=payload }
        val clips=ClipStore(ctx,codec);val entry=clips.addText("不会被删掉的内容")!!
        clips.updateCategory(entry.id,"研究");clips.toggleLocked(entry.id);clips.togglePinned(entry.id)
        val oldPosition=categories.categories().indexOf("研究")
        clips.renameCategory("研究","实验");assertTrue(categories.rename("研究","实验"))
        assertEquals(oldPosition,categories.categories().indexOf("实验"));assertEquals("book",categories.iconKey("实验"));assertEquals("purple",categories.colorKey("实验"))
        assertFalse(categories.rename("实验","工作"));assertFalse(categories.rename(CategoryStore.DEFAULT_CATEGORY,"其他"))
        clips.moveCategoryToDefault("实验");assertTrue(categories.delete("实验"))
        val retained=ClipStore(ctx,codec).entries().single();assertEquals(entry.key,retained.key);assertEquals(entry.text,retained.text);assertTrue(retained.locked&&retained.pinned);assertEquals(CategoryStore.DEFAULT_CATEGORY,retained.category)
        assertTrue(clips.clearAll().isEmpty())
    }
    @Test fun textAndZipPreviewReadActualBoundedContentWithoutExtraction() {
        val text=file().apply{writeText("中文 😀\n# 标题\n<not executed>")}
        assertTrue(FilePreviewContent.text(text).contains("中文 😀"))
        text.writeBytes("旧中文编码".toByteArray(charset("GB18030")));assertEquals("旧中文编码",FilePreviewContent.text(text))
        text.writeBytes(byteArrayOf(0,1,2));assertTrue(runCatching{FilePreviewContent.text(text)}.isFailure)
        text.writeBytes(ByteArray(1_048_577){65});assertTrue(runCatching{FilePreviewContent.text(text)}.isFailure)
        val zip=file();ZipOutputStream(zip.outputStream()).use{it.putNextEntry(ZipEntry("目录/内容 😀.txt"));it.write("真实内容".toByteArray());it.closeEntry()}
        assertTrue(FilePreviewContent.zip(zip).contains("目录/内容 😀.txt"));assertFalse(File(context.cacheDir,"目录").exists())
        assertEquals(FilePreviewContent.Kind.UNSUPPORTED,FilePreviewContent.kind("sample.apk"));assertEquals(FilePreviewContent.Kind.PDF,FilePreviewContent.kind("中文.PDF"))
    }
    @Test fun imageColumnsKeepTextFullWidthAndShareSavedPreference() {
        val prefs=OverlayPreferences(context);assertFalse(prefs.twoColumnClipboard());prefs.saveTwoColumnClipboard(true);assertTrue(OverlayPreferences(context).twoColumnClipboard())
        val entries=listOf(
            ClipEntry(4,"图一",kind="image"),ClipEntry(3,"图二",kind="image"),
            ClipEntry(2,"文字"),ClipEntry(1,"第三张图",kind="image"),
        )
        val target=LinearLayout(context);ClipboardLayout.render(target,entries,true){View(context).apply{tag=it.id}}
        fun tags(view:View):List<Long> = listOfNotNull(view.tag as? Long)+if(view is ViewGroup)(0 until view.childCount).flatMap{tags(view.getChildAt(it))} else emptyList()
        assertEquals(listOf(4L,3L,2L,1L),tags(target))
        assertEquals(3,target.childCount)
        val imagePair=target.getChildAt(0) as LinearLayout
        assertEquals(2,imagePair.childCount)
        assertEquals(4L,imagePair.getChildAt(0).tag)
        assertEquals(3L,imagePair.getChildAt(1).tag)
        assertEquals(2L,target.getChildAt(1).tag)
        val trailingImageRow=target.getChildAt(2) as LinearLayout
        assertEquals(2,trailingImageRow.childCount)
        assertEquals(1L,trailingImageRow.getChildAt(0).tag)
        prefs.saveTwoColumnClipboard(false)
    }
    @Test fun clearingDuringFilterAnimationCannotRestoreStaleRowsOrDropImportFooter() {
        val target=LinearLayout(context)
        val a=ClipEntry(91,"first");val b=ClipEntry(92,"second")
        ClipboardLayout.render(target,listOf(a,b),false){View(context).apply{tag=it.id}}
        ClipboardLayout.renderAnimated(target,listOf(b),false,{View(context).apply{tag=it.id}},190L)
        ClipboardLayout.clear(target)
        val empty=View(context).apply{tag="empty"};target.addView(empty)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(1,target.childCount);assertSame(empty,target.getChildAt(0))
        ClipboardLayout.clear(target)
        val footer=View(context).apply{tag="import"}
        ClipboardLayout.renderAnimated(target,listOf(a),false,{View(context).apply{tag=it.id}},190L,onRendered={target.addView(footer)})
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(2,target.childCount);assertSame(footer,target.getChildAt(1))
        ClipboardLayout.renderAnimated(target,listOf(a,b),false,{View(context).apply{tag=it.id}},190L,onRendered={target.addView(footer)})
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(3,target.childCount);assertSame(footer,target.getChildAt(2))
    }
    @Test fun longImageStartsAtReadableWidthAndDragStaysInsideImage() {
        val view=ZoomImageView(context);view.setImageBitmap(Bitmap.createBitmap(200,1600,Bitmap.Config.ARGB_8888));view.layout(0,0,400,400)
        fun field(name:String)=ZoomImageView::class.java.getDeclaredField(name).apply{isAccessible=true}.getFloat(view)
        assertEquals(2f,field("zoom"),.001f);assertEquals(0f,field("offsetY"),.001f)
        fun event(action:Int,y:Float,time:Long){val e=MotionEvent.obtain(0,time,action,100f,y,0);view.onTouchEvent(e);e.recycle()}
        event(MotionEvent.ACTION_DOWN,200f,0);event(MotionEvent.ACTION_MOVE,100f,50);assertTrue(field("offsetY")<0)
        event(MotionEvent.ACTION_MOVE,4000f,100);assertEquals(0f,field("offsetY"),.001f);event(MotionEvent.ACTION_UP,4000f,150)
    }
}
