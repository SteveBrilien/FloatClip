package com.floatclip.probe

import android.content.Context
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.TextView
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Exercise onCreate only: never start the LAN listener or access the clipboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF) // No ARM64 desktop JNI is needed for UI creation.
class ShareActivityStartupTest {
    private fun withActivity(block: (ShareActivity) -> Unit) {
        RuntimeEnvironment.getApplication().getSharedPreferences("share-ui", Context.MODE_PRIVATE)
            .edit().clear().putString("theme", "light").commit()
        val controller = Robolectric.buildActivity(ShareActivity::class.java)
        try { block(controller.create().get()) } finally { controller.destroy() }
    }
    private fun ShareActivity.click(id: Int) { findViewById<View>(id).performClick() }
    private fun ShareActivity.title() = findViewById<TextView>(R.id.page_title).text.toString()
    private fun opens(mode: String) {
        RuntimeEnvironment.getApplication().getSharedPreferences("share-ui", Context.MODE_PRIVATE)
            .edit().clear().putString("theme", mode).commit()
        val controller = Robolectric.buildActivity(ShareActivity::class.java)
        try {
            val activity = controller.create().get()
            val content = activity.findViewById<FrameLayout>(android.R.id.content)
            assertTrue("The sharing screen must be created", content.childCount > 0)
        } finally { controller.destroy() }
    }
    @Test fun opensInLightMode() = opens("light")
    @Test fun opensInDarkMode() = opens("dark")
    @Test fun opensFollowingSystem() = opens("system")

    @Test fun appearanceReturnsToSettings() = withActivity { activity ->
        activity.click(R.id.nav_settings)
        activity.click(R.id.menu_appearance)
        assertEquals("外观", activity.title())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.nav_container).visibility)
        activity.click(R.id.detail_back)
        assertEquals("设置", activity.title())
        assertTrue(activity.findViewById<View>(R.id.nav_settings).isSelected)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.nav_container).visibility)
    }

    @Test fun draftSurvivesDetailsAndThemeChange() = withActivity { activity ->
        val draft = "待发送草稿\nFloatClip"
        activity.findViewById<EditText>(R.id.share_editor).setText(draft)
        activity.click(R.id.nav_settings)
        activity.click(R.id.menu_appearance)
        activity.click(R.id.theme_dark)
        assertEquals("外观", activity.title())
        assertEquals("dark", activity.getSharedPreferences("share-ui", Context.MODE_PRIVATE).getString("theme", null))
        activity.click(R.id.detail_back)
        activity.click(R.id.nav_share)
        assertEquals(draft, activity.findViewById<EditText>(R.id.share_editor).text.toString())
    }

    @Suppress("DEPRECATION")
    @Test fun nestedHelpBackTracksToDevices() = withActivity { activity ->
        activity.click(R.id.nav_devices)
        activity.click(R.id.menu_help)
        activity.click(R.id.menu_advanced)
        assertEquals("网络与备用连接", activity.title())
        activity.onBackPressed()
        assertEquals("连接帮助", activity.title())
        activity.onBackPressed()
        assertEquals("设备", activity.title())
        assertTrue(activity.findViewById<View>(R.id.nav_devices).isSelected)
    }

    @Test fun settingsDetailsReturnWithoutChangingTab() = withActivity { activity ->
        activity.click(R.id.nav_settings)
        listOf(R.id.menu_cache to "接收缓存", R.id.menu_updates to "版本与更新", R.id.menu_sharing to "共享说明").forEach { (id, title) ->
            activity.click(id)
            assertEquals(title, activity.title())
            activity.click(R.id.detail_back)
            assertEquals("设置", activity.title())
        }
    }
}

