package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
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
) : AutoCloseable {
    private var socket: DatagramSocket? = null
    private val closed = AtomicBoolean(false)
    private var thread: Thread? = null

    val packets = AtomicLong()
    val rejected = AtomicLong()
    val stale = AtomicLong()

    @Volatile var port: Int = 0
        private set

    @Volatile var lastState: PadState = PadState.NEUTRAL
        private set

    /** Where the guest's valid input comes from: audio goes back there (same UDP pair, through any firewall state). */
    @Volatile private var guestUdp: java.net.SocketAddress? = null
    @Volatile private var guestUdpNs = 0L
    val audioSent = AtomicLong()

    /** Binds [preferredPort], or an ephemeral port when it is taken. Throws StreamFailure(NETWORK). */
    fun open(preferredPort: Int) {
        val s = DatagramSocket(null)
        try {
            s.reuseAddress = true
            com.gamebex.client.stream.proto.StreamSockets.markInput(s)
            try {
                s.bind(InetSocketAddress(preferredPort))
            } catch (_: BindException) {
                s.bind(InetSocketAddress(0))
            }
            s.soTimeout = POLL_MS
        } catch (e: IOException) {
            s.close()
            throw StreamFailure(com.gamebex.client.stream.StreamError.NETWORK, "input socket: ${e.message}", e)
        }
        socket = s
        port = s.localPort
    }

    fun start() {
        val s = socket ?: throw IllegalStateException("open() first")
        thread = Thread({ loop(s) }, "gbx-input").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop(s: DatagramSocket) {
        val buffer = ByteArray(64)
        val packet = DatagramPacket(buffer, buffer.size)
        var haveSeq = false
        var lastSeq = 0
        var lastPacketNs = clock()
        var released = true
        while (!closed.get()) {
            packet.setData(buffer, 0, buffer.size)
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                if (!released && clock() - lastPacketNs > timeoutMs * 1_000_000L) {
                    drive(PadState.NEUTRAL)
                    released = true
                }
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
            if (haveSeq && !InputWire.isNewer(p.seq, lastSeq)) {
                stale.incrementAndGet()
                continue
            }
            haveSeq = true
            lastSeq = p.seq
            lastPacketNs = clock()
            guestUdp = packet.socketAddress
            guestUdpNs = lastPacketNs
            packets.incrementAndGet()
            val state = if (p.flags and StreamWire.INPUT_FLAG_NEUTRAL != 0) PadState.NEUTRAL else p.state
            drive(state)
            released = state.isNeutral
            val ack = InputWire.encodeAck(InputAck(p.seq, p.sampleNs, clock()))
            runCatching { s.send(DatagramPacket(ack, ack.size, packet.socketAddress)) }
        }
    }

    /**
     * Sends one PCM chunk to player 2 over UDP. False when no valid input arrived in the last
     * [AUDIO_ROUTE_MS] (the guest isn't sending, or is on an older build), so the caller uses TCP instead.
     */
    fun sendAudio(chunk: PcmChunk): Boolean {
        val s = socket ?: return false
        val to = guestUdp ?: return false
        if (closed.get() || clock() - guestUdpNs > AUDIO_ROUTE_MS * 1_000_000L) return false
        val bytes = InputWire.encodeAudio(key, chunk.seq, chunk.captureNs, chunk.frames, chunk.data)
        return try {
            s.send(DatagramPacket(bytes, bytes.size, to))
            audioSent.incrementAndGet()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun drive(state: PadState) {
        if (state == lastState) return
        lastState = state
        pad.write(state)
    }

    /** Releases player 2 and closes the socket. The pad itself belongs to the session. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { if (!lastState.isNeutral) pad.write(PadState.NEUTRAL) }
        lastState = PadState.NEUTRAL
        runCatching { socket?.close() }
        thread?.join(1_000L)
    }

    private companion object {
        const val POLL_MS = 50
        /** The guest sends input at least every 50 ms; a second of silence means audio should take TCP. */
        const val AUDIO_ROUTE_MS = 1_000L
    }
}
