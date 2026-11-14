package com.floatclip.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.clipboard.ClipTextViewerView
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.theme.OverlayPalette
import com.floatclip.app.ui.ClipEntryActionMenu
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UnifiedClipboardViewerTest {
    private fun palette()=OverlayPalette(Color.rgb(30,40,60),Color.rgb(30,40,60),
        Color.rgb(40,50,70),Color.WHITE,Color.LTGRAY,true)
    @Test fun appAndOverlayCanReuseFullTextViewerWithImageAlignedCopyFeedback() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        File(activity.filesDir,"floatclip_vault.bin").delete()
        val cipher=object:com.floatclip.app.security.VaultCipher {
            override fun encrypt(plain:ByteArray)=plain
            override fun decrypt(payload:ByteArray)=payload
        }
        val store=ClipStore(activity,cipher)
        val a=store.addText("第一条\\n"+"正文 ".repeat(500))!!
        val b=store.addText("第二条")!!
        val viewer=ClipTextViewerView(activity,store,listOf(a,b),a.id,palette()){}
        activity.setContentView(viewer)
        val layout=viewer.getChildAt(0) as LinearLayout
        val scroller=layout.getChildAt(1) as ScrollView
        assertTrue(scroller.isVerticalScrollBarEnabled)
        val actionBar=layout.getChildAt(2) as LinearLayout
        assertEquals(6,actionBar.childCount)
        assertEquals("选择文字",actionBar.getChildAt(0).contentDescription)
        assertEquals("保护",actionBar.getChildAt(1).contentDescription)
        assertEquals("置顶",actionBar.getChildAt(2).contentDescription)
        assertEquals("复制全文",actionBar.getChildAt(5).contentDescription)
        assertTrue(viewer.page(1))
        assertEquals(b.id,viewer.currentEntryId())
        assertFalse(viewer.page(1))
        assertTrue(viewer.page(-1))
        assertTrue((actionBar.getChildAt(5) as ImageView).performClick())
        assertEquals(View.VISIBLE,actionBar.getChildAt(4).visibility)
        assertEquals("已复制",(actionBar.getChildAt(4) as TextView).text.toString())
        assertFalse(actionBar.getChildAt(5).isEnabled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertEquals(View.GONE,actionBar.getChildAt(4).visibility)
        assertTrue(actionBar.getChildAt(5).isEnabled)
    }
    @Test fun categoryMenuRequiresConfirmationBeforeDeleteAndShowsOriginalIconRows() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor=TextView(activity).apply{text="sample";contentDescription="词条"}
        activity.setContentView(anchor)
        var action:ClipEntryActionMenu.Action?=null
        val popup=ClipEntryActionMenu.show(anchor,com.floatclip.app.model.ClipEntry(5,"文字"),
            CategoryStore(activity)) { event, _ -> action=event }
        val list=(popup.contentView as ScrollView).getChildAt(0) as LinearLayout
        assertEquals(7,list.childCount) // six choices and one separator before delete
        val deleteRow=list.getChildAt(list.childCount-1) as LinearLayout
        assertEquals("删除",deleteRow.contentDescription)
        deleteRow.performClick()
        assertNull(action)
        val confirm=list.getChildAt(list.childCount-1) as LinearLayout
        assertEquals("确认删除",confirm.contentDescription)
        confirm.performClick()
        assertEquals(ClipEntryActionMenu.Action.DELETE,action)
        popup.dismiss()
    }
}
