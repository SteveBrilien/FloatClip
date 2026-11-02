package com.floatclip.app.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Read-only one-file provider used only to hand the verified update APK to Package Installer. */
class UpdateApkProvider:ContentProvider() {
    override fun onCreate()=true
    override fun getType(uri:Uri)=if(valid(uri))"application/vnd.android.package-archive" else null
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        if(mode!="r"||!valid(uri))throw FileNotFoundException()
        val file=apk();if(!file.isFile)throw FileNotFoundException()
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor? {
        if(!valid(uri))return null
        val file=apk();val columns=projection?.toList()?:listOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        val cursor=MatrixCursor(columns.toTypedArray(),1);val row=cursor.newRow()
        for(column in columns)when(column){OpenableColumns.DISPLAY_NAME->row.add("FloatClip-update.apk");OpenableColumns.SIZE->row.add(file.length());else->row.add(null)}
        return cursor
    }
    override fun insert(uri:Uri,values:ContentValues?)=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException()
    private fun apk()=File(requireNotNull(context).filesDir,"updates/FloatClip-update.apk")
    private fun valid(uri:Uri)=uri.authority==requireNotNull(context).packageName+".updates"&&uri.path=="/package"
    companion object { fun uri(packageName:String)=Uri.parse("content://$packageName.updates/package") }
}
