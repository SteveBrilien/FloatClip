package com.floatclip.app.sync

import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.floatclip.app.clipboard.ClipboardWrites
import com.floatclip.share.ShareWire
import java.lang.ref.WeakReference
import com.floatclip.share.ShareSession
import java.io.File
import java.util.function.Consumer

/** Application-facing entry point shared by the activity, floating panel and accessibility bridge. */
object SyncRuntime {
    interface Observer {
        fun changed(service: ClipboardSyncService)
        fun verify(code: String,answer: Consumer<Boolean>)
        fun offer(peer: String,offer: ShareWire.Offer)
        fun progress(name: String,done: Long,total: Long,outgoing: Boolean)
        fun file(offer: ShareWire.Offer,file: File,outgoing: Boolean)
    }
    @Volatile private var runtime: WeakReference<ClipboardSyncService>?=null
    @Volatile private var observer: WeakReference<Observer>?=null
    fun attach(service: ClipboardSyncService){runtime=WeakReference(service)}
    fun detach(service: ClipboardSyncService){if(runtime?.get()===service)runtime=null}
    fun current()=runtime?.get()
    fun setObserver(value: Observer){observer=WeakReference(value);current()?.refreshUi()}
    fun clearObserver(value: Observer){if(observer?.get()===value){observer=null;current()?.answerPairing(false)}}
    fun hasObserver()=observer?.get()!=null
    const val STATE_CHANGED="com.floatclip.app.SYNC_STATE_CHANGED"
    fun changed(){current()?.let{
        observer?.get()?.changed(it)
        it.sendBroadcast(Intent(STATE_CHANGED).setPackage(it.packageName),it.packageName+".permission.INTERNAL")
    }}
    fun verify(code: String,answer: Consumer<Boolean>){val ui=observer?.get();if(ui==null)answer.accept(false) else ui.verify(code,answer)}
    fun offer(peer: String,offer: ShareWire.Offer){observer?.get()?.offer(peer,offer)}
    fun progress(name: String,done: Long,total: Long,outgoing: Boolean){observer?.get()?.progress(name,done,total,outgoing)}
    fun file(offer: ShareWire.Offer,file: File,outgoing: Boolean){observer?.get()?.file(offer,file,outgoing)}
    fun pair(context: Context){check(SyncPreferences(context).enabled()){ "请先开启剪贴板共享" };context.startForegroundService(Intent(context,ClipboardSyncService::class.java).setAction(ClipboardSyncService.PAIR))}
    fun refresh(context: Context) {
        val intent=Intent(context,ClipboardSyncService::class.java)
        if(SyncPreferences(context).enabled())context.startForegroundService(intent)
        else {current()?.pauseImmediately();context.stopService(intent)}
    }
    fun stamp(clip: ClipData): String {
        val value=if(clip.itemCount>0)clip.getItemAt(0).text?.toString().orEmpty() else ""
        return clip.description.timestamp.toString()+":"+ShareWire.digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    /** A deliberate copy of an existing row is a new local user action, not an echo. */
    fun userCopied(context: Context,text: String) {
        val policy=SyncPreferences(context).policy()
        if(!policy.enabled||text.isBlank()||text.toByteArray(Charsets.UTF_8).size>com.floatclip.share.ClipboardSync.MAX_BYTES)return
        current()?.copied(text,"user:"+java.util.UUID.randomUUID(),
            shareGeneration=policy.sendGeneration.takeIf{policy.sending},recordHistory=false)
    }
    fun unsupportedCopied(context:Context) {
        if(SyncPreferences(context).enabled())current()?.copied("","unsupported:"+java.util.UUID.randomUUID(),true)
    }
    fun observe(context: Context,clip: ClipData) {
        val policy=SyncPreferences(context).policy()
        if(!policy.enabled||ClipboardWrites.isOwnClip(context,clip.description))return
        val event=stamp(clip)
        if(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true){current()?.copied("",event,true);return}
        if(com.floatclip.app.clipboard.ClipboardImages.isImage(context,clip)){current()?.copied("",event,true);return}
        val value=if(clip.itemCount>0)clip.getItemAt(0).text?.toString().orEmpty() else ""
        if(value.isBlank()||value.toByteArray(Charsets.UTF_8).size>com.floatclip.share.ClipboardSync.MAX_BYTES){current()?.copied("",event,true);return}
        current()?.copied(value,event,shareGeneration=policy.sendGeneration.takeIf{policy.sending})
    }
}
