package com.floatclip.app.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.floatclip.app.model.ClipEntry

/** Stable reading order for mixed text/images; both clipboard surfaces use the same preference. */
object ClipboardLayout {
    fun render(target: LinearLayout, entries: List<ClipEntry>, twoColumns: Boolean, create: (ClipEntry) -> View) {
        val context = target.context; val density = target.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val frame = FrameLayout(context)
        val column = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(View.MeasureSpec.getSize(widthMeasureSpec), dp(if (twoColumns) 960 else 640))
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply { orientation = LinearLayout.VERTICAL }
        frame.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        entries.chunked(if (twoColumns) 2 else 1).forEach { pair ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            pair.forEachIndexed { index, entry ->
                row.addView(create(entry), LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) })
            }
            if (twoColumns && pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) })
            column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        target.addView(frame)
    }
}
