package com.floatclip.app

import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.overlay.ui.PanelGeometry
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.ui.ClipboardHistoryList
import com.floatclip.app.ui.LatestSnapshotLoader
import java.util.concurrent.Executor
import org.junit.Assert.*
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
class OverlayHistoryTest {
    private class Queue:Executor {
        val tasks=ArrayDeque<Runnable>()
        override fun execute(command:Runnable) { tasks.add(command) }
        fun drain() { while(tasks.isNotEmpty())tasks.removeFirst().run() }
    }
    private fun layout(view:View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(440,View.MeasureSpec.EXACTLY))
        view.layout(0,0,360,440)
    }
    @Test fun fiveHundredRowsCreateOnlyViewportAndKeepCurrentCardsOnRefresh() {
        val context=RuntimeEnvironment.getApplication()
        val list=ClipboardHistoryList(context)
        list.attachHeader(LinearLayout(context))
        var created=0
        val entries=(1L..500L).map { ClipEntry(it,"record "+it,createdAt=it) }
        val create:(ClipEntry)->View={ entry ->
            created++
            TextView(context).apply { text=entry.text;minimumHeight=80 }
        }
        list.submit(entries,false,"all","",Color.WHITE,create)
        assertEquals("data submission must not eagerly create cards",0,created)
        layout(list)
        assertTrue("visible viewport must be filled",list.childCount>1)
        assertTrue("500 records must not create 500 views",created<24)
        val first=list.getChildAt(list.childCount-1)
        val before=created
        list.submit(entries,false,"all","",Color.WHITE,create);layout(list)
        assertSame(first,list.getChildAt(list.childCount-1))
        assertEquals(before,created)
        list.setSelectionFromTop(450,0);layout(list)
        assertTrue(list.firstVisiblePosition>=440)
        assertTrue(list.childCount<24)
    }
    @Test fun switchingFiltersImmediatelyShowsLatestAndRestoresScroll() {
        val context=RuntimeEnvironment.getApplication()
        val list=ClipboardHistoryList(context)
        list.attachHeader(LinearLayout(context))
        val entries=(1L..500L).map { ClipEntry(it,"record "+it,createdAt=it) }
        val create:(ClipEntry)->View={ entry -> TextView(context).apply { text=entry.text;minimumHeight=80 } }
        list.submit(entries,false,"all","",Color.WHITE,create);layout(list)
        list.setSelectionFromTop(120,-7);layout(list)
        val saved=list.firstVisiblePosition
        list.submit(entries.takeLast(2),false,"filtered","",Color.WHITE,create);layout(list)
        assertEquals(3,list.count) // header + 2 cards
        assertEquals(0,list.firstVisiblePosition)
        list.submit(entries,false,"all","",Color.WHITE,create);layout(list)
        assertEquals(saved,list.firstVisiblePosition)
    }
    @Test fun consecutiveImagesPairButTextKeepsFullRow() {
        val context=RuntimeEnvironment.getApplication()
        val list=ClipboardHistoryList(context)
        list.attachHeader(LinearLayout(context))
        val text=ClipEntry(1,"text")
        fun image(id:Long)=ClipEntry(id,"image",kind="image")
        val entries=listOf(text,image(2),image(3),text.copy(id=4),image(5))
        list.submit(entries,true,"all","",Color.WHITE) { TextView(context).apply { minimumHeight=80 } }
        layout(list)
        assertEquals(5,list.count) // header + text, image pair, text, image
        list.submit(entries,false,"all","",Color.WHITE) { TextView(context).apply { minimumHeight=80 } }
        layout(list)
        assertEquals(6,list.count)
    }
    @Test fun obsoleteReadsAreSkippedAndStaleDeliveryCannotRestoreOldSnapshot() {
        val worker=Queue();val delivery=Queue()
        var reads=0;val accepted=mutableListOf<Int>()
        val loader=LatestSnapshotLoader(worker,delivery,{ ++reads }) { accepted+=it.getOrThrow() }
        repeat(20) { loader.request() }
        worker.drain()
        assertEquals(1,reads)
        loader.request()
        delivery.drain()
        assertTrue(accepted.isEmpty())
        worker.drain();delivery.drain()
        assertEquals(listOf(2),accepted)
        loader.request();worker.drain();loader.close();delivery.drain()
        assertEquals(listOf(2),accepted)
    }
    @Test fun anchoredPoseMapsExactlyToBubbleCenterAndSize() {
        val panel=PanelGeometry.Box(10,200,352,430)
        for(ball in listOf(PanelGeometry.Box(-20,50,50,50),PanelGeometry.Box(340,720,50,50))) {
            val pose=PanelGeometry.atBubble(panel,ball)
            assertEquals(ball.x+25f,pose.x+panel.width/2f,.001f)
            assertEquals(ball.y+25f,pose.y+panel.height/2f,.001f)
            assertEquals(50f,panel.width*pose.scaleX,.001f)
            assertEquals(50f,panel.height*pose.scaleY,.001f)
        }
    }
    @Test fun followPlacementClampsAllEdgesAndRememberedModeKeepsItsPosition() {
        val p=PanelGeometry
        val panel=PanelGeometry.Box(20,80,352,430)
        val left=PanelGeometry.Box(-20,0,50,50)
        val right=PanelGeometry.Box(370,790,50,50)
        assertEquals(PanelGeometry.Box(8,16,352,430),p.placement(400,800,panel,left,true,8,16))
        assertEquals(PanelGeometry.Box(40,354,352,430),p.placement(400,800,panel,right,true,8,16))
        assertEquals(panel,p.placement(400,800,panel,right,false,8,16))
        val prefs=OverlayPreferences(RuntimeEnvironment.getApplication())
        prefs.resetAppearance();assertFalse(prefs.panelFollowsBubble())
        prefs.savePanelFollowsBubble(true)
        assertTrue(OverlayPreferences(RuntimeEnvironment.getApplication()).panelFollowsBubble())
        prefs.resetAppearance();assertFalse(prefs.panelFollowsBubble())
    }
    @Test fun closingDuringOpenKeepsTrajectoryAndRetainsWindowsForReopen() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=OverlayPreferences(context)
        prefs.saveKeepAliveEnabled(false)
        java.io.File(context.filesDir,"floatclip_vault.bin").delete()
        val controller=org.robolectric.Robolectric.buildService(com.floatclip.app.overlay.ClipboardOverlayService::class.java).create()
        val service=controller.get()
        val type=service.javaClass
        fun field(name:String)=type.getDeclaredField(name).apply { isAccessible=true }.get(service)
        fun open() { type.getDeclaredMethod("expandPanel",Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(service,false) }
        fun close() { type.getDeclaredMethod("collapsePanel").apply { isAccessible=true }.invoke(service) }
        try {
            open()
            val panel=field("panelView")
            val list=field("historyList")
            val params=field("panelParams") as android.view.WindowManager.LayoutParams
            val width=params.width;val height=params.height
            val opening=field("panelTransitionAnimator") as? android.animation.ValueAnimator
            opening?.currentPlayTime=(opening?.duration?:240L)/2
            val beforeX=params.x;val beforeY=params.y
            close()
            assertEquals("reverse must begin at the current surface position",beforeX,params.x)
            assertEquals(beforeY,params.y)
            assertEquals(width,params.width);assertEquals(height,params.height)
            (field("panelTransitionAnimator") as? android.animation.ValueAnimator)?.end()
            assertEquals(0f,params.alpha,0f)
            assertEquals(false,field("panelCollapsing"))
            assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE!=0)
            assertEquals(0,params.flags and android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            open()
            (field("panelTransitionAnimator") as? android.animation.ValueAnimator)?.end()
            assertSame(panel,field("panelView"))
            assertSame(list,field("historyList"))
            assertEquals(1f,params.alpha,0f)
            assertEquals(true,field("panelOpen"))
            assertFalse((panel as com.floatclip.app.overlay.ui.BorderDragFrameLayout).interactionBlocked)
        } finally {
            prefs.saveOverlayEnabled(false)
            controller.destroy()
        }
    }

}
