package com.floatclip.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.prefs.OverlayThemeMode
import com.floatclip.app.ui.FloatDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FloatDialogThemeTest {
    @Test fun openDialogChangesPaletteInPlaceWithoutLosingTypedTextOrActions() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        val prefs=OverlayPreferences(activity)
        prefs.saveThemeMode(OverlayThemeMode.LIGHT)
        val input=EditText(activity).apply {
            setText("unfinished category")
            setSelection(5)
            setTextColor(Color.rgb(25,40,63))
            setBackgroundColor(Color.rgb(240,243,248))
        }
        var saves=0
        val handle=FloatDialog.show(activity,"分类",custom=input,onPositive={ saves++;true })
        val title=handle.body.getChildAt(0) as TextView
        try {
            prefs.saveThemeMode(OverlayThemeMode.DARK)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(handle.dialog.isShowing)
            assertEquals(Color.rgb(192,202,245),title.currentTextColor)
            assertEquals(Color.rgb(36,40,59),(handle.body.background as GradientDrawable).color!!.defaultColor)
            assertEquals(Color.rgb(192,202,245),input.currentTextColor)
            assertEquals("unfinished category",input.text.toString())
            assertEquals(5,input.selectionStart)
            prefs.saveThemeMode(OverlayThemeMode.LIGHT)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Color.rgb(25,40,63),title.currentTextColor)
            handle.primary!!.performClick()
            assertEquals(1,saves)
            assertFalse(handle.dialog.isShowing)
        } finally {
            handle.dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }
}
