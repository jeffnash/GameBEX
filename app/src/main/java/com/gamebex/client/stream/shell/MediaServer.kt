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
import com.gamebex.client.stream.proto.DeliveryWire
import com.gamebex.client.stream.StreamDebug
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
    private val queueDeadlineMs: Int = StreamDebug.int("queue_deadline_ms", 0, 0..40),
    private val latencyControl: Boolean = StreamDebug.flag("latency_cc"),
    private val videoWriteStallMs: Int = StreamDebug.int("video_write_stall_ms", 0, 0..50),
    private val logger: (String) -> Unit = { System.err.println(it) },
) : MediaChannel {
    private val token = StreamToken.bytes(config.token)
    private val lock = Object()
    private val attachmentLifecycleLock = Any()
    private var server: ServerSocket? = null
    private var guest: Guest? = null
    private var control: VideoControl? = null
    private var policy = CongestionPolicy(StreamCaps.LEVELS, config.level, startNs = clock())
    private var pending: CongestionPolicy.Action? = null
    private var audioOn = false
    private var actualFormat: EncodedFormat? = null
    private var actualLevel = StreamCaps.LEVELS[config.level.coerceIn(0, StreamCaps.LEVELS.lastIndex)]
    private var actualBitrate = actualLevel.maxBitrate
    private var requestedLevel = actualLevel.index
    private var requestedBitrate = actualBitrate
    private var delivery = DeliveryControl(StreamCaps.LEVELS, actualLevel.index, startNs = clock())
    private var pressureDrops = 0L
    private var observedPressureDrops = 0L
    private val writeTimes = ArrayDeque<Pair<Long, Long>>()
    @Volatile override var onGuestAttached: (Long) -> Unit = {}
    @Volatile override var onGuestDetached: (Long) -> Unit = {}
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
    val videoConfigDiscarded = AtomicLong()
    val videoRetiredDiscarded = AtomicLong()
    val tcpAudioEvictedFrames = AtomicLong()
    val deliveryRejected = AtomicLong()
    val deadlineAgeFallbacks = AtomicLong()

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
            val levels = (control as? AvcScreenSource)?.eligibleLevels ?: StreamCaps.LEVELS
            policy = CongestionPolicy(levels, level.index, startNs = clock())
            delivery = DeliveryControl(levels, level.index, startNs = clock())
            if (actualFormat == null) {
                actualLevel = level
                actualBitrate = bitrate
            }
            requestedLevel = level.index
            requestedBitrate = bitrate
            audioOn = audio
            logQuality("start")
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
                bitrate = actualBitrate,
                level = actualLevel.index,
                framesSent = framesSent.get(),
                framesDropped = framesDropped.get(),
                guestVideoP95Us = guestP95Us,
                guestChangedNs = guestChangedNs,
                failure = failure,
                videoConfigDiscarded = videoConfigDiscarded.get(),
                videoRetiredDiscarded = videoRetiredDiscarded.get(),
                tcpAudioEvictedFrames = tcpAudioEvictedFrames.get(),
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
            val current = actualFormat ?: return
            onFormat(EncodedFormat(current.level, actualBitrate, csd, current.codecEpoch))
        }
    }

    override fun onFormat(format: EncodedFormat) {
        synchronized(lock) {
            if (closed || format.codecEpoch <= 0L || format.codecEpoch < (actualFormat?.codecEpoch ?: 0L)) return
            val changed = actualLevel != format.level || actualBitrate != format.bitrate
            actualFormat = format
            actualLevel = format.level
            actualBitrate = format.bitrate
            if (changed) logQuality("format")
            val g = guest ?: return
            val now = clock()
            val ageNs = g.video.ageNs(now)
            val depth = g.video.size
            val count = g.video.reset(format.codecEpoch)
            recordDrop(count, "config", ageNs, depth, "format", now)
            videoConfigDiscarded.addAndGet(count.toLong())
            g.latestReport = null
            g.control.addLast(Out.Format(format))
            lock.notifyAll()
        }
    }

    override fun onFrame(frame: EncodedFrame) {
        val now = clock()
        var askKey = false
        synchronized(lock) {
            val g = guest
            if (g == null || closed) {
                recordDrop(1, if (closed) "closed" else "no_guest", trigger = "offer", now = now)
                videoRetiredDiscarded.incrementAndGet()
                return
            }
            if (actualFormat == null || frame.codecEpoch <= 0L || frame.codecEpoch != g.video.epoch) {
                val reason = when {
                    frame.codecEpoch <= 0L -> "malformed_epoch"
                    actualFormat == null -> "missing_config"
                    else -> "epoch"
                }
                recordDrop(1, reason, depth = g.video.size, trigger = "offer", now = now)
                videoConfigDiscarded.incrementAndGet()
                return
            }
            val fallbacks = g.video.ageFallbacks
            val result = g.video.offer(frame, now)
            deadlineAgeFallbacks.addAndGet(g.video.ageFallbacks - fallbacks)
            account(result, now, "offer")
            askKey = result.requestKeyframe
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
            while (g.audio.size >= MAX_AUDIO_QUEUE) {
                tcpAudioEvictedFrames.addAndGet(g.audio.pollFirst().frames.toLong())
            }
            g.audio.addLast(Audio(MediaFrames.encode(MediaFrame.Audio(chunk.seq, chunk.captureNs, chunk.frames, chunk.data)), chunk.frames))
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
        val hello = StreamHandshake.readHello(input)
            ?: throw IOException("not a stream hello")
        val status = when {
            hello.version != StreamWire.VERSION -> StreamWire.STATUS_BAD_VERSION
            !MessageDigest.isEqual(hello.token, token) -> StreamWire.STATUS_BAD_TOKEN
            else -> StreamWire.STATUS_OK
        }
        val level = synchronized(lock) { actualLevel }
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
        val g = Guest(socket, out, hello.attachId, queueDeadlineMs)
        synchronized(attachmentLifecycleLock) {
            synchronized(lock) { if (closed) { socket.close(); return } }
            onGuestAttached(hello.attachId)
            synchronized(lock) {
                if (closed) {
                    socket.close()
                    return
                }
                val old = guest
                if (old != null) retire(old, StreamWire.BYE_REPLACED)
                guest = g
                guestChangedNs = clock()
                guestP95Us = 0
                observedPressureDrops = pressureDrops
                writeTimes.clear()
                actualFormat?.let {
                    g.video.reset(it.codecEpoch)
                    g.control.addLast(Out.Format(it))
                } ?: g.control.addLast(Out.Frame(infoFrame()))
                g.writer = spawn("gbx-media-write") { writeLoop(g) }
                lock.notifyAll()
            }
        }
        spawn("gbx-media-read") { readLoop(g, input) }
        requestKeyframe(clock(), force = true)
    }

    private fun writeLoop(g: Guest) {
        try {
            while (true) {
                var askKey = false
                val next: Any? = synchronized(lock) {
                    while (g.isEmpty() && guest === g && !closed) lock.wait(WAIT_MS)
                    if (g.isEmpty()) return
                    g.control.pollFirst() ?: g.audio.pollFirst() ?: g.video.poll(clock()).let { (entry, result) ->
                        account(result, clock(), "poll")
                        askKey = result.requestKeyframe
                        entry
                    }
                }
                if (askKey) requestKeyframe(clock())
                when (next) {
                    is Out.Format -> {
                        val f = next.format
                        val bundle = MediaFrames.encode(MediaFrame.Info(f.level.index, f.level.width, f.level.height, f.bitrate)) +
                            MediaFrames.encode(MediaFrame.VideoConfig(f.csd))
                        synchronized(lock) {
                            g.configCount++
                            g.sentHostEpoch = f.codecEpoch
                        }
                        g.out.write(bundle)
                    }
                    is Out.Frame -> {
                        g.out.write(next.bytes)
                        if (next.bye) {
                            runCatching { g.socket.shutdownOutput() }
                            return
                        }
                    }
                    is Out.Pong -> g.out.write(MediaFrames.encode(MediaFrame.Pong(next.id, next.t0, next.t1, clock())))
                    is Audio -> {
                        try {
                            g.out.write(next.bytes)
                        } catch (e: IOException) {
                            tcpAudioEvictedFrames.addAndGet(next.frames.toLong())
                            throw e
                        }
                    }
                    is DeadlineQueue.Entry -> {
                        g.videoWrites++
                        if (videoWriteStallMs > 0 && g.videoWrites % 100 == 0) {
                            System.err.println("FAULT video_write_stall_ms=$videoWriteStallMs au=${g.videoWrites}")
                            try {
                                Thread.sleep(videoWriteStallMs.toLong())
                            } catch (e: InterruptedException) {
                                recordDrop(1, "interrupted", trigger = "write", now = clock())
                                videoRetiredDiscarded.incrementAndGet()
                                throw e
                            }
                        }
                        val f = next.frame
                        val bytes = try {
                            MediaFrames.encode(MediaFrame.Video(f.key, f.seq, f.captureNs, f.encodedNs, 0L, f.data))
                        } catch (e: IOException) {
                            recordDrop(1, "malformed", trigger = "encode", now = clock())
                            throw e
                        }
                        val valid = synchronized(lock) {
                            val now = clock()
                            if (guest !== g || closed || !g.video.valid(next, now)) {
                                val ageNs = (now - if (queueDeadlineMs == 40 && f.encodedNs > 0L)
                                    f.encodedNs else next.queuedNs).coerceAtLeast(0L)
                                val depth = g.video.size
                                if (guest !== g || closed) {
                                    recordDrop(1, "retired_polled", ageNs, depth, "validate", now)
                                    videoRetiredDiscarded.incrementAndGet()
                                } else if (f.codecEpoch != g.video.epoch) {
                                    recordDrop(1, "epoch_polled", ageNs, depth, "validate", now)
                                    videoConfigDiscarded.incrementAndGet()
                                } else if (next.revision != g.video.revision) {
                                    recordDrop(1, "revision_polled", ageNs, depth, "validate", now)
                                } else {
                                    framesDropped.incrementAndGet()
                                    val count = g.video.clear()
                                    account(DeadlineQueue.Result(count, true, true, DeadlineQueue.Reason.STALE_POLLED,
                                        ageNs, depth), clock(), "validate", extraDropped = 1)
                                    pressureDrops++
                                    askKey = true
                                }
                                false
                            } else true
                        }
                        if (!valid) {
                            if (askKey) requestKeyframe(clock())
                            continue
                        }
                        val begin = clock()
                        java.nio.ByteBuffer.wrap(bytes).putLong(26, begin)
                        try {
                            g.out.write(bytes)
                        } catch (e: IOException) {
                            recordDrop(1, "write_failed", trigger = "write", now = clock())
                            videoRetiredDiscarded.incrementAndGet()
                            throw e
                        }
                        framesSent.incrementAndGet()
                        synchronized(lock) {
                            val end = clock()
                            writeTimes.addLast(end to (end - begin).coerceAtLeast(0L))
                            while (writeTimes.size > MAX_SENT_TIMES) writeTimes.pollFirst()
                            sentTimes.addLast(end)
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
                        if (guest === g && !closed) {
                            g.control.addLast(Out.Pong(frame.id, frame.t0, clock()))
                            lock.notifyAll()
                        }
                    }
                    MediaFrame.KeyframeRequest -> {
                        val current = synchronized(lock) {
                            if (guest !== g || closed) false else {
                                val now = clock()
                                val ageNs = g.video.ageNs(now)
                                val depth = g.video.size
                                recordDrop(g.video.clear(), "key_request", ageNs, depth, "guest_request", now)
                                true
                            }
                        }
                        if (current) requestKeyframe(clock())
                    }
                    is MediaFrame.Delivery -> synchronized(lock) {
                        val report = frame.report
                        if (guest === g && !closed && validReport(g, report)) {
                            g.lastReportSeq = report.seq
                            g.lastReportEnd = report.endNs
                            g.latestReport = report to clock()
                            guestP95Us = if (report.relativeP95Ns < 0L) 0 else (report.relativeP95Ns / 1_000L).toInt()
                        } else deliveryRejected.incrementAndGet()
                    }
                    is MediaFrame.Bye -> {
                        synchronized(attachmentLifecycleLock) {
                            val leave = synchronized(lock) {
                                if (guest === g && frame.reason == StreamWire.BYE_LEAVING) {
                                    guest = null
                                    guestChangedNs = clock()
                                    discardRetired(g, "leave")
                                    true
                                } else false
                            }
                            if (leave) onGuestDetached(g.attachId)
                        }
                        break
                    }
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
                    if (latencyControl) {
                        val g = guest
                        val dropped = pressureDrops != observedPressureDrops
                        observedPressureDrops = pressureDrops
                        val age = g?.video?.ageNs(now) ?: 0L
                        val report = g?.latestReport
                        if (g != null) g.latestReport = null
                        val a = if (g != null && report != null && now - report.second in 0L..1500 * MS &&
                            g.sentHostEpoch == actualFormat?.codecEpoch && report.first.codecEpoch == g.configCount) {
                            while (writeTimes.isNotEmpty() && now - writeTimes.peekFirst().first > 500 * MS) writeTimes.pollFirst()
                            val samples = writeTimes.map { it.second }.sorted()
                            val p95 = if (samples.isEmpty()) 0L else samples[((samples.size * 95 + 99) / 100 - 1).coerceAtLeast(0)]
                            delivery.window(now, report.first.relativeP95Ns.takeIf { it >= 0L }, age, p95, report.first.pending, dropped)
                        } else delivery.localPressure(now, age, dropped)
                        when (a) {
                            is DeliveryControl.Action.Bitrate -> CongestionPolicy.Action.SetBitrate(a.bps)
                            is DeliveryControl.Action.Level -> CongestionPolicy.Action.ChangeLevel(a.level, a.bps)
                            null -> null
                        }
                    } else {
                        val a = pending ?: policy.tick(now)
                        pending = null
                        a
                    }
                } ?: continue
                val c = control ?: continue
                when (action) {
                    is CongestionPolicy.Action.SetBitrate -> {
                        synchronized(lock) {
                            requestedLevel = actualLevel.index
                            requestedBitrate = action.bps
                        }
                        c.setBitrate(action.bps)
                        synchronized(lock) {
                            actualBitrate = action.bps
                            actualFormat?.let { actualFormat = EncodedFormat(it.level, action.bps, it.csd, it.codecEpoch) }
                            guest?.control?.addLast(Out.Frame(infoFrame()))
                            logQuality("bitrate")
                            lock.notifyAll()
                        }
                    }
                    is CongestionPolicy.Action.ChangeLevel -> {
                        synchronized(lock) {
                            requestedLevel = action.level.index
                            requestedBitrate = action.bps
                            logQuality("level_request")
                        }
                        c.changeLevel(action.level, action.bps)
                    }
                }
            }
        } catch (_: InterruptedException) {
            // close()
        }
    }

    /** Called with [lock] held. */
    private fun infoFrame(): ByteArray {
        val l = actualLevel
        return MediaFrames.encode(MediaFrame.Info(l.index, l.width, l.height, actualBitrate))
    }

    /** Called with [lock] held: the writer sends BYE after what is already queued for control, then stops. */
    private fun retire(g: Guest, reason: Int) {
        discardRetired(g, if (reason == StreamWire.BYE_REPLACED) "replaced" else "stopping")
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
            discardRetired(g, "lost")
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

    private fun account(result: DeadlineQueue.Result, now: Long, trigger: String, extraDropped: Int = 0) {
        val total = framesDropped.addAndGet(result.dropped.toLong())
        if (result.dropped + extraDropped > 0) {
            logDrop(result.dropped + extraDropped, result.reason.name.lowercase(), result.ageNs, result.depth,
                trigger, now, total, result.incomingReason.name.lowercase())
        }
        if (result.pressure) {
            pressureDrops += result.dropped
            if (!latencyControl) policy.onDrop(now)?.let { pending = it }
        }
    }

    private fun recordDrop(count: Int, reason: String, ageNs: Long = 0L, depth: Int = 0,
        trigger: String, now: Long) {
        val total = framesDropped.addAndGet(count.toLong())
        logDrop(count, reason, ageNs, depth, trigger, now, total)
    }

    private fun logDrop(count: Int, reason: String, ageNs: Long, depth: Int, trigger: String,
        now: Long, total: Long, incomingReason: String = "none") {
        if (count <= 0) return
        runCatching {
            logger("HOST_DROP reason=$reason count=$count ageNs=$ageNs depth=$depth trigger=$trigger " +
                "incomingReason=$incomingReason totalDropped=$total nowNs=$now")
        }
    }

    private fun logQuality(trigger: String) {
        runCatching {
            logger("HOST_QUALITY trigger=$trigger requestedLevel=$requestedLevel requestedBitrate=$requestedBitrate " +
                "actualLevel=${actualLevel.index} actualBitrate=$actualBitrate totalDropped=${framesDropped.get()}")
        }
    }

    private fun discardRetired(g: Guest, trigger: String) {
        val now = clock()
        val ageNs = g.video.ageNs(now)
        val depth = g.video.size
        val count = g.video.clear()
        recordDrop(count, "retired", ageNs, depth, trigger, now)
        videoRetiredDiscarded.addAndGet(count.toLong())
        g.audio.forEach { tcpAudioEvictedFrames.addAndGet(it.frames.toLong()) }
        g.audio.clear()
    }

    private fun validReport(g: Guest, report: DeliveryWire.Report): Boolean =
        report.codecEpoch == g.configCount && g.sentHostEpoch == actualFormat?.codecEpoch &&
            report.spanNs in 200_000_000L..2_000_000_000L && report.bytes in 0L..64_000_000L &&
            report.frames in 0..500 && report.relativeP95Ns in -1L..2_000_000_000L && report.pending in 0..32 &&
            report.endNs > 0L && report.endNs > g.lastReportEnd &&
            (g.lastReportSeq == null || report.seq - g.lastReportSeq!! > 0)

    private class Audio(val bytes: ByteArray, val frames: Int)

    private sealed interface Out {
        class Frame(val bytes: ByteArray, val bye: Boolean = false) : Out
        class Pong(val id: Int, val t0: Long, val t1: Long) : Out
        class Format(val format: EncodedFormat) : Out
    }

    private class Guest(val socket: Socket, val out: OutputStream, val attachId: Long, deadlineMs: Int) {
        val control = ArrayDeque<Out>()
        val audio = ArrayDeque<Audio>()
        val video = DeadlineQueue(deadlineMs)
        var configCount = 0L
        var sentHostEpoch: Long? = null
        var lastReportSeq: Int? = null
        var lastReportEnd = Long.MIN_VALUE
        var latestReport: Pair<DeliveryWire.Report, Long>? = null
        var videoWrites = 0
        @Volatile var writer: Thread? = null
        @Volatile var retired = false

        fun isEmpty(): Boolean = control.isEmpty() && (retired || (audio.isEmpty() && video.isEmpty))
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
