package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamDebug
import com.gamebex.client.stream.proto.InputAck
import com.gamebex.client.stream.proto.InputWire
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.stream.proto.StreamWire
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives player 2's input over UDP and drives [pad]. Only
 * packets from [guestAddress] carrying [key] count; the newest sequence number
 * wins; silence longer than [timeoutMs] releases everything. Plain java.net, so
 * the JVM tests run it on loopback with a fake pad.
 */
class InputServer(
    private val pad: VirtualPad,
    private val key: Long,
    private val guestAddress: String,
    private val clock: () -> Long = System::nanoTime,
    private val timeoutMs: Long = StreamCaps.INPUT_TIMEOUT_MS,
    private val cs6: Boolean = StreamDebug.flag("input_cs6"),
    private val ackDropEvery: Int = StreamDebug.int("input_ack_drop_every", 0, 0..1000),
    private val socketFactory: () -> DatagramSocket = { DatagramSocket(null) },
    private val audioSocketFactory: () -> DatagramSocket = { DatagramSocket() },
) : AutoCloseable {
    private data class AudioRoute(val socket: DatagramSocket, val address: java.net.SocketAddress, val generation: Long)

    private var socket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var routeGeneration = 0L
    private val closed = AtomicBoolean(false)
    private var thread: Thread? = null
    private var ackThread: Thread? = null
    private val ackLock = Object()
    private val pendingAcks = ArrayDeque<DatagramPacket>()

    val packets = AtomicLong()
    val rejected = AtomicLong()
    val stale = AtomicLong()
    val writeFailures = AtomicLong()
    val audioDropped = AtomicLong()
    val ackDropped = AtomicLong()
    private var appliedPackets = 0L
    private val authority = InputAuthority({ state ->
        pad.write(state).also { if (!it) writeFailures.incrementAndGet() }
    }, clock, timeoutMs * 1_000_000L)

    @Volatile var port: Int = 0
        private set

    val lastState: PadState get() = authority.state
    @Volatile var actualTos: Int? = null
        private set
    val audioSent = AtomicLong()

    fun attach(attachId: Long): Boolean = synchronized(authority) {
        if (closed.get()) false else {
            if (!authority.isAttached(attachId)) routeGeneration++
            authority.attach(attachId)
        }
    }

    fun detach(attachId: Long) = synchronized(authority) {
        if (authority.isAttached(attachId)) routeGeneration++
        authority.detach(attachId)
    }

    /** Binds [preferredPort], or an ephemeral port when it is taken. Throws StreamFailure(NETWORK). */
    fun open(preferredPort: Int) {
        val s = socketFactory()
        var audio: DatagramSocket? = null
        try {
            s.reuseAddress = true
            actualTos = com.gamebex.client.stream.proto.StreamSockets.markInput(s, cs6)
            try {
                s.bind(InetSocketAddress(preferredPort))
            } catch (_: BindException) {
                s.bind(InetSocketAddress(0))
            }
            s.soTimeout = POLL_MS
            audio = audioSocketFactory()
            com.gamebex.client.stream.proto.StreamSockets.markAudio(audio)
        } catch (e: IOException) {
            audio?.close()
            s.close()
            throw StreamFailure(com.gamebex.client.stream.StreamError.NETWORK, "input socket: ${e.message}", e)
        }
        synchronized(authority) {
            socket = s
            audioSocket = audio
            port = s.localPort
        }
    }

    fun start() {
        val s = socket ?: throw IllegalStateException("open() first")
        ackThread = Thread({ ackLoop(s) }, "gbx-input-write").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
            start()
        }
        thread = Thread({ loop(s) }, "gbx-input").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop(s: DatagramSocket) {
        val buffer = ByteArray(64)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed.get()) {
            authority.tick()
            packet.setData(buffer, 0, buffer.size)
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (closed.get()) return
                continue
            }
            val from = packet.address?.hostAddress?.removePrefix("::ffff:")
            val p = if (from == guestAddress) InputWire.decode(packet.data, packet.length) else null
            if (p == null || p.key != key) {
                rejected.incrementAndGet()
                continue
            }
            val reply = synchronized(authority) {
                if (closed.get()) return
                if (!authority.isAttached(p.attachId)) {
                    rejected.incrementAndGet()
                    null
                } else if (authority.isStale(p.seq)) {
                    stale.incrementAndGet()
                    null
                } else {
                    packets.incrementAndGet()
                    val state = if (p.flags and StreamWire.INPUT_FLAG_NEUTRAL != 0) PadState.NEUTRAL else p.state
                    val applied = authority.apply(p.attachId, p.seq, p.sampleNs, state, packet.socketAddress)
                    if (applied != null) {
                        appliedPackets++
                        if (ackDropEvery > 0 && appliedPackets % ackDropEvery == 0L) {
                            ackDropped.incrementAndGet()
                            null
                        } else {
                            val flags = if (applied.wrote) StreamWire.ACK_FLAG_WROTE else 0
                            val ack = InputWire.encodeAck(InputAck(applied.packetSeq, applied.sampleNs, applied.appliedNs, p.attachId, flags))
                            DatagramPacket(ack, ack.size, packet.socketAddress)
                        }
                    } else null
                }
            }
            if (reply != null) synchronized(ackLock) {
                if (closed.get()) return
                if (pendingAcks.size >= MAX_PENDING_ACKS) {
                    pendingAcks.removeFirst()
                    ackDropped.incrementAndGet()
                }
                pendingAcks.addLast(reply)
                ackLock.notifyAll()
            }
        }
    }

    private fun ackLoop(s: DatagramSocket) {
        try {
            while (!closed.get()) {
                val reply = synchronized(ackLock) {
                    while (pendingAcks.isEmpty() && !closed.get()) ackLock.wait()
                    if (closed.get()) return
                    pendingAcks.removeFirst()
                }
                try {
                    s.send(reply)
                } catch (_: IOException) {
                    if (!closed.get()) ackDropped.incrementAndGet()
                }
            }
        } catch (_: InterruptedException) {
            return
        }
    }

    fun sendAudio(chunk: PcmChunk): AudioSendResult {
        val route = synchronized(authority) {
            if (closed.get()) return AudioSendResult.NO_ROUTE
            val s = audioSocket ?: return AudioSendResult.NO_ROUTE
            val to = authority.audioRoute(AUDIO_ROUTE_MS * 1_000_000L) ?: return AudioSendResult.NO_ROUTE
            AudioRoute(s, to, routeGeneration)
        }
        val bytes = InputWire.encodeAudio(key, chunk.seq, chunk.captureNs, chunk.frames, chunk.data)
        val valid = synchronized(authority) {
            !closed.get() && route.socket === audioSocket && route.generation == routeGeneration &&
                authority.audioRoute(AUDIO_ROUTE_MS * 1_000_000L) == route.address
        }
        if (!valid) {
            audioDropped.incrementAndGet()
            return AudioSendResult.DROPPED
        }
        return try {
            route.socket.send(DatagramPacket(bytes, bytes.size, route.address))
            audioSent.incrementAndGet()
            AudioSendResult.SENT
        } catch (_: IOException) {
            audioDropped.incrementAndGet()
            AudioSendResult.DROPPED
        }
    }

    /** Releases player 2 and closes the socket. The pad itself belongs to the session. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { audioSocket?.close() }
        runCatching { socket?.close() }
        synchronized(ackLock) {
            pendingAcks.clear()
            ackLock.notifyAll()
        }
        try {
            authority.close()
        } finally {
            thread?.join(1_000L)
            ackThread?.join(1_000L)
        }
    }

    private companion object {
        const val POLL_MS = 8
        const val MAX_PENDING_ACKS = 8
        /** The guest sends input at least every 50 ms; a second of silence means audio should take TCP. */
        const val AUDIO_ROUTE_MS = 1_000L
    }
}
