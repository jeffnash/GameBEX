package com.gamebex.client.stream.guest

import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.proto.InputAck
import com.gamebex.client.stream.proto.InputPacket
import com.gamebex.client.stream.proto.InputWire
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.stream.proto.StreamWire
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Input packets from the guest, seen by a loopback host socket. */
class InputSenderTest {
    private val token = "00112233445566778899aabbccddeeff"
    private val host = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).apply { soTimeout = 1_000 }
    private val acks: MutableList<Triple<Long, Long, Long>> = Collections.synchronizedList(mutableListOf())
    private val sender = InputSender("127.0.0.1", host.localPort, token, { s, a, n -> acks += Triple(s, a, n) })

    @After
    fun tearDown() {
        sender.close()
        host.close()
    }

    private fun receive(): Pair<InputPacket, DatagramPacket> {
        val buffer = ByteArray(64)
        val p = DatagramPacket(buffer, buffer.size)
        host.receive(p)
        return InputWire.decode(p.data, p.length)!! to p
    }

    /** Packets until one carries [state]. */
    private fun until(state: PadState): Pair<InputPacket, DatagramPacket> {
        repeat(200) {
            val r = receive()
            if (r.first.state == state) return r
        }
        throw AssertionError("never saw $state")
    }

    @Test
    fun idlePacketsCarryTheKeyAndRisingSequenceNumbers() {
        sender.start()
        val a = receive().first
        val b = receive().first
        assertEquals(StreamToken.key(token), a.key)
        assertTrue(InputWire.isNewer(b.seq, a.seq))
        assertEquals(PadState.NEUTRAL, a.state)
    }

    @Test
    fun aChangeGoesOutAtOnceWithItsSampleTime() {
        sender.start()
        receive()
        val jump = PadState(buttons = PadState.A)
        sender.update(jump, atNs = 123_456L)
        val (p, _) = until(jump)
        assertEquals(123_456L, p.sampleNs)
    }

    @Test
    fun heldInputRepeatsQuickly() {
        sender.start()
        sender.update(PadState(buttons = PadState.B))
        until(PadState(buttons = PadState.B))
        val t0 = System.nanoTime()
        repeat(10) { receive() }
        val perPacketMs = (System.nanoTime() - t0) / 10 / 1_000_000.0
        assertTrue("repeat every $perPacketMs ms", perPacketMs < 30.0)
    }

    @Test
    fun theFirstAckOfASampleIsReported() {
        sender.start()
        sender.update(PadState(buttons = PadState.X), atNs = 777L)
        val (p, dp) = until(PadState(buttons = PadState.X))
        repeat(2) {
            val ack = InputWire.encodeAck(InputAck(p.seq, p.sampleNs, 999L))
            host.send(DatagramPacket(ack, ack.size, dp.socketAddress))
        }
        val end = System.nanoTime() + 2_000_000_000L
        while (acks.isEmpty() && System.nanoTime() < end) Thread.sleep(5)
        Thread.sleep(100)
        assertEquals(1, acks.size)
        assertEquals(777L, acks[0].first)
        assertEquals(999L, acks[0].second)
    }

    @Test
    fun closingSendsThreeReleases() {
        sender.start()
        sender.update(PadState(buttons = PadState.A))
        until(PadState(buttons = PadState.A))
        sender.close()
        var releases = 0
        try {
            repeat(60) {
                val p = receive().first
                if (p.flags and StreamWire.INPUT_FLAG_NEUTRAL != 0) releases += 1
            }
        } catch (_: SocketTimeoutException) {
            // no more packets
        }
        assertEquals(3, releases)
    }

    @Test
    fun hostAudioOnTheInputSocketReachesOnAudioWhenTheKeyMatches() {
        val got: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val listener = InputSender("127.0.0.1", host.localPort, token, onAudio = { seq, _, _ -> got += seq })
        listener.start()
        try {
            val (_, from) = receive()
            val wrong = InputWire.encodeAudio(1L, 10, 5L, 240, ByteArray(960))
            host.send(DatagramPacket(wrong, wrong.size, from.socketAddress))
            val right = InputWire.encodeAudio(StreamToken.key(token), 9, 5L, 240, ByteArray(960))
            host.send(DatagramPacket(right, right.size, from.socketAddress))
            val end = System.nanoTime() + 2_000_000_000L
            while (got.isEmpty() && System.nanoTime() < end) Thread.sleep(5)
            assertEquals(listOf(9), got.toList())
        } finally {
            listener.close()
        }
    }
}
