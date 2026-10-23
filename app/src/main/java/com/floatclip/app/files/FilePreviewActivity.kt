package com.floatclip.app.files

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import com.floatclip.app.ui.FileUi
import com.floatclip.app.ui.ZoomImageView
import java.util.concurrent.Executors

/** Internal entry accepts a catalog key only. Sharing remains on the file shelf. */
class FilePreviewActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var currentPage = 0
    private var bitmap: Bitmap? = null
    private var pageCount = 0
    private lateinit var body: FrameLayout
    private lateinit var status: TextView
    private lateinit var previous: TextView
    private lateinit var next: TextView
    private lateinit var row: SharedFile
    private val colors by lazy { FileUi.palette(this) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); currentPage = state?.getInt("page") ?: 0
        val entry = intent.getStringExtra("key")?.let { FileRuntime.get(this).byKey(it) }
        if (entry == null) { finish(); return }; row = entry
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(colors.page); setPadding(dp(16), dp(8), dp(16), dp(12)) }
        fun action(label: String, run: () -> Unit) = TextView(this).apply {
            text = label; setTextColor(colors.accent); gravity = Gravity.CENTER; textSize = 14f; minHeight = dp(FileUi.HIT)
            setPadding(dp(12), 0, dp(12), 0); isFocusable = true; background = FileUi.action(this@FilePreviewActivity, colors); setOnClickListener { run() }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(action("返回") { finish() })
        header.addView(TextView(this).apply { text = row.name; setTextColor(colors.text); textSize = 17f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(header)
        body = FrameLayout(this); root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        previous = action("上一页") { load(currentPage - 1) }; footer.addView(previous)
        status = TextView(this).apply { setTextColor(colors.muted); textSize = 12f; gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8)) }; footer.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        next = action("下一页") { load(currentPage + 1) }; footer.addView(next); root.addView(footer)
        window.statusBarColor = colors.page; window.navigationBarColor = colors.page
        if (android.graphics.Color.luminance(colors.page) > .5) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(root); load(currentPage)
    }
    private fun load(index: Int) {
        val serial = ++generation; previous.isEnabled = false; next.isEnabled = false; status.text = "正在读取内容…"
        val kind = FilePreviewContent.kind(row.name)
        previous.visibility = if (kind == FilePreviewContent.Kind.PDF) View.VISIBLE else View.GONE; next.visibility = previous.visibility
        body.removeAllViews(); body.addView(ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(colors.accent) }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        worker.execute {
            var decoded: Bitmap? = null
            val result = runCatching {
                val file = FileRuntime.get(this).local(row) ?: error("文件缓存已移除，请返回文件页重新下载")
                when (kind) {
                    FilePreviewContent.Kind.IMAGE -> { decoded = FilePreviewContent.image(file); "" }
                    FilePreviewContent.Kind.PDF -> { val page = FilePreviewContent.pdf(file, index); decoded = page.bitmap; pageCount = page.count; "" }
                    FilePreviewContent.Kind.ZIP -> FilePreviewContent.zip(file)
                    FilePreviewContent.Kind.TEXT -> FilePreviewContent.text(file)
                    else -> "暂不支持此类型的应用内预览。可返回文件页使用独立的分享入口。"
                }
            }
            runOnUiThread {
                if (isDestroyed || serial != generation) { decoded?.recycle(); return@runOnUiThread }
                body.removeAllViews(); bitmap?.recycle(); bitmap = decoded
                if (result.isFailure) {
                    decoded?.recycle(); bitmap = null; showText(result.exceptionOrNull()?.message ?: "无法预览此文件"); status.text = "预览未完成"
                } else if (decoded != null) {
                    body.addView(ZoomImageView(this).apply { setImageBitmap(decoded!!) }, FrameLayout.LayoutParams(-1, -1))
                    currentPage = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    status.text = if (kind == FilePreviewContent.Kind.PDF) "${currentPage + 1} / $pageCount · 双指缩放 / 拖动" else "双指缩放 / 拖动 · 动图仅显示首帧"
                    previous.isEnabled = currentPage > 0; next.isEnabled = currentPage + 1 < pageCount
                } else { showText(result.getOrThrow()); status.text = if (kind == FilePreviewContent.Kind.ZIP) "压缩包目录 · 未解压" else "只读预览" }
                previous.alpha = if (previous.isEnabled) 1f else .45f; next.alpha = if (next.isEnabled) 1f else .45f
            }
        }
    }
    private fun showText(value: String) {
        val text = TextView(this).apply { text = value; setTextColor(colors.text); textSize = 15f; setTextIsSelectable(true); setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(colors.surface) }
        body.addView(ScrollView(this).apply { addView(text) }, FrameLayout.LayoutParams(-1, -1))
    }
    override fun onSaveInstanceState(out: Bundle) { out.putInt("page", currentPage); super.onSaveInstanceState(out) }
    override fun onDestroy() { generation++; worker.shutdownNow(); if (::body.isInitialized) body.removeAllViews(); bitmap?.recycle(); super.onDestroy() }
}
