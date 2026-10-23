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
import com.floatclip.probe.FloatClipAccent

class EntryLockAction(
    context: Context,
    locked: Boolean,
    dark: Boolean,
    accentTheme: String,
    callback: () -> Unit,
) : LinearLayout(context) {
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    init {
        orientation=VERTICAL
        gravity=Gravity.CENTER
        val accent=FloatClipAccent.color(accentTheme,dark)
        val bg=if(dark) Color.rgb(37,53,72) else Color.rgb(232,241,252)
        background=GradientDrawable().apply { setColor(bg); cornerRadius=dp(12).toFloat() }
        contentDescription=if(locked)"取消保护" else "保护"
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_lock)
            imageTintList=ColorStateList.valueOf(accent)
        },LayoutParams(dp(24),dp(24)))
        addView(TextView(context).apply {
            text=if(locked)"取消保护" else "保护"
            textSize=13f
            gravity=Gravity.CENTER
            setTextColor(accent)
            setPadding(0,dp(5),0,0)
        })
        setOnClickListener { callback() }
    }
}
