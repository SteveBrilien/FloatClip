package com.floatclip.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import com.floatclip.app.model.ClipEntry
import java.util.WeakHashMap

/** Text keeps full width; only consecutive images can share a row. */
object ClipboardLayout {
    private data class Group(val signature:String,val entries:List<ClipEntry>)
    private val generations=WeakHashMap<LinearLayout,Int>()
    private val signatures=WeakHashMap<View,String>()

    private fun groups(entries:List<ClipEntry>,twoColumnImages:Boolean):List<Group>{
        val out=mutableListOf<Group>();var index=0
        while(index<entries.size){
            val first=entries[index]
            val rows=if(twoColumnImages&&first.kind=="image"){
                val pair=mutableListOf(first);index++
                if(index<entries.size&&entries[index].kind=="image"){pair+=entries[index];index++}
                pair
            }else{index++;listOf(first)}
            val signature=(if(twoColumnImages&&first.kind=="image")"images:" else "row:")+rows.joinToString("|"){entry->
                listOf(entry.key,entry.kind,entry.pinned,entry.locked,entry.category,entry.updatedAt,entry.imageRef).joinToString(":")
            }
            out+=Group(signature,rows)
        }
        return out
    }

    private fun createGroup(target:LinearLayout,group:Group,twoColumnImages:Boolean,create:(ClipEntry)->View):View {
        val context=target.context;val density=target.resources.displayMetrics.density
        fun dp(value:Int)=(value*density+.5f).toInt()
        val view=if(twoColumnImages&&group.entries.first().kind=="image"){
            LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP
                group.entries.forEachIndexed{index,entry->
                    addView(create(entry),LinearLayout.LayoutParams(0,-2,1f).apply{if(index>0)marginStart=dp(8)})
                }
                if(group.entries.size==1)addView(View(context),LinearLayout.LayoutParams(0,1,1f).apply{marginStart=dp(8)})
            }
        }else create(group.entries.first())
        signatures[view]=group.signature
        return view
    }

    /** Invalidate callbacks before clearing, so an old filter cannot restore removed cards. */
    fun clear(target:LinearLayout){
        generations[target]=(generations[target]?:0)+1
        for(index in 0 until target.childCount)target.getChildAt(index).animate().setListener(null).cancel()
        target.removeAllViews()
    }

    fun render(target:LinearLayout,entries:List<ClipEntry>,twoColumnImages:Boolean,create:(ClipEntry)->View){
        clear(target)
        groups(entries,twoColumnImages).forEach{group->
            target.addView(createGroup(target,group,twoColumnImages,create),LinearLayout.LayoutParams(-1,-2).apply{
                bottomMargin=(10*target.resources.displayMetrics.density+.5f).toInt()
            })
        }
    }

    /** Reuse unchanged cards and commit filters immediately, without animating every bitmap. */
    fun renderRetained(target:LinearLayout,entries:List<ClipEntry>,twoColumnImages:Boolean,create:(ClipEntry)->View) {
        generations[target]=(generations[target]?:0)+1
        val existing=(0 until target.childCount).map{target.getChildAt(it)}
        existing.forEach{it.animate().setListener(null).cancel();it.alpha=1f;it.scaleX=1f;it.scaleY=1f;it.translationY=0f}
        val reusable=existing.mapNotNull{v->signatures[v]?.let{it to v}}.toMap()
        val ordered=groups(entries,twoColumnImages).map{g->reusable[g.signature]?:createGroup(target,g,twoColumnImages,create)}
        existing.filter{it !in ordered}.forEach{target.removeView(it)}
        ordered.forEachIndexed{index,v->
            if(v.parent!==target)target.addView(v,index,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=(10*target.resources.displayMetrics.density+.5f).toInt()})
            else if(target.indexOfChild(v)!=index){target.removeView(v);target.addView(v,index)}
        }
    }

    /**
     * Retain unchanged views, shrink removed rows and slide new rows into position.
     * The footer is added only after the current generation has committed its children.
     */
    fun renderAnimated(target:LinearLayout,entries:List<ClipEntry>,twoColumnImages:Boolean,create:(ClipEntry)->View,durationMs:Long=190L,onRendered:()->Unit={}){
        if(!ValueAnimator.areAnimatorsEnabled()||target.childCount==0){
            render(target,entries,twoColumnImages,create);onRendered();return
        }
        val density=target.resources.displayMetrics.density
        fun dp(value:Int)=(value*density+.5f).toInt()
        val generation=(generations[target]?:0)+1;generations[target]=generation
        val wanted=groups(entries,twoColumnImages)
        val wantedSignatures=wanted.map{it.signature}.toSet()
        val existing=(0 until target.childCount).map{target.getChildAt(it)}
        val oldTop=existing.associateWith{it.top+it.translationY}
        existing.forEach{it.animate().setListener(null).cancel()}
        val reusable=existing.mapNotNull{view->signatures[view]?.let{it to view}}.toMap().toMutableMap()
        val exiting=existing.filter{signatures[it] !in wantedSignatures}

        fun applyFinal(){
            if(generations[target]!=generation)return
            exiting.forEach{if(it.parent===target)target.removeView(it)}
            val ordered=wanted.map{group->
                reusable.remove(group.signature)?:createGroup(target,group,twoColumnImages,create).also{
                    it.alpha=0f;it.scaleX=.94f;it.scaleY=.94f;it.translationY=dp(18).toFloat()
                }
            }
            ordered.forEachIndexed{index,view->
                if(view.parent!==target)target.addView(view,index,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
                else if(target.indexOfChild(view)!=index){target.removeView(view);target.addView(view,index)}
            }
            while(target.childCount>ordered.size)target.removeViewAt(target.childCount-1)
            onRendered()
            target.post{
                if(generations[target]!=generation)return@post
                ordered.forEach{view->
                    oldTop[view]?.let{view.translationY=it-view.top}
                    view.animate().setListener(null).alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                        .setInterpolator(DecelerateInterpolator()).setDuration(durationMs).start()
                }
            }
        }

        if(exiting.isEmpty()){applyFinal();return}
        var remaining=exiting.size
        exiting.forEach{view->
            view.animate().alpha(0f).scaleX(.82f).scaleY(.82f).translationY(dp(8).toFloat())
                .setInterpolator(DecelerateInterpolator()).setDuration((durationMs*.72f).toLong())
                .setListener(object:AnimatorListenerAdapter(){
                    override fun onAnimationEnd(animation:Animator){
                        view.animate().setListener(null)
                        remaining--
                        if(remaining==0)applyFinal()
                    }
                }).start()
        }
    }
}
