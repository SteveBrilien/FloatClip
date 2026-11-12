package com.floatclip.app.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.floatclip.app.R
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.sync.ClipboardSyncService
import com.floatclip.app.ui.ZoomImageView
import java.util.concurrent.Executors
import kotlin.math.roundToLong

/** The gallery shares a category order with the clipboard and owns only the currently visible image. */
class ClipImageViewerView(
    context: Context,
    private val store: ClipStore,
    entries: List<ClipEntry>,
    startId: Long,
    private val onCloseRequested: () -> Unit,
) : FrameLayout(context), AutoCloseable {
    private val groups = entries.filter { it.kind == "image" && it.imageRef.isNotBlank() }.groupBy { it.category }
    private val categories = (CategoryStore(context).categories() + groups.keys).distinct().filter(groups::containsKey)
    private var categoryIndex = categories.indexOfFirst { category -> groups.getValue(category).any { it.id == startId } }.coerceAtLeast(0)
    private val images get() = categories.getOrNull(categoryIndex)?.let(groups::get).orEmpty()
    private var index = images.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    private val changedEntries = mutableMapOf<Long, ClipEntry>()
    private val appearance = OverlayPreferences(context)
    private val imageHost = FrameLayout(context)
    private val topChrome = FrameLayout(context)
    private val bottomChrome = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val counter = TextView(context)
    private val categoryLabel = TextView(context)
    private val noteLabel = TextView(context)
    private val noteEditor = EditText(context)
    private val editRow = LinearLayout(context)
    private val actions = LinearLayout(context)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener(::updateCopyAction)
    private val lockButton: ImageButton
    private val pinButton: ImageButton
    private val copyButton: ImageButton
    private var imageRequest: AutoCloseable? = null
    private var imageSource: ClipImageSource? = null
    private var zoomView: ZoomImageView? = null
    private var copyRequest: AutoCloseable? = null
    private var current: ClipEntry? = null
    private var listeningToClipboard = false
    private var editing = false
    private var saving = false
    private var closed = false
    var onEditingChanged: ((Boolean) -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        contentDescription = "图片查看器"
        addView(imageHost, LayoutParams(-1, -1))
        counter.apply { gravity = Gravity.CENTER; textSize = 17f; setTextColor(Color.WHITE) }
        categoryLabel.apply {
            gravity = Gravity.CENTER
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.argb(180, 255, 255, 255))
        }
        val meta = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(counter, LinearLayout.LayoutParams(-1, dp(26)))
            addView(categoryLabel, LinearLayout.LayoutParams(-1, dp(18)))
        }
        topChrome.addView(meta, LayoutParams(-1, dp(48), Gravity.CENTER).apply { marginStart = dp(58); marginEnd = dp(58) })
        topChrome.addView(action(R.drawable.ic_chevron_left, "返回") {
            if (!cancelNoteEdit()) onCloseRequested()
        }, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START))
        topChrome.addView(action(R.drawable.ic_close, "关闭图片") {
            if (!cancelNoteEdit()) onCloseRequested()
        }, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
        addView(topChrome, LayoutParams(-1, dp(52), Gravity.TOP).apply {
            topMargin = dp(4)
            marginStart = dp(8)
            marginEnd = dp(8)
        })

        noteLabel.apply {
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { beginNote() }
        }
        bottomChrome.addView(noteLabel, LinearLayout.LayoutParams(-1, -2))
        editRow.gravity = Gravity.CENTER_VERTICAL
        editRow.visibility = GONE
        noteEditor.apply {
            hint = "输入图片备注"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            textSize = 15f
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(ClipStore.MAX_IMAGE_NOTE_LENGTH))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, id, _ ->
                if (id != EditorInfo.IME_ACTION_DONE) false else { saveNote(); true }
            }
        }
        editRow.addView(noteEditor, LinearLayout.LayoutParams(0, -2, 1f))
        editRow.addView(textAction("取消") { cancelNoteEdit() }, LinearLayout.LayoutParams(-2, dp(48)))
        editRow.addView(textAction("保存", ::saveNote), LinearLayout.LayoutParams(-2, dp(48)))
        bottomChrome.addView(editRow, LinearLayout.LayoutParams(-1, -2))

        actions.gravity = Gravity.CENTER_VERTICAL
        lockButton = action(R.drawable.ic_unlock, "锁定图片") { updateEntry { store.toggleLocked(it.id) } }
        pinButton = action(R.drawable.ic_pin, "置顶图片") { updateEntry { store.togglePinned(it.id) } }
        copyButton = action(R.drawable.ic_copy_paste, "复制图片", ::copyImage)
        actions.addView(action(R.drawable.ic_image_note, "图片备注", ::beginNote), LinearLayout.LayoutParams(dp(48), dp(48)))
        actions.addView(lockButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        actions.addView(pinButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        actions.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        actions.addView(copyButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        bottomChrome.addView(actions, LinearLayout.LayoutParams(-1, dp(50)))
        addView(bottomChrome, LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            bottomMargin = (if (id != 0) resources.getDimensionPixelSize(id) else 0) + dp(8)
        })
        if (images.isNotEmpty()) show() else {
            imageHost.addView(TextView(context).apply {
                text = "图片暂不可用"
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
            }, LayoutParams(-1, -1))
            bottomChrome.visibility = GONE
        }
    }

    fun currentEntryId(): Long? = current?.id

    fun page(delta: Int): Boolean {
        val next = index + delta
        if (editing || saving || delta == 0 || next !in images.indices) return false
        index = next
        show(horizontal = delta)
        return true
    }

    fun categoryPage(delta: Int): Boolean {
        val next = categoryIndex + delta
        if (editing || saving || delta == 0 || next !in categories.indices) return false
        categoryIndex = next
        index = 0
        show(vertical = delta)
        return true
    }

    private fun show(horizontal: Int = 0, vertical: Int = 0) {
        val original = images.getOrNull(index) ?: return
        val entry = changedEntries[original.id] ?: original
        current = entry
        imageRequest?.close()
        imageSource?.close()
        zoomView?.clearImages()
        imageHost.animate().cancel()
        imageHost.removeAllViews()
        counter.text = "${index + 1}/${images.size}"
        categoryLabel.text = entry.category
        updateActions(entry)

        val progress = ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(Color.WHITE) }
        // Portrait images may begin at full-height; landscape images must fit completely
        // inside the viewport (no forced height crop). Pinch and double-tap still zoom.
        val zoom = ZoomImageView(context, fillHeight = shouldFillHeight(entry.imageWidth, entry.imageHeight)).apply {
            onHorizontalPage = ::page
            onVerticalPage = ::categoryPage
            setOnClickListener { toggleChrome() }
        }
        zoomView = zoom
        imageHost.addView(zoom, LayoutParams(-1, -1))
        imageHost.addView(progress, LayoutParams(dp(40), dp(40), Gravity.CENTER))
        var hasImage = false
        val source = ClipImageSource(store, entry) {
            if (!closed && zoomView === zoom && !hasImage) {
                progress.visibility = View.GONE
                imageHost.addView(TextView(context).apply {
                    text = "图片暂不可用，请保留本机数据"
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTextColor(Color.argb(190, 255, 255, 255))
                }, LayoutParams(-1, -1))
            }
        }
        imageSource = source
        zoom.onViewportChanged = { rect, scale ->
            source.request(rect, ClipImageSource.sample(rect.width(), rect.height(), scale)) { bitmap, region ->
                if (!closed && zoomView === zoom) {
                    hasImage = true
                    progress.visibility = View.GONE
                    zoom.setImageRegion(bitmap, region)
                } else bitmap.recycle()
            }
        }
        zoom.setImageDimensions(entry.imageWidth.coerceAtLeast(1), entry.imageHeight.coerceAtLeast(1))
        // A cheap full-image thumbnail appears first. Detail is a bounded region from the same
        // original attachment; switching/closing releases its decoder and does not evict card cache.
        imageRequest = ClipboardImages.thumbnailAsync(store, entry, 480) { bitmap ->
            if (!closed && zoomView === zoom && bitmap != null) {
                hasImage = true
                progress.visibility = View.GONE
                zoom.setImageBitmap(bitmap, entry.imageWidth, entry.imageHeight)
            }
        }
        val animate = (horizontal != 0 || vertical != 0) && android.animation.ValueAnimator.areAnimatorsEnabled()
        imageHost.alpha = if (animate) 0.45f else 1f
        imageHost.translationX = if (animate) dp(horizontal * 24).toFloat() else 0f
        imageHost.translationY = if (animate) dp(vertical * 24).toFloat() else 0f
        if (animate) imageHost.animate().alpha(1f).translationX(0f).translationY(0f)
            .setInterpolator(appearance.motionInterpolator())
            .setDuration((appearance.transitionDurationMs() * 0.72).roundToLong().coerceIn(0L, 320L))
            .start()
    }

    private fun updateActions(entry: ClipEntry) {
        pinButton.setImageResource(if (entry.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin)
        pinButton.actionLabel(if (entry.pinned) "取消置顶" else "置顶图片")
        pinButton.isSelected = entry.pinned
        lockButton.setImageResource(if (entry.locked) R.drawable.ic_lock_filled else R.drawable.ic_unlock)
        lockButton.actionLabel(if (entry.locked) "取消锁定" else "锁定图片")
        lockButton.isSelected = entry.locked
        noteLabel.text = entry.imageNote
        noteLabel.visibility = if (editing || entry.imageNote.isBlank()) GONE else VISIBLE
        updateCopyAction()
    }

    private fun updateCopyAction() {
        if (closed) return
        val entry = current
        val focused = hasWindowFocus()
        val copied = focused && entry != null && ClipboardImages.isPrimaryImage(context, clipboard, entry)
        copyButton.isEnabled = focused && entry != null && copyRequest == null && !copied
        copyButton.actionLabel(when {
            copied -> "已复制图片"
            copyRequest != null -> "正在复制图片"
            else -> "复制图片"
        })
    }

    private fun copyImage() {
        updateCopyAction()
        val entry = current ?: return
        if (!copyButton.isEnabled) return
        copyRequest = ClipboardImages.copyAsync(context, store, entry) { result ->
            copyRequest = null
            updateCopyAction()
            toast(if (result.isSuccess) "已复制图片" else result.exceptionOrNull()?.message ?: "复制未完成")
        }
        updateCopyAction()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!closed) {
            clipboard.addPrimaryClipChangedListener(clipboardListener)
            listeningToClipboard = true
            updateCopyAction()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updateCopyAction()
    }

    private fun updateEntry(onSaved: () -> Unit = {}, change: (ClipEntry) -> Unit) {
        val entry = current ?: return
        if (saving) return
        saving = true
        val app = context.applicationContext
        writes.execute {
            val result = runCatching {
                change(entry)
                val updated = store.entries().firstOrNull { it.id == entry.id } ?: error("图片已移除")
                app.sendBroadcast(Intent(ClipboardSyncService.HISTORY_CHANGED).setPackage(app.packageName), "${app.packageName}.permission.INTERNAL")
                updated
            }
            main.post {
                saving = false
                if (closed) return@post
                result.onSuccess {
                    changedEntries[it.id] = it
                    current = it
                    updateActions(it)
                    onSaved()
                }.onFailure { toast(it.message ?: "未能保存图片更改") }
            }
        }
    }

    private fun beginNote() {
        val entry = current ?: return
        if (saving || editing) return
        editing = true
        onEditingChanged?.invoke(true)
        noteLabel.visibility = GONE
        editRow.visibility = VISIBLE
        actions.visibility = GONE
        noteEditor.setText(entry.imageNote)
        noteEditor.setSelection(noteEditor.length())
        noteEditor.requestFocus()
        noteEditor.post {
            if (editing && !closed) context.getSystemService(InputMethodManager::class.java).showSoftInput(noteEditor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun saveNote() {
        val value = noteEditor.text.toString()
        updateEntry(onSaved = { cancelNoteEdit() }) { store.saveImageNote(it.id, value) }
    }

    fun cancelNoteEdit(): Boolean {
        if (!editing) return false
        if (saving) return true
        editing = false
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(noteEditor.windowToken, 0)
        noteEditor.clearFocus()
        editRow.visibility = GONE
        actions.visibility = VISIBLE
        current?.let(::updateActions)
        onEditingChanged?.invoke(false)
        return true
    }

    private fun toggleChrome() {
        if (editing) return
        val visibility = if (topChrome.visibility == VISIBLE) INVISIBLE else VISIBLE
        topChrome.visibility = visibility
        bottomChrome.visibility = visibility
    }

    private fun ImageButton.actionLabel(label: String) {
        contentDescription = label
        tooltipText = label
    }

    private fun action(icon: Int, label: String, clicked: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(0xff848892.toInt(), 0xff8dbcff.toInt(), Color.WHITE),
        )
        setPadding(dp(12), dp(12), dp(12), dp(12))
        actionLabel(label)
        background = actionBackground()
        setOnClickListener { clicked() }
    }

    private fun textAction(label: String, clicked: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(Color.WHITE)
        background = actionBackground()
        setOnClickListener { clicked() }
    }

    private fun actionBackground() = RippleDrawable(
        ColorStateList.valueOf(Color.argb(66, 255, 255, 255)), null,
        GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.WHITE) },
    )

    override fun close() {
        if (closed) return
        closed = true
        if (listeningToClipboard) clipboard.removePrimaryClipChangedListener(clipboardListener)
        listeningToClipboard = false
        imageRequest?.close()
        imageRequest = null
        imageSource?.close()
        imageSource = null
        zoomView?.clearImages()
        zoomView = null
        copyRequest?.close()
        copyRequest = null
        onEditingChanged = null
        imageHost.animate().cancel()
        imageHost.removeAllViews()
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToLong().toInt()

    companion object {
        /** Full-height initial zoom is reserved for portrait attachments, never landscape. */
        internal fun shouldFillHeight(width: Int, height: Int): Boolean = width > 0 && height > width
        private val writes = Executors.newSingleThreadExecutor { task -> Thread(task, "FloatClip gallery writes").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())
    }
}
