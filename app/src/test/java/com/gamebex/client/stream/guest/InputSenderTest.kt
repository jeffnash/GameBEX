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
import org.junit.Assert.assertFalse
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
            val ack = InputWire.encodeAck(InputAck(p.seq, p.sampleNs, 999L, p.attachId, StreamWire.ACK_FLAG_WROTE))
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
    fun closingRetriesNeutralUntilAckInsteadOfThreeShots() {
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
        assertTrue("neutral retries during bounded grace: $releases", releases >= 3)
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

    private fun reply(input: InputPacket, from: DatagramPacket, seq: Int = input.seq, sampleNs: Long = input.sampleNs,
        attachId: Long = input.attachId, flags: Int = StreamWire.ACK_FLAG_WROTE) {
        val bytes = InputWire.encodeAck(InputAck(seq, sampleNs, 999L, attachId, flags))
        host.send(DatagramPacket(bytes, bytes.size, from.socketAddress))
    }

    private fun waitFor(check: () -> Boolean) {
        val end = System.nanoTime() + 2_000_000_000L
        while (!check() && System.nanoTime() < end) Thread.sleep(2)
        assertTrue("condition before deadline", check())
    }

    @Test fun ackMustMatchSentSequenceSampleAndAttachmentAndWrote() {
        sender.start()
        sender.update(PadState(buttons = PadState.A), 101L)
        val (input, from) = until(PadState(buttons = PadState.A))
        reply(input, from, attachId = if (input.attachId == 1L) 2L else 1L)
        reply(input, from, seq = input.seq + 1000)
        reply(input, from, sampleNs = input.sampleNs + 1)
        Thread.sleep(30)
        assertTrue(acks.isEmpty())
        reply(input, from, flags = 0)
        Thread.sleep(30)
        assertTrue(acks.isEmpty())
        sender.update(PadState(buttons = PadState.B), 102L)
        val (next, nextFrom) = until(PadState(buttons = PadState.B))
        reply(next, nextFrom)
        waitFor { acks.size == 1 }
        assertEquals(102L, acks.single().first)
    }

    @Test fun audioTheSameLengthAsAckStillDispatchesByMagic() {
        val got = Collections.synchronizedList(mutableListOf<Int>())
        val listener = InputSender("127.0.0.1", host.localPort, token, onAudio = { seq, _, _ -> got += seq })
        try {
            listener.start()
            val (_, from) = receive()
            val audio = InputWire.encodeAudio(StreamToken.key(token), 17, 5L, 1, ByteArray(4))
            assertEquals(StreamWire.ACK_SIZE, audio.size)
            host.send(DatagramPacket(audio, audio.size, from.socketAddress))
            waitFor { got.isNotEmpty() }
            assertEquals(listOf(17), got.toList())
        } finally { listener.close() }
    }

    @Test fun orderedTapHoldsHeadUntilExactAckAndDuplicateCannotPopRelease() {
        val reliable = InputSender("127.0.0.1", host.localPort, token, reliableEdges = true, attachId = 7L)
        try {
            reliable.update(PadState(buttons = PadState.A), 1L)
            reliable.update(PadState.NEUTRAL, 2L)
            reliable.start()
            val (down, from) = receive()
            assertEquals(PadState.A, down.state.buttons)
            val retry = receive().first
            assertTrue(InputWire.isNewer(retry.seq, down.seq))
            assertEquals(down.sampleNs, retry.sampleNs)
            assertEquals(down.state, retry.state)
            reply(down, from, sampleNs = 99L)
            assertEquals(PadState.A, receive().first.state.buttons)
            reply(down, from, flags = 0)
            val (up, _) = until(PadState.NEUTRAL)
            assertEquals(2L, up.sampleNs)
            assertEquals(0, up.flags)
            reply(down, from)
            val upRetry = receive().first
            assertTrue(upRetry.state.isNeutral)
            assertEquals(up.sampleNs, upRetry.sampleNs)
            reply(up, from, flags = 0)
        } finally { reliable.close() }
    }

    @Test fun cancelDiscardsBothSourcesAndCloseWaitsForCancelAckOffCallerThread() {
        val reliable = InputSender("127.0.0.1", host.localPort, token, reliableEdges = true, attachId = 7L)
        try {
            reliable.start()
            reliable.update(PadState(buttons = PadState.A, lx = 100), 1L)
            val (_, from) = until(PadState(buttons = PadState.A, lx = 100))
            reliable.cancel(2L)
            var cancel = receive().first
            while (cancel.flags == 0) cancel = receive().first
            assertTrue(cancel.state.isNeutral)
            assertEquals(StreamWire.INPUT_FLAG_NEUTRAL, cancel.flags)
            reply(cancel, from, flags = 0)
            val closer = Thread { reliable.close() }.apply { start() }
            var finalCancel = receive().first
            while (finalCancel.flags == 0 || finalCancel.sampleNs == 2L) finalCancel = receive().first
            reply(finalCancel, from, flags = 0)
            closer.join(1_000)
            assertFalse(closer.isAlive)
        } finally { reliable.close() }
    }
}
