package com.floatclip.app.clipboard

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.floatclip.app.R
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.ui.CopyFeedbackButton
import com.floatclip.app.ui.FileUi

/** The internal activity receives a stable history ID and can return to the floating panel. */
class ClipViewerActivity : Activity() {
    private lateinit var store: ClipStore
    private var gallery: ClipImageViewerView? = null
    private var selectedId = -1L
    private var returned = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = ClipStore(this)
        selectedId = state?.getLong(EXTRA_ID) ?: intent.getLongExtra(EXTRA_ID, -1L)
        window.setBackgroundDrawable(ColorDrawable(FileUi.palette(this).page))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onStart() { super.onStart(); showEntry() }

    override fun onStop() {
        closeGallery()
        if (!isFinishing && !isChangingConfigurations && intent.getStringExtra(EXTRA_RETURN) != null) {
            sendReturn(false)
            finish()
        }
        super.onStop()
    }

    override fun onNewIntent(value: Intent) {
        super.onNewIntent(value)
        closeGallery()
        setIntent(value)
        returned = false
        selectedId = value.getLongExtra(EXTRA_ID, -1L)
        showEntry()
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        if (gallery == null) showEntry()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(EXTRA_ID, gallery?.currentEntryId() ?: selectedId)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    private fun showEntry() {
        if (gallery != null) return
        val entries = store.entries()
        val entry = entries.firstOrNull { it.id == selectedId }
        if (entry == null) {
            Toast.makeText(this, "这条记录已移除", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (entry.kind == "image") {
            val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
            val preferences = OverlayPreferences(this)
            val candidates = entries.filter {
                it.kind == "image" && when (source) {
                    "" -> true
                    ClipboardSources.IMAGE_SCOPE -> preferences.mergeRemoteImages() || it.sourceDeviceId == "local"
                    else -> it.sourceDeviceId == source
                }
            }
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
            setImageBars(false)
            val viewer = ClipImageViewerView(this, store, if (entry in candidates) candidates else listOf(entry), entry.id, ::finish)
            viewer.onEditingChanged = ::setImageBars
            gallery = viewer
            setContentView(viewer)
            return
        }

        val palette = FileUi.palette(this)
        window.setBackgroundDrawable(ColorDrawable(palette.page))
        window.statusBarColor = palette.page
        window.navigationBarColor = palette.page
        window.decorView.systemUiVisibility = if (Color.luminance(palette.page) > .5f)
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.page)
            setPadding(dp(12), dp(6), dp(12), dp(12))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_left)
            imageTintList = ColorStateList.valueOf(palette.text)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "返回"
            background = FileUi.action(this@ClipViewerActivity, palette)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = "查看全文"
            textSize = 16f
            setTextColor(palette.text)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)
        val body = TextView(this).apply {
            text = entry.text
            textSize = 17f
            setTextColor(palette.text)
            setTextIsSelectable(true)
            setPadding(dp(10), dp(12), dp(10), dp(20))
            setLineSpacing(dp(4).toFloat(), 1f)
            contentDescription = "剪贴板全文"
        }
        root.addView(ScrollView(this).apply { addView(body); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        val copy = CopyFeedbackButton(this, "复制全文", "已复制全文") { done ->
            done(runCatching { ClipboardWrites.copyByUser(this, getSystemService(ClipboardManager::class.java), entry.text) })
        }
        root.addView(copy, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun setImageBars(editing: Boolean) {
        window.decorView.systemUiVisibility = if (editing) 0 else
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    @Deprecated("Android 11 back handling")
    override fun onBackPressed() {
        if (gallery?.cancelNoteEdit() != true) finish()
    }

    private fun closeGallery() {
        gallery?.let {
            selectedId = it.currentEntryId() ?: selectedId
            it.close()
        }
        gallery = null
    }

    override fun onDestroy() {
        closeGallery()
        if (isFinishing) sendReturn(true)
        super.onDestroy()
    }

    private fun sendReturn(showPanel: Boolean) {
        val token = intent.getStringExtra(EXTRA_RETURN) ?: return
        if (returned) return
        returned = true
        sendBroadcast(Intent(RETURN_ACTION).setPackage(packageName).putExtra(EXTRA_RETURN, token)
            .putExtra(EXTRA_SHOW_PANEL, showPanel), "$packageName.permission.INTERNAL")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    companion object {
        const val EXTRA_ID = "clip_viewer_id"
        const val EXTRA_RETURN = "clip_viewer_return"
        const val EXTRA_SHOW_PANEL = "clip_viewer_show_panel"
        private const val EXTRA_SOURCE = "clip_viewer_source"
        const val RETURN_ACTION = "com.floatclip.app.CLIP_VIEWER_RETURN"

        fun open(context: Context, entry: ClipEntry, returnToken: String? = null, source: String = "") {
            context.startActivity(Intent(context, ClipViewerActivity::class.java).putExtra(EXTRA_ID, entry.id)
                .putExtra(EXTRA_RETURN, returnToken).putExtra(EXTRA_SOURCE, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}
