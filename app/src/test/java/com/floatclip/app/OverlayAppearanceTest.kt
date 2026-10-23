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
        assertEquals(86,prefs.cardAlphaPercent())
        assertEquals(28,prefs.frostStrengthPercent())
        assertTrue(prefs.borderEnabled())
        assertEquals(98,prefs.cardPressScalePercent())
        assertEquals(96,prefs.bubblePressScalePercent())
        assertEquals(240,prefs.transitionDurationMs())
        assertEquals("blue",prefs.accentTheme())
    }

    @Test fun appearanceResetRestoresVisualFlagsAndKeepsServiceConsent() {
        val prefs = reset()
        prefs.saveSourceColors(false)
        prefs.saveTranslucentCards(false)
        prefs.saveDetailedNotification(true)
        prefs.saveKeepAliveEnabled(false)
        prefs.saveOverlayEnabled(true)
        prefs.saveTransitionDurationMs(480)
        prefs.resetAppearance()
        assertTrue(prefs.sourceColors())
        assertTrue(prefs.translucentCards())
        assertEquals(240, prefs.transitionDurationMs())
        assertTrue(prefs.detailedNotification())
        org.junit.Assert.assertFalse(prefs.keepAliveEnabled())
        assertTrue(prefs.overlayEnabled())
    }

    @Test fun forcedDarkOverlayUsesTokyoNightTextAndSurfaceTogether() {
        val prefs = reset()
        prefs.saveThemeMode(com.floatclip.app.prefs.OverlayThemeMode.DARK)
        val context = RuntimeEnvironment.getApplication()
        val bridge = com.floatclip.app.integration.NoOpOriginOsSystemBridge
        val palette = com.floatclip.app.theme.AdaptiveOverlayThemeProvider(context, bridge).palette()
        assertEquals(android.graphics.Color.rgb(26,27,38), palette.panelBackground)
        assertEquals(android.graphics.Color.rgb(36,40,59), palette.rowBackground)
        assertEquals(android.graphics.Color.rgb(192,202,245), palette.primaryText)
        assertEquals(android.graphics.Color.rgb(154,165,206), palette.secondaryText)
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
