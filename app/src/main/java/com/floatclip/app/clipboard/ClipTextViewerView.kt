package com.floatclip.app.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.floatclip.app.R
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.CategoryStore

/** Full-text viewer with the same dark full-screen overlay and chrome as image preview. */
class ClipTextViewerView(
    context: Context, private val store: ClipStore, entries: List<ClipEntry>, startId: Long,
    private val close: () -> Unit,
) : FrameLayout(context) {
    private val groups = entries.filter { it.kind == "text" }.groupBy { it.category }
    private val categories = (CategoryStore(context).categories() + groups.keys).distinct().filter(groups::containsKey)
    private var groupIndex = categories.indexOfFirst { c -> groups.getValue(c).any { it.id == startId } }.coerceAtLeast(0)
    private val group get() = categories.getOrNull(groupIndex)?.let(groups::get).orEmpty()
    private var position = group.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    private val counter = TextView(context).apply { textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
    private val category = TextView(context).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xffb7cef1.toInt()) }
    private val contentText = TextView(context).apply {
        textSize = 17f; setTextColor(Color.WHITE); setTextIsSelectable(true)
        setLineSpacing(dp(5).toFloat(), 1.08f)
        setPadding(dp(20), dp(20), dp(20), dp(25))
        contentDescription = "剪贴板全文，长按可选择文字"
    }
    private val scroll = ScrollView(context).apply { isFillViewport = true; addView(contentText) }
    private val pin = icon(R.drawable.ic_pin, "置顶") { current()?.let { store.togglePinned(it.id); refresh() } }
    private val lock = icon(R.drawable.ic_unlock, "保护") { current()?.let { store.toggleLocked(it.id); refresh() } }
    init {
        setBackgroundColor(Color.BLACK)
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val top = FrameLayout(context)
        top.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(counter, LinearLayout.LayoutParams(-1, dp(26)))
            addView(category, LinearLayout.LayoutParams(-1, dp(18)))
        }, LayoutParams(-1, dp(48), Gravity.CENTER).apply { marginStart = dp(60); marginEnd = dp(60) })
        top.addView(icon(R.drawable.ic_chevron_left, "返回", close), LayoutParams(dp(48), dp(48), Gravity.START))
        top.addView(icon(R.drawable.ic_close, "关闭全文", close), LayoutParams(dp(48), dp(48), Gravity.END))
        layout.addView(top, LinearLayout.LayoutParams(-1, dp(52)).apply {
            marginStart = dp(8); marginEnd = dp(8); topMargin = dp(8)
        })
        layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_chevron_left, "上一条") { page(-1) }, LinearLayout.LayoutParams(dp(44), dp(48)))
            addView(icon(R.drawable.ic_chevron_right, "下一条") { page(1) }, LinearLayout.LayoutParams(dp(44), dp(48)))
            addView(pin, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(lock, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
            addView(icon(R.drawable.ic_copy_paste, "复制全文") {
                current()?.let { entry ->
                    runCatching { ClipboardWrites.copyByUser(context, context.getSystemService(ClipboardManager::class.java), entry.text) }
                        .onSuccess { Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "复制失败，请重试", Toast.LENGTH_SHORT).show() }
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        layout.addView(bottom, LinearLayout.LayoutParams(-1, dp(56)).apply {
            marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(16)
        })
        addView(layout, LayoutParams(-1, -1))
        refresh()
    }
    private fun current(): ClipEntry? = group.getOrNull(position)?.let { selected ->
        store.entries().firstOrNull { it.id == selected.id } ?: selected
    }
    private fun page(delta: Int) {
        val next = position + delta
        if(next in group.indices) { position = next; refresh() }
    }
    private fun refresh() {
        val entry = current() ?: return
        counter.text = "${position + 1}/${group.size}"
        category.text = entry.category
        contentText.text = entry.text
        scroll.scrollTo(0, 0)
        pin.setImageResource(if(entry.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin)
        lock.setImageResource(if(entry.locked) R.drawable.ic_lock_filled else R.drawable.ic_unlock)
    }
    private fun icon(resource: Int, label: String, action: () -> Unit) = ImageView(context).apply {
        setImageResource(resource)
        contentDescription = label
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = RippleDrawable(ColorStateList.valueOf(0x554c7dcb), GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(13).toFloat()
        }, null)
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
