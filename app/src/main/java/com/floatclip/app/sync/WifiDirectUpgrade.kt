package com.floatclip.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.floatclip.app.R
import com.floatclip.share.ShareSession
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Runs negotiation on the sync actor and keeps the existing paired link until authentication. */
internal class WifiDirectUpgrade(
    private val context:Context,
    private val actor:ScheduledExecutorService,
    private val control:(String)->ShareSession?,
    private val credential:(String)->ByteArray?,
    private val attach:(String,WifiDirectTransferClient.Offer,ByteArray,()->Unit,(Closeable)->Unit)->Unit,
) {
    private class Attempt(val peer:String,var id:String) {
        val result=CompletableFuture<Boolean>()
        var offer:WifiDirectTransferClient.Offer?=null
        var join:Closeable?=null
        var starting=false
    }
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var stopped=false
    private val attempts=java.util.concurrent.ConcurrentHashMap<String,Attempt>()
    private val cooldowns=mutableMapOf<String,Long>()
    private fun send(peer:String,id:String,phase:String) {
        val session=control(peer)?:return
        session.sendCatalog(JSONObject().put("v",1).put("op","wifi-direct").put("phase",phase).put("id",id).toString().toByteArray())
    }

    fun prepare(peer:String):CompletableFuture<Boolean> {
        val result=CompletableFuture<Boolean>()
        actor.execute {
            val session=control(peer)
            val now=SystemClock.elapsedRealtime()
            val prior=attempts[peer]
            if(prior!=null){prior.result.whenComplete{ok,_->result.complete(ok==true)};return@execute}
            if(stopped||Build.VERSION.SDK_INT<29||session==null||!session.supportsWifiDirect()||session.hasActiveFile()||now<(cooldowns[peer]?:0L)) {
                result.complete(false);return@execute
            }
            val attempt=begin(peer,UUID.randomUUID().toString().replace("-",""))
            attempt.result.whenComplete{ok,_->result.complete(ok==true)}
            runCatching{send(peer,attempt.id,"request")}.onFailure{finish(attempt,false)}
        }
        return result
    }

    fun receive(peer:String,session:ShareSession,data:JSONObject) {
        if(stopped||Build.VERSION.SDK_INT<29||control(peer)!==session||!session.supportsWifiDirect()||data.optInt("v")!=1)return
        val id=data.optString("id")
        if(!id.matches(Regex("[a-f0-9]{32}")))return
        when(data.optString("phase")) {
            "unavailable" -> attempts[peer]?.takeIf{it.id==id}?.let{finish(it,false)}
            "offer" -> {
                if(session.hasActiveFile())return
                val offer=runCatching{WifiDirectTransferClient.Offer(data.getString("ssid"),data.getString("password"),data.getInt("port"),data.optString("address"))}.getOrNull()?:return
                val attempt=attempts[peer]?:run {
                    if(SystemClock.elapsedRealtime()<(cooldowns[peer]?:0L)){runCatching{send(peer,id,"declined")};return}
                    begin(peer,id)
                }
                if(attempt.starting)return
                attempt.id=id;attempt.offer=offer
                main.post {
                    val foreground=SyncRuntime.foregroundActivity()
                    if(foreground!=null)foreground.startActivity(consent(peer,id))
                    else notifyConsent(peer,id)
                }
            }
        }
    }

    private fun begin(peer:String,id:String):Attempt {
        val attempt=Attempt(peer,id)
        attempts[peer]=attempt
        cooldowns[peer]=SystemClock.elapsedRealtime()+90000
        actor.schedule({if(attempts[peer]===attempt)finish(attempt,false)},50,TimeUnit.SECONDS)
        return attempt
    }

    fun continueAttempt(peer:String,id:String):CompletableFuture<Boolean> {
        val result=CompletableFuture<Boolean>()
        actor.execute {
            val attempt=attempts[peer]?.takeIf{it.id==id}?:run{result.complete(false);return@execute}
            attempt.result.whenComplete{ok,_->result.complete(ok==true)}
            if(attempt.starting)return@execute
            val offer=attempt.offer?:run{finish(attempt,false);return@execute}
            val token=credential(peer)?:run{finish(attempt,false);return@execute}
            attempt.starting=true
            main.post {
                attach(peer,offer,token,
                    { actor.execute { if(attempts[peer]===attempt)finish(attempt,false) } },
                    { closeable -> actor.execute {
                        if(attempts[peer]===attempt)attempt.join=closeable else if(!attempt.result.getNow(false))closeable.close()
                    } })
            }
        }
        return result
    }

    fun ready(peer:String) { attempts[peer]?.let{finish(it,true)} }
    fun decline(peer:String,id:String) { actor.execute{attempts[peer]?.takeIf{it.id==id}?.let{finish(it,false)}} }
    fun stop() { stopped=true;attempts.values.toList().forEach{finish(it,false)} }

    private fun finish(attempt:Attempt,success:Boolean) {
        if(!attempts.remove(attempt.peer,attempt))return
        if(!success){attempt.join?.close();runCatching{send(attempt.peer,attempt.id,"declined")}}
        attempt.result.complete(success)
        context.getSystemService(NotificationManager::class.java).cancel(attempt.id.hashCode())
    }

    private fun consent(peer:String,id:String)=Intent(context,WifiDirectConsentActivity::class.java)
        .putExtra("peer",peer).putExtra("request",id)

    private fun notifyConsent(peer:String,id:String) {
        val manager=context.getSystemService(NotificationManager::class.java)
        val channel="floatclip_direct_transfer"
        manager.createNotificationChannel(NotificationChannel(channel,"文件直连",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=PendingIntent.getActivity(context,id.hashCode(),consent(peer,id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title=if(WifiDirectTransferClient.wifiOn(context))"连接 Wi-Fi 以接收共享内容" else "打开 Wi-Fi 以使用直连"
        runCatching{manager.notify(id.hashCode(),Notification.Builder(context,channel).setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification).setContentIntent(intent).setAutoCancel(true).build())}
    }
}
