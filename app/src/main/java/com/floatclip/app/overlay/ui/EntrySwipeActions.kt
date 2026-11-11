package com.floatclip.app.overlay.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.floatclip.app.R

class EntrySwipeActions(
    context: Context, pinned: Boolean, dark: Boolean, onPin: () -> Unit, onDelete: () -> Unit,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    init {
        orientation = HORIZONTAL
        fun action(label: String, icon: Int, color: Int, tint: Int, pressedOnly: Boolean = false, callback: () -> Unit) {
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                val shape = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(12).toFloat()
                }
                background = if (pressedOnly) RippleDrawable(ColorStateList.valueOf(color), null, shape) else shape
                contentDescription = label
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(tint)
                }, LayoutParams(dp(24), dp(24)))
                addView(TextView(context).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(tint)
                    setPadding(0, dp(5), 0, 0)
                })
                setOnClickListener { callback() }
            }
            addView(column, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                if (childCount > 0) marginStart = dp(4)
            })
        }
        action(if (pinned) "取消置顶" else "置顶", R.drawable.ic_pin,
            if (dark) Color.rgb(47, 62, 93) else Color.rgb(224, 234, 255),
            if (dark) Color.rgb(196, 213, 255) else Color.rgb(42, 80, 164), callback = onPin)
        action("删除", R.drawable.ic_delete,
            if (dark) Color.rgb(85, 49, 56) else Color.rgb(255, 231, 233),
            if (dark) Color.rgb(255, 185, 191) else Color.rgb(176, 47, 60), pressedOnly = true, callback = onDelete)
    }
}
