package com.floatclip.app.overlay.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.floatclip.app.R

class EntrySwipeActions(
    context: Context, pinned: Boolean, onPin: () -> Unit, onDelete: () -> Unit,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    init {
        orientation = HORIZONTAL
        fun action(label: String, icon: Int, color: Int, callback: () -> Unit) {
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(14).toFloat()
                }
                contentDescription = label
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                }, LayoutParams(dp(20), dp(20)))
                addView(TextView(context).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(5), 0, 0)
                })
                setOnClickListener { callback() }
            }
            addView(column, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                if (childCount > 0) marginStart = dp(4)
            })
        }
        action(if (pinned) "取消置顶" else "置顶", R.drawable.ic_pin, Color.rgb(67, 106, 204), onPin)
        action("删除", R.drawable.ic_delete, Color.rgb(199, 65, 76), onDelete)
    }
}
