package com.floatclip.app.clipboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.floatclip.app.sync.ClipboardSyncService
import com.floatclip.app.ui.FloatDialog

/** System share target and document picker; imports only a user-selected URI, never scans photos. */
class ImageImportActivity:Activity() {
    private var importing=false
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.decorView.setBackgroundColor(com.floatclip.app.ui.FileUi.palette(this).page)
        if(state!=null)return
        if(intent.action==Intent.ACTION_SEND) {
            @Suppress("DEPRECATION") val uri=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if(uri==null){failed("没有收到图片");return}
            import(uri)
        } else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),1)
    }
    @Deprecated("Native Activity result") override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        if(request==1&&result==RESULT_OK&&data?.data!=null)import(data.data!!) else finish()
    }
    private fun import(uri:Uri) {
        if(importing)return;importing=true
        val progress=FloatDialog.show(this,"加入剪贴板","正在读取图片…",negative=null,positive=null).also{it.dialog.setCancelable(false)}
        Thread {
            val result=runCatching {val store=ClipStore(applicationContext);val entry=ClipboardImages.importUri(applicationContext,uri,store)?:error("记录已满，请先清理普通记录");store to entry}
            runOnUiThread {
                if(isDestroyed)return@runOnUiThread
                progress.dialog.dismiss()
                result.onSuccess { (store,entry) ->
                    runCatching{ClipboardImages.copy(this,store,entry)}.onFailure {failed(it.message?:"图片已保存，复制未完成");return@onSuccess}
                    sendBroadcast(Intent(ClipboardSyncService.HISTORY_CHANGED).setPackage(packageName),"$packageName.permission.INTERNAL")
                    Toast.makeText(this,"图片已加入剪贴板",Toast.LENGTH_SHORT).show();finish()
                }.onFailure{failed(it.message?:"无法读取图片，请从相册重新分享")}
            }
        }.start()
    }
    private fun failed(message:String){FloatDialog.show(this,"图片未能加入",message,negative=null,positive="关闭",onPositive={finish();true},onCancel={finish()})}
}
