package com.floatclip.app.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.floatclip.app.R
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.theme.OverlayPalette
import kotlin.math.abs
import kotlin.math.max

/** In-place, theme-aware text viewer. Text scrolling wins over category paging. */
class ClipTextViewerView(
    context: Context, private val store: ClipStore, entries: List<ClipEntry>, startId: Long,
    private val palette: OverlayPalette,
    private val close: () -> Unit,
) : FrameLayout(context) {
    private val groups = entries.filter { it.kind == "text" }.groupBy { it.category }
    private val categories = (CategoryStore(context).categories() + groups.keys).distinct().filter(groups::containsKey)
    private var groupIndex = categories.indexOfFirst { c -> groups.getValue(c).any { it.id == startId } }.coerceAtLeast(0)
    private val group get() = categories.getOrNull(groupIndex)?.let(groups::get).orEmpty()
    private var position = group.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    private val counter = TextView(context).apply { textSize = 17f; gravity = Gravity.CENTER; setTextColor(palette.primaryText) }
    private val category = TextView(context).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(palette.secondaryText) }
    private val contentText = TextView(context).apply {
        textSize = 16f; setTextColor(palette.primaryText); setTextIsSelectable(true)
        setLineSpacing(dp(4).toFloat(),1.10f)
        setPadding(dp(22),dp(18),dp(22),dp(42))
        contentDescription = "剪贴板全文，长按可选择文字，上下滑动可阅读"
    }
    private val scroll = object: ScrollView(context) {
        private var startX=0f;private var startY=0f;private var beganAtTop=false;private var beganAtBottom=false
        private var beganAt=0L;private var touchedMultiple=false
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX=event.x;startY=event.y;beganAt=SystemClock.uptimeMillis()
                    beganAtTop=!canScrollVertically(-1);beganAtBottom=!canScrollVertically(1)
                    touchedMultiple=false
                }
                MotionEvent.ACTION_POINTER_DOWN -> touchedMultiple=true
            }
            // The ScrollView must receive the original events: never intercept its vertical
            // drag just to detect navigation at a content boundary.
            val handled=super.dispatchTouchEvent(event)
            if(event.actionMasked==MotionEvent.ACTION_UP&&!touchedMultiple &&
                SystemClock.uptimeMillis()-beganAt<1000L) {
                val dx=event.x-startX;val dy=event.y-startY
                val threshold=max(dp(64).toFloat(),width*.15f)
                when {
                    abs(dx)>threshold && abs(dx)>abs(dy)*1.3f -> page(if(dx<0)1 else -1)
                    abs(dy)>threshold && abs(dy)>abs(dx)*1.3f &&
                        ((dy>0&&beganAtTop)|| (dy<0&&beganAtBottom)) -> categoryPage(if(dy<0)1 else -1)
                }
            }
            return handled
        }
    }.apply { isFillViewport=false;isVerticalScrollBarEnabled=true;addView(contentText) }
    private val pin=icon(R.drawable.ic_pin,"置顶") { current()?.let { store.togglePinned(it.id); refresh() } }
    private val lock=icon(R.drawable.ic_unlock,"保护") { current()?.let { store.toggleLocked(it.id); refresh() } }
    init {
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(
            palette.panelBackground,palette.rowBackground,palette.panelBackground
        )).apply { alpha=250 }
        val layout=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        val top=FrameLayout(context)
        top.addView(LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            addView(counter,LinearLayout.LayoutParams(-1,dp(26)))
            addView(category,LinearLayout.LayoutParams(-1,dp(18)))
        },LayoutParams(-1,dp(48),Gravity.CENTER).apply{marginStart=dp(60);marginEnd=dp(60)})
        top.addView(icon(R.drawable.ic_chevron_left,"返回",close),LayoutParams(dp(48),dp(48),Gravity.START))
        top.addView(icon(R.drawable.ic_close,"关闭全文",close),LayoutParams(dp(48),dp(48),Gravity.END))
        layout.addView(top,LinearLayout.LayoutParams(-1,dp(52)).apply{marginStart=dp(8);marginEnd=dp(8);topMargin=dp(8)})
        layout.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val bottom=LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_chevron_left,"上一条") { page(-1) },LinearLayout.LayoutParams(dp(44),dp(48)))
            addView(icon(R.drawable.ic_chevron_right,"下一条") { page(1) },LinearLayout.LayoutParams(dp(44),dp(48)))
            addView(pin,LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(lock,LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(View(context),LinearLayout.LayoutParams(0,dp(1),1f))
            addView(icon(R.drawable.ic_copy_paste,"复制全文") {
                current()?.let { entry ->
                    runCatching { ClipboardWrites.copyByUser(context,context.getSystemService(ClipboardManager::class.java),entry.text) }
                        .onSuccess { Toast.makeText(context,"已复制",Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context,"复制失败，请重试",Toast.LENGTH_SHORT).show() }
                }
            },LinearLayout.LayoutParams(dp(48),dp(48)))
        }
        layout.addView(bottom,LinearLayout.LayoutParams(-1,dp(56)).apply{marginStart=dp(12);marginEnd=dp(12);bottomMargin=dp(16)})
        addView(layout,LayoutParams(-1,-1));refresh()
    }
    fun currentEntryId():Long?=group.getOrNull(position)?.id
    private fun current(): ClipEntry?=group.getOrNull(position)?.let { selection ->
        store.entries().firstOrNull { it.id==selection.id } ?: selection
    }
    fun page(delta:Int):Boolean {
        val next=position+delta
        if(next !in group.indices)return false
        position=next;refresh();return true
    }
    fun categoryPage(delta:Int):Boolean {
        val next=groupIndex+delta
        if(next !in categories.indices)return false
        groupIndex=next;position=0;refresh();return true
    }
    private fun refresh() {
        val entry=current() ?: return
        counter.text="${position+1}/${group.size}";category.text=entry.category
        contentText.text=entry.text;scroll.scrollTo(0,0)
        pin.setImageResource(if(entry.pinned)R.drawable.ic_pin_filled else R.drawable.ic_pin)
        lock.setImageResource(if(entry.locked)R.drawable.ic_lock_filled else R.drawable.ic_unlock)
    }
    private fun icon(resource:Int,label:String,action:()->Unit)=ImageView(context).apply {
        setImageResource(resource);contentDescription=label
        imageTintList=ColorStateList.valueOf(palette.primaryText)
        setPadding(dp(12),dp(12),dp(12),dp(12))
        background=RippleDrawable(ColorStateList.valueOf(palette.rowBackground),GradientDrawable().apply {
            setColor(Color.TRANSPARENT);cornerRadius=dp(13).toFloat()
        },null)
        isClickable=true;isFocusable=true;setOnClickListener{action()}
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
}
