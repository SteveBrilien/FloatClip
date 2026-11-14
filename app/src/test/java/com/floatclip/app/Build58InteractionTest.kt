package com.floatclip.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.floatclip.app.clipboard.ClipboardImages
import com.floatclip.app.clipboard.ClipViewerActivity
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.overlay.ui.BorderDragFrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class Build58InteractionTest {
    @Test fun imageShareGrantsReadUriWithoutConvertingOriginalJpeg() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val entry=ClipEntry(id=1,text="图片",kind="image",imageRef="a".repeat(32),mimeType="image/jpeg")
        val intent=ClipboardImages.imageShareIntent(activity,entry)
        assertEquals(Intent.ACTION_SEND,intent.action)
        assertEquals("image/jpeg",intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
        assertEquals("content",intent.clipData!!.getItemAt(0).uri.scheme)
        assertEquals(intent.clipData!!.getItemAt(0).uri,intent.getParcelableExtra(Intent.EXTRA_STREAM))
        assertTrue(intent.clipData!!.getItemAt(0).uri.toString().endsWith(".jpg"))
    }

    @Test fun pinnedPanelCanDismissOnlyMenuByWatchingAnOutsideEvent() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val panel=BorderDragFrameLayout(activity)
        var dismissed=0
        panel.onOutsideTouch={dismissed++}
        val event=MotionEvent.obtain(0L,0L,MotionEvent.ACTION_OUTSIDE,100f,100f,0)
        assertTrue(panel.dispatchTouchEvent(event))
        assertEquals(1,dismissed)
        panel.onOutsideTouch=null
        assertFalse(panel.dispatchTouchEvent(event))
        assertEquals(1,dismissed)
        event.recycle()
    }

    @Test fun imageViewerCanLayOutBehindTransparentNavigationAndStatusBars() {
        val activity=Robolectric.buildActivity(ClipViewerActivity::class.java).get()
        val method=ClipViewerActivity::class.java.getDeclaredMethod("setImageBars",Boolean::class.javaPrimitiveType)
        method.isAccessible=true
        method.invoke(activity,false)
        val flags=activity.window.decorView.systemUiVisibility
        assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN!=0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION!=0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY!=0)
        assertEquals(Color.TRANSPARENT,activity.window.statusBarColor)
        assertEquals(Color.TRANSPARENT,activity.window.navigationBarColor)
        method.invoke(activity,true)
        assertEquals(0,activity.window.decorView.systemUiVisibility)
    }
}
