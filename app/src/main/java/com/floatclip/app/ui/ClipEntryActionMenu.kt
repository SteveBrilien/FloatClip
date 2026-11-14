package com.floatclip.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.floatclip.app.R
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.CategoryIcons
import com.floatclip.app.prefs.CategoryStore

/** Same compact, icon-led single-column action hierarchy as the floating clipboard. */
object ClipEntryActionMenu {
    enum class Action { OPEN, COPY, PIN, LOCK, CATEGORY, DELETE }

    fun show(anchor: View, entry: ClipEntry, categories: CategoryStore, onAction: (Action, String?) -> Unit): PopupWindow {
        val context=anchor.context
        val palette=FileUi.palette(context)
        fun dp(value:Int)=(value*context.resources.displayMetrics.density+.5f).toInt()
        val width=dp(216).coerceAtMost(context.resources.displayMetrics.widthPixels-dp(28))
        val content=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(6),dp(6),dp(6),dp(6))
        }
        val scroll=ScrollView(context).apply {
            isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER
            addView(content)
            background=FileUi.shape(context,palette.surface,palette.border,20)
            clipToOutline=true
        }
        val popup=PopupWindow(scroll,width,ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation=dp(10).toFloat()
            isOutsideTouchable=true
            animationStyle=0
            inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        fun done(action:Action,value:String?=null) {popup.dismiss();onAction(action,value)}
        lateinit var render:(Int)->Unit
        render={mode ->
            content.removeAllViews()
            fun add(label:String,icon:Int,tint:Int=palette.text,divider:Boolean=false,selected:Boolean=false,click:()->Unit) {
                if(divider) content.addView(View(context).apply{setBackgroundColor(palette.border)},
                    LinearLayout.LayoutParams(-1,dp(1)).apply {setMargins(dp(10),dp(4),dp(10),dp(4))})
                val row=LinearLayout(context).apply {
                    orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
                    minimumHeight=dp(48)
                    setPadding(dp(10),0,dp(12),0)
                    background=RippleDrawable(ColorStateList.valueOf(palette.selected),
                        FileUi.shape(context,if(selected)palette.selected else Color.TRANSPARENT,radius=9),
                        FileUi.shape(context,Color.WHITE,radius=9))
                    contentDescription=label
                    isFocusable=true;isClickable=true
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        click()
                    }
                }
                row.addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList=ColorStateList.valueOf(tint)
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(dp(24),dp(24)).apply {marginEnd=dp(12)})
                row.addView(TextView(context).apply {
                    text=label;textSize=14f;setTextColor(tint)
                    gravity=Gravity.CENTER_VERTICAL
                    setPadding(0,dp(10),0,dp(10));includeFontPadding=false
                },LinearLayout.LayoutParams(0,-2,1f))
                content.addView(row,LinearLayout.LayoutParams(-1,-2))
            }
            when(mode) {
                1 -> {
                    add("返回",R.drawable.ic_chevron_left){render(0)}
                    categories.categories().distinct().forEach {category->
                        add(if(category==entry.category)"✓  $category" else category,
                            CategoryIcons.resource(categories.iconKey(category)),
                            if(category==entry.category)palette.accent else palette.text,
                            selected=category==entry.category) { done(Action.CATEGORY,category) }
                    }
                }
                2 -> {
                    add("删除这条记录？",R.drawable.ic_document,palette.muted) {render(0)}
                    add("返回",R.drawable.ic_chevron_left){render(0)}
                    add("确认删除",R.drawable.ic_delete,palette.destructive,divider=true){done(Action.DELETE)}
                }
                else -> {
                    add(if(entry.kind=="image")"全屏查看图片" else "查看全文",
                        if(entry.kind=="image")R.drawable.ic_image else R.drawable.ic_document) {done(Action.OPEN)}
                    add(if(entry.kind=="image")"复制图片" else "复制",R.drawable.ic_copy_paste){done(Action.COPY)}
                    add(if(entry.pinned)"取消置顶" else "置顶",R.drawable.ic_pin){done(Action.PIN)}
                    add(if(entry.locked)"取消保护" else "保护",R.drawable.ic_lock){done(Action.LOCK)}
                    add("更改分类",R.drawable.ic_category){render(1)}
                    add("删除",R.drawable.ic_delete,palette.destructive,divider=true){render(2)}
                }
            }
            content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            popup.height=content.measuredHeight.coerceAtMost(context.resources.displayMetrics.heightPixels*2/3)
            if(popup.isShowing) popup.update(width,popup.height)
        }
        render(0)
        // As in the floating sheet, prefer below the selected card but reverse above if needed.
        val position=IntArray(2);anchor.getLocationOnScreen(position)
        val availableBelow=context.resources.displayMetrics.heightPixels-(position[1]+anchor.height)
        val y=if(availableBelow>=popup.height+dp(8))position[1]+anchor.height+dp(4)
            else (position[1]-popup.height-dp(4)).coerceAtLeast(dp(8))
        popup.showAtLocation(anchor.rootView,Gravity.TOP or Gravity.END,dp(12),y)
        return popup
    }
}
