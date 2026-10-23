package com.floatclip.app.clipboard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Android distributes per-URI clipboard read grants; the provider never exposes a writable file. */
class ClipboardImageProvider:ContentProvider() {
    override fun onCreate()=true
    private fun read(uri:Uri):ByteArray {
        val app=context ?: throw FileNotFoundException("图片服务未启动")
        val ref=reference(uri,app.packageName) ?: throw FileNotFoundException("图片地址无效")
        return ClipStore(app).readImageReference(ref)
    }
    override fun getType(uri:Uri):String {
        require(reference(uri,context!!.packageName)!=null) { "图片地址无效" }
        return "image/png"
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        if(mode!="r")throw FileNotFoundException("图片仅允许读取")
        val png=read(uri)
        val pipe=ParcelFileDescriptor.createPipe()
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(png) } }
        }.apply { name="FloatClip image read";isDaemon=true;start() }
        return pipe[0]
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val columns=(projection ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).filter {
            it==OpenableColumns.DISPLAY_NAME || it==OpenableColumns.SIZE
        }.toTypedArray()
        val bytes=read(uri).size
        return MatrixCursor(columns).apply { addRow(columns.map { if(it==OpenableColumns.DISPLAY_NAME)"FloatClip.png" else bytes }) }
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException("图片仅允许读取")
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("图片仅允许读取")
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("图片仅允许读取")
    companion object {
        fun uri(packageName:String,reference:String):Uri=Uri.parse("content://$packageName.clipboard-images/image/$reference")
        fun reference(uri:Uri?,packageName:String):String?=uri?.takeIf {
            it.scheme=="content" && it.authority=="$packageName.clipboard-images" && it.pathSegments.size==2 &&
                it.pathSegments[0]=="image" && it.pathSegments[1].matches(Regex("[a-f0-9]{32}")) && it.query==null && it.fragment==null
        }?.pathSegments?.get(1)
    }
}
