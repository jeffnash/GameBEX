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
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
    private val onRtt: (sentNs: Long, ackNs: Long) -> Unit = { _, _ -> },
) : AutoCloseable {
    private data class Sent(val sampleNs: Long, val generation: Int?, val cancel: Boolean, @Volatile var sentNs: Long? = null)
    private data class Prepared(val seq: Int, val next: ReliableTransitions.Send, val pending: Sent, val cancellations: Long)

    private val key = StreamToken.key(token)
    private val destination = InetSocketAddress(host, port)
    private val socket = socketFactory().apply {
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
                val prepared = synchronized(lock) {
                    var ready: Prepared? = null
                    while (!closed && ready == null) {
                        val graceRemaining = if (closing) closeDeadlineNs - System.nanoTime() else Long.MAX_VALUE
                        if (closing && (cancelAcknowledged || graceRemaining <= 0L)) return
                        val now = clock()
                        val next = transitions.next(now)
                        if (next != null) {
                            val n = ++seq
                            val pending = Sent(next.originNs, next.generation, next.cancel)
                            outstanding[n] = pending
                            while (outstanding.size > MAX_OUTSTANDING) outstanding.remove(outstanding.keys.first())
                            ready = Prepared(n, next, pending, transitions.cancellations)
                        } else {
                            val delay = minOf(transitions.delayNs(now), graceRemaining).coerceAtLeast(1L)
                            lock.wait(delay / 1_000_000L, (delay % 1_000_000L).toInt())
                        }
                    }
                    ready
                } ?: return
                send(prepared)
            }
        } catch (_: InterruptedException) {
            return
        }
    }

    private fun send(prepared: Prepared) {
        val next = prepared.next
        val flags = if (next.cancel) StreamWire.INPUT_FLAG_NEUTRAL else 0
        val bytes = InputWire.encode(InputPacket(flags, key, prepared.seq, next.originNs, next.state, attachId))
        synchronized(lock) {
            if (closed || prepared.cancellations != transitions.cancellations) {
                outstanding.remove(prepared.seq)
                return
            }
            sentCount++
        }
        prepared.pending.sentNs = clock()
        try {
            socket.send(DatagramPacket(bytes, bytes.size, destination))
        } catch (_: IOException) {
            synchronized(lock) { outstanding.remove(prepared.seq) }
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
            if (packet.address != destination.address) continue
            if (hasMagic(packet, StreamWire.AUDIO_UDP_MAGIC)) {
                val audio = InputWire.decodeAudio(packet.data, packet.length)
                if (audio != null && audio.key == key) onAudio(audio.seq, audio.captureNs, audio.pcm)
                continue
            }
            if (packet.port != destination.port || !hasMagic(packet, StreamWire.ACK_MAGIC)) continue
            val ack = InputWire.decodeAck(packet.data, packet.length) ?: continue
            if (ack.attachId != attachId) continue
            val ackNs = clock()
            val received = synchronized(lock) {
                val pending = outstanding[ack.seq]
                if (pending == null || pending.sampleNs != ack.sampleNs) return@synchronized null
                val sentNs = pending.sentNs ?: return@synchronized null
                outstanding.remove(ack.seq)
                val generation = pending.generation
                if (generation != null && transitions.ack(generation)) {
                    if (pending.cancel) cancelAcknowledged = true
                    lock.notifyAll()
                }
                val first = ack.wrote && ack.sampleNs != 0L && measured.add(ack.sampleNs)
                while (measured.size > MAX_OUTSTANDING) measured.remove(measured.first())
                sentNs to first
            } ?: continue
            if (closed) continue
            onRtt(received.first, ackNs)
            if (!closed && received.second) onAck(ack.sampleNs, ack.appliedNs, ackNs)
        }
    }

    private fun hasMagic(packet: DatagramPacket, magic: ByteArray): Boolean =
        packet.length >= magic.size && magic.indices.all { packet.data[packet.offset + it] == magic[it] }

    private companion object {
        const val MAX_OUTSTANDING = 256
        const val CLOSE_GRACE_NS = 100_000_000L
    }
}
