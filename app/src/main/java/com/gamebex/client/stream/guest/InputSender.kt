package com.gamebex.client.stream.guest

import com.gamebex.client.stream.StreamDebug
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

class InputSender(
    host: String,
    port: Int,
    token: String,
    private val onAck: (sampleNs: Long, appliedHostNs: Long, ackNs: Long) -> Unit = { _, _, _ -> },
    private val clock: () -> Long = System::nanoTime,
    private val onAudio: (seq: Int, captureNs: Long, pcm: ByteArray) -> Unit = { _, _, _ -> },
    reliableEdges: Boolean = StreamDebug.flag("input_reliable"),
    cs6: Boolean = StreamDebug.flag("input_cs6"),
    private val attachId: Long = StreamToken.attachId(),
) : AutoCloseable {
    private data class Sent(val sampleNs: Long, val generation: Int?, val cancel: Boolean)

    private val key = StreamToken.key(token)
    private val socket = DatagramSocket().apply {
        connect(InetSocketAddress(host, port))
        soTimeout = 25
    }
    val actualTos: Int? = com.gamebex.client.stream.proto.StreamSockets.markInput(socket, cs6)
    private val lock = Object()
    private val transitions = ReliableTransitions(reliableEdges)
    private val outstanding = LinkedHashMap<Int, Sent>()
    private val measured = LinkedHashSet<Long>()
    private var seq = 0
    private var sentCount = 0L
    private var started = false
    private var closing = false
    private var cancelAcknowledged = false
    private var closeDeadlineNs = 0L
    @Volatile private var closed = false
    private val sender = Thread({ sendLoop() }, "gbx-input-send").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
    }
    private val receiver = Thread({ ackLoop() }, "gbx-input-ack").apply { isDaemon = true }

    val sent: Long get() = synchronized(lock) { sentCount }
    val cancellations: Long get() = synchronized(lock) { transitions.cancellations }

    fun start() = synchronized(lock) {
        if (started || closed || closing) return
        started = true
        sender.start()
        receiver.start()
    }

    fun update(next: PadState, atNs: Long = clock()) = synchronized(lock) {
        if (closed || closing) return
        transitions.update(next, atNs, clock())
        lock.notifyAll()
    }

    fun cancel(atNs: Long = clock()) = synchronized(lock) {
        if (closed || closing) return
        transitions.cancel(atNs)
        cancelAcknowledged = false
        lock.notifyAll()
    }

    override fun close() {
        synchronized(lock) {
            if (closed || closing) return
            closing = true
            transitions.cancel(clock())
            cancelAcknowledged = false
            closeDeadlineNs = System.nanoTime() + CLOSE_GRACE_NS
            lock.notifyAll()
        }
        if (Thread.currentThread() !== sender) sender.join(150)
        synchronized(lock) {
            closed = true
            outstanding.clear()
            lock.notifyAll()
        }
        runCatching { socket.close() }
        if (Thread.currentThread() !== sender) sender.join(200)
        if (Thread.currentThread() !== receiver) receiver.join(300)
    }

    private fun sendLoop() {
        try {
            while (!closed) {
                synchronized(lock) {
                    while (!closed) {
                        val graceRemaining = if (closing) closeDeadlineNs - System.nanoTime() else Long.MAX_VALUE
                        if (closing && (cancelAcknowledged || graceRemaining <= 0L)) return
                        val now = clock()
                        val next = transitions.next(now)
                        if (next != null) {
                            send(next)
                            break
                        }
                        val delay = minOf(transitions.delayNs(now), graceRemaining).coerceAtLeast(1L)
                        lock.wait(delay / 1_000_000L, (delay % 1_000_000L).toInt())
                    }
                }
            }
        } catch (_: InterruptedException) {
            return
        }
    }

    private fun send(next: ReliableTransitions.Send) {
        val n = ++seq
        val flags = if (next.cancel) StreamWire.INPUT_FLAG_NEUTRAL else 0
        val bytes = InputWire.encode(InputPacket(flags, key, n, next.originNs, next.state, attachId))
        outstanding[n] = Sent(next.originNs, next.generation, next.cancel)
        while (outstanding.size > MAX_OUTSTANDING) outstanding.remove(outstanding.keys.first())
        sentCount++
        try {
            socket.send(DatagramPacket(bytes, bytes.size))
        } catch (_: IOException) {
            outstanding.remove(n)
        }
    }

    private fun ackLoop() {
        val buffer = ByteArray(2_048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed) {
            try {
                packet.setData(buffer, 0, buffer.size)
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                return
            }
            if (hasMagic(packet, StreamWire.AUDIO_UDP_MAGIC)) {
                val audio = InputWire.decodeAudio(packet.data, packet.length)
                if (audio != null && audio.key == key) onAudio(audio.seq, audio.captureNs, audio.pcm)
                continue
            }
            if (!hasMagic(packet, StreamWire.ACK_MAGIC)) continue
            val ack = InputWire.decodeAck(packet.data, packet.length) ?: continue
            if (ack.attachId != attachId) continue
            val first = synchronized(lock) {
                val pending = outstanding[ack.seq]
                if (pending == null || pending.sampleNs != ack.sampleNs) return@synchronized false
                outstanding.remove(ack.seq)
                val generation = pending.generation
                if (generation != null && transitions.ack(generation)) {
                    outstanding.entries.removeAll { it.value.generation == generation }
                    if (pending.cancel) cancelAcknowledged = true
                    lock.notifyAll()
                }
                if (!ack.wrote || ack.sampleNs == 0L || !measured.add(ack.sampleNs)) false else {
                    while (measured.size > MAX_OUTSTANDING) measured.remove(measured.first())
                    true
                }
            }
            if (first) onAck(ack.sampleNs, ack.appliedNs, clock())
        }
    }

    private fun hasMagic(packet: DatagramPacket, magic: ByteArray): Boolean =
        packet.length >= magic.size && magic.indices.all { packet.data[packet.offset + it] == magic[it] }

    private companion object {
        const val MAX_OUTSTANDING = 256
        const val CLOSE_GRACE_NS = 100_000_000L
    }
}
