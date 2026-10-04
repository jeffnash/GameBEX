package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.MediaFrame
import com.gamebex.client.stream.proto.MediaFrames
import com.gamebex.client.stream.proto.StreamWire
import java.io.OutputStream
import java.util.EnumMap

class ControlWriter(
    private val output: OutputStream,
    private val closeSocket: () -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
) : AutoCloseable {
    enum class Kind { BYE, KEYFRAME, PING, REPORT }
    private val lock = Object()
    private val pending = EnumMap<Kind, (Long) -> ByteArray>(Kind::class.java)
    private var stopping = false
    private var stopped = false
    private val worker = Thread({ writeLoop() }, "gbx-stream-control").apply { isDaemon = true; start() }

    fun offer(kind: Kind, encode: (writtenNs: Long) -> ByteArray): Boolean = synchronized(lock) {
        if (stopping || stopped) return false
        pending[kind] = encode
        lock.notifyAll()
        true
    }

    fun leave() {
        synchronized(lock) {
            if (!stopping && !stopped) {
                stopping = true
                pending.clear()
                pending[Kind.BYE] = { MediaFrames.encode(MediaFrame.Bye(StreamWire.BYE_LEAVING)) }
                lock.notifyAll()
            }
        }
        if (Thread.currentThread() !== worker) runCatching { worker.join(CLOSE_GRACE_MS) }
        close()
    }

    override fun close() {
        synchronized(lock) {
            stopping = true
            stopped = true
            pending.clear()
            lock.notifyAll()
        }
        runCatching { closeSocket() }
        worker.interrupt()
    }

    private fun writeLoop() {
        try {
            while (true) {
                val job = synchronized(lock) {
                    while (pending.isEmpty() && !stopped) lock.wait()
                    if (stopped) return
                    val kind = Kind.values().first { pending.containsKey(it) }
                    kind to pending.remove(kind)!!
                }
                val bytes = job.second(clock())
                output.write(bytes)
                if (job.first == Kind.BYE) return
            }
        } catch (error: Throwable) {
            val notify = synchronized(lock) { !stopped }
            if (notify) runCatching { onFailure(error) }
        } finally {
            close()
        }
    }

    companion object {
        const val CLOSE_GRACE_MS = 100L
    }
}
