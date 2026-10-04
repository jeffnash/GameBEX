package com.gamebex.client.stream.shell

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class RSoundAudioSource(
    private val fallback: AudioSource?,
    private val playbackFactory: RSoundPlaybackFactory = RSoundPlaybackFactory { RSoundPlayback() },
    private val port: Int = 12345,
    private val address: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
    private val noConnectMs: Long = 5_000,
    private val pairWaitMs: Int = 1_500,
    private val headerWaitMs: Int = 1_000,
    private val logger: (String) -> Unit = { message -> runCatching { Log.w("GameBEXStream", message) }; Unit },
    private val useControlProtocol: Boolean = false,
    private val transportChunkBytes: Int = 512,
    private val quickAckEnabled: Boolean = true,
    private val quickAckFactory: RSoundQuickAckFactory = AndroidRSoundQuickAckFactory,
) : AudioSource {
    private enum class Mode { WAITING, RSOUND, FALLBACK, STOPPED }

    private class Run {
        lateinit var guest: RSoundGuestQueue
        val resources = Any()
        val wake = Object()
        val sockets = mutableSetOf<Socket>()
        val players = mutableSetOf<RSoundPlayer>()
        val threads = mutableSetOf<Thread>()
        @Volatile var alive = true
        @Volatile var mode = Mode.WAITING
        @Volatile var server: ServerSocket? = null
        @Volatile var prepared: RSoundPlayer? = null
        @Volatile var active: Connection? = null
        var fallbackStarted = false
        var launchTimerStarted = false
        var connected = false
    }

    private data class LocalLoss(val pendingChunks: Int, val pendingBytes: Int, val inFlightBytes: Int, val rejectedBytes: Int) {
        val discardedBytes: Int get() = pendingBytes + inFlightBytes + rejectedBytes
    }

    private class LocalQueue {
        private val lock = Object()
        private val pending = ArrayDeque<ByteArray>()
        private var alive = true
        private var pendingBytes = 0
        private var inFlightBytes = 0
        private var rejectedBytes = 0
        val pendingChunks: Int get() = synchronized(lock) { pending.size }

        fun enqueue(bytes: ByteArray) = synchronized(lock) {
            if (!alive) return@synchronized
            if (pending.size == LOCAL_QUEUE_CHUNKS) {
                rejectedBytes += bytes.size
                throw LocalPlaybackFailure(IOException("RSound local queue overflow: $LOCAL_QUEUE_CHUNKS chunks / ${LOCAL_QUEUE_CHUNKS * 5}ms"))
            }
            pending.addLast(bytes)
            pendingBytes += bytes.size
            lock.notifyAll()
        }

        fun take(): ByteArray? = synchronized(lock) {
            while (alive && pending.isEmpty()) lock.wait()
            if (!alive) null else pending.removeFirst().also {
                pendingBytes -= it.size
                inFlightBytes = it.size
            }
        }

        fun written(count: Int) = synchronized(lock) {
            if (alive) inFlightBytes -= count
        }

        fun close(): LocalLoss? = synchronized(lock) {
            if (!alive) return@synchronized null
            alive = false
            val loss = LocalLoss(pending.size, pendingBytes, inFlightBytes, rejectedBytes)
            pending.clear()
            pendingBytes = 0
            inFlightBytes = 0
            lock.notifyAll()
            loss
        }
    }

    private data class Counters(
        val receivedBytes: Long,
        val writtenBytes: Long,
        val nonzeroChunks: Long,
        val lastArrivalNs: Long,
    )

    private data class DiagnosticSnapshot(val counters: Counters, val arrival: String, val timings: String)

    private class Connection(val data: Socket, val control: Socket, val player: RSoundPlayer) {
        val local = LocalQueue()
        val metrics = Any()
        @Volatile var alive = true
        var receivedBytes = 0L
        var writtenBytes = 0L
        var nonzeroChunks = 0L
        var nextLogNs = 0L
        var lastArrivalNs = 0L
        var firstArrivalNs = 0L
        var quickAckSuccesses = 0L
        var quickAckFailures = 0L
        var quickAckWarning = false
        val arrivalGaps = LongArray(4096)
        var arrivalGapIndex = 0
        var arrivalGapSamples = 0
        var arrivalGapsOver30Ms = 0L
        val readTiming = Timing()
        val deliverTiming = Timing()
        val writeTiming = Timing()
        val arrivalGapTiming = Timing()
        val arrivalToDeliverTiming = Timing()

        fun counters(): Counters = synchronized(metrics) {
            Counters(receivedBytes, writtenBytes, nonzeroChunks, lastArrivalNs)
        }
    }

    private class LocalPlaybackFailure(cause: Throwable) : IOException("RSound local playback failed: ${cause.message}", cause)

    private class Timing {
        var count = 0L
        var totalNs = 0L
        var lastNs = 0L
        var maxNs = 0L

        fun record(durationNs: Long) {
            count++
            totalNs += durationNs
            lastNs = durationNs
            maxNs = maxOf(maxNs, durationNs)
        }

        fun summary(label: String): String =
            "${label}Count=$count ${label}TotalUs=${totalNs / 1_000} ${label}LastUs=${lastNs / 1_000} ${label}MaxUs=${maxNs / 1_000}"
    }

    private val transitions = Any()
    private val sequence = AtomicInteger()
    @Volatile private var session: Run? = null
    @Volatile private var ready = false
    @Volatile private var mode = "loopback"
    @Volatile private var reason: String? = null
    @Volatile private var failure: String? = null
    @Volatile private var stoppedGuestDrops = 0L

    override val rsoundReady: Boolean get() = ready
    override val audioMode: String get() = mode
    override val fallbackReason: String? get() = reason
    val boundPort: Int get() = session?.server?.localPort ?: -1
    val clientConnected: Boolean get() = session?.active?.alive == true
    val failureKind: String? get() = failure
    val guestDrops: Long get() = session?.guest?.drops ?: stoppedGuestDrops
    override val audioFailureKind: String? get() = failureKind
    override val guestAudioDrops: Long get() = guestDrops

    override fun flushGuestAudio() {
        synchronized(transitions) {
            val current = session ?: return
            current.guest.flush()
            if (failure == GUEST_NETWORK) failure = null
        }
    }

    init {
        require(port in 0..65535 && noConnectMs > 0 && pairWaitMs > 0 && headerWaitMs > 0)
        require(transportChunkBytes in 1..1024 && transportChunkBytes % RSoundProtocol.FRAME_BYTES == 0)
    }

    override fun start(sink: AudioSink): Boolean {
        stop()
        return synchronized(transitions) {
            val next = Run()
            next.guest = RSoundGuestQueue(sink, { error, valid ->
                val reported = synchronized(transitions) {
                    if (isCurrent(next) && valid()) {
                        failure = GUEST_NETWORK
                        true
                    } else false
                }
                if (reported) log("RSound guest-network send failed: ${detail(error)}; local playback retained")
            })
            session = next
            ready = false
            reason = null
            failure = null
            stoppedGuestDrops = 0L
            mode = "rsound-waiting"
            try {
                require(address.address.contentEquals(byteArrayOf(127, 0, 0, 1))) {
                    "RSound listener must use 127.0.0.1"
                }
                val server = ServerSocket()
                next.server = server
                server.reuseAddress = true
                server.bind(InetSocketAddress(address, port), 4)
                val player = createPlayer(next)
                next.prepared = player
                ready = true
                log("RSound ready 127.0.0.1:${server.localPort} rate=48000 channels=2 chunkBytes=960 transportChunkBytes=$transportChunkBytes controlProtocol=$useControlProtocol bufferBytes=${player.bufferBytes} receiveBufferSize=${server.receiveBufferSize} quickAckEnabled=$quickAckEnabled guestQueueChunks=${RSoundGuestQueue.CAPACITY_CHUNKS} guestQueueCapacityMs=${RSoundGuestQueue.CAPACITY_MS} localQueueChunks=$LOCAL_QUEUE_CHUNKS localQueueCapacityMs=${LOCAL_QUEUE_CHUNKS * 5}; loopback disabled")
                spawn(next, "gbx-rsound-accept") { acceptLoop(next, server) }
                true
            } catch (t: Throwable) {
                ready = false
                closeResources(next)
                next.server = null
                next.prepared = null
                val started = beginFallback(next, "RSound startup failed: ${detail(t)}", SETUP)
                if (!started) {
                    next.alive = false
                    next.mode = Mode.STOPPED
                    next.guest.close()
                    stoppedGuestDrops = next.guest.drops
                    session = null
                }
                started
            }
        }
    }

    override fun gameLaunched() {
        synchronized(transitions) {
            val current = session ?: return
            if (!current.alive || !ready || current.launchTimerStarted) return
            current.launchTimerStarted = true
            spawn(current, "gbx-rsound-deadline") {
                val end = System.nanoTime() + noConnectMs * 1_000_000L
                synchronized(current.wake) {
                    while (current.alive) {
                        val remaining = end - System.nanoTime()
                        if (remaining <= 0) break
                        current.wake.wait(maxOf(1L, remaining / 1_000_000L))
                    }
                }
                synchronized(transitions) {
                    if (isCurrent(current) && !current.connected && current.active == null) {
                        beginFallback(current, "RSound no connection within ${noConnectMs}ms of game launch; listener retained", SETUP)
                    }
                }
            }
        }
    }

    override fun stop() {
        val stopped = synchronized(transitions) {
            val current = session ?: return
            session = null
            current.alive = false
            current.mode = Mode.STOPPED
            ready = false
            current.guest.close()
            stoppedGuestDrops = current.guest.drops
            synchronized(current.wake) { current.wake.notifyAll() }
            closeResources(current)
            if (current.fallbackStarted) {
                current.fallbackStarted = false
                runCatching { fallback?.stop() }.onFailure { log("RSound fallback stop failed: ${detail(it)}") }
            }
            current
        }
        val threads = synchronized(stopped.resources) { stopped.threads.toList() }
        val end = System.nanoTime() + 1_000_000_000L
        for (thread in threads) {
            if (thread === Thread.currentThread()) continue
            val remaining = end - System.nanoTime()
            if (remaining <= 0) break
            runCatching { thread.join(maxOf(1L, remaining / 1_000_000L)) }
        }
    }

    private fun createPlayer(current: Run): RSoundPlayer {
        val player = playbackFactory.create()
        synchronized(current.resources) { current.players.add(player) }
        try {
            check(isCurrent(current) && player.ready) { "RSound local playback is not ready" }
            check(player.bufferBytes in RSoundProtocol.FRAME_BYTES..MAX_BUFFER_BYTES) {
                "RSound playback buffer exceeds cap: ${player.bufferBytes}"
            }
            return player
        } catch (t: Throwable) {
            closePlayer(current, player)
            throw t
        }
    }

    private fun acceptLoop(current: Run, server: ServerSocket) {
        while (isCurrent(current)) {
            var data: Socket? = null
            var control: Socket? = null
            var connection: Connection? = null
            try {
                server.soTimeout = 0
                val acceptedData = server.accept()
                data = acceptedData
                if (!registerSocket(current, acceptedData)) return
                server.soTimeout = pairWaitMs
                val acceptedControl = server.accept()
                control = acceptedControl
                if (!registerSocket(current, acceptedControl)) return
                configure(acceptedData)
                configure(acceptedControl)
                val deadline = System.nanoTime() + headerWaitMs * 1_000_000L
                val header = RSoundProtocol.readExactly(acceptedData.getInputStream(), RSoundProtocol.HEADER_BYTES) {
                    setDeadline(acceptedData, deadline)
                }
                RSoundProtocol.validateHeader(header)
                val acceptedConnection = activate(current, acceptedData, acceptedControl) ?: continue
                connection = acceptedConnection
                acceptedData.getOutputStream().write(
                    RSoundProtocol.backendReply(
                        latencyBytes = if (useControlProtocol) acceptedConnection.player.bufferBytes else 0,
                        chunkBytes = transportChunkBytes,
                        useControlProtocol = useControlProtocol,
                    ),
                )
                acceptedData.soTimeout = 0
                spawn(current, "gbx-rsound-control") { controlLoop(current, acceptedConnection) }
                spawn(current, "gbx-rsound-local", Thread.NORM_PRIORITY + 2) { localLoop(current, acceptedConnection) }
                spawn(current, "gbx-rsound-data", Thread.NORM_PRIORITY + 2) { dataLoop(current, acceptedConnection) }
            } catch (t: Throwable) {
                if (isCurrent(current)) log("RSound client rejected/disconnected: ${detail(t)}")
                if (connection != null) disconnected(current, connection, t)
                if (server.isClosed) {
                    synchronized(transitions) {
                        if (isCurrent(current)) {
                            ready = false
                            beginFallback(current, "RSound listener closed: ${detail(t)}", SETUP)
                        }
                    }
                    return
                }
            } finally {
                if (connection == null || !connection.alive) {
                    data?.let { closeSocket(current, it) }
                    control?.let { closeSocket(current, it) }
                }
            }
        }
    }

    private fun activate(current: Run, data: Socket, control: Socket): Connection? = synchronized(transitions) {
        if (!isCurrent(current)) return null
        current.guest.flush()
        current.active?.let {
            current.active = null
            it.alive = false
            closeConnection(current, it)
        }
        current.mode = Mode.WAITING
        var player: RSoundPlayer? = null
        try {
            player = current.prepared ?: createPlayer(current)
            current.prepared = null
            if (current.fallbackStarted) {
                fallback?.stop()
                current.fallbackStarted = false
            }
            player.start()
            check(player.ready) { "RSound local playback failed to start" }
            val connection = Connection(data, control, player)
            current.active = connection
            current.connected = true
            current.mode = Mode.RSOUND
            mode = "rsound"
            reason = null
            failure = null
            log("RSound connected 48000Hz stereo s16le dataReceiveBufferSize=${data.receiveBufferSize} dataSendBufferSize=${data.sendBufferSize} dataTcpNoDelay=${data.tcpNoDelay} controlReceiveBufferSize=${control.receiveBufferSize} controlSendBufferSize=${control.sendBufferSize} controlTcpNoDelay=${control.tcpNoDelay}; loopback stopped before local playback")
            connection
        } catch (t: Throwable) {
            player?.let { closePlayer(current, it) }
            beginFallback(current, "RSound playback activation failed: ${detail(t)}", LOCAL_PLAYBACK)
            null
        }
    }

    private fun dataLoop(current: Run, connection: Connection) {
        var quickAck: RSoundQuickAck? = null
        try {
            if (quickAckEnabled) {
                try {
                    quickAck = quickAckFactory.create(connection.data)
                } catch (_: Exception) {
                    Unit
                } catch (_: LinkageError) {
                    Unit
                }
            }
            val chunker = RSoundChunker { bytes, arrivalNs ->
                if (isActive(current, connection)) {
                    val localBytes = bytes.copyOf()
                    val deliverStartNs = System.nanoTime()
                    synchronized(connection.metrics) {
                        connection.arrivalToDeliverTiming.record(deliverStartNs - arrivalNs)
                    }
                    deliver(current, Mode.RSOUND, connection, bytes, arrivalNs, RSoundProtocol.CHUNK_FRAMES)
                    synchronized(connection.metrics) {
                        connection.deliverTiming.record(System.nanoTime() - deliverStartNs)
                    }
                    if (isActive(current, connection)) connection.local.enqueue(localBytes)
                }
            }
            val input = connection.data.getInputStream()
            val bytes = ByteArray(transportChunkBytes)
            while (isActive(current, connection)) {
                val readStartNs = System.nanoTime()
                val count = input.read(bytes)
                val arrivalNs = System.nanoTime()
                if (count > 0 && quickAckEnabled) rearmQuickAck(connection, quickAck)
                if (count < 0) throw IOException("RSound data EOF")
                if (count == 0) throw IOException("RSound data read stalled")
                val firstRead = synchronized(connection.metrics) {
                    connection.readTiming.record(arrivalNs - readStartNs)
                    if (connection.lastArrivalNs != 0L) {
                        val gapNs = arrivalNs - connection.lastArrivalNs
                        connection.arrivalGapTiming.record(gapNs)
                        connection.arrivalGaps[connection.arrivalGapIndex] = gapNs
                        connection.arrivalGapIndex = (connection.arrivalGapIndex + 1) % connection.arrivalGaps.size
                        connection.arrivalGapSamples = minOf(connection.arrivalGapSamples + 1, connection.arrivalGaps.size)
                        if (gapNs > 30_000_000L) connection.arrivalGapsOver30Ms++
                    } else {
                        connection.firstArrivalNs = arrivalNs
                    }
                    connection.lastArrivalNs = arrivalNs
                    connection.receivedBytes += count
                    connection.receivedBytes == count.toLong()
                }
                if (firstRead) {
                    log("RSound first PCM read bytes=$count arrivalNs=$arrivalNs origin=bridge-arrival")
                }
                chunker.accept(bytes, 0, count, arrivalNs)
            }
        } catch (failure: LocalPlaybackFailure) {
            disconnected(current, connection, failure, LOCAL_PLAYBACK)
        } catch (failure: IOException) {
            disconnected(current, connection, failure)
        } catch (failure: Throwable) {
            disconnected(current, connection, failure)
        } finally {
            runCatching { quickAck?.close() }
        }
    }

    private fun localLoop(current: Run, connection: Connection) {
        try {
            while (isActive(current, connection)) {
                val bytes = connection.local.take() ?: return
                if (!isActive(current, connection)) return
                val writeStartNs = System.nanoTime()
                val written = rsoundWriteFully(connection.player, bytes, onWritten = { count ->
                    synchronized(connection.metrics) {
                        if (isActive(current, connection)) {
                            connection.local.written(count)
                            connection.writtenBytes += count
                        }
                    }
                }) { isActive(current, connection) }
                synchronized(connection.metrics) {
                    connection.writeTiming.record(System.nanoTime() - writeStartNs)
                    if (written && bytes.any { it != 0.toByte() }) connection.nonzeroChunks++
                }
                if (!isActive(current, connection)) return
                if (!written) throw IOException("RSound playback stopped")
                logPlayback(current, connection)
            }
        } catch (failure: Throwable) {
            if (isActive(current, connection)) disconnected(current, connection, failure, LOCAL_PLAYBACK)
        }
    }

    private fun rearmQuickAck(connection: Connection, quickAck: RSoundQuickAck?) {
        val succeeded = try {
            quickAck?.rearm() == true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        val warn = synchronized(connection.metrics) {
            if (succeeded) {
                connection.quickAckSuccesses++
                false
            } else {
                connection.quickAckFailures++
                if (connection.quickAckWarning) false else {
                    connection.quickAckWarning = true
                    true
                }
            }
        }
        if (warn) log("RSound TCP_QUICKACK unavailable; continuing with OS ACK policy")
    }

    private fun controlLoop(current: Run, connection: Connection) {
        try {
            val socket = connection.control
            val input = socket.getInputStream()
            while (isActive(current, connection)) {
                socket.soTimeout = CONTROL_IDLE_MS
                val first = try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (first < 0) return
                val end = System.nanoTime() + headerWaitMs * 1_000_000L
                when (val command = RSoundProtocol.readControl(input, first) { setDeadline(socket, end) }) {
                    is RSoundProtocol.Control.Info -> {
                        val played = try {
                            logInfo(current, connection, command.clientBytes)
                        } catch (failure: Throwable) {
                            disconnected(current, connection, failure, LOCAL_PLAYBACK)
                            return
                        }
                        socket.getOutputStream().write(
                            RSoundProtocol.controlReply(" INFO ${command.clientBytes} $played"),
                        )
                    }
                    RSoundProtocol.Control.Close -> {
                        socket.getOutputStream().write(RSoundProtocol.controlReply(" CLOSECTL OK"))
                        return
                    }
                    RSoundProtocol.Control.Stop -> {
                        disconnected(current, connection, IOException("RSound STOP"))
                        return
                    }
                    RSoundProtocol.Control.Identity, RSoundProtocol.Control.Unknown -> Unit
                }
            }
        } catch (t: Throwable) {
            if (isActive(current, connection)) log("RSound control closed: ${detail(t)}")
        } finally {
            closeSocket(current, connection.control)
        }
    }

    private fun disconnected(current: Run, connection: Connection, failure: Throwable, kind: String? = null) {
        synchronized(transitions) {
            connection.alive = false
            closeConnection(current, connection)
            if (!isCurrent(current) || current.active !== connection) return
            current.active = null
            beginFallback(current, "RSound disconnected: ${detail(failure)}; listener retained", kind)
        }
    }

    private fun beginFallback(current: Run, why: String, kind: String? = null): Boolean = synchronized(transitions) {
        if (!isCurrent(current)) return false
        current.mode = Mode.FALLBACK
        current.guest.flush()
        mode = "loopback"
        reason = why
        failure = kind
        log("RSOUND FALLBACK: $why")
        if (current.fallbackStarted) return true
        val source = fallback ?: run {
            log("RSOUND FALLBACK unavailable: no loopback source")
            return false
        }
        current.fallbackStarted = true
        try {
            val started = source.start(AudioSink { chunk ->
                deliver(current, Mode.FALLBACK, null, chunk.data, chunk.captureNs, chunk.frames)
            })
            if (!started) {
                current.fallbackStarted = false
                runCatching { source.stop() }
                reason = "$why; loopback unavailable"
                log("RSOUND FALLBACK unavailable: loopback start returned false")
            }
            started
        } catch (t: Throwable) {
            current.fallbackStarted = false
            runCatching { source.stop() }
            reason = "$why; loopback start failed: ${detail(t)}"
            log("RSOUND FALLBACK unavailable: ${detail(t)}")
            false
        }
    }

    private fun deliver(
        current: Run,
        expected: Mode,
        connection: Connection?,
        bytes: ByteArray,
        captureNs: Long,
        frames: Int,
    ) {
        if (!isCurrent(current) || current.mode != expected) return
        if (connection != null && current.active !== connection) return
        current.guest.enqueue({ PcmChunk(sequence.incrementAndGet(), captureNs, frames, bytes) }) {
            isCurrent(current) && current.mode == expected &&
                (connection == null || isActive(current, connection))
        }
    }

    private fun logInfo(current: Run, connection: Connection, clientBytes: Long): Long {
        val stats = connection.player.stats()
        val now = System.nanoTime()
        val counters = connection.counters()
        val played = playedFramesNow(stats, now, counters.writtenBytes / RSoundProtocol.FRAME_BYTES) * RSoundProtocol.FRAME_BYTES
        val queuedChunks = connection.local.pendingChunks
        val queuedUs = queuedChunks * RSoundProtocol.CHUNK_FRAMES * 1_000_000L / RSoundProtocol.RATE
        if (isActive(current, connection)) {
            log("RSound INFO clientBytes=$clientBytes receivedBytes=${counters.receivedBytes} writtenBytes=${counters.writtenBytes} playedBytes=$played localQueueChunks=$queuedChunks localQueueCapacityChunks=$LOCAL_QUEUE_CHUNKS localQueuedAudioUs=$queuedUs trackQueuedAudioUs=${(counters.writtenBytes - played) * 1_000_000L / (RSoundProtocol.RATE * RSoundProtocol.FRAME_BYTES)}")
        }
        return played
    }

    private fun playedFramesNow(stats: RSoundPlaybackStats, now: Long, writtenFrames: Long): Long {
        val timestampNs = stats.timestampNs
        val timestampFrames = stats.timestampFrames
        val frames = if (timestampNs != null && timestampFrames != null && timestampNs > 0 && timestampNs <= now) {
            val elapsedNs = (now - timestampNs).coerceAtMost(60_000_000_000L)
            timestampFrames + elapsedNs * RSoundProtocol.RATE / 1_000_000_000L
        } else stats.playedFrames
        return frames.coerceIn(0, writtenFrames)
    }

    private fun diagnosticSnapshot(connection: Connection): DiagnosticSnapshot = synchronized(connection.metrics) {
        val counters = connection.counters()
        val gaps = connection.arrivalGaps.copyOf(connection.arrivalGapSamples).apply { sort() }
        fun percentile(percent: Int): Long = if (gaps.isEmpty()) 0L else
            gaps[((gaps.size.toLong() * percent + 99) / 100 - 1).toInt()] / 1_000
        val arrivalElapsedNs = (connection.lastArrivalNs - connection.firstArrivalNs).coerceAtLeast(0)
        val actualByteCadence = if (arrivalElapsedNs > 0) counters.receivedBytes.toDouble() * 1_000_000_000.0 / arrivalElapsedNs else 0.0
        val arrival = "RSound bridge-arrival origin=bridge-arrival quickAckEnabled=$quickAckEnabled quickAckSuccessCount=${connection.quickAckSuccesses} quickAckFailureCount=${connection.quickAckFailures} readGapSamples=${gaps.size} readGapP50Us=${percentile(50)} readGapP95Us=${percentile(95)} readGapMaxUs=${connection.arrivalGapTiming.maxNs / 1_000} readGapsOver30Ms=${connection.arrivalGapsOver30Ms} receivedBytes=${counters.receivedBytes} arrivalElapsedNs=$arrivalElapsedNs actualByteCadenceBps=${actualByteCadence.toLong()} ${connection.arrivalGapTiming.summary("arrivalGap")} ${connection.arrivalToDeliverTiming.summary("arrivalToDeliver")} guestDrops=$guestDrops"
        val timings = "${connection.readTiming.summary("read")} ${connection.deliverTiming.summary("deliver")} ${connection.writeTiming.summary("write")} ${connection.arrivalGapTiming.summary("arrivalGap")} ${connection.arrivalToDeliverTiming.summary("arrivalToDeliver")}"
        DiagnosticSnapshot(counters, arrival, timings)
    }

    private fun logPlayback(current: Run, connection: Connection) {
        val now = System.nanoTime()
        if (now < connection.nextLogNs) return
        connection.nextLogNs = now + 2_000_000_000L
        val stats = connection.player.stats()
        val snapshot = diagnosticSnapshot(connection)
        val counters = snapshot.counters
        val played = playedFramesNow(stats, now, counters.writtenBytes / RSoundProtocol.FRAME_BYTES) * RSoundProtocol.FRAME_BYTES
        val queuedUs = (counters.writtenBytes - played) * 1_000_000L / (RSoundProtocol.RATE * RSoundProtocol.FRAME_BYTES)
        val queuedChunks = connection.local.pendingChunks
        val localQueuedUs = queuedChunks * RSoundProtocol.CHUNK_FRAMES * 1_000_000L / RSoundProtocol.RATE
        val timestampNs = stats.timestampNs
        val timestampFrames = stats.timestampFrames
        val timestampAgeUs = timestampNs?.let { (now - it) / 1_000 }
        val timestampLatencyUs = if (timestampNs != null && timestampFrames != null) {
            (timestampNs + (counters.writtenBytes / RSoundProtocol.FRAME_BYTES - timestampFrames) * 1_000_000_000L / RSoundProtocol.RATE - now) / 1_000
        } else null
        val phase = if (counters.writtenBytes < RSoundProtocol.RATE * RSoundProtocol.FRAME_BYTES) "startup" else "steady"
        if (!isActive(current, connection)) return
        log(snapshot.arrival)
        if (!isActive(current, connection)) return
        log("RSound local phase=$phase receivedBytes=${counters.receivedBytes} writtenBytes=${counters.writtenBytes} nonzeroChunks=${counters.nonzeroChunks} playedBytes=$played headFrames=${stats.headFrames} timestampFrames=$timestampFrames timestampNs=$timestampNs timestampAgeUs=$timestampAgeUs timestampLatencyUs=$timestampLatencyUs queuedLatencyUs=$queuedUs localQueueChunks=$queuedChunks localQueueCapacityChunks=$LOCAL_QUEUE_CHUNKS localQueuedAudioUs=$localQueuedUs guestDrops=$guestDrops underruns=${stats.underruns} lastArrivalNs=${counters.lastArrivalNs} ${snapshot.timings}")
    }

    private fun configure(socket: Socket) {
        socket.tcpNoDelay = true
        socket.sendBufferSize = RSoundProtocol.CHUNK_BYTES * 2
    }

    private fun registerSocket(current: Run, socket: Socket): Boolean = synchronized(current.resources) {
        if (!isCurrent(current)) {
            runCatching { socket.close() }
            false
        } else {
            current.sockets.add(socket)
            true
        }
    }

    private fun closeSocket(current: Run, socket: Socket) {
        runCatching { socket.close() }
        synchronized(current.resources) { current.sockets.remove(socket) }
    }

    private fun closePlayer(current: Run, player: RSoundPlayer) {
        val removed = synchronized(current.resources) { current.players.remove(player) }
        if (removed) runCatching { player.close() }
    }

    private fun closeLocalQueue(connection: Connection) {
        val snapshot = synchronized(connection.metrics) {
            val loss = connection.local.close() ?: return
            loss to connection.counters()
        }
        val (loss, counters) = snapshot
        log("RSound local queue closed localPendingChunks=${loss.pendingChunks} localPendingBytes=${loss.pendingBytes} localInFlightBytes=${loss.inFlightBytes} localRejectedBytes=${loss.rejectedBytes} localDiscardedBytes=${loss.discardedBytes} localDiscardedFrames=${loss.discardedBytes / RSoundProtocol.FRAME_BYTES} receivedBytes=${counters.receivedBytes} writtenBytes=${counters.writtenBytes}")
    }

    private fun closeConnection(current: Run, connection: Connection) {
        connection.alive = false
        closeLocalQueue(connection)
        closeSocket(current, connection.data)
        closeSocket(current, connection.control)
        closePlayer(current, connection.player)
    }

    private fun closeResources(current: Run) {
        runCatching { current.server?.close() }
        current.active?.let {
            it.alive = false
            closeLocalQueue(it)
        }
        val sockets: List<Socket>
        val players: List<RSoundPlayer>
        synchronized(current.resources) {
            sockets = current.sockets.toList()
            players = current.players.toList()
            current.sockets.clear()
            current.players.clear()
        }
        sockets.forEach { runCatching { it.close() } }
        players.forEach { runCatching { it.close() } }
    }

    private fun spawn(current: Run, name: String, priority: Int = Thread.NORM_PRIORITY, block: () -> Unit) {
        val thread = Thread({
            try {
                if (isCurrent(current)) block()
            } catch (t: Throwable) {
                if (isCurrent(current)) log("RSound worker $name failed: ${detail(t)}")
            } finally {
                synchronized(current.resources) { current.threads.remove(Thread.currentThread()) }
            }
        }, name).apply { isDaemon = true; this.priority = priority }
        synchronized(current.resources) {
            if (!isCurrent(current)) return
            current.threads.add(thread)
            thread.start()
        }
    }

    private fun setDeadline(socket: Socket, deadline: Long) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw SocketTimeoutException("RSound packet deadline exceeded")
        socket.soTimeout = maxOf(1L, remaining / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun isCurrent(current: Run): Boolean = current.alive && session === current
    private fun isActive(current: Run, connection: Connection): Boolean =
        isCurrent(current) && connection.alive && current.active === connection && current.mode == Mode.RSOUND
    private fun detail(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message}"
    private fun log(message: String) { runCatching { logger(message) } }

    companion object {
        private const val SETUP = "setup"
        private const val LOCAL_PLAYBACK = "local-playback"
        private const val GUEST_NETWORK = "guest-network"
        private const val LOCAL_QUEUE_CHUNKS = 8
        private const val CONTROL_IDLE_MS = 500
        private const val MAX_BUFFER_BYTES = RSoundProtocol.RATE * RSoundProtocol.FRAME_BYTES / 25
    }
}
