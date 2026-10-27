package com.floatclip.app.overlay.ui

import android.util.Log
import android.view.View
import android.view.WindowManager
import java.util.IdentityHashMap

/** Own all service surfaces. Late callbacks cannot update or reattach a stopped service. */
class OverlayWindows(private val manager:WindowManager):AutoCloseable {
    private val surfaces=IdentityHashMap<View,WindowManager.LayoutParams>()
    private var closed=false
    fun add(view:View,params:WindowManager.LayoutParams):Boolean {
        if(closed)return false
        if(surfaces.containsKey(view))return update(view,params)
        return try { manager.addView(view,params);surfaces[view]=params;true }
        catch(error:RuntimeException) { rejected(error);false }
    }
    fun update(view:View,params:WindowManager.LayoutParams):Boolean {
        if(closed || !surfaces.containsKey(view))return false
        return try { manager.updateViewLayout(view,params);surfaces[view]=params;true }
        catch(error:IllegalArgumentException) {
            // Android/OEM may already have detached a surface. Recover it once while active.
            surfaces.remove(view)
            runCatching { manager.removeViewImmediate(view) }
            add(view,params)
        } catch(error:RuntimeException) { rejected(error);false }
    }
    fun remove(view:View) {
        if(surfaces.remove(view)==null)return
        try { manager.removeViewImmediate(view) }
        catch(error:RuntimeException) { rejected(error) }
    }
    override fun close() {
        if(closed)return
        closed=true
        surfaces.keys.toList().forEach(::remove)
    }
    private fun rejected(error:RuntimeException) {
        if(error !is IllegalArgumentException && error !is SecurityException &&
            error !is WindowManager.BadTokenException && error !is WindowManager.InvalidDisplayException)throw error
        Log.w("FloatClipOverlay","Overlay surface unavailable",error)
    }
}
