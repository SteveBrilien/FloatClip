package com.floatclip.app.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** A bounded PNG compatibility copy for existing Windows/Android file-sync peers.
 * The user's local JPEG original is never replaced with this transport derivative.
 */
internal object ClipboardImageSyncEncoding {
    data class Prepared(val bytes:ByteArray,val width:Int,val height:Int)
    fun prepare(original:ByteArray):Prepared {
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(original,0,original.size,options)
        require(options.outWidth>0&&options.outHeight>0) { "无法读取共享图片" }
        val png=original.size>=8&&original.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        if(png && original.size<=8*1024*1024 && options.outWidth.toLong()*options.outHeight<=64_000_000L)
            return Prepared(original,options.outWidth,options.outHeight)
        var sample=1
        while(options.outWidth.toLong()/sample*(options.outHeight/sample)>2_000_000L ||
              options.outWidth/sample>4096||options.outHeight/sample>4096)sample*=2
        var bitmap=BitmapFactory.decodeByteArray(original,0,original.size,BitmapFactory.Options().apply {
            inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888
        })?:error("无法生成共享图片")
        try {
            repeat(8) {
                val output=ByteArrayOutputStream(512*1024)
                if(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)&&output.size()<=8*1024*1024)
                    return Prepared(output.toByteArray(),bitmap.width,bitmap.height)
                val next=Bitmap.createScaledBitmap(bitmap,(bitmap.width*3/4).coerceAtLeast(1),(bitmap.height*3/4).coerceAtLeast(1),true)
                if(next!==bitmap){bitmap.recycle();bitmap=next}
            }
            error("图片无法转换为共享格式")
        } finally { bitmap.recycle() }
    }
}
