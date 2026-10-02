package com.gamebex.client.stream.guest

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.proto.InputPacket
import com.gamebex.client.stream.proto.InputWire
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.stream.proto.StreamWire
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Sends player 2's whole controller state to the host over UDP:
 * at once on every change, then every [StreamCaps.INPUT_ACTIVE_MS] while
 * anything is held and every [StreamCaps.INPUT_IDLE_MS] at rest, so a lost
 * datagram is corrected within milliseconds and the host's 250 ms release
 * never fires during play. [close] sends three "release everything" packets.
 * Each host acknowledgement reports (sample time, host apply time, arrival time).
 */
class InputSender(
    host: String,
    port: Int,
    token: String,
    private val onAck: (sampleNs: Long, appliedHostNs: Long, ackNs: Long) -> Unit = { _, _, _ -> },
    private val clock: () -> Long = System::nanoTime,
 /** Host audio that came over this socket pair (seq, host capture time, PCM). */
    private val onAudio: (seq: Int, captureNs: Long, pcm: ByteArray) -> Unit = { _, _, _ -> },
) : AutoCloseable {
    private val key = StreamToken.key(token)
    private val socket = DatagramSocket().apply {
        connect(InetSocketAddress(host, port))
        soTimeout = 200
        com.gamebex.client.stream.proto.StreamSockets.markInput(this)
    }
    private val lock = Object()
    private var state = PadState.NEUTRAL
    private var sampleNs = 0L
    private var dirty = false
    private var seq = 0
 /** 0 is the idle sample before the first input; it never measures anything. */
    private var lastAckedSample = 0L
    @Volatile private var closed = false
    private val sender = Thread({ sendLoop() }, "gbx-input-send").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
    }
    private val receiver = Thread({ ackLoop() }, "gbx-input-ack").apply { isDaemon = true }

    val sent: Long get() = synchronized(lock) { seq.toLong() }

    fun start() {
        sender.start()
        receiver.start()
    }

 /** The controller now reads [next], sampled at [atNs] (the input event's time). */
    fun update(next: PadState, atNs: Long = clock()) {
        val s = next.sanitized()
        synchronized(lock) {
            if (s == state) return
            state = s
            sampleNs = atNs
            dirty = true
            lock.notifyAll()
        }
    }

    override fun close() {
        if (closed) return
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        repeat(3) { send(PadState.NEUTRAL, clock(), StreamWire.INPUT_FLAG_NEUTRAL) }
        sender.join(200)
        runCatching { socket.close() }
        receiver.join(300)
    }

    private fun sendLoop() {
        try {
            while (true) {
                val (s, at) = synchronized(lock) {
                    if (!dirty && !closed) lock.wait(if (state.isNeutral) StreamCaps.INPUT_IDLE_MS else StreamCaps.INPUT_ACTIVE_MS)
                    if (closed) return
                    dirty = false
                    state to sampleNs
                }
                send(s, at, 0)
            }
        } catch (_: InterruptedException) {
 // closing
        }
    }

    private fun send(s: PadState, at: Long, flags: Int) {
        val n = synchronized(lock) { ++seq }
        val bytes = InputWire.encode(InputPacket(flags, key, n, at, s))
        try {
            socket.send(DatagramPacket(bytes, bytes.size))
        } catch (_: IOException) {
 // Wi-Fi hiccup or closed socket: the next packet carries the same state.
        }
    }

    private fun ackLoop() {
        val buffer = ByteArray(2_048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed || !socket.isClosed) {
            try {
                packet.setData(buffer, 0, buffer.size)
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                if (closed) return
                continue
            } catch (_: IOException) {
                return
            }
            if (packet.length > StreamWire.ACK_SIZE) {
                val a = InputWire.decodeAudio(packet.data, packet.length)
                if (a != null && a.key == key) onAudio(a.seq, a.captureNs, a.pcm)
                continue
            }
            val ack = InputWire.decodeAck(packet.data, packet.length) ?: continue
 // Only the first ack for a sample measures the change; repeats carry the same sample time.
            val first = synchronized(lock) {
                if (ack.sampleNs == lastAckedSample) false else {
                    lastAckedSample = ack.sampleNs
                    true
                }
            }
            if (first) onAck(ack.sampleNs, ack.appliedNs, clock())
        }
    }
}
