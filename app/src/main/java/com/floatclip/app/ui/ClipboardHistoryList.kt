package com.floatclip.app.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.overlay.ui.EntryGestureRow

/** Only visible rows are materialized. Detached row containers are recycled by ListView. */
class ClipboardHistoryList(context:Context):ListView(context) {
    var automaticImageColumns=false
    private data class Submission(val entries:List<ClipEntry>,val columns:Boolean,val key:String,val hint:String,val color:Int,val create:(ClipEntry)->View)
    private var submission:Submission?=null
    private val reflow=Runnable {
        submission?.let { submit(it.entries,it.columns,it.key,it.hint,it.color,it.create) }
    }
    private fun wideEnough(pixels:Int)=pixels/resources.displayMetrics.density>=400f
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(automaticImageColumns && wideEnough(w)!=wideEnough(oldw)) {
            removeCallbacks(reflow);post(reflow)
        }
    }
    private data class Row(val entries:List<ClipEntry>,val columns:Boolean)
    private class Holder(context:Context):FrameLayout(context) { var row:Row?=null }
    private var rows=emptyList<Row>()
    private var emptyText=""
    private var emptyColor=Color.GRAY
    private var factory:((ClipEntry)->View)?=null
    private var filter=""
    private val prefetch=Runnable {
        val store=com.floatclip.app.clipboard.ClipStore(context)
        rows.drop((lastVisiblePosition-headerViewsCount+1).coerceAtLeast(0)).asSequence().flatMap { it.entries.asSequence() }
            .filter { it.kind=="image" }.take(3).forEach { com.floatclip.app.clipboard.ClipboardImages.prefetch(store,it) }
    }
    override fun onDetachedFromWindow() { removeCallbacks(reflow);removeCallbacks(prefetch);super.onDetachedFromWindow() }
    private val positions=linkedMapOf<String,Pair<Int,Int>>()
    private val historyAdapter=object:BaseAdapter() {
        override fun getCount()=rows.size.coerceAtLeast(1)
        override fun getItem(position:Int):Any?=rows.getOrNull(position)
        override fun getItemId(position:Int)=position.toLong()
        override fun isEnabled(position:Int)=true
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
            val holder=convertView as? Holder ?: Holder(context)
            val row=rows.getOrNull(position)
            if(row==null) {
                holder.removeAllViews();holder.row=null
                holder.addView(TextView(context).apply {
                    text=emptyText;textSize=14f;gravity=Gravity.CENTER;setTextColor(emptyColor)
                    setPadding(dp(10),dp(28),dp(10),dp(28))
                },FrameLayout.LayoutParams(-1,-2))
            } else if(holder.row!=row) {
                cancelGestures(holder)
                holder.removeAllViews();holder.row=row
                val create=factory!!
                val content=if(row.columns && row.entries.first().kind=="image") LinearLayout(context).apply {
                    orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP
                    row.entries.forEachIndexed { index,entry ->
                        addView(create(entry),LinearLayout.LayoutParams(0,-2,1f).apply { if(index>0)marginStart=dp(8) })
                    }
                    if(row.entries.size==1)addView(View(context),LinearLayout.LayoutParams(0,1,1f).apply { marginStart=dp(8) })
                } else create(row.entries.first())
                holder.addView(content,FrameLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            }
            return holder
        }
    }
    init {
        divider=null;dividerHeight=0;cacheColorHint=Color.TRANSPARENT
        setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        overScrollMode=View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled=false
        clipToPadding=false
        setOnScrollListener(object:android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view:android.widget.AbsListView?,state:Int) {
                removeCallbacks(prefetch)
                if(state==android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE)postDelayed(prefetch,120)
            }
            override fun onScroll(view:android.widget.AbsListView?,first:Int,count:Int,total:Int) {
                removeCallbacks(prefetch);postDelayed(prefetch,160)
            }
        })
    }
    fun attachHeader(header:View) {
        check(adapter==null)
        addHeaderView(header,null,true)
        adapter=historyAdapter
    }
    fun submit(entries:List<ClipEntry>,twoColumnImages:Boolean,filterKey:String,hint:String,color:Int,create:(ClipEntry)->View) {
        submission=Submission(entries,twoColumnImages,filterKey,hint,color,create)
        val columns=if(automaticImageColumns)wideEnough(if(width>0)width else resources.displayMetrics.widthPixels) else twoColumnImages
        val changedFilter=filter!=filterKey
        if(changedFilter) {
            positions[filter]=firstVisiblePosition to (getChildAt(0)?.top?:0)
            if(positions.size>32)positions.remove(positions.keys.first())
        }
        val next=buildList {
            var index=0
            while(index<entries.size) {
                val first=entries[index++]
                val group=if(columns && first.kind=="image" && index<entries.size && entries[index].kind=="image")
                    listOf(first,entries[index++]) else listOf(first)
                add(Row(group,columns))
            }
        }
        factory=create;emptyColor=color
        if(!changedFilter && rows==next && emptyText==hint)return
        val previous=firstVisiblePosition to (getChildAt(0)?.top?:0)
        filter=filterKey;rows=next;emptyText=hint
        historyAdapter.notifyDataSetChanged()
        val position=if(changedFilter)positions[filterKey]?: (0 to 0) else previous
        setSelectionFromTop(position.first.coerceAtMost(count-1).coerceAtLeast(0),position.second)
    }
    fun cancelVisibleGestures() { for(i in 0 until childCount)cancelGestures(getChildAt(i)) }
    private fun cancelGestures(view:View) {
        if(view is EntryGestureRow)view.cancelPendingGestures()
        if(view is ViewGroup)for(i in 0 until view.childCount)cancelGestures(view.getChildAt(i))
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
}
