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
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The guest's media connection: connect, hello, then read
 * frames until the host says BYE or the connection drops. A dropped connection
 * is retried for [reconnectWindowMs]; BYE, a refused token or a newer protocol
 * end the stream. Pings run once a second (a burst of [StreamCaps.PING_BURST]
 * right after each connect) for [ClockSync]; a report goes out once a second.
 * Plain java.net, so JVM tests run it against a loopback host.
 */
class StreamClient(
    private val endpoint: StreamEndpoint,
    private val listener: Listener,
    private val report: () -> MediaFrame.Report,
    private val clock: () -> Long = System::nanoTime,
    private val reconnectWindowMs: Long = StreamCaps.RECONNECT_WINDOW_MS,
    private val connectTimeoutMs: Int = 3_000,
) : AutoCloseable {
    interface Listener {
        fun onState(state: State)
        fun onConfig(csd: ByteArray)
        fun onVideo(frame: MediaFrame.Video, receivedNs: Long)
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

    private val writeLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closed = false
    @Volatile private var connectedThisAttempt = false
    private var thread: Thread? = null
    private var pinger: Thread? = null
    private var pingId = 0

    fun start() {
        thread = Thread({ run() }, "gbx-stream-client").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        pinger = Thread({ pingLoop() }, "gbx-stream-ping").apply {
            isDaemon = true
            start()
        }
    }

    fun requestKeyframe() = send(MediaFrame.KeyframeRequest)

 /** Leaves: BYE to the host, then close. */
    override fun close() {
        if (closed) return
        closed = true
        send(MediaFrame.Bye(StreamWire.BYE_LEAVING))
        runCatching { socket?.close() }
        thread?.interrupt()
        pinger?.interrupt()
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

 /** One connection. Returns why the stream ended, or null when it dropped and may be retried. */
    private fun session(): EndReason? {
        val s = Socket()
        socket = s
        try {
            s.tcpNoDelay = true
            StreamSockets.markVideo(s)
            s.connect(InetSocketAddress(endpoint.host, endpoint.mediaPort), connectTimeoutMs)
            s.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            val o = s.getOutputStream()
            o.write(StreamHandshake.encodeHello(StreamToken.bytes(endpoint.token)))
            val welcome = StreamHandshake.decodeWelcome(StreamHandshake.readExactly(input, StreamWire.WELCOME_SIZE))
                ?: return EndReason.BAD_VERSION
            when (welcome.status) {
                StreamWire.STATUS_OK -> Unit
                StreamWire.STATUS_BAD_TOKEN -> return EndReason.BAD_TOKEN
                StreamWire.STATUS_BAD_VERSION -> return EndReason.BAD_VERSION
                else -> return null
            }
            out = o
            connectedThisAttempt = true
            listener.onState(State.Connected(welcome))
            burst()
            var lastReportNs = clock()
            while (!closed) {
                val frame = MediaFrames.read(input) ?: return null
                val now = clock()
                when (frame) {
                    is MediaFrame.VideoConfig -> listener.onConfig(frame.csd)
                    is MediaFrame.Video -> listener.onVideo(frame, now)
                    is MediaFrame.Audio -> listener.onAudio(frame)
                    is MediaFrame.Info -> listener.onInfo(frame)
                    is MediaFrame.Pong -> listener.onPong(frame.t0, frame.t1, frame.t2, now)
                    is MediaFrame.Bye -> return when (frame.reason) {
                        StreamWire.BYE_REPLACED -> EndReason.REPLACED
                        else -> EndReason.HOST_STOPPED
                    }
                    else -> Unit
                }
                if (now - lastReportNs >= REPORT_EVERY_NS) {
                    lastReportNs = now
                    send(report())
                }
            }
            return EndReason.LEFT
        } finally {
            out = null
            runCatching { s.close() }
        }
    }

    private fun burst() {
        Thread({
            try {
                repeat(StreamCaps.PING_BURST) {
                    ping()
                    Thread.sleep(BURST_SPACING_MS)
                }
            } catch (_: InterruptedException) {
 // closing
            }
        }, "gbx-stream-burst").apply {
            isDaemon = true
            start()
        }
    }

    private fun pingLoop() {
        try {
            while (!closed) {
                Thread.sleep(StreamCaps.PING_INTERVAL_MS)
                ping()
            }
        } catch (_: InterruptedException) {
 // closing
        }
    }

    private fun ping() {
        val id = synchronized(writeLock) { ++pingId }
        send(MediaFrame.Ping(id, clock()))
    }

    private fun send(frame: MediaFrame) {
        val bytes = MediaFrames.encode(frame)
        synchronized(writeLock) {
            val o = out ?: return
            try {
                o.write(bytes)
            } catch (_: IOException) {
                runCatching { socket?.close() }
            }
        }
    }

    private companion object {
        const val RETRY_MS = 500L
        const val BURST_SPACING_MS = 50L
 /** A host that sends nothing for this long is gone (it sends video at least every 100 ms). */
        const val READ_TIMEOUT_MS = 3_000
        const val REPORT_EVERY_NS = 1_000_000_000L
    }
}
