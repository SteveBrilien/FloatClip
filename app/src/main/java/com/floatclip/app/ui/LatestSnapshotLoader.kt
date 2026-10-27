package com.floatclip.app.ui

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Work and delivery both check the generation: rapid refreshes cannot restore stale data. */
class LatestSnapshotLoader<T>(
    private val worker:Executor,
    private val delivery:Executor,
    private val read:()->T,
    private val accept:(Result<T>)->Unit,
):AutoCloseable {
    private val generation=AtomicLong()
    @Volatile private var closed=false
    fun request() {
        if(closed)return
        val token=generation.incrementAndGet()
        worker.execute {
            if(closed || token!=generation.get())return@execute
            val result=runCatching(read)
            delivery.execute { if(!closed && token==generation.get())accept(result) }
        }
    }
    override fun close() { closed=true;generation.incrementAndGet() }
}
