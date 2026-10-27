package com.floatclip.app.ui

import android.os.Handler
import android.os.Looper
import com.floatclip.probe.ShareUpdates
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One cancellable request per screen owner, with a real UI deadline and stale-result rejection. */
internal class ClientUpdateCheck(
    private val read:(ShareUpdates.Channel,ShareUpdates.CheckCancellation,(String)->Unit)->ShareUpdates.Release=
        { channel,cancellation,source -> ShareUpdates.check(channel,cancellation=cancellation,onSource=source) },
    private val worker:ExecutorService=Executors.newSingleThreadExecutor { task ->
        Thread(task,"FloatClip updates").apply { isDaemon=true }
    },
    private val main:Handler=Handler(Looper.getMainLooper()),
    private val timeoutMs:Long=12000L,
    listener:(State)->Unit,
):AutoCloseable {
    enum class Phase { IDLE,CHECKING,SUCCESS,FAILED }
    data class State(val phase:Phase=Phase.IDLE,val source:String="",val release:ShareUpdates.Release?=null,val message:String="")
    var state=State();private set
    private var listener:((State)->Unit)?=listener
    private var closed=false
    private var generation=0
    private var cancellation:ShareUpdates.CheckCancellation?=null
    private var future:Future<*>?=null
    private var timeout:Runnable?=null

    fun start(channel:ShareUpdates.Channel):Boolean {
        if(closed || state.phase==Phase.CHECKING)return false
        val token=++generation
        val request=ShareUpdates.CheckCancellation()
        cancellation=request
        publish(State(Phase.CHECKING,source="正在连接更新源…"))
        val deadline=Runnable {
            if(!closed && token==generation && state.phase==Phase.CHECKING) {
                generation++
                request.cancel();future?.cancel(true)
                publish(State(Phase.FAILED,message="检查超时，请重试"))
            }
        }
        timeout=deadline;main.postDelayed(deadline,timeoutMs)
        future=worker.submit {
            val result=runCatching {
                read(channel,request) { source -> main.post {
                    if(!closed && token==generation && state.phase==Phase.CHECKING)
                        publish(State(Phase.CHECKING,source="正在连接"+source+"…"))
                } }
            }
            main.post {
                if(closed || token!=generation)return@post
                main.removeCallbacks(deadline);timeout=null;cancellation=null
                result.onSuccess { publish(State(Phase.SUCCESS,release=it)) }.onFailure {
                    val message=when(it) {
                        is java.io.IOException -> it.message?:"网络连接失败，请重试"
                        is java.util.concurrent.CancellationException -> "检查已取消"
                        else -> "更新信息校验失败："+(it.message?:"请稍后重试")
                    }
                    publish(State(Phase.FAILED,message=message))
                }
            }
        }
        return true
    }
    fun cancel() {
        generation++
        timeout?.let(main::removeCallbacks);timeout=null
        cancellation?.cancel();cancellation=null
        future?.cancel(true);future=null
        if(!closed)publish(State())
    }
    private fun publish(value:State) { state=value;listener?.invoke(value) }
    override fun close() {
        if(closed)return
        closed=true;listener=null
        cancel();worker.shutdownNow()
    }
}
