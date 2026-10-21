package com.floatclip.app

import android.content.Context
import android.view.ViewGroup
import android.widget.TextView
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.overlay.ui.ClipboardCard
import com.floatclip.app.prefs.OverlayPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OverlayAppearanceTest {
    private fun reset(): OverlayPreferences {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("floatclip_overlay",Context.MODE_PRIVATE).edit().clear().commit()
        return OverlayPreferences(context)
    }

    @Test fun defaultsKeepFloatingPanelGlassyAndUseTwoLineCards() {
        val prefs=reset()
        assertEquals(90,prefs.panelAlphaPercent())
        assertEquals(2,prefs.entryLineCount())
        assertTrue(prefs.translucentCards())
    }

    @Test fun cardHeightSelectorControlsVisibleLineCount() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=reset()
        val entry=ClipEntry(1,"""第一行
第二行
第三行
第四行""")
        for(lines in 1..3) {
            prefs.saveEntryLineCount(lines)
            val card=ClipboardCard(context,entry,false,0xff19283f.toInt(),0xffffffff.toInt(),true)
            val text=(0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<TextView>().first()
            assertEquals(lines,text.maxLines)
            assertTrue(card.minimumHeight>0)
        }
    }
}
