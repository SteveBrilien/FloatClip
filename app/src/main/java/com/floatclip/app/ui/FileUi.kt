package com.floatclip.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import com.floatclip.app.prefs.OverlayPreferences

/** Shared file surfaces and action states. Dimensions are dp, text remains sp. */
object FileUi {
    const val CARD_RADIUS=12
    const val CONTROL_RADIUS=8
    const val HIT=48
    data class Palette(val page:Int,val surface:Int,val text:Int,val muted:Int,val border:Int,val accent:Int,val selected:Int,val success:Int)
    fun palette(context:Context):Palette {
        val dark=when(OverlayPreferences(context).themeMode().name){"DARK"->true;"LIGHT"->false;else->context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES}
        fun c(light:String,night:String)=Color.parseColor(if(dark)night else light)
        return Palette(c("#F0F3F8","#1A1B26"),c("#FFFFFF","#24283B"),c("#19283F","#E4E9FF"),c("#596C85","#BBC4E5"),c("#DCE3EF","#414868"),c("#225BC4","#9AB7FF"),c("#EAF0FF","#303C5B"),c("#237858","#7EDDB7"))
    }
    fun shape(context:Context,fill:Int,border:Int=Color.TRANSPARENT,radius:Int=CARD_RADIUS)=GradientDrawable().apply {
        val density=context.resources.displayMetrics.density
        setColor(fill);cornerRadius=radius*density;setStroke(density.toInt().coerceAtLeast(1),border)
    }
    fun action(context:Context,p:Palette,filled:Boolean=false)=RippleDrawable(ColorStateList.valueOf(p.selected),shape(context,if(filled)p.selected else Color.TRANSPARENT,radius=CONTROL_RADIUS),shape(context,Color.WHITE,radius=CONTROL_RADIUS))
    fun size(bytes:Long):String=when {
        bytes==0L->"0 KB";bytes<1024->"< 1 KB"
        bytes<1024L*1024->"%.1f KB".format(bytes/1024.0)
        bytes<1024L*1024*1024->"%.1f MB".format(bytes/1048576.0)
        else->"%.1f GB".format(bytes/1073741824.0)
    }
}
