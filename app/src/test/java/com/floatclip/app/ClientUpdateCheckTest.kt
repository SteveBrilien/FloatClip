package com.floatclip.app

import android.os.Handler
import android.os.Looper
import com.floatclip.app.ui.ClientUpdateCheck
import com.floatclip.probe.ShareUpdates
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClientUpdateCheckTest {
    private class Queue:AbstractExecutorService() {
        val tasks=ArrayDeque<Runnable>();private var closed=false
        override fun execute(command:Runnable) { check(!closed);tasks.add(command) }
        override fun shutdown() { closed=true }
        override fun shutdownNow():MutableList<Runnable> { closed=true;return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown()=closed
        override fun isTerminated()=closed && tasks.isEmpty()
        override fun awaitTermination(timeout:Long,unit:TimeUnit)=isTerminated
        fun drain() { while(tasks.isNotEmpty())tasks.removeFirst().run() }
    }
    private fun release()=ShareUpdates.Release(29,"0.7.9","notes","https://github.com/example", "a".repeat(64),100,ShareUpdates.PACKAGE,ShareUpdates.SIGNER)
    @Test fun repeatedTapsShareOneRequestAndProgressThenSuccessArrive() {
        val worker=Queue();var reads=0;val states=mutableListOf<ClientUpdateCheck.State>()
        val check=ClientUpdateCheck({ _,_,source -> reads++;source("备用源");release() },worker,Handler(Looper.getMainLooper()),1000L) { states+=it }
        assertTrue(check.start(ShareUpdates.Channel.PREVIEW))
        repeat(20) { assertFalse(check.start(ShareUpdates.Channel.PREVIEW)) }
        worker.drain();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,reads)
        assertTrue(states.any { it.source.contains("备用源") })
        assertEquals(ClientUpdateCheck.Phase.SUCCESS,check.state.phase)
        check.close()
    }
    @Test fun deadlineFinishesVisibleStateAndRetryWorksAfterCancelledOldTask() {
        val worker=Queue();var reads=0
        val check=ClientUpdateCheck({ _,_,_ -> reads++;release() },worker,Handler(Looper.getMainLooper()),50L) {}
        check.start(ShareUpdates.Channel.PREVIEW)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))
        assertEquals(ClientUpdateCheck.Phase.FAILED,check.state.phase)
        assertTrue(check.state.message.contains("超时"))
        worker.drain()
        assertEquals(0,reads)
        assertTrue(check.start(ShareUpdates.Channel.PREVIEW))
        worker.drain();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ClientUpdateCheck.Phase.SUCCESS,check.state.phase)
        check.close()
    }
    @Test fun closingScreenDiscardsQueuedResultAndAllLaterCallbacks() {
        val worker=Queue();val states=mutableListOf<ClientUpdateCheck.State>()
        val check=ClientUpdateCheck({ _,_,_ -> release() },worker,Handler(Looper.getMainLooper()),50L) { states+=it }
        check.start(ShareUpdates.Channel.PREVIEW);worker.drain()
        check.close()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))
        assertEquals(1,states.size)
        assertFalse(check.start(ShareUpdates.Channel.PREVIEW))
    }
    @Test fun cancelledResultCannotReplaceNewRequestOrPublishFailure() {
        val worker=Queue();var reads=0;val states=mutableListOf<ClientUpdateCheck.State>()
        val check=ClientUpdateCheck({ _,_,_ -> reads++;release() },worker,Handler(Looper.getMainLooper()),1000L) { states+=it }
        check.start(ShareUpdates.Channel.PREVIEW);worker.drain()
        check.cancel()
        check.start(ShareUpdates.Channel.PREVIEW);worker.drain()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2,reads)
        assertEquals(1,states.count { it.phase==ClientUpdateCheck.Phase.SUCCESS })
        assertEquals(0,states.count { it.phase==ClientUpdateCheck.Phase.FAILED })
        check.close()
    }
}
