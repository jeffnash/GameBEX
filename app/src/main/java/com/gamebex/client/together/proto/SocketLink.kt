package com.gamebex.client.together.proto

import com.gamebex.client.together.Link
import com.gamebex.client.together.LinkEvent
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.LinkTimeoutException
import com.gamebex.client.together.TogetherTiming
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A length-framed link over one TCP socket. */
class SocketLink(
    private val socket: Socket,
    timing: TogetherTiming = TogetherTiming(),
) : Link {
    override val remoteAddress: String = socket.inetAddress?.hostAddress ?: ""

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val writeMutex = Mutex()
    private val eventChannel = Channel<LinkEvent>(capacity = 64)
    private val eventMutex = Mutex()
    override val events: ReceiveChannel<LinkEvent> = eventChannel

    private val eventCloseScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val emittedClosed = AtomicBoolean(false)
    private val closeCause = AtomicReference<Throwable?>(null)
    private val lastReceivedNs = AtomicLong(System.nanoTime())

    init {
        scope.launch { readLoop() }
        scope.launch { silenceLoop(timing) }
        scope.launch { pingLoop(timing) }
    }

    override suspend fun send(msg: LinkMsg) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                ensureOpen()
                try {
                    Frames.writeJson(output, LinkJson.encode(msg))
                    output.flush()
                } catch (e: IOException) {
                    finish(e)
                    throw e
                }
            }
        }
    }

    override suspend fun sendBlob(blobId: Int, file: File, onProgress: (sent: Long) -> Unit) {
        withContext(Dispatchers.IO) {
            FileInputStream(file).use { source ->
                val buffer = ByteArray(Frames.MAX_CHUNK)
                var sent = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    writeMutex.withLock {
                        ensureOpen()
                        try {
                            Frames.writeBlob(output, blobId, buffer, 0, count)
                            output.flush()
                        } catch (e: IOException) {
                            finish(e)
                            throw e
                        }
                    }
                    sent += count
                    onProgress(sent)
                }
            }
        }
    }

    override fun close() {
        finish(null)
    }

    private suspend fun readLoop() {
        var failure: Throwable? = null
        try {
            while (currentCoroutineContext().isActive) {
                when (val frame = Frames.read(input)) {
                    null -> break
                    is Frame.Json -> {
                        lastReceivedNs.set(System.nanoTime())
                        val msg = LinkJson.decode(frame.text) ?: continue
                        when (msg) {
                            is LinkMsg.Ping -> trySend(LinkMsg.Pong(msg.n))
                            is LinkMsg.Pong -> Unit
                            else -> emitEvent(LinkEvent.Message(msg))
                        }
                    }
                    is Frame.Blob -> {
                        lastReceivedNs.set(System.nanoTime())
                        emitEvent(LinkEvent.Blob(frame.blobId, frame.bytes))
                    }
                }
            }
        } catch (e: CancellationException) {
            // close() cancels the reader after closing the socket; its cause is retained.
        } catch (e: Throwable) {
            failure = e
        } finally {
            finish(failure)
            emitClosed()
        }
    }

    /** Fires [LinkTimeoutException] without taking [writeMutex], so a blocked blob write cannot stall it. */
    private suspend fun silenceLoop(timing: TogetherTiming) {
        val tickMs = minOf(timing.pingMs, timing.silenceMs).coerceAtLeast(1L)
        val silenceMs = timing.silenceMs.coerceAtLeast(1L)
        while (currentCoroutineContext().isActive && !closed.get()) {
            delay(tickMs)
            if (closed.get()) return
            val silentMs = (System.nanoTime() - lastReceivedNs.get()) / 1_000_000L
            if (silentMs >= silenceMs) {
                finish(LinkTimeoutException())
                return
            }
        }
    }

    private suspend fun pingLoop(timing: TogetherTiming) {
        val pingMs = timing.pingMs.coerceAtLeast(1L)
        var number = 0L
        while (currentCoroutineContext().isActive && !closed.get()) {
            delay(pingMs)
            if (closed.get()) return
            if (!trySend(LinkMsg.Ping(++number)) && closed.get()) return
        }
    }

    /** Sends one JSON frame if no blob write holds [writeMutex]. Does not wait for that lock. */
    private fun trySend(msg: LinkMsg): Boolean {
        if (!writeMutex.tryLock()) return false
        try {
            if (closed.get()) return false
            Frames.writeJson(output, LinkJson.encode(msg))
            output.flush()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            finish(e)
            return false
        } finally {
            writeMutex.unlock()
        }
    }

    private fun ensureOpen() {
        if (closed.get()) throw IOException("link closed")
    }

    private suspend fun emitEvent(event: LinkEvent) {
        eventMutex.withLock {
            eventChannel.send(event)
        }
    }

    private fun finish(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        closeCause.set(cause)
        runCatching { socket.close() }
        scope.cancel()
        emitClosed()
    }

    private fun emitClosed() {
        if (!emittedClosed.compareAndSet(false, true)) return
        eventCloseScope.launch {
            eventMutex.withLock {
                eventChannel.send(LinkEvent.Closed(closeCause.get()))
                eventChannel.close()
            }
            eventCloseScope.cancel()
        }
    }
}
