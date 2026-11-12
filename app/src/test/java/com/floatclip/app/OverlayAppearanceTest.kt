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
        org.junit.Assert.assertFalse(prefs.borderEnabled())
        assertTrue(prefs.overlayBorderEnabled())
        assertEquals(98,prefs.cardPressScalePercent())
        assertEquals(96,prefs.bubblePressScalePercent())
        assertEquals(240,prefs.transitionDurationMs())
        assertEquals("soft",prefs.motionPreset())
        assertEquals("blue",prefs.accentTheme())
    }

    @Test fun transparencyControlsReadAsTransparencyAndPreserveLegacyStoredAlpha() {
        val prefs=reset()
        assertEquals(10,prefs.panelTransparencyPercent())
        assertEquals(14,prefs.cardTransparencyPercent())
        prefs.savePanelTransparencyPercent(45)
        prefs.saveCardTransparencyPercent(30)
        prefs.saveBubbleTransparencyPercent(55)
        assertEquals(55,prefs.panelAlphaPercent())
        assertEquals(70,prefs.cardAlphaPercent())
        assertEquals(45,prefs.bubbleAlphaPercent())
        assertEquals(45,OverlayPreferences(RuntimeEnvironment.getApplication()).panelTransparencyPercent())
    }

    @Test fun androidElevenDisablesFakeNoiseFrostWithoutErasingPreference() {
        val prefs=reset()
        prefs.saveBackgroundMode("frost")
        assertEquals("transparent",prefs.backgroundMode())
        // The original setting remains saved for safe compatibility with newer OS.
        assertEquals("frost",RuntimeEnvironment.getApplication()
            .getSharedPreferences("floatclip_overlay",Context.MODE_PRIVATE)
            .getString("panel_background_mode",null))
    }

    @Test fun appearanceResetRestoresVisualFlagsAndKeepsServiceConsent() {
        val prefs = reset()
        prefs.saveSourceColors(false)
        prefs.saveTranslucentCards(false)
        prefs.saveDetailedNotification(true)
        prefs.saveOverlayEnabled(true)
        prefs.saveTransitionDurationMs(480)
        prefs.resetAppearance()
        assertTrue(prefs.sourceColors())
        assertTrue(prefs.translucentCards())
        assertEquals(240, prefs.transitionDurationMs())
        assertTrue(prefs.detailedNotification())
        assertTrue(prefs.overlayEnabled())
    }

    @Test fun forcedDarkOverlayUsesTokyoNightTextAndSurfaceTogether() {
        val prefs = reset()
        prefs.saveThemeMode(com.floatclip.app.prefs.OverlayThemeMode.LIGHT)
        prefs.saveBubbleThemeOverride(com.floatclip.app.prefs.OverlayThemeMode.DARK)
        val context = RuntimeEnvironment.getApplication()
        val bridge = com.floatclip.app.integration.NoOpOriginOsSystemBridge
        val palette = com.floatclip.app.theme.AdaptiveOverlayThemeProvider(context, bridge).palette()
        assertEquals(android.graphics.Color.rgb(26,27,38), palette.panelBackground)
        assertEquals(android.graphics.Color.rgb(36,40,59), palette.rowBackground)
        assertEquals(android.graphics.Color.rgb(192,202,245), palette.primaryText)
        assertEquals(android.graphics.Color.rgb(154,165,206), palette.secondaryText)
    }

    @Test fun floatingLayerThemeIsIndependentFromAppAndSharedByBubbleAndPanel() {
        val prefs=reset()
        prefs.saveThemeMode(com.floatclip.app.prefs.OverlayThemeMode.LIGHT)
        prefs.saveBubbleThemeOverride(com.floatclip.app.prefs.OverlayThemeMode.DARK)
        val provider=com.floatclip.app.theme.AdaptiveOverlayThemeProvider(
            RuntimeEnvironment.getApplication(), com.floatclip.app.integration.NoOpOriginOsSystemBridge
        )
        assertTrue(provider.palette().isDark)
        assertEquals(provider.palette(), provider.bubblePalette())
        prefs.saveThemeMode(com.floatclip.app.prefs.OverlayThemeMode.DARK)
        prefs.saveBubbleThemeOverride(com.floatclip.app.prefs.OverlayThemeMode.LIGHT)
        assertTrue(!provider.palette().isDark)
        assertEquals(provider.palette(), provider.bubblePalette())
    }

    @Test fun imageCardsHideSynthesizedSizeAndShowOnlyExplicitNote() {
        val context=RuntimeEnvironment.getApplication()
        reset()
        fun labels(note:String): List<TextView> {
            val card=ClipboardCard(context,ClipEntry(
                id=11,text="图片",kind="image",imageRef="a".repeat(32),
                imageWidth=1920,imageHeight=4000,imageNote=note,
            ),false,android.graphics.Color.BLACK,android.graphics.Color.WHITE)
            return (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<TextView>()
        }
        assertTrue(labels("").all { it.visibility!=android.view.View.VISIBLE })
        assertTrue(labels("我的备注").any { it.visibility==android.view.View.VISIBLE && it.text=="我的备注" })
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
    @Test fun nonlinearCurveAndExtendedInteractionRangePersistAndReset() {
        val prefs=reset()
        prefs.saveTransitionDurationMs(0);prefs.saveSwitchAnimationMs(1000)
        prefs.saveCardPressScalePercent(60);prefs.savePanelPressScalePercent(70)
        assertEquals(0,prefs.transitionDurationMs());assertEquals(1000,prefs.switchAnimationMs())
        assertEquals(60,prefs.cardPressScalePercent());assertEquals(70,prefs.panelPressScalePercent())
        prefs.saveMotionPreset("snappy")
        assertEquals("snappy",prefs.motionPreset())
        assertEquals(16,prefs.curveControl(0));assertEquals(100,prefs.curveControl(1))
        assertEquals(30,prefs.curveControl(2));assertEquals(100,prefs.curveControl(3))
        assertTrue(prefs.motionInterpolator().getInterpolation(.5f)>.7f)
        prefs.saveCurveControl(0,22);assertEquals("custom",prefs.motionPreset())
        prefs.saveBackgroundMode("window");prefs.saveWindowAlphaPercent(45)
        assertEquals("window",OverlayPreferences(RuntimeEnvironment.getApplication()).backgroundMode())
        assertEquals(45,prefs.windowAlphaPercent())
        prefs.saveInteractionRange("card",20,120);prefs.saveCardPressScalePercent(110)
        assertEquals(110,prefs.cardPressScalePercent())
        prefs.saveInteractionRange("transition",0,5000);prefs.saveTransitionDurationMs(3000)
        assertEquals(3000,prefs.transitionDurationMs())
        prefs.resetAppearance();assertEquals("transparent",prefs.backgroundMode());assertEquals(20,prefs.curveControl(0));assertEquals("soft",prefs.motionPreset())
    }
    @Test fun restoredCardOpacitySliderControlsBackgroundAndSolidModeIsOpaque() {
        val prefs=reset();val context=RuntimeEnvironment.getApplication()
        prefs.saveTranslucentCards(false);prefs.saveCardAlphaPercent(55)
        fun alpha():Int {
            val card=ClipboardCard(context,ClipEntry(1,"a"),false,android.graphics.Color.BLACK,android.graphics.Color.WHITE)
            return android.graphics.Color.alpha((card.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        }
        assertEquals(255*55/100,alpha())
        prefs.saveBackgroundMode("solid");assertEquals(255,alpha())
    }

    @Test fun appCardsHaveNoHairlineWhenOverlayBordersAreEnabled() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=reset()
        prefs.saveBorderEnabled(true);prefs.saveBorderAlphaPercent(60)
        prefs.saveBackgroundMode("solid")
        for(dark in listOf(false,true)) for(image in listOf(false,true)) {
            val color=if(dark) android.graphics.Color.rgb(36,40,59) else android.graphics.Color.WHITE
            val entry=if(image) ClipEntry(2,"image",kind="image",imageRef="a".repeat(32),imageWidth=80,imageHeight=60) else ClipEntry(1,"text")
            val card=ClipboardCard(context,entry,dark,android.graphics.Color.BLACK,color)
            val bitmap=android.graphics.Bitmap.createBitmap(100,100,android.graphics.Bitmap.Config.ARGB_8888)
            card.background.setBounds(0,0,100,100)
            card.background.draw(android.graphics.Canvas(bitmap))
            assertEquals("straight card edges must have the same fill as the interior",bitmap.getPixel(50,1),bitmap.getPixel(50,50))
            bitmap.recycle()
        }
    }

}
