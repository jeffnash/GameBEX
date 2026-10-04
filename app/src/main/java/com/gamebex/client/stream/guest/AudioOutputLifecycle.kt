package com.gamebex.client.stream.guest

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Native operations run outside the ownership lock, including construction and play. */
internal interface AudioOutputHandle {
    fun play()
    fun stop()
    fun setMuted(muted: Boolean)
    fun release()
}

/** One reservation spans construction, play and writing; cancellation cannot miss unpublished output. */
internal class AudioOutputLifecycle<P : AudioOutputHandle>(
    private val factory: () -> P,
    private val write: (P, () -> Boolean) -> Unit,
    private val interruptOutput: Boolean,
    private val onStopTimeout: () -> Unit = {},
) {
    private class Attempt<P>(val onFailure: (String) -> Unit) {
        val cancelled = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        @Volatile var output: P? = null
        @Volatile var writer: Thread? = null
    }

    private val lock = Any()
    private var current: Attempt<P>? = null
    private var closed = false
    @Volatile private var muted = false

    val isActive: Boolean get() = synchronized(lock) { current?.cancelled?.get() == false }

    fun setMuted(value: Boolean) {
        muted = value
        val output = synchronized(lock) { current?.output }
        runCatching { output?.setMuted(value) }
    }

    fun start(onFailure: (String) -> Unit): Boolean {
        val attempt = synchronized(lock) {
            if (closed) return false
            current?.let { return !it.cancelled.get() }
            Attempt<P>(onFailure).also { current = it }
        }
        var handedToWriter = false
        var failure: String? = null
        try {
            val output = factory()
            attempt.output = output
            if (attempt.cancelled.get()) return false
            output.setMuted(muted)
            output.play()
            if (attempt.cancelled.get()) return false
            val writer = Thread({
                var writerFailure: String? = null
                try {
                    if (!attempt.cancelled.get()) write(output) { !attempt.cancelled.get() }
                } catch (error: Exception) {
                    writerFailure = error.javaClass.simpleName
                } finally {
                    complete(attempt, writerFailure)
                }
            }, "gbx-audio-play").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
            }
            synchronized(lock) {
                if (closed || current !== attempt || attempt.cancelled.get()) return false
                attempt.writer = writer
                writer.start()
                handedToWriter = true
            }
            return true
        } catch (error: Exception) {
            failure = error.javaClass.simpleName
            return false
        } finally {
            if (!handedToWriter) complete(attempt, failure)
        }
    }

    private fun complete(attempt: Attempt<P>, failure: String?) {
        try {
            synchronized(attempt) {
                if (attempt.released.compareAndSet(false, true)) attempt.output?.release()
            }
        } finally {
            val notify = synchronized(lock) {
                val owned = current === attempt
                if (owned) current = null
                owned && !closed && !attempt.cancelled.get() && failure != null
            }
            if (notify) attempt.onFailure(failure!!)
        }
    }

    private fun cancel(terminal: Boolean): Attempt<P>? = synchronized(lock) {
        if (terminal) closed = true
        current?.also { it.cancelled.set(true) }
    }

    private fun wake(attempt: Attempt<P>?) {
        attempt?.writer?.let { it.interrupt(); LockSupport.unpark(it) }
    }

    /** Terminal ownership fence: safe on the UI thread; no native calls or joins. */
    fun close() { wake(cancel(terminal = true)) }

    /** Call on the session's close/recovery worker, never on the UI thread. */
    fun stop() {
        val attempt = cancel(terminal = false) ?: return
        wake(attempt)
        val writer = attempt.writer ?: return // The startup owner reclaims a cancelled build/play.
        if (interruptOutput) synchronized(attempt) {
            if (!attempt.released.get()) runCatching { attempt.output?.stop() }
        }
        if (writer !== Thread.currentThread()) writer.join(750L)
        if (writer.isAlive) onStopTimeout()
    }
}

/** Session-local retry policy, not a second output owner. Tokens reject stale recovery coroutines. */
internal class AudioOutputRecovery {
    data class Attempt internal constructor(val id: Long)
    sealed class Failure {
        object Ignored : Failure()
        data class Retry(val attempt: Attempt) : Failure()
        object Disabled : Failure()
    }

    private var epoch = Long.MIN_VALUE
    private var nextId = 0L
    private var active: Attempt? = null
    private var pending = false
    private var starting = false
    private var connected = false
    private var ended = false
    private var disabled = false
    private var failures = 0

    val accepting: Boolean get() = synchronized(this) { connected && !ended && !disabled }

    private fun reserve(): Attempt = Attempt(++nextId).also { active = it; pending = true }

    @Synchronized fun connected(sourceEpoch: Long, outputActive: Boolean): Attempt? {
        if (ended) return null
        connected = true
        if (epoch != sourceEpoch) {
            epoch = sourceEpoch
            failures = 0
            disabled = false
        }
        if (disabled || (active != null && (pending || starting || outputActive))) return null
        return reserve()
    }

    @Synchronized fun reconnecting() {
        if (ended) return
        connected = false
        if (pending) { active = null; pending = false }
    }

    @Synchronized fun claim(attempt: Attempt): Boolean {
        if (!connected || !isCurrent(attempt) || !pending) return false
        pending = false
        starting = true
        return true
    }

    @Synchronized fun started(attempt: Attempt) {
        if (active == attempt) starting = false
    }

    @Synchronized fun isCurrent(attempt: Attempt): Boolean =
        !ended && !disabled && active == attempt

    @Synchronized fun failed(attempt: Attempt, sourceEpoch: Long): Failure {
        if (ended || active != attempt) return Failure.Ignored
        active = null
        pending = false
        starting = false
        if (!connected) return Failure.Ignored
        if (epoch != sourceEpoch) {
            epoch = sourceEpoch
            failures = 0
            disabled = false
        }
        failures++
        if (failures == 1) return Failure.Retry(reserve())
        disabled = true
        return Failure.Disabled
    }

    @Synchronized fun close() {
        ended = true
        active = null
        pending = false
        starting = false
    }
}
