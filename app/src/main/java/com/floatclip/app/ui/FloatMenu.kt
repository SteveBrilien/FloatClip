package com.floatclip.app.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.floatclip.app.R

/** Compact actions stay next to their trigger, without a modal entrance animation. */
object FloatMenu {
    fun show(anchor:View,items:List<String>,selected:Int=-1,onSelect:(Int)->Unit):PopupWindow {
        val context=anchor.context
        val p=FileUi.palette(context)
        fun dp(n:Int)=(n*context.resources.displayMetrics.density+.5f).toInt()
        val list=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(6),dp(6),dp(6),dp(6))
            background=FileUi.shape(context,p.surface,p.border,16)
        }
        val scroll=ScrollView(context).apply { isVerticalScrollBarEnabled=false;addView(list) }
        val popup=PopupWindow(scroll,dp(224).coerceAtMost(context.resources.displayMetrics.widthPixels-dp(32)),ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable=true;elevation=dp(8).toFloat();animationStyle=0
            inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        items.forEachIndexed { index,label ->
            val destructive=label=="删除" || label.startsWith("清除") || label.startsWith("清理")
            val color=if(index==selected)p.accent else if(destructive)p.destructive else p.text
            if(destructive)list.addView(View(context).apply{setBackgroundColor(p.border)},LinearLayout.LayoutParams(-1,dp(1)).apply{setMargins(dp(8),dp(4),dp(8),dp(4))})
            val row=LinearLayout(context).apply {
                gravity=android.view.Gravity.CENTER_VERTICAL
                minimumHeight=dp(FileUi.HIT);setPadding(dp(10),0,dp(10),0)
                background=FileUi.action(context,p,index==selected)
                isFocusable=true;contentDescription=label;isSelected=index==selected
            }
            val icon=when {
                index==selected->R.drawable.ic_file_downloaded
                label.contains("复制")->R.drawable.ic_copy_paste
                label.contains("置顶")->R.drawable.ic_pin
                label.contains("保护")->R.drawable.ic_lock
                destructive->R.drawable.ic_delete
                else->R.drawable.ic_folder
            }
            row.addView(ImageView(context).apply{setImageResource(icon);imageTintList=ColorStateList.valueOf(color);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(20),dp(20)).apply{marginEnd=dp(12)})
            row.addView(TextView(context).apply{text=label;textSize=14f;setTextColor(color);includeFontPadding=false;maxLines=2;setPadding(0,dp(10),0,dp(10))},LinearLayout.LayoutParams(0,-2,1f))
            row.setOnClickListener{popup.dismiss();onSelect(index)}
            list.addView(row,LinearLayout.LayoutParams(-1,-2))
        }
        list.measure(View.MeasureSpec.makeMeasureSpec(popup.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        popup.height=list.measuredHeight.coerceAtMost(context.resources.displayMetrics.heightPixels/2)
        popup.showAsDropDown(anchor,0,0,android.view.Gravity.END)
        return popup
    }
}
