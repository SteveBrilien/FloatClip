package com.floatclip.app.prefs

import android.graphics.Color

/** Semantic feature colors, shared by category management and both clipboard surfaces. */
object CategoryColors {
    val keys=listOf("blue","teal","purple","orange","red","neutral")
    val labels=listOf("蓝色","青绿","紫色","橙色","红色","中性")
    fun color(key:String,dark:Boolean):Int {
        val index=keys.indexOf(key).coerceAtLeast(0)
        return Color.parseColor((if(dark)listOf("#9AB7FF","#7EDDB7","#C8ACEF","#E9BA8B","#F7B4C3","#BBC4E5") else listOf("#225BC4","#14755F","#7650A3","#956025","#B33643","#596C85"))[index])
    }
}
