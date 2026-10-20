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

/** The content owns the row; provenance never consumes a second line. */
class ClipboardCard(context: Context, entry: ClipEntry, dark: Boolean, foreground: Int,
    surface: Int, compact: Boolean = false) : FrameLayout(context) {
    init {
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        val prefs = OverlayPreferences(context)
        val accent = Color.parseColor(when {
            !prefs.sourceColors() -> if(dark) "#9BBEFF" else "#2563EB"
            entry.sourcePlatform == "windows" -> if(dark) "#9BBEFF" else "#2563EB"
            entry.sourcePlatform == "ipad" -> if(dark) "#C8ACEF" else "#7650A3"
            else -> if(dark) "#83D9BE" else "#14755F"
        })
        fun mix(a: Int, b: Int, amount: Float) = Color.rgb(
            (Color.red(a)*(1-amount)+Color.red(b)*amount).toInt(),
            (Color.green(a)*(1-amount)+Color.green(b)*amount).toInt(),
            (Color.blue(a)*(1-amount)+Color.blue(b)*amount).toInt())
        minimumHeight = dp(if(compact) 76 else 88)
        background = GradientDrawable().apply {
            setColor(if(prefs.translucentCards()) mix(surface,accent,if(dark) .12f else .075f) else surface)
            cornerRadius = dp(15).toFloat()
        }
        val hasSource = entry.sourceDeviceId != "local"
        val markerWidth = if(hasSource || entry.pinned) 38 else 16
        addView(TextView(context).apply {
            text = entry.text; textSize = if(compact) 15f else 16f
            setTextColor(foreground); includeFontPadding = false; setLineSpacing(dp(3).toFloat(),1f)
            gravity = Gravity.CENTER_VERTICAL; maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16),dp(19),dp(markerWidth),dp(19))
        }, LayoutParams(-1,-2,Gravity.CENTER_VERTICAL))
        val marks = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        fun mark(resource: Int, label: String) {
            marks.addView(ImageView(context).apply {
                setImageResource(resource); imageTintList = ColorStateList.valueOf(accent)
                alpha = .68f; contentDescription = label
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(14),dp(14)).apply { bottomMargin = dp(6) })
        }
        if(entry.pinned) mark(R.drawable.ic_pin,"已置顶")
        if(hasSource) mark(when(entry.sourcePlatform) {
            "windows" -> R.drawable.ic_device_computer
            "ipad" -> R.drawable.ic_device_tablet
            else -> R.drawable.ic_device_phone
        },entry.sourceDeviceName)
        addView(marks,LayoutParams(dp(18),-2,Gravity.TOP or Gravity.END).apply { topMargin=dp(11); marginEnd=dp(10) })
    }
}
