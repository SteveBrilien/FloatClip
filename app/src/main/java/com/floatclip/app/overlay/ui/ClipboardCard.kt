package com.floatclip.app.overlay.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.floatclip.app.R
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.clipboard.ClipboardImages

/** The content owns the row; provenance never consumes a second line. */
class ClipboardCard(context: Context, entry: ClipEntry, dark: Boolean, foreground: Int,
    surface: Int, compact: Boolean = false) : FrameLayout(context) {
    init {
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        val prefs = OverlayPreferences(context)
        val accent = Color.parseColor(when {
            !prefs.sourceColors() -> if(dark) "#8DBBFF" else "#245DCE"
            entry.sourcePlatform == "windows" -> if(dark) "#8DBBFF" else "#245DCE"
            entry.sourcePlatform == "ipad" -> if(dark) "#C8ACEF" else "#7650A3"
            else -> if(dark) "#83D9BE" else "#14755F"
        })
        fun mix(a: Int, b: Int, amount: Float) = Color.rgb(
            (Color.red(a)*(1-amount)+Color.red(b)*amount).toInt(),
            (Color.green(a)*(1-amount)+Color.green(b)*amount).toInt(),
            (Color.blue(a)*(1-amount)+Color.blue(b)*amount).toInt())
        val lines = prefs.entryLineCount()
        val minHeight = when(lines) {
            1 -> if(compact) 54 else 58
            2 -> if(compact) 72 else 78
            else -> if(compact) 90 else 98
        }
        minimumHeight = dp(if(entry.kind=="image")132 else minHeight)
        background = GradientDrawable().apply {
            val tinted = if(prefs.translucentCards()) mix(surface,accent,if(dark) .10f else .055f) else surface
            val cardColor = if(prefs.translucentCards()) {
                val alpha=(255f*prefs.cardAlphaPercent()/100f).toInt().coerceIn(0,255)
                Color.argb(alpha, Color.red(tinted), Color.green(tinted), Color.blue(tinted))
            } else tinted
            setColor(cardColor)
            if(prefs.borderEnabled()) {
                val alpha=(255f*prefs.borderAlphaPercent()/100f).toInt().coerceIn(0,255)
                setStroke(dp(1), if(dark) Color.argb(alpha,255,255,255) else Color.argb(alpha,25,40,63))
            }
            cornerRadius = dp(16).toFloat()
        }
        val hasSource = entry.sourceDeviceId != "local"
        val markerWidth = if(hasSource || entry.pinned || entry.locked) 38 else 16
        if(entry.kind=="image") {
            val longImage=entry.imageHeight>entry.imageWidth*2.2f
            val image=ClipboardImages.thumbnail(ClipStore(context),entry,if(longImage)1600 else 480)
            val previewHeight=if(longImage)224 else 132
            minimumHeight=dp(previewHeight)
            val cropTop=android.graphics.Matrix()
            addView(ImageView(context).apply {
                setImageBitmap(image);scaleType=if(longImage)ImageView.ScaleType.MATRIX else ImageView.ScaleType.FIT_CENTER
                if(longImage&&image!=null)addOnLayoutChangeListener { view,_,_,_,_,_,_,_,_ ->
                    val width=(view.width-view.paddingLeft-view.paddingRight).coerceAtLeast(1)
                    val factor=width.toFloat()/image.width
                    cropTop.setScale(factor,factor);imageMatrix=cropTop
                }
                contentDescription=if(image==null)"图片暂不可用" else "图片 ${entry.imageWidth} × ${entry.imageHeight}"
                setPadding(dp(12),dp(10),dp(markerWidth),dp(30))
            },LayoutParams(-1,dp(previewHeight)))
            addView(TextView(context).apply {
                text=if(image==null)"图片暂不可用" else "${entry.imageWidth} × ${entry.imageHeight} · ${if(longImage) "长图 · 点击展开" else "点击预览"}"
                textSize=12f;setTextColor(foreground);alpha=.72f;setPadding(dp(14),0,dp(markerWidth),dp(9))
            },LayoutParams(-1,-2,Gravity.BOTTOM))
        } else addView(TextView(context).apply {
            text = entry.text; textSize = if(compact) 15f else 16f
            setTextColor(foreground); includeFontPadding = false; setLineSpacing(dp(2).toFloat(),1f)
            gravity = Gravity.CENTER_VERTICAL; maxLines = lines
            ellipsize = android.text.TextUtils.TruncateAt.END
            val verticalPadding = when(lines) { 1 -> 13; 2 -> 14; else -> 15 }
            setPadding(dp(16),dp(verticalPadding),dp(markerWidth),dp(verticalPadding))
        }, LayoutParams(-1,-2,Gravity.CENTER_VERTICAL))
        val marks = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        fun mark(resource: Int, label: String, color: Int = accent) {
            marks.addView(ImageView(context).apply {
                setImageResource(resource); imageTintList = ColorStateList.valueOf(color)
                alpha = .68f; contentDescription = label
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(14),dp(14)).apply { bottomMargin = dp(6) })
        }
        if(entry.category!=com.floatclip.app.prefs.CategoryStore.DEFAULT_CATEGORY) {
            val categories=com.floatclip.app.prefs.CategoryStore(context)
            mark(com.floatclip.app.prefs.CategoryIcons.resource(categories.iconKey(entry.category)),entry.category,
                com.floatclip.app.prefs.CategoryColors.color(categories.colorKey(entry.category),dark))
        }
        if(entry.locked) mark(R.drawable.ic_lock,"已保护")
        if(entry.pinned) mark(R.drawable.ic_pin,"已置顶")
        if(hasSource) mark(when(entry.sourcePlatform) {
            "windows" -> R.drawable.ic_device_computer
            "ipad" -> R.drawable.ic_device_tablet
            else -> R.drawable.ic_device_phone
        },entry.sourceDeviceName)
        addView(marks,LayoutParams(dp(18),-2,Gravity.TOP or Gravity.END).apply { topMargin=dp(11); marginEnd=dp(10) })
    }
}
