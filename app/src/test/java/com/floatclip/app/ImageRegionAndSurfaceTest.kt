package com.floatclip.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import com.floatclip.app.clipboard.ClipImageSource
import com.floatclip.app.clipboard.ClipboardImageProvider
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.ui.ZoomImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ImageRegionAndSurfaceTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Test fun pathologicalLongAndOddSizedRegionsStayWithinPixelBudget() {
        for((w,h) in listOf(16383 to 3901,1081 to 16383,8000 to 8000,4001 to 3001,1 to 16383)) {
            for(scale in listOf(.05f,.5f,1f,4f)) {
                val sample=ClipImageSource.sample(w,h,scale)
                assertEquals(0,sample and (sample-1))
                assertTrue(ceil(w.toDouble()/sample)*ceil(h.toDouble()/sample)<=3_000_000)
            }
        }
    }
    @Test fun longImageScrollRequestsOnlyTheVisibleOriginalRegion() {
        val top=ClipImageSource.viewport(2000,12000,1000,800,.5f,0f,0f)
        val middle=ClipImageSource.viewport(2000,12000,1000,800,.5f,0f,-2300f)
        assertEquals(Rect(0,0,2000,1600),top)
        assertEquals(Rect(0,4600,2000,6200),middle)
        assertTrue(middle.height()<12000)
        assertEquals(Rect(0,0,800,600),ClipImageSource.viewport(800,600,1000,1000,1f,100f,200f))
    }
    @Test fun thumbnailUpgradePreservesLongImageScrollAndSourceCoordinates() {
        val owner=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val view=ZoomImageView(owner.get())
        owner.get().setContentView(view)
        owner.visible()
        var viewport:Rect?=null
        view.onViewportChanged={r,_->viewport=Rect(r)}
        view.setImageDimensions(2000,6000)
        view.layout(0,0,1000,800)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(0,viewport!!.top)
        fun touch(action:Int,y:Float,time:Long) {
            val event=MotionEvent.obtain(0,time,action,500f,y,0)
            view.onTouchEvent(event);event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN,500f,0)
        touch(MotionEvent.ACTION_MOVE,200f,30)
        touch(MotionEvent.ACTION_UP,200f,60)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val prior=Rect(viewport!!)
        assertTrue(prior.top>0)
        view.setImageBitmap(Bitmap.createBitmap(20,60,Bitmap.Config.ARGB_8888),2000,6000)
        view.layout(0,0,1000,800)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(prior,viewport)
        view.clearImages()
        owner.pause().stop().destroy()
    }
    @Test fun removedControlsMigrateWithoutResettingServiceConsentAndFeedbackIsOneSetting() {
        val raw=context.getSharedPreferences("floatclip_overlay",Context.MODE_PRIVATE)
        raw.edit().clear().putBoolean("overlay_enabled",true)
            .putInt("motion_curve_0",90).putInt("range_min_card",10)
            .putString("panel_background_mode","window").putInt("window_alpha",25)
            .putBoolean("border_enabled",false).commit()
        val prefs=OverlayPreferences(context)
        assertTrue(prefs.overlayEnabled())
        assertEquals("soft",prefs.motionPreset())
        assertEquals(50,prefs.interactionRange("card").first)
        assertEquals("transparent",prefs.backgroundMode())
        assertEquals(75,prefs.surfaceTransparencyPercent())
        assertTrue(prefs.borderEnabled())
        for(strength in 0..2) {
            prefs.saveFeedbackStrength(strength)
            assertEquals(strength,OverlayPreferences(context).feedbackStrength())
        }
        assertEquals(96,prefs.cardPressScalePercent())
        assertEquals(93,prefs.bubblePressScalePercent())
        assertEquals(98,prefs.panelPressScalePercent())
    }
    @Test fun highTransparencyKeepsForegroundSurfacesAndFrostFallbackReadable() {
        val raw=context.getSharedPreferences("floatclip_overlay",Context.MODE_PRIVATE)
        raw.edit().clear().commit()
        val prefs=OverlayPreferences(context)
        prefs.saveSurfaceTransparencyPercent(75)
        prefs.saveBackgroundMode("transparent")
        assertEquals(25,prefs.surfacePanelAlphaPercent())
        assertTrue(prefs.surfaceCardAlphaPercent()>=94)
        prefs.saveBackgroundMode("frost")
        assertTrue(prefs.surfacePanelAlphaPercent(false)>=85)
        assertTrue(prefs.surfaceCardAlphaPercent()>=94)
        prefs.saveBackgroundMode("solid")
        assertEquals(100,prefs.surfacePanelAlphaPercent())
        assertEquals(100,prefs.surfaceCardAlphaPercent())
    }
    @Test fun newImageUriHasPngSuffixWhileOldReadGrantsRemainValid() {
        val ref="0123456789abcdef0123456789abcdef"
        val uri=ClipboardImageProvider.uri(context.packageName,ref)
        assertTrue(uri.toString().endsWith(".png"))
        assertEquals(ref,ClipboardImageProvider.reference(uri,context.packageName))
        assertEquals(ref,ClipboardImageProvider.reference(Uri.parse("content://"+context.packageName+".clipboard-images/image/"+ref),context.packageName))
        assertNull(ClipboardImageProvider.reference(Uri.parse(uri.toString()+"?other=1"),context.packageName))
        assertNull(ClipboardImageProvider.reference(Uri.parse(uri.toString().replace(ref,"../outside")),context.packageName))
    }
    @Test fun edgeResizeKeepsOppositeCornerAndClampsToTheScreen() {
        val g=com.floatclip.app.overlay.ui.PanelGeometry
        assertEquals(0,g.resizeEdges(150f,100f,400,600,14))
        assertEquals(g.RIGHT or g.BOTTOM,g.resizeEdges(399f,599f,400,600,14))
        val leftTop=g.resize(com.floatclip.app.overlay.ui.PanelGeometry.Box(100,120,350,400),g.LEFT or g.TOP,40,60,1000,1000,280,300,420,620,8,16)
        assertEquals(com.floatclip.app.overlay.ui.PanelGeometry.Box(140,180,310,340),leftTop)
        val clamped=g.resize(com.floatclip.app.overlay.ui.PanelGeometry.Box(8,16,300,400),g.RIGHT or g.BOTTOM,1000,1000,360,780,280,300,420,620,8,16)
        assertEquals(com.floatclip.app.overlay.ui.PanelGeometry.Box(8,16,344,620),clamped)
    }
    @Test fun slowOpenStillClosesQuicklyAndToggleSpeedFollowsPreset() {
        val raw=context.getSharedPreferences("floatclip_overlay",Context.MODE_PRIVATE)
        raw.edit().clear().commit()
        val prefs=OverlayPreferences(context)
        for(speed in 0..2) {
            prefs.saveMotionSpeed(speed)
            assertTrue(prefs.panelMotionDurationMs(false)<prefs.panelMotionDurationMs(true))
            assertTrue(prefs.panelMotionDurationMs(false)<=200)
        }
        assertEquals(440,prefs.panelMotionDurationMs(true))
        assertEquals(180,prefs.switchAnimationMs())
    }
    @Test fun migrationMergesImagesAndProtectionWithoutReplacingCurrentText() {
        java.io.File(context.filesDir,"floatclip_vault.bin").delete()
        val cipher=object:com.floatclip.app.security.VaultCipher {
            override fun encrypt(value:ByteArray)=value
            override fun decrypt(value:ByteArray)=value
        }
        val store=com.floatclip.app.clipboard.ClipStore(context,cipher)
        val image=Bitmap.createBitmap(12,8,Bitmap.Config.ARGB_8888)
        val bytes=java.io.ByteArrayOutputStream().also {image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        image.recycle()
        val row=store.addImage(bytes)!!
        store.togglePinned(row.id);store.toggleLocked(row.id)
        val portable=store.exportJson()
        // A new handset has its own current history; imported attachments get fresh local refs.
        java.io.File(context.filesDir,"floatclip_vault.bin").delete()
        val target=com.floatclip.app.clipboard.ClipStore(context,cipher)
        val keep=target.addText("new handset text")!!
        assertTrue(target.mergeImportJson(portable))
        assertEquals("new handset text",target.entries().first {it.key==keep.key}.text)
        val restored=target.entries().single {it.kind=="image"}
        assertTrue(restored.pinned);assertTrue(restored.locked)
        assertArrayEquals(bytes,target.readImage(restored))
        assertNotEquals(row.imageRef,restored.imageRef)
        val before=target.exportJson()
        val corrupt=org.json.JSONArray(portable).getJSONObject(0).put("key","corrupt-import")
            .put("imageData",android.util.Base64.encodeToString(byteArrayOf(1,2,3),android.util.Base64.NO_WRAP))
        assertFalse(target.mergeImportJson(org.json.JSONArray().put(corrupt).toString()))
        assertEquals(before,target.exportJson())
    }

}
