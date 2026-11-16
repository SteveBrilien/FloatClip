package com.floatclip.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import com.floatclip.app.R
import com.floatclip.app.prefs.OverlayPreferences

/** Shared file surfaces and action states. Dimensions are dp, text remains sp. */
object FileUi {
    const val CARD_RADIUS=12
    const val CONTROL_RADIUS=8
    const val HIT=48
    data class Palette(val page:Int,val surface:Int,val text:Int,val muted:Int,val border:Int,val accent:Int,val selected:Int,val success:Int,val destructive:Int)
    fun palette(context:Context):Palette {
        val mode=OverlayPreferences(context).themeMode().name
        if(mode=="KLEIN")return Palette(
            Color.rgb(15,34,84),Color.rgb(23,47,100),Color.rgb(242,247,255),Color.rgb(183,206,241),
            Color.rgb(47,78,135),Color.rgb(160,205,255),Color.rgb(35,69,137),
            Color.rgb(126,219,185),Color.rgb(255,165,177)
        )
        val dark=when(mode){"DARK"->true;"LIGHT"->false;else->context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES}
        fun c(light:String,night:String)=Color.parseColor(if(dark)night else light)
        return Palette(c("#F0F3F8","#1A1B26"),c("#FFFFFF","#24283B"),c("#19283F","#E4E9FF"),c("#596C85","#BBC4E5"),c("#DCE3EF","#414868"),com.floatclip.probe.FloatClipAccent.color(OverlayPreferences(context).accentTheme(),dark),c("#EAF0FF","#303C5B"),c("#237858","#7EDDB7"),c("#B3263B","#FF9BA8"))
    }
    fun shape(context:Context,fill:Int,border:Int=Color.TRANSPARENT,radius:Int=CARD_RADIUS)=GradientDrawable().apply {
        val density=context.resources.displayMetrics.density
        setColor(fill);cornerRadius=radius*density;setStroke(density.toInt().coerceAtLeast(1),border)
    }
    fun action(context:Context,p:Palette,filled:Boolean=false)=RippleDrawable(ColorStateList.valueOf(p.selected),shape(context,if(filled)p.selected else Color.TRANSPARENT,radius=CONTROL_RADIUS),shape(context,Color.WHITE,radius=CONTROL_RADIUS))
    fun fileGlyph(name:String):Int {
        val ext=name.substringAfterLast('.', "").lowercase()
        return when(ext) {
            "jpg","jpeg","png","gif","webp","bmp","heic","heif","avif","svg" -> R.drawable.ic_image
            "mp4","mkv","mov","avi","webm","m4v","3gp","ts","mpeg","mpg" -> R.drawable.ic_file_video
            "mp3","wav","flac","aac","m4a","ogg","opus","amr","wma" -> R.drawable.ic_file_audio
            "pdf" -> R.drawable.ic_file_pdf
            "zip","rar","7z","tar","gz","tgz","bz2","xz","zst" -> R.drawable.ic_file_archive
            "xls","xlsx","xlsm","ods","csv","tsv" -> R.drawable.ic_file_sheet
            "ppt","pptx","pps","ppsx","odp","key" -> R.drawable.ic_file_presentation
            "txt","rtf","log","ini","cfg","conf" -> R.drawable.ic_file_text
            "kt","kts","java","c","cc","cpp","h","hpp","py","js","jsx","ts","tsx","html","htm","css","scss","xml","json","yaml","yml","toml","sh","ps1","sql","md" -> R.drawable.ic_code
            "apk","aab","apks","exe","msi","dmg","pkg","deb","rpm" -> R.drawable.ic_file_app
            else -> R.drawable.ic_document
        }
    }
    fun size(bytes:Long):String=when {
        bytes==0L->"0 KB";bytes<1024->"< 1 KB"
        bytes<1024L*1024->"%.1f KB".format(bytes/1024.0)
        bytes<1024L*1024*1024->"%.1f MB".format(bytes/1048576.0)
        else->"%.1f GB".format(bytes/1073741824.0)
    }
}
