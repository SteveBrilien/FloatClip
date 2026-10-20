package com.floatclip.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import com.floatclip.app.clipboard.ClipboardQuery
import com.floatclip.app.model.ClipEntry
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
    @Test fun searchCombinesTrimmedTextCategoryAndSourceWithoutChangingSourceFilter() {
        val entry=ClipEntry(1,"Mixed URL 中文",category="研究",sourceDeviceId="pc-1",sourceDeviceName="实验室电脑")
        assertTrue(ClipboardQuery.matches(entry,"  mixed url  ","pc-1"))
        assertTrue(ClipboardQuery.matches(entry,"研究"));assertTrue(ClipboardQuery.matches(entry,"实验室"))
        assertFalse(ClipboardQuery.matches(entry,"mixed","local"))
    }
}
