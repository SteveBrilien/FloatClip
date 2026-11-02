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
    private var copyRequest:AutoCloseable?=null
    private var worker:Thread?=null
    private var pendingUri:Uri?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.decorView.setBackgroundColor(com.floatclip.app.ui.FileUi.palette(this).page)
        if(state!=null){state.getString("pending_uri")?.let{import(Uri.parse(it))};return}
        if(intent.action==Intent.ACTION_SEND) {
            val uri=runCatching { incomingUri(intent) }.getOrElse {failed(it.message?:"无法读取图片");return}
            if(uri==null){failed("没有收到图片");return}
            import(uri)
        } else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),1)
    }
    @Deprecated("Native Activity result") override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        if(request==1&&result==RESULT_OK&&data?.data!=null)import(data.data!!) else finish()
    }
    private fun import(uri:Uri) {
        if(importing)return;importing=true;pendingUri=uri
        val cancel={worker?.interrupt();finish()}
        val progress=FloatDialog.show(this,"加入剪贴板","正在处理图片…",negative="取消",positive=null,onNegative=cancel,onCancel=cancel)
        worker=Thread {
            val result=runCatching {
                val store=ClipStore(applicationContext)
                val imported=ClipboardImages.importSelected(applicationContext,uri,store)
                require(imported.entry!=null){"记录已满，请先清理普通记录"}
                Triple(store,imported.entry,imported.resized)
            }
            runOnUiThread {
                if(isDestroyed||isFinishing)return@runOnUiThread
                progress.dialog.dismiss()
                result.onSuccess { (store,entry,resized) ->
                    copyRequest=ClipboardImages.copyAsync(this,store,entry) { copied ->
                        if(isDestroyed)return@copyAsync
                        copied.onSuccess {
                            sendBroadcast(Intent(ClipboardSyncService.HISTORY_CHANGED).setPackage(packageName),"$packageName.permission.INTERNAL")
                            Toast.makeText(this,if(resized)"图片已加入，已优化副本尺寸" else "图片已加入剪贴板",Toast.LENGTH_SHORT).show();finish()
                        }.onFailure {failed("图片已保存，复制未完成："+(it.message?:"请重试"))}
                    }
                }.onFailure{failed(it.message?:"无法读取图片，请从相册重新分享")}
            }
        }.apply {isDaemon=true;start()}
    }
    override fun onSaveInstanceState(state:Bundle){pendingUri?.let{state.putString("pending_uri",it.toString())};super.onSaveInstanceState(state)}
    override fun onDestroy(){worker?.interrupt();copyRequest?.close();super.onDestroy()}
    companion object {
        @Suppress("DEPRECATION")
        internal fun incomingUri(intent:Intent):Uri? {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let{return it}
            val clip=intent.clipData
            require(clip==null || clip.itemCount<=1){"请一次选择一张图片"}
            return clip?.takeIf{it.itemCount>0}?.getItemAt(0)?.uri?:intent.data
        }
    }
    private fun failed(message:String){FloatDialog.show(this,"图片未能加入",message,negative=null,positive="关闭",onPositive={finish();true},onCancel={finish()})}
}
