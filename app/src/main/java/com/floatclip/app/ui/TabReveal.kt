package com.floatclip.app.ui

import android.view.View
import android.widget.HorizontalScrollView

/** Keep the leading tabs readable; overflow selections move toward the middle. */
object TabReveal {
    fun target(left:Int,width:Int,viewport:Int,content:Int):Int =
        if(content<=viewport || left+width<=viewport) 0
        else (left+width/2-viewport/2).coerceIn(0,(content-viewport).coerceAtLeast(0))

    fun reveal(scroll:HorizontalScrollView,selected:View) {
        scroll.post {
            if(scroll.width<=0 || selected.parent!==scroll.getChildAt(0))return@post
            val x=target(selected.left,selected.width,scroll.width,scroll.getChildAt(0).width)
            if(x!=scroll.scrollX)scroll.smoothScrollTo(x,0)
        }
    }
}
