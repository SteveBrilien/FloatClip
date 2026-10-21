package com.floatclip.app.prefs

import com.floatclip.app.R

object CategoryIcons {
    data class Choice(val key:String,val label:String,val res:Int)
    val choices=listOf(
        Choice("folder","文件夹",R.drawable.ic_folder),
        Choice("star","星标",R.drawable.ic_star),
        Choice("link","链接",R.drawable.ic_link),
        Choice("mail","邮件",R.drawable.ic_mail),
        Choice("phone","电话",R.drawable.ic_phone),
        Choice("location","位置",R.drawable.ic_location),
        Choice("code","代码",R.drawable.ic_code),
        Choice("work","工作",R.drawable.ic_work),
        Choice("lock","保护",R.drawable.ic_lock),
    )
    fun resource(key:String?)=choices.firstOrNull{it.key==key}?.res ?: R.drawable.ic_folder
    fun defaultKey(category:String)=when(category) {
        "常用"->"star";"网址"->"link";"邮箱"->"mail";"电话"->"phone";"地址"->"location";"验证码"->"lock";"工作"->"work";else->"folder"
    }
}
