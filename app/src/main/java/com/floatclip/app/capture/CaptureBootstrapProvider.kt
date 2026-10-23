package com.floatclip.app.capture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.floatclip.app.sync.SyncPreferences
import com.floatclip.app.sync.SyncRuntime

/** Only an authorized ADB shell can fetch the ephemeral local bridge credential. */
class CaptureBootstrapProvider:ContentProvider() {
    override fun onCreate()=true
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        checkShell(Binder.getCallingUid())
        require(method=="session")
        val prefs=SyncPreferences(requireNotNull(context))
        if(!prefs.backgroundCapture())return Bundle().apply{putString("state","disabled")}
        if(!prefs.enabled())return Bundle().apply{putString("state","paused")}
        if(SyncRuntime.current()==null&&prefs.keepAlive())runCatching{SyncRuntime.refresh(requireNotNull(context))}
        return SyncRuntime.current()?.captureBootstrap()?:Bundle().apply{putString("state","waiting")}
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
    override fun getType(uri:Uri):String?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
    companion object {
        internal fun checkShell(uid:Int){if(uid!=2000)throw SecurityException("Authorized ADB shell required")}
    }
}
