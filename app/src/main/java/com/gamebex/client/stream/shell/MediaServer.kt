package com.gamebex.client.stream.shell

import com.gamebex.client.stream.proto.StreamSockets
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamError
import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.VideoLevel
import com.gamebex.client.stream.proto.MediaFrame
import com.gamebex.client.stream.proto.MediaFrames
import com.gamebex.client.stream.proto.StreamHandshake
import com.gamebex.client.stream.proto.StreamWire
import com.gamebex.client.stream.proto.Welcome
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * The guest-facing media connection: one TCP listener, one guest
 * at a time (a new valid guest replaces the old one, which gets BYE
 * "replaced"), and a writer that always sends control frames first, then
 * audio, then video. Video that can't leave fast enough is dropped whole
 * back to the next keyframe, and the [CongestionPolicy] decides the
 * bitrate. Plain java.net, so JVM tests run it on loopback.
 *
 * Threads: the accept thread, one reader and one writer per guest, and the
 * ticker, which is the only thread that calls [VideoControl.setBitrate] and
 * [VideoControl.changeLevel] (never the encoder's own thread).
 */
class MediaServer(
    private val config: StreamConfig,
    private val clock: () -> Long = System::nanoTime,
    private val tickMs: Long = 100L,
) : MediaChannel {
    private val token = StreamToken.bytes(config.token)
    private val lock = Object()
    private var server: ServerSocket? = null
    private var guest: Guest? = null
    private var control: VideoControl? = null
    private var policy = CongestionPolicy(StreamCaps.LEVELS, config.level, startNs = clock())
    private var pending: CongestionPolicy.Action? = null
    private var audioOn = false
    private var csd: ByteArray? = null
    private var waitingForKey = true
    private var lastKeyRequestNs = Long.MIN_VALUE
    private val sentTimes = ArrayDeque<Long>()
    private val threads = mutableListOf<Thread>()

    @Volatile private var closed = false
    @Volatile private var guestChangedNs = clock()
    @Volatile private var guestP95Us = 0
    @Volatile private var failure: String? = null

    val framesSent = AtomicLong()
    val framesDropped = AtomicLong()
    val rejectedConnections = AtomicLong()

    @Volatile override var port: Int = 0
        private set

    override fun open(preferredPort: Int) {
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            try {
                s.bind(InetSocketAddress(preferredPort), 1)
            } catch (_: BindException) {
                s.bind(InetSocketAddress(0), 1)
            }
        } catch (e: IOException) {
            runCatching { s.close() }
            throw StreamFailure(StreamError.NETWORK, "media socket: ${e.message}", e)
        }
        server = s
        port = s.localPort
    }

    override fun start(control: VideoControl, level: VideoLevel, bitrate: Int, audio: Boolean) {
        val s = server ?: throw IllegalStateException("open() first")
        synchronized(lock) {
            this.control = control
            policy = CongestionPolicy(StreamCaps.LEVELS, level.index, startNs = clock())
            audioOn = audio
        }
        spawn("gbx-media-accept") { acceptLoop(s) }
        spawn("gbx-media-tick") { tickLoop() }
    }

    override fun stats(): MediaStats {
        val now = clock()
        synchronized(lock) {
            while (sentTimes.isNotEmpty() && now - sentTimes.peekFirst() > SECOND) sentTimes.pollFirst()
            return MediaStats(
                guestConnected = guest != null,
                fps = sentTimes.size,
                bitrate = policy.bitrate,
                level = policy.level.index,
                framesSent = framesSent.get(),
                framesDropped = framesDropped.get(),
                guestVideoP95Us = guestP95Us,
                guestChangedNs = guestChangedNs,
                failure = failure,
            )
        }
    }

    override fun close() {
        val g: Guest?
        synchronized(lock) {
            if (closed) return
            closed = true
            g = guest
            guest = null
            if (g != null) retire(g, StreamWire.BYE_STOPPING)
            lock.notifyAll()
        }
        g?.writer?.join(BYE_WAIT_MS)
        g?.let { runCatching { it.socket.close() } }
        runCatching { server?.close() }
        synchronized(threads) { threads.toList() }.forEach { if (it !== Thread.currentThread()) it.join(BYE_WAIT_MS) }
    }

    override fun onConfig(csd: ByteArray) {
        synchronized(lock) {
            this.csd = csd
            val g = guest ?: return
            g.video.clear()
            g.control.addLast(Out.Frame(MediaFrames.encode(MediaFrame.VideoConfig(csd))))
            waitingForKey = true
            lock.notifyAll()
        }
    }

    override fun onFrame(frame: EncodedFrame) {
        val now = clock()
        var askKey = false
        synchronized(lock) {
            val g = guest
            if (g == null || closed) return
            if (waitingForKey && !frame.key) {
                framesDropped.incrementAndGet()
                askKey = true
            } else {
                val oldest = g.video.peekFirst()
                val late = oldest != null && now - oldest.queuedNs > StreamCaps.MAX_QUEUE_AGE_MS * MS
                if (g.video.size >= StreamCaps.MAX_QUEUED_FRAMES || late) {
                    framesDropped.addAndGet(g.video.size.toLong())
                    g.video.clear()
                    policy.onDrop(now)?.let { pending = it }
                    if (!frame.key) {
                        framesDropped.incrementAndGet()
                        waitingForKey = true
                        askKey = true
                    }
                }
                if (!waitingForKey || frame.key) {
                    g.video.addLast(Queued(frame, now))
                    waitingForKey = false
                }
            }
            lock.notifyAll()
        }
        if (askKey) requestKeyframe(now)
    }

    /** The video source gave up; StreamHostSession sees [MediaStats.failure] and stops the stream. */
    override fun onFailure(reason: String) {
        failure = reason
        close()
    }

    override fun onPcm(chunk: PcmChunk) {
        synchronized(lock) {
            val g = guest ?: return
            if (!audioOn || closed) return
            while (g.audio.size >= MAX_AUDIO_QUEUE) g.audio.pollFirst()
            g.audio.addLast(MediaFrames.encode(MediaFrame.Audio(chunk.seq, chunk.captureNs, chunk.frames, chunk.data)))
            lock.notifyAll()
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!closed) {
            val socket = try {
                s.accept()
            } catch (e: IOException) {
                if (closed) return
                continue
            }
            val from = socket.inetAddress?.hostAddress?.removePrefix("::ffff:")
            if (from != config.guestAddress) {
                rejectedConnections.incrementAndGet()
                runCatching { socket.close() }
                continue
            }
            runCatching { handshake(socket) }.onFailure {
                rejectedConnections.incrementAndGet()
                runCatching { socket.close() }
            }
        }
    }

    private fun handshake(socket: Socket) {
        socket.soTimeout = HELLO_TIMEOUT_MS
        socket.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val out = socket.getOutputStream()
        val hello = StreamHandshake.decodeHello(StreamHandshake.readExactly(input, StreamWire.HELLO_SIZE))
            ?: throw IOException("not a stream hello")
        val status = when {
            hello.version != StreamWire.VERSION -> StreamWire.STATUS_BAD_VERSION
            !MessageDigest.isEqual(hello.token, token) -> StreamWire.STATUS_BAD_TOKEN
            else -> StreamWire.STATUS_OK
        }
        val level = synchronized(lock) { policy.level }
        val rate = if (audioOn) StreamCaps.AUDIO_RATE else 0
        val welcome = Welcome(StreamWire.VERSION, status, level.width, level.height, rate, StreamCaps.AUDIO_CHANNELS, clock())
        out.write(StreamHandshake.encodeWelcome(welcome))
        if (status != StreamWire.STATUS_OK) {
            runCatching { socket.shutdownOutput() }
            socket.close()
            return
        }
        socket.soTimeout = 0
        runCatching { socket.sendBufferSize = SEND_BUFFER }
        StreamSockets.tuneMedia(socket)
        val g = Guest(socket, out)
        val old: Guest?
        synchronized(lock) {
            if (closed) {
                socket.close()
                return
            }
            old = guest
            if (old != null) retire(old, StreamWire.BYE_REPLACED)
            guest = g
            guestChangedNs = clock()
            waitingForKey = true
            csd?.let { g.control.addLast(Out.Frame(MediaFrames.encode(MediaFrame.VideoConfig(it)))) }
            g.control.addLast(Out.Frame(infoFrame()))
            lock.notifyAll()
        }
        g.writer = spawn("gbx-media-write") { writeLoop(g) }
        spawn("gbx-media-read") { readLoop(g, input) }
        requestKeyframe(clock(), force = true)
    }

    private fun writeLoop(g: Guest) {
        try {
            while (true) {
                val next: Any = synchronized(lock) {
                    while (g.isEmpty() && guest === g && !closed) lock.wait(WAIT_MS)
                    if (g.isEmpty()) return
                    g.control.pollFirst() ?: g.audio.pollFirst() ?: g.video.pollFirst() ?: return
                }
                when (next) {
                    is Out.Frame -> {
                        g.out.write(next.bytes)
                        if (next.bye) {
                            runCatching { g.socket.shutdownOutput() }
                            return
                        }
                    }
                    is Out.Pong -> g.out.write(MediaFrames.encode(MediaFrame.Pong(next.id, next.t0, next.t1, clock())))
                    is ByteArray -> g.out.write(next)
                    is Queued -> {
                        val f = next.frame
                        g.out.write(MediaFrames.encode(MediaFrame.Video(f.key, f.seq, f.captureNs, f.encodedNs, clock(), f.data)))
                        framesSent.incrementAndGet()
                        synchronized(lock) {
                            sentTimes.addLast(clock())
                            while (sentTimes.size > MAX_SENT_TIMES) sentTimes.pollFirst()
                        }
                    }
                }
            }
        } catch (_: IOException) {
            lost(g)
        } catch (_: InterruptedException) {
            lost(g)
        }
    }

    private fun readLoop(g: Guest, input: DataInputStream) {
        try {
            while (true) {
                val frame = MediaFrames.read(input) ?: break
                when (frame) {
                    is MediaFrame.Ping -> synchronized(lock) {
                        g.control.addLast(Out.Pong(frame.id, frame.t0, clock()))
                        lock.notifyAll()
                    }
                    MediaFrame.KeyframeRequest -> {
                        synchronized(lock) {
                            if (guest === g) {
                                framesDropped.addAndGet(g.video.size.toLong())
                                g.video.clear()
                                waitingForKey = true
                            }
                        }
                        requestKeyframe(clock())
                    }
                    is MediaFrame.Report -> guestP95Us = frame.videoP95Us
                    is MediaFrame.Bye -> break
                    else -> Unit
                }
            }
        } catch (_: IOException) {
            // Lost connection; handled below.
        }
        lost(g)
    }

    private fun tickLoop() {
        try {
            while (!closed) {
                Thread.sleep(tickMs)
                val now = clock()
                val action = synchronized(lock) {
                    val a = pending ?: policy.tick(now)
                    pending = null
                    if (a != null) guest?.let { g ->
                        g.control.addLast(Out.Frame(infoFrame()))
                        lock.notifyAll()
                    }
                    a
                } ?: continue
                val c = control ?: continue
                when (action) {
                    is CongestionPolicy.Action.SetBitrate -> c.setBitrate(action.bps)
                    is CongestionPolicy.Action.ChangeLevel -> c.changeLevel(action.level, action.bps)
                }
            }
        } catch (_: InterruptedException) {
            // close()
        }
    }

    /** Called with [lock] held. */
    private fun infoFrame(): ByteArray {
        val l = policy.level
        return MediaFrames.encode(MediaFrame.Info(l.index, l.width, l.height, policy.bitrate))
    }

    /** Called with [lock] held: the writer sends BYE after what is already queued for control, then stops. */
    private fun retire(g: Guest, reason: Int) {
        g.audio.clear()
        g.video.clear()
        g.control.addLast(Out.Frame(MediaFrames.encode(MediaFrame.Bye(reason)), bye = true))
        g.retired = true
        lock.notifyAll()
    }

    private fun lost(g: Guest) {
        synchronized(lock) {
            if (guest === g) {
                guest = null
                guestChangedNs = clock()
            }
            lock.notifyAll()
        }
        runCatching { g.socket.close() }
    }

    private fun requestKeyframe(now: Long, force: Boolean = false) {
        val c = synchronized(lock) {
            if (!force && lastKeyRequestNs != Long.MIN_VALUE && now - lastKeyRequestNs < KEY_REQUEST_SPACING_MS * MS) return
            lastKeyRequestNs = now
            control
        }
        runCatching { c?.requestKeyframe() }
    }

    private fun spawn(name: String, body: () -> Unit): Thread = Thread(body, name).apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
        synchronized(threads) { threads += this }
        start()
    }

    private class Queued(val frame: EncodedFrame, val queuedNs: Long)

    private sealed interface Out {
        class Frame(val bytes: ByteArray, val bye: Boolean = false) : Out
        class Pong(val id: Int, val t0: Long, val t1: Long) : Out
    }

    private class Guest(val socket: Socket, val out: OutputStream) {
        val control = ArrayDeque<Out>()
        val audio = ArrayDeque<ByteArray>()
        val video = ArrayDeque<Queued>()
        @Volatile var writer: Thread? = null
        @Volatile var retired = false

        fun isEmpty(): Boolean = control.isEmpty() && (retired || (audio.isEmpty() && video.isEmpty()))
    }

    private companion object {
        const val MS = 1_000_000L
        const val SECOND = 1_000_000_000L
        const val HELLO_TIMEOUT_MS = 3_000
        const val WAIT_MS = 50L
        const val BYE_WAIT_MS = 300L
        const val KEY_REQUEST_SPACING_MS = 250L
        /** 100 ms of 48 kHz stereo audio (5 ms chunks); older chunks are dropped first. */
        const val MAX_AUDIO_QUEUE = 20
        const val MAX_SENT_TIMES = 240
        /** Small on purpose: data waiting in the kernel is latency the queue check can't see. */
        const val SEND_BUFFER = 64 * 1024
    }
}
