package com.floatclip.app.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Keep camera JPEGs compressed and at native resolution; normalize EXIF only when needed. */
internal object ClipboardPhotoImporter {
    const val MAX_SOURCE_BYTES=32*1024*1024
    private const val MAX_ROTATION_PIXELS=16_000_000L
    // "png" is the legacy API name: now contains unmodified JPEG or PNG bytes.
    data class Prepared(val png:ByteArray,val resized:Boolean,val originalWidth:Int,val originalHeight:Int)
    internal fun sample(width:Int,height:Int):Int {
        require(width>0 && height>0)
        var value=1
        while(width/value>4096 || height/value>4096 || width.toLong()/value*(height/value)>MAX_ROTATION_PIXELS)value*=2
        return value
    }
    fun prepare(encoded:ByteArray):Prepared {
        require(encoded.size in 45..MAX_SOURCE_BYTES) { "原图片超过 32 MiB，请先在相册缩小后分享" }
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(encoded,0,encoded.size,bounds)
        val w=bounds.outWidth;val h=bounds.outHeight
        require(w in 1..65535 && h in 1..65535 && w.toLong()*h<=400_000_000L){"无法读取图片，请从相册导出 JPG 或 PNG"}
        val isPng=encoded.size>=8 && encoded.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        val isJpeg=encoded[0]==0xff.toByte() && encoded[1]==0xd8.toByte()
        require(isPng||isJpeg) { "暂支持 JPG 与 PNG，其他格式请从相册导出" }
        val orientation=if(isJpeg)runCatching {
            ExifInterface(ByteArrayInputStream(encoded)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL) else ExifInterface.ORIENTATION_NORMAL
        // A standard EXIF-normal camera JPEG already has the exact bytes the gallery uses.
        // Keep it: no 4 MP downsampling, PNG transcode, or needless image allocation.
        if((isJpeg && orientation==ExifInterface.ORIENTATION_NORMAL || isPng) &&
            encoded.size<=ImageBlobStore.MAX_IMAGE_BYTES && w<=16384 && h<=16384 && w.toLong()*h<=ImageBlobStore.MAX_PIXELS) {
            return Prepared(encoded,false,w,h)
        }
        // Rotation is necessary for source formats where Android BitmapFactory ignores EXIF.
        // Encode an orientation-correct high-quality JPEG once, rather than retrying lossless PNG.
        val sampling=sample(w,h)
        var bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.size,BitmapFactory.Options().apply {
            inSampleSize=sampling;inPreferredConfig=Bitmap.Config.ARGB_8888
        })?:error("图片无法解码，请从相册导出 JPG 后重试")
        try {
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
                val rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
                if(rotated!==bitmap){bitmap.recycle();bitmap=rotated}
            }
            val output=ByteArrayOutputStream((encoded.size/2).coerceIn(65536,4*1024*1024))
            check(bitmap.compress(Bitmap.CompressFormat.JPEG,92,output)){"图片压缩失败"}
            require(output.size()<=ImageBlobStore.MAX_IMAGE_BYTES){"图片数据过大，请从相册缩小后重试"}
            return Prepared(output.toByteArray(),sampling>1 || bitmap.width!=w || bitmap.height!=h,w,h)
        } finally {bitmap.recycle()}
    }
}
