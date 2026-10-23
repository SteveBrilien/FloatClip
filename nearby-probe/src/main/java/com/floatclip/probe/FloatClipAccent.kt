package com.floatclip.probe

import android.graphics.Color

object FloatClipAccent {
    data class Choice(val id:String,val label:String,val light:Int,val dark:Int)
    val choices=listOf(
        Choice("blue","蓝",Color.rgb(36,93,206),Color.rgb(122,162,247)),
        Choice("cyan","青",Color.rgb(0,120,153),Color.rgb(125,207,255)),
        Choice("green","绿",Color.rgb(24,126,88),Color.rgb(158,206,106)),
        Choice("purple","紫",Color.rgb(111,76,180),Color.rgb(187,154,247)),
        Choice("orange","橙",Color.rgb(190,92,25),Color.rgb(255,158,100)),
        Choice("rose","红",Color.rgb(178,63,88),Color.rgb(247,118,142)),
    )
    fun normalize(id:String?)=choices.firstOrNull{it.id==id}?.id ?: "blue"
    fun color(id:String?,dark:Boolean):Int=choices.firstOrNull{it.id==normalize(id)}!!.let{if(dark)it.dark else it.light}
    fun label(id:String?)=choices.firstOrNull{it.id==normalize(id)}!!.label
}
