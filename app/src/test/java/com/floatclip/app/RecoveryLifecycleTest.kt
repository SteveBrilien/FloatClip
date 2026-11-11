package com.floatclip.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.floatclip.app.overlay.ClipboardOverlayService
import com.floatclip.app.overlay.ui.OverlayWindows
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.prefs.OverlayThemeMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecoveryLifecycleTest {
    private fun service() : org.robolectric.android.controller.ServiceController<ClipboardOverlayService> {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("floatclip_overlay",Context.MODE_PRIVATE).edit().clear().commit()
        val prefs=OverlayPreferences(context)
        prefs.saveKeepAliveEnabled(false)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        java.io.File(context.filesDir,"floatclip_vault.bin").delete()
        return Robolectric.buildService(ClipboardOverlayService::class.java).create()
    }
    private fun field(service:ClipboardOverlayService,name:String):Any?=
        service.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(service)
    private fun open(service:ClipboardOverlayService) {
        service.javaClass.getDeclaredMethod("expandPanel",Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(service,false)
    }
    private fun closePanel(service:ClipboardOverlayService) {
        service.javaClass.getDeclaredMethod("collapsePanel").apply { isAccessible=true }.invoke(service)
    }
    @Test fun fortyImmediateOpenCloseCyclesRestoreVisibleTouchableBallWithoutWaitingForFrames() {
        val owner=service();val service=owner.get()
        try {
            // Zero is an explicit instant transition, still requiring no animation callback.
            OverlayPreferences(service).saveTransitionDurationMs(0)
            val ball=field(service,"bubbleView") as View
            val ballParams=field(service,"bubbleParams") as WindowManager.LayoutParams
            val x=ballParams.x;val y=ballParams.y
            var retainedPanel:Any?=null;var retainedList:Any?=null
            repeat(40) {
                open(service)
                val panel=field(service,"panelParams") as WindowManager.LayoutParams
                if(it==0) { retainedPanel=field(service,"panelView");retainedList=field(service,"historyList") }
                assertSame(retainedPanel,field(service,"panelView"));assertSame(retainedList,field(service,"historyList"))
                assertEquals(true,field(service,"panelOpen"))
                assertTrue(panel.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED!=0)
                assertTrue(ballParams.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED!=0)
                assertEquals(1f,panel.alpha,0f);assertEquals(0f,ballParams.alpha,0f)
                closePanel(service)
                assertEquals(false,field(service,"panelOpen"));assertEquals(false,field(service,"panelCollapsing"))
                assertEquals(1f,ball.alpha,0f);assertEquals(1f,ballParams.alpha,0f)
                assertEquals(1f,ball.scaleX,0f);assertEquals(1f,ball.scaleY,0f)
                assertEquals(0,ballParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                assertEquals(0f,panel.alpha,0f)
                assertTrue(panel.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE!=0)
                assertEquals(0,panel.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                assertEquals(x,ballParams.x);assertEquals(y,ballParams.y)
            }
        } finally {
            OverlayPreferences(service).saveOverlayEnabled(false);owner.destroy()
        }
    }
    @Test fun interruptedCurvedCloseNeverHidesNewlyOpenedPanelOrBlocksRestoredBall() {
        val owner=service();val service=owner.get()
        try {
            OverlayPreferences(service).saveTransitionDurationMs(300)
            open(service);closePanel(service)
            val ball=field(service,"bubbleParams") as WindowManager.LayoutParams
            val panel=field(service,"panelParams") as WindowManager.LayoutParams
            assertEquals(1f,ball.alpha,0f)
            assertEquals(0,ball.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertTrue(panel.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE!=0)
            open(service)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            assertEquals(true,field(service,"panelOpen"));assertEquals(1f,panel.alpha,0f)
            closePanel(service)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            assertEquals(0f,panel.alpha,0f);assertEquals(1f,ball.alpha,0f)
        } finally {OverlayPreferences(service).saveOverlayEnabled(false);owner.destroy()}
    }
    @Test fun fullscreenViewerReturnTokenRestoresRetainedPanelAndLateReturnCannotReviveStoppedService() {
        val owner=service();val service=owner.get()
        try {
            open(service)
            val panel=field(service,"panelView")
            service.javaClass.getDeclaredMethod("openEntryViewer",com.floatclip.app.model.ClipEntry::class.java).apply{isAccessible=true}
                .invoke(service,com.floatclip.app.model.ClipEntry(40,"全文"))
            assertEquals(false,field(service,"panelOpen"))
            val token=field(service,"pendingViewerToken") as String
            val receiver=field(service,"viewerReturn") as android.content.BroadcastReceiver
            fun back(value:String)=android.content.Intent(com.floatclip.app.clipboard.ClipViewerActivity.RETURN_ACTION).putExtra(com.floatclip.app.clipboard.ClipViewerActivity.EXTRA_RETURN,value)
            receiver.onReceive(service,back("other"));assertEquals(false,field(service,"panelOpen"))
            receiver.onReceive(service,back(token));assertEquals(true,field(service,"panelOpen"));assertSame(panel,field(service,"panelView"))
            OverlayPreferences(service).saveOverlayEnabled(false);owner.destroy()
            receiver.onReceive(service,back(token));assertNull(field(service,"panelView"))
        } finally {if(field(service,"stopping")!=true){OverlayPreferences(service).saveOverlayEnabled(false);owner.destroy()}}
    }
    @Test fun stopWhileOpenAndLateCallbacksCannotReattachWindows() {
        val owner=service();val service=owner.get()
        open(service)
        val windows=field(service,"overlayWindows") as OverlayWindows
        val ball=field(service,"bubbleView") as View
        val params=field(service,"bubbleParams") as WindowManager.LayoutParams
        OverlayPreferences(service).saveOverlayEnabled(false)
        owner.destroy()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertFalse(windows.update(ball,params))
        assertFalse(windows.add(ball,params))
        windows.close()
    }
    @Test fun externallyDetachedSurfaceRecoversOnceAndCloseIsIdempotent() {
        var adds=0;var updates=0;var removes=0;var detached=false
        val manager=java.lang.reflect.Proxy.newProxyInstance(WindowManager::class.java.classLoader,arrayOf(WindowManager::class.java)) { _,method,_ ->
            when(method.name) {
                "addView" -> { adds++;detached=false;null }
                "updateViewLayout" -> { updates++;if(detached)throw IllegalArgumentException("test detached");null }
                "removeViewImmediate" -> { removes++;null }
                else -> null
            }
        } as WindowManager
        val windows=OverlayWindows(manager)
        val view=View(RuntimeEnvironment.getApplication())
        val params=WindowManager.LayoutParams()
        assertTrue(windows.add(view,params))
        detached=true
        assertTrue(windows.update(view,params))
        assertEquals(2,adds);assertEquals(1,updates)
        windows.close();windows.close()
        val before=adds+updates+removes
        assertFalse(windows.update(view,params));assertFalse(windows.add(view,params))
        assertEquals(before,adds+updates+removes)
    }
    @Test fun forcedDarkFirstResumeKeepsFirstContentAndHasNoWhiteLaunchPreview() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=OverlayPreferences(context);prefs.saveThemeMode(OverlayThemeMode.DARK)
        val owner=Robolectric.buildActivity(UnifiedActivity::class.java).create().start()
        val activity=owner.get()
        try {
            val root=activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            assertEquals(Color.rgb(26,27,38),(root.background as ColorDrawable).color)
            val value=android.util.TypedValue()
            assertTrue(activity.theme.resolveAttribute(android.R.attr.windowDisablePreview,value,true))
            assertEquals(android.util.TypedValue.TYPE_INT_BOOLEAN,value.type)
            assertTrue(value.data!=0)
            owner.resume()
            assertSame("first resume must not build the whole clipboard again",root,
                activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
        } finally { owner.pause().stop().destroy();prefs.resetAppearance() }
    }
}
