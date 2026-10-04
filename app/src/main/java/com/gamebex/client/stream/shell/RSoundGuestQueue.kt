package com.gamebex.client.stream.shell

import java.util.ArrayDeque

internal class RSoundGuestQueue(
    private val sink: AudioSink,
    private val onFailure: (Throwable, () -> Boolean) -> Unit,
    private val capacity: Int = CAPACITY_CHUNKS,
) : AutoCloseable {
    private class Entry(val generation: Long, val chunk: PcmChunk, val valid: () -> Boolean)

    private val lock = Object()
    private val pending = ArrayDeque<Entry>()
    @Volatile private var alive = true
    @Volatile private var generation = 0L
    private var accepting = true
    @Volatile var drops = 0L
        private set
    val pendingChunks: Int get() = synchronized(lock) { pending.size }
    private val worker: Thread

    init {
        require(capacity > 0)
        worker = Thread({ drain() }, "gbx-rsound-guest").apply {
            isDaemon = true
            start()
        }
    }

    fun enqueue(chunk: PcmChunk, valid: () -> Boolean) = enqueue({ chunk }, valid)

    fun enqueue(chunk: () -> PcmChunk, valid: () -> Boolean) {
        synchronized(lock) {
            if (!alive || !valid()) return
            val pcm = chunk()
            if (!accepting) {
                drops++
                return
            }
            if (pending.size == capacity) {
                pending.removeFirst()
                drops++
            }
            pending.addLast(Entry(generation, pcm, valid))
            lock.notifyAll()
        }
    }

    fun flush() {
        synchronized(lock) {
            generation++
            drops += pending.size
            pending.clear()
            accepting = true
        }
    }

    override fun close() {
        synchronized(lock) {
            alive = false
            generation++
            drops += pending.size
            pending.clear()
            lock.notifyAll()
        }
        worker.interrupt()
    }

    private fun drain() {
        while (alive) {
            val entry = try {
                synchronized(lock) {
                    while (alive && pending.isEmpty()) lock.wait()
                    if (!alive) return
                    pending.removeFirst()
                }
            } catch (_: InterruptedException) {
                if (!alive) return
                continue
            }
            if (!alive || entry.generation != generation || !entry.valid()) continue
            try {
                sink.onPcm(entry.chunk)
            } catch (failure: Throwable) {
                val report = synchronized(lock) {
                    if (!alive || entry.generation != generation || !entry.valid()) false else {
                        accepting = false
                        drops += pending.size + 1
                        pending.clear()
                        true
                    }
                }
                if (report) runCatching {
                    onFailure(failure) { alive && entry.generation == generation && entry.valid() }
                }
            }
        }
    }

    companion object {
        const val CAPACITY_CHUNKS = 6
        const val CAPACITY_MS = 30
    }
}
