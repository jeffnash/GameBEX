package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.StreamSockets
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamEndpoint
import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.proto.MediaFrame
import com.gamebex.client.stream.proto.MediaFrames
import com.gamebex.client.stream.proto.StreamHandshake
import com.gamebex.client.stream.proto.StreamWire
import com.gamebex.client.stream.proto.Welcome
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class StreamClient(
    private val endpoint: StreamEndpoint,
    private val listener: Listener,
    private val decoderPending: () -> Int = { 0 },
    private val clock: () -> Long = System::nanoTime,
    private val reconnectWindowMs: Long = StreamCaps.RECONNECT_WINDOW_MS,
    private val connectTimeoutMs: Int = 3_000,
    private val attachId: Long = StreamToken.attachId(),
    private val onCounter: (String, Long) -> Unit = { _, _ -> },
    private val tickerFactory: (() -> Unit) -> AutoCloseable = { action ->
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "gbx-stream-control-timer").apply { isDaemon = true }
        }
        executor.scheduleAtFixedRate({ action() }, 0L, 50L, TimeUnit.MILLISECONDS)
        AutoCloseable { executor.shutdownNow() }
    },
) : AutoCloseable {
    interface Listener {
        fun onState(state: State)
        fun onConfig(csd: ByteArray)
        fun onVideo(frame: MediaFrame.Video, receivedNs: Long)
        fun onConfig(csd: ByteArray, codecEpoch: Long) = onConfig(csd)
        fun onVideo(frame: MediaFrame.Video, receivedNs: Long, codecEpoch: Long) = onVideo(frame, receivedNs)
        fun onAudio(frame: MediaFrame.Audio)
        fun onInfo(info: MediaFrame.Info)
        fun onPong(t0: Long, t1: Long, t2: Long, t3: Long)
    }

    sealed interface State {
        data object Connecting : State
        data class Connected(val welcome: Welcome) : State
        data object Reconnecting : State
        data class Ended(val reason: EndReason) : State
    }

    enum class EndReason { LEFT, HOST_STOPPED, REPLACED, BAD_TOKEN, BAD_VERSION, LOST }

    private class Connection(val socket: Socket, val writer: ControlWriter, val delivery: DeliveryWindow,
        val openedNs: Long) {
        @Volatile var epoch = 0L
        @Volatile var leaving = false
        @Volatile var ticker: AutoCloseable? = null
        var nextPingNs = openedNs
        var burstRemaining = StreamCaps.PING_BURST
    }

    private val stateLock = Any()
    private val current = AtomicReference<Connection?>()
    private val pingId = AtomicInteger()
    private var connectingSocket: Socket? = null
    @Volatile private var closed = false
    @Volatile private var connectedThisAttempt = false
    private var thread: Thread? = null

    fun start() = synchronized(stateLock) {
        if (closed || thread != null) return@synchronized
        thread = Thread({ run() }, "gbx-stream-client").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun requestKeyframe() {
        current.get()?.writer?.offer(ControlWriter.Kind.KEYFRAME) { MediaFrames.encode(MediaFrame.KeyframeRequest) }
    }

    override fun close() {
        val connection = synchronized(stateLock) {
            if (closed) return
            val c = current.get()
            c?.leaving = true
            current.compareAndSet(c, null)
            closed = true
            runCatching { connectingSocket?.close() }
            c
        }
        runCatching { connection?.ticker?.close() }
        connection?.writer?.leave()
        thread?.interrupt()
    }

    private fun run() {
        var lostSinceNs = -1L
        var first = true
        while (!closed) {
            listener.onState(if (first) State.Connecting else State.Reconnecting)
            first = false
            connectedThisAttempt = false
            val end = try {
                session()
            } catch (_: IOException) {
                null
            }
            if (closed) {
                listener.onState(State.Ended(EndReason.LEFT))
                return
            }
            if (end != null) {
                listener.onState(State.Ended(end))
                closed = true
                return
            }
            val now = clock()
            if (connectedThisAttempt || lostSinceNs < 0) lostSinceNs = now
            if (now - lostSinceNs > reconnectWindowMs * 1_000_000L) {
                listener.onState(State.Ended(EndReason.LOST))
                closed = true
                return
            }
            try {
                Thread.sleep(RETRY_MS)
            } catch (_: InterruptedException) {
                listener.onState(State.Ended(EndReason.LEFT))
                return
            }
        }
    }

    private fun session(): EndReason? {
        val s = Socket()
        var connection: Connection? = null
        synchronized(stateLock) {
            if (closed) { s.close(); return EndReason.LEFT }
            connectingSocket = s
        }
        try {
            s.tcpNoDelay = true
            StreamSockets.markVideo(s)
            s.connect(InetSocketAddress(endpoint.host, endpoint.mediaPort), connectTimeoutMs)
            s.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            val output = s.getOutputStream()
            output.write(StreamHandshake.encodeHello(StreamToken.bytes(endpoint.token), attachId))
            val welcome = StreamHandshake.decodeWelcome(StreamHandshake.readExactly(input, StreamWire.WELCOME_SIZE))
                ?: return EndReason.BAD_VERSION
            if (welcome.version != StreamWire.VERSION) return EndReason.BAD_VERSION
            when (welcome.status) {
                StreamWire.STATUS_OK -> Unit
                StreamWire.STATUS_BAD_TOKEN -> return EndReason.BAD_TOKEN
                StreamWire.STATUS_BAD_VERSION -> return EndReason.BAD_VERSION
                else -> return null
            }
            val c = Connection(s, ControlWriter(output, { s.close() }, { s.close() }, clock),
                DeliveryWindow { onCounter(it, 1L) }, clock())
            connection = c
            synchronized(stateLock) {
                if (closed) return EndReason.LEFT
                connectingSocket = null
                current.set(c)
            }
            connectedThisAttempt = true
            listener.onState(State.Connected(welcome))
            c.ticker = tickerFactory { tick(c) }
            if (closed || current.get() !== c) { c.ticker?.close(); return EndReason.LEFT }
            while (!closed && current.get() === c) {
                val frame = MediaFrames.read(input) ?: return null
                if (closed || current.get() !== c) return EndReason.LEFT
                val now = clock()
                when (frame) {
                    is MediaFrame.VideoConfig -> {
                        c.epoch += 1L
                        c.delivery.begin(c.epoch, now)
                        listener.onConfig(frame.csd, c.epoch)
                    }
                    is MediaFrame.Video -> {
                        if (c.epoch == 0L) onCounter("v_before_config", 1L) else {
                            c.delivery.received(c.epoch, frame.seq, now, frame.sendNs, frame.data.size)
                            listener.onVideo(frame, now, c.epoch)
                        }
                    }
                    is MediaFrame.Audio -> listener.onAudio(frame)
                    is MediaFrame.Info -> listener.onInfo(frame)
                    is MediaFrame.Pong -> listener.onPong(frame.t0, frame.t1, frame.t2, now)
                    is MediaFrame.Bye -> return when (frame.reason) {
                        StreamWire.BYE_REPLACED -> EndReason.REPLACED
                        else -> EndReason.HOST_STOPPED
                    }
                    else -> Unit
                }
            }
            return EndReason.LEFT
        } finally {
            connection?.let { c ->
                val leaving = synchronized(stateLock) {
                    current.compareAndSet(c, null)
                    c.leaving
                }
                runCatching { c.ticker?.close() }
                if (!leaving) c.writer.close()
            }
            synchronized(stateLock) { if (connectingSocket === s) connectingSocket = null }
            if (connection?.leaving != true) runCatching { s.close() }
        }
    }

    private fun tick(c: Connection) {
        if (closed || current.get() !== c) return
        val now = clock()
        if (now >= c.nextPingNs) {
            val id = pingId.incrementAndGet()
            c.writer.offer(ControlWriter.Kind.PING) { writtenNs -> MediaFrames.encode(MediaFrame.Ping(id, writtenNs)) }
            if (c.burstRemaining > 0) c.burstRemaining -= 1
            c.nextPingNs = now + if (c.burstRemaining > 0) BURST_SPACING_NS else StreamCaps.PING_INTERVAL_MS * 1_000_000L
        }
        c.delivery.report(now, decoderPending())?.let { report ->
            c.writer.offer(ControlWriter.Kind.REPORT) { MediaFrames.encode(MediaFrame.Delivery(report)) }
        }
    }

    private companion object {
        const val RETRY_MS = 500L
        const val BURST_SPACING_NS = 50_000_000L
        const val READ_TIMEOUT_MS = 3_000
    }
}
