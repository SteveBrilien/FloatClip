package com.floatclip.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.prefs.OverlayThemeMode
import com.floatclip.app.ui.FileUi
import com.floatclip.app.ui.ZoomImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.ConscryptMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class Round3UiTest {
    @Test fun decodedLandscapeDimensionsReplaceStalePortraitMetadataAndFitScreen() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val view=ZoomImageView(activity,fillHeight=false)
        activity.setContentView(view)
        view.layout(0,0,1080,720)
        view.setImageDimensions(720,1280) // Stale/transposed source metadata.
        var lastScale=0f
        var lastRect:Rect?=null
        view.onViewportChanged={ rect,scale->lastScale=scale;lastRect=Rect(rect) }
        view.useDecodedDimensions(1600,720)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        // Robolectric may re-layout the Activity content to a default 320dp window.
        // The decoded landscape must always use FIT against the final actual viewport.
        assertEquals(kotlin.math.min(view.width.toFloat()/1600f,view.height.toFloat()/720f),lastScale,0.002f)
        assertEquals(Rect(0,0,1600,720),lastRect)
    }

    @Test fun kleinThemePersistsIndependentlyForAppAndFloatingPanel() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("floatclip_overlay",Context.MODE_PRIVATE).edit().clear().commit()
        val settings=OverlayPreferences(app)
        settings.saveThemeMode(OverlayThemeMode.KLEIN)
        settings.saveBubbleThemeOverride(OverlayThemeMode.DARK)
        assertEquals(OverlayThemeMode.KLEIN,settings.themeMode())
        assertEquals(OverlayThemeMode.DARK,settings.bubbleThemeMode())
        assertEquals(Color.rgb(15,34,84),FileUi.palette(app).page)
        assertTrue(Color.luminance(FileUi.palette(app).text) > Color.luminance(FileUi.palette(app).page))
        settings.saveBubbleThemeOverride(OverlayThemeMode.KLEIN)
        assertEquals(OverlayThemeMode.KLEIN,settings.bubbleThemeMode())
    }
}
