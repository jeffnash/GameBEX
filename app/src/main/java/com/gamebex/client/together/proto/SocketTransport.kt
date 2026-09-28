package com.gamebex.client.together.proto

import com.gamebex.client.together.Link
import com.gamebex.client.together.LinkListener
import com.gamebex.client.together.LinkTransport
import com.gamebex.client.together.TogetherTiming
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@OptIn(InternalCoroutinesApi::class)
class SocketTransport(
    private val timing: TogetherTiming = TogetherTiming(),
) : LinkTransport {
    override suspend fun listen(preferredPort: Int): LinkListener {
        val server = withContext(Dispatchers.IO) {
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                try {
                    server.bind(InetSocketAddress(preferredPort))
                } catch (_: BindException) {
                    server.bind(InetSocketAddress(0))
                }
                server.soTimeout = ACCEPT_POLL_MS
                currentCoroutineContext().ensureActive()
                server
            } catch (e: Throwable) {
                runCatching { server.close() }
                throw e
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            return SocketListener(server, timing)
        } catch (e: Throwable) {
            runCatching { server.close() }
            throw e
        }
    }

    override suspend fun connect(address: String, port: Int, timeoutMs: Long): Link {
        val socket = withContext(Dispatchers.IO) {
            val socket = Socket()
            val handle = currentCoroutineContext()[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause != null) runCatching { socket.close() }
            }
            try {
                socket.connect(
                    InetSocketAddress(address, port),
                    timeoutMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
                socket.tcpNoDelay = true
                socket.soTimeout = 0
                currentCoroutineContext().ensureActive()
                socket
            } catch (e: Throwable) {
                runCatching { socket.close() }
                currentCoroutineContext().ensureActive()
                if (e is CancellationException || e is IOException) throw e
                throw IOException("could not connect to $address:$port", e)
            } finally {
                handle?.dispose()
            }
        }
        val link = try {
            currentCoroutineContext().ensureActive()
            SocketLink(socket, timing)
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
        try {
            currentCoroutineContext().ensureActive()
            return link
        } catch (e: Throwable) {
            link.close()
            throw e
        }
    }

    private companion object {
        /** How long one blocking accept waits before it rechecks cancellation. */
        const val ACCEPT_POLL_MS = 250
    }

    private class SocketListener(
        private val server: ServerSocket,
        private val timing: TogetherTiming,
    ) : LinkListener {
        override val port: Int get() = server.localPort

        /** Polls so a cancelled accept returns without closing the listener; [close] ends it. */
        override suspend fun accept(): Link {
            val socket = withContext(Dispatchers.IO) {
                var accepted: Socket? = null
                while (accepted == null) {
                    currentCoroutineContext().ensureActive()
                    accepted = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        null
                    } catch (e: Throwable) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    }
                }
                try {
                    accepted.tcpNoDelay = true
                    accepted.soTimeout = 0
                    currentCoroutineContext().ensureActive()
                    accepted
                } catch (e: Throwable) {
                    runCatching { accepted.close() }
                    throw e
                }
            }
            val link = try {
                currentCoroutineContext().ensureActive()
                SocketLink(socket, timing)
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
            try {
                currentCoroutineContext().ensureActive()
                return link
            } catch (e: Throwable) {
                link.close()
                throw e
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }
}
