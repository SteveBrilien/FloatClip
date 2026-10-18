package com.floatclip.probe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Foreground-only synthetic probe, always using authenticated/encrypted RFCOMM. */
@SuppressLint("MissingPermission")
class BluetoothProbe(private val report: (String) -> Unit) : AutoCloseable {
    private val workers = Executors.newFixedThreadPool(3)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val generation = AtomicInteger()
    private val lock = Any()
    private val outputLock = Any()
    private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var ready = false
    private data class Pending(val frame: ProbeWire.Frame, val start: Long)
    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun stop() {
        synchronized(lock) {
            generation.incrementAndGet()
            ready = false
            pending.clear()
            runCatching { server?.close() }
            runCatching { socket?.close() }
            server = null
            socket = null
        }
    }

    fun listen(adapter: BluetoothAdapter, selected: BluetoothDevice) {
        stop()
        val epoch = generation.get()
        val address = selected.address
        workers.execute {
            try {
                val listener = adapter.listenUsingRfcommWithServiceRecord("FloatClip P0", ProbeWire.SERVICE)
                synchronized(lock) {
                    if (epoch != generation.get()) { listener.close(); return@execute }
                    server = listener
                }
                report("等待已选设备连接（30 秒）")
                val peer = listener.accept(30_000)
                listener.close()
                if (peer.remoteDevice.address != address || peer.remoteDevice.bondState != BluetoothDevice.BOND_BONDED) {
                    peer.close()
                    throw IOException("拒绝未选择或未配对的设备")
                }
                runSession(peer, epoch)
            } catch (e: Exception) { fail(epoch, e) }
        }
    }

    fun connect(selected: BluetoothDevice) {
        stop()
        val epoch = generation.get()
        workers.execute {
            try {
                if (selected.bondState != BluetoothDevice.BOND_BONDED) throw IOException("请先在系统设置配对")
                val peer = selected.createRfcommSocketToServiceRecord(ProbeWire.SERVICE)
                synchronized(lock) {
                    if (epoch != generation.get()) { peer.close(); return@execute }
                    socket = peer
                }
                val deadline = timer.schedule({
                    if (epoch == generation.get() && !ready) runCatching { peer.close() }
                }, 15, TimeUnit.SECONDS)
                report("正在连接已选设备")
                try { peer.connect() } finally { deadline.cancel(false) }
                runSession(peer, epoch)
            } catch (e: Exception) { fail(epoch, e) }
        }
    }

    private fun runSession(peer: BluetoothSocket, epoch: Int) {
        synchronized(lock) {
            if (epoch != generation.get()) { peer.close(); return }
            socket = peer
            ready = true
        }
        report("安全蓝牙连接已建立；开始双向测试")
        sendBatch(epoch)
        try {
            val input = peer.inputStream
            var count = 0
            while (epoch == generation.get()) {
                val frame = ProbeWire.read(input) ?: break
                if (epoch != generation.get()) break
                if (++count > 256) throw IOException("本次测试帧数超出限制")
                if (frame.kind == ProbeWire.PROBE) {
                    val ack = ProbeWire.acknowledge(frame)
                    write(peer, epoch, ack)
                    report("接收测试文字：${frame.payload().size} 字节，完整性通过")
                } else {
                    val sent = pending.remove(frame.id) ?: throw IOException("收到未请求的 ACK")
                    if (!ProbeWire.matchesAck(frame, sent.frame)) throw IOException("ACK 内容不匹配")
                    val ms = (System.nanoTime() - sent.start) / 1_000_000
                    report("发送已确认：往返 ${ms} ms")
                }
            }
            synchronized(lock) {
                if (epoch == generation.get()) { stop(); report("对端已断开；测试会话结束") }
            }
        } finally { runCatching { peer.close() } }
    }

    fun sendBatch(expectedEpoch: Int? = null) {
        val current = synchronized(lock) {
            if (expectedEpoch != null && expectedEpoch != generation.get()) return
            val peer = socket
            if (!ready || peer == null) { report("请先建立连接"); return }
            Pair(generation.get(), peer)
        }
        val (epoch, peer) = current
        workers.execute {
            try {
                repeat(10) {
                    if (epoch != generation.get()) return@execute
                    val frame = ProbeWire.probe(UUID.randomUUID())
                    val sent = Pending(frame, System.nanoTime())
                    synchronized(lock) {
                        if (epoch != generation.get()) return@execute
                        if (pending.size >= 16) throw IOException("等待确认的测试过多")
                        pending[frame.id] = sent
                    }
                    write(peer, epoch, frame)
                    timer.schedule({
                        synchronized(lock) {
                            if (epoch == generation.get() && pending.remove(frame.id, sent)) {
                                fail(epoch, IOException("测试文字 10 秒内未获确认"))
                            }
                        }
                    }, 10, TimeUnit.SECONDS)
                    Thread.sleep(100)
                }
            } catch (e: Exception) { fail(epoch, e) }
        }
    }

    private fun write(peer: BluetoothSocket, epoch: Int, frame: ProbeWire.Frame) {
        synchronized(outputLock) {
            if (epoch != generation.get()) throw IOException("测试已取消")
            ProbeWire.write(peer.outputStream, frame)
        }
    }

    private fun fail(epoch: Int, error: Exception) {
        synchronized(lock) {
            if (epoch != generation.get()) return
            stop()
        }
        report("测试停止：${error.javaClass.simpleName} / ${error.message ?: "连接失败"}")
    }

    override fun close() { stop(); workers.shutdownNow(); timer.shutdownNow() }
}
