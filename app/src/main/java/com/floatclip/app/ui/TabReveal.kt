package com.floatclip.app.ui

import android.view.View
import android.widget.HorizontalScrollView

/** Keep leading tabs visible and allow selected overflow tabs to be centered. */
object TabReveal {
    fun target(left: Int, width: Int, viewport: Int, content: Int, center: Boolean = false): Int =
        if (content <= viewport || !center && left + width <= viewport) 0
        else (left + width / 2 - viewport / 2).coerceIn(0, content - viewport)

    fun reveal(scroll: HorizontalScrollView, selected: View?, center: Boolean = false) {
        if (selected == null) return
        val move = Runnable {
            val row = scroll.getChildAt(0) ?: return@Runnable
            if (scroll.width <= 0 || selected.parent !== row) return@Runnable
            val viewport = if (center) scroll.width - scroll.paddingLeft - scroll.paddingRight else scroll.width
            if (viewport <= 0) return@Runnable
            val x = target(selected.left, selected.width, viewport, row.width, center)
            if (x != scroll.scrollX) scroll.smoothScrollTo(x, 0)
        }
        if (!selected.isLaidOut || selected.isLayoutRequested) {
            selected.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                                            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    view.removeOnLayoutChangeListener(this)
                    scroll.post(move)
                }
            })
        } else scroll.post(move)
    }
}
