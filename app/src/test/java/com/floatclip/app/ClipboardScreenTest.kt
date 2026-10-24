package com.floatclip.app

import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.webkit.WebView
import android.widget.EditText
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.clipboard.ClipboardQuery
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.security.VaultCipher
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
class ClipboardScreenTest {
    @Test fun bulkClearButtonPreservesProtectionWithoutUndo() {
        val controller=Robolectric.buildActivity(UnifiedActivity::class.java).setup()
        val activity=controller.get()
        val codec=object:VaultCipher {
            override fun encrypt(plain:ByteArray)=plain
            override fun decrypt(payload:ByteArray)=payload
        }
        java.io.File(activity.filesDir,"floatclip_vault.bin").delete()
        activity.getSharedPreferences("floatclip_store",Context.MODE_PRIVATE).edit().clear().commit()
        val store=ClipStore(activity,codec)
        store.toggleLocked(store.addText("fixed")!!.id)
        store.togglePinned(store.addText("pinned")!!.id)
        store.addText("ordinary one");store.addText("ordinary two")
        MainActivity::class.java.getDeclaredField("clipStore").apply { isAccessible=true }.set(activity,store)
        try {
            activity.findViewById<View>(R.id.clipboard_clear).performClick()
            assertEquals(setOf("fixed","pinned"),ClipStore(activity,codec).entries().map { it.text }.toSet())
            store.addText("new after clear")
            assertTrue(activity.findViewById<View>(R.id.clipboard_undo)?.isShown!=true)
            assertEquals(setOf("fixed","pinned","new after clear"),ClipStore(activity,codec).entries().map { it.text }.toSet())
            assertTrue(store.entries().first { it.text=="fixed" }.locked)
            assertTrue(store.entries().first { it.text=="pinned" }.pinned)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun android11LaunchesWithoutNetworkOrWebViewAndKeepsSearchFocus() {
        val controller=Robolectric.buildActivity(UnifiedActivity::class.java).setup()
        val activity=controller.get()
        fun containsWeb(view: View): Boolean = view is WebView || view is ViewGroup && (0 until view.childCount).any { containsWeb(view.getChildAt(it)) }
        assertFalse(containsWeb(activity.findViewById(android.R.id.content)))
        val search=activity.findViewById<EditText>(R.id.clipboard_search)
        assertNotNull(search);search.requestFocus();search.setText("测试 Search")
        assertSame(search,activity.findViewById(R.id.clipboard_search));assertTrue(search.hasFocus())
        assertEquals("测试 Search",search.text.toString())
        val settings=activity.findViewById<View>(R.id.clipboard_settings)
        assertEquals((48*activity.resources.displayMetrics.density).toInt(),settings.layoutParams.width)
        settings.performClick();assertNotNull(activity.findViewById<View>(R.id.clipboard_back))
        activity.findViewById<View>(R.id.clipboard_back).performClick()
        assertEquals("测试 Search",activity.findViewById<EditText>(R.id.clipboard_search).text.toString())
        controller.pause().stop().destroy()
    }
    @Test fun stateHeartbeatKeepsCardsAndImportButtonOutsideScrollingList() {
        val controller=Robolectric.buildActivity(UnifiedActivity::class.java).setup()
        val activity=controller.get()
        try {
            val codec=object:VaultCipher {
                override fun encrypt(plain:ByteArray)=plain
                override fun decrypt(payload:ByteArray)=payload
            }
            java.io.File(activity.filesDir,"floatclip_vault.bin").delete()
            val store=ClipStore(activity,codec)
            val bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888)
            val output=java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);bitmap.recycle()
            store.addImage(output.toByteArray())
            MainActivity::class.java.getDeclaredField("clipStore").apply{isAccessible=true}.set(activity,store)
            MainActivity::class.java.getDeclaredField("sourceFilter").apply{isAccessible=true}.set(activity,com.floatclip.app.clipboard.ClipboardSources.IMAGE_SCOPE)
            MainActivity::class.java.getDeclaredMethod("render").apply{isAccessible=true}.invoke(activity)
            val list=activity.findViewById<android.widget.LinearLayout>(R.id.clipboard_list)
            val card=list.getChildAt(0)
            val receiver=MainActivity::class.java.getDeclaredField("sharedHistoryChanged").apply{isAccessible=true}.get(activity) as android.content.BroadcastReceiver
            repeat(4){receiver.onReceive(activity,android.content.Intent(com.floatclip.app.sync.SyncRuntime.STATE_CHANGED))}
            assertSame("a connection heartbeat must not recreate the image card",card,list.getChildAt(0))
            val button=activity.findViewById<View>(R.id.clipboard_image_import)
            assertEquals(View.VISIBLE,button.visibility)
            assertTrue(button.parent is android.widget.FrameLayout)
            assertNotSame(list,button.parent)
            val params=button.layoutParams as android.widget.FrameLayout.LayoutParams
            assertEquals(android.view.Gravity.BOTTOM or android.view.Gravity.END,params.gravity)
            assertTrue(params.bottomMargin>0)
            assertEquals(1,list.childCount)
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun selectingSourceRetainsPageAndImagesHaveNoSwipePanels() {
        val controller=Robolectric.buildActivity(UnifiedActivity::class.java).setup()
        val activity=controller.get()
        try {
            val search=activity.findViewById<EditText>(R.id.clipboard_search)
            val sources=activity.findViewById<android.widget.LinearLayout>(R.id.clipboard_sources)
            sources.getChildAt(1).performClick()
            assertSame(search,activity.findViewById(R.id.clipboard_search))
            assertSame(sources,activity.findViewById(R.id.clipboard_sources))
            val entry=ClipEntry(90,"图片",kind="image",imageRef="a".repeat(32),imageSha256="b".repeat(64),imageWidth=1080,imageHeight=2400)
            val create=MainActivity::class.java.getDeclaredMethod("addHistoryCard",ClipEntry::class.java).apply{isAccessible=true}
            val row=create.invoke(activity,entry) as ViewGroup
            assertEquals("image actions belong to the long-press menu",1,row.childCount)
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun tabRevealKeepsLeadingItemsAndCentersOverflowWithoutOverscrolling() {
        val tabs=com.floatclip.app.ui.TabReveal
        assertEquals(0,tabs.target(100,60,360,800))
        assertEquals(0,tabs.target(10,60,360,280))
        assertEquals(250,tabs.target(400,60,360,800))
        assertEquals(440,tabs.target(740,60,360,800))
        val region=com.floatclip.app.clipboard.ClipboardImages.cardRegion(1080,15000)
        assertEquals(2160,region.height())
        assertEquals(1080,region.width())
    }
    @Test fun searchCombinesTrimmedTextCategoryAndSourceWithoutChangingSourceFilter() {
        val entry=ClipEntry(1,"Mixed URL 中文",category="研究",sourceDeviceId="pc-1",sourceDeviceName="实验室电脑")
        assertTrue(ClipboardQuery.matches(entry,"  mixed url  ","pc-1"))
        assertTrue(ClipboardQuery.matches(entry,"研究"));assertTrue(ClipboardQuery.matches(entry,"实验室"))
        assertFalse(ClipboardQuery.matches(entry,"mixed","local"))
    }
}
