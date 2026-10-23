package com.floatclip.app.files

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException

/** A granted URI identifies one catalog entry, never an arbitrary filesystem path. */
class SharedFileProvider:ContentProvider() {
    override fun onCreate()=true
    private fun row(uri:Uri):SharedFile {
        val app=context?:throw FileNotFoundException()
        if(uri.scheme!="content"||uri.authority!="${app.packageName}.shared-files"||uri.pathSegments.size!=2||uri.pathSegments[0]!="file"||uri.query!=null||uri.fragment!=null)throw FileNotFoundException()
        return FileRuntime.get(app).byKey(uri.pathSegments[1])?:throw FileNotFoundException("文件已移除")
    }
    override fun getType(uri:Uri)=mime(row(uri).name)
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        if(mode!="r")throw FileNotFoundException("文件仅允许读取")
        val file=FileRuntime.get(context!!).local(row(uri))?:throw FileNotFoundException("请先下载文件")
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val entry=row(uri);val columns=(projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).filter{it==OpenableColumns.DISPLAY_NAME||it==OpenableColumns.SIZE}.toTypedArray()
        return MatrixCursor(columns).apply{addRow(columns.map{if(it==OpenableColumns.DISPLAY_NAME)entry.name else entry.size})}
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    companion object {
        fun uri(packageName:String,key:String):Uri=Uri.Builder().scheme("content").authority("$packageName.shared-files").appendPath("file").appendPath(key).build()
        fun mime(name:String)=MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.',"").lowercase())?:"application/octet-stream"
    }
}
