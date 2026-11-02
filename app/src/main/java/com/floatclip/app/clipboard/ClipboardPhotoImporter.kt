package com.floatclip.app.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Normalize only the selected copy. Shared history remains PNG-compatible with older peers. */
internal object ClipboardPhotoImporter {
    const val MAX_SOURCE_BYTES=32*1024*1024
    private const val DECODE_PIXELS=4_000_000L
    data class Prepared(val png:ByteArray,val resized:Boolean,val originalWidth:Int,val originalHeight:Int)
    private class OutputFull:java.io.IOException()
    private class PngOutput:ByteArrayOutputStream() {
        var full=false
        override fun write(value:Int){if(count>=ImageBlobStore.MAX_IMAGE_BYTES){full=true;throw OutputFull()};super.write(value)}
        override fun write(bytes:ByteArray,offset:Int,length:Int){if(count.toLong()+length>ImageBlobStore.MAX_IMAGE_BYTES){full=true;throw OutputFull()};super.write(bytes,offset,length)}
    }
    internal fun sample(width:Int,height:Int):Int {
        require(width>0&&height>0)
        var value=1
        while(width/value>4096 || height/value>4096 || (width.toLong()/value)*(height/value)>DECODE_PIXELS)value*=2
        return value
    }
    fun prepare(encoded:ByteArray):Prepared {
        require(encoded.size<=MAX_SOURCE_BYTES){"原图片超过 32 MiB，请先在相册缩小后分享"}
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(encoded,0,encoded.size,bounds)
        val w=bounds.outWidth;val h=bounds.outHeight
        require(w in 1..65535 && h in 1..65535 && w.toLong()*h<=400_000_000L){"系统无法读取此图片，请导出为 JPG、PNG 或 WebP 后重试"}
        val png=encoded.size>=8 && encoded.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        // Small screenshots retain exact original bytes; do not allocate their full-resolution bitmap.
        if(png && encoded.size<=ImageBlobStore.MAX_IMAGE_BYTES && w<=16384 && h<=16384 && w.toLong()*h<=ImageBlobStore.MAX_PIXELS)
            return Prepared(encoded,false,w,h)
        val sampling=sample(w,h)
        var bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.size,BitmapFactory.Options().apply {
            inSampleSize=sampling;inPreferredConfig=Bitmap.Config.ARGB_8888
        })?:error("图片无法解码，请从相册导出 JPG 或 PNG 后重试")
        var resized=sampling>1
        try {
            val orientation=runCatching { ExifInterface(ByteArrayInputStream(encoded)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix=Matrix().apply { when(orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f,1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f,-1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {setRotate(90f);postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {setRotate(270f);postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            } }
            if(!matrix.isIdentity) {
                val oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
                if(oriented!==bitmap){bitmap.recycle();bitmap=oriented}
            }
            while(true) {
                if(Thread.currentThread().isInterrupted)throw InterruptedException("图片导入已取消")
                val output=PngOutput()
                try {
                    val encodedOk=runCatching {bitmap.compress(Bitmap.CompressFormat.PNG,100,output)}.getOrElse { if(output.full)false else throw it }
                    if(output.full)throw OutputFull()
                    check(encodedOk){"图片转换失败"}
                    return Prepared(output.toByteArray(),resized,w,h)
                } catch(_:OutputFull) {
                    val width=(bitmap.width*3/4).coerceAtLeast(1);val height=(bitmap.height*3/4).coerceAtLeast(1)
                    check(width<bitmap.width || height<bitmap.height){"图片转换后仍超过单张 8 MiB 限制"}
                    val scaled=Bitmap.createScaledBitmap(bitmap,width,height,true)
                    if(scaled!==bitmap){bitmap.recycle();bitmap=scaled}
                    resized=true
                }
            }
        } finally {bitmap.recycle()}
    }
}
