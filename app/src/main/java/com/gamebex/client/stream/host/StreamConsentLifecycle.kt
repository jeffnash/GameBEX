package com.gamebex.client.stream.host

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal class StreamConsentLifecycle<T : Any>(private val lock: Any = Any()) {
    internal class Request<T : Any> internal constructor(
        val id: String,
        internal val result: CompletableDeferred<T>,
    )

    private class Pending<T : Any>(val request: Request<T>, val allowed: () -> Boolean) {
        var owner: Any? = null
        var close: (() -> Unit)? = null
        var launched = false
    }

    private var pending: Pending<T>? = null

    fun open(): Request<T> = synchronized(lock) { openLocked { true } }

    fun openIfAllowed(allowed: () -> Boolean): Request<T>? = synchronized(lock) {
        if (allowed()) openLocked(allowed) else null
    }

    private fun openLocked(allowed: () -> Boolean): Request<T> {
        clearLocked()
        return Request(UUID.randomUUID().toString(), CompletableDeferred<T>()).also { pending = Pending(it, allowed) }
    }

    fun stop() = synchronized(lock) { clearLocked() }

    fun cancel(request: Request<T>) = synchronized(lock) {
        if (pending?.request === request) clearLocked()
    }

    suspend fun await(request: Request<T>, timeoutMs: Long): T? {
        try {
            return withTimeoutOrNull(timeoutMs) { request.result.await() }.also {
                if (it == null) cancel(request)
            }
        } catch (cancelled: CancellationException) {
            cancel(request)
            throw cancelled
        }
    }

    fun <R : Any> runIfCurrent(request: Request<T>, action: () -> R): R? = synchronized(lock) {
        val state = pending
        if (state == null || state.request !== request) return null
        if (!state.allowed()) {
            clearLocked()
            return null
        }
        action()
    }

    fun attach(id: String, owner: Any, close: () -> Unit, launch: () -> Unit): Boolean = synchronized(lock) {
        val state = pending
        if (state == null || state.request.id != id || state.request.result.isCompleted) {
            close()
            return false
        }
        if (!state.allowed()) {
            clearLocked()
            close()
            return false
        }
        state.owner = owner
        state.close = close
        if (!state.launched) {
            state.launched = true
            launch()
        }
        true
    }

    fun detach(id: String, owner: Any, finishing: Boolean) = synchronized(lock) {
        val state = pending
        if (state == null || state.request.id != id || state.owner !== owner) return
        if (finishing) {
            clearLocked()
        } else {
            state.owner = null
        }
    }

    fun answer(id: String, owner: Any, value: T): Boolean = synchronized(lock) {
        val state = pending
        if (state == null || state.request.id != id || state.owner !== owner) return false
        if (!state.allowed()) {
            clearLocked()
            return false
        }
        if (!state.request.result.complete(value)) return false
        val close = state.close
        state.owner = null
        state.close = null
        close?.invoke()
        true
    }

    private fun clearLocked() {
        val state = pending ?: return
        pending = null
        state.request.result.cancel()
        val close = state.close
        state.owner = null
        state.close = null
        close?.invoke()
    }
}
