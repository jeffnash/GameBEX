package com.gamebex.client.stream.guest

import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.proto.InputAck
import com.gamebex.client.stream.proto.InputPacket
import com.gamebex.client.stream.proto.InputWire
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.stream.proto.StreamWire
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    private val rtts: MutableList<Pair<Long, Long>> = Collections.synchronizedList(mutableListOf())
    private val sender = InputSender("127.0.0.1", host.localPort, token, { s, a, n -> acks += Triple(s, a, n) },
        onRtt = { sentNs, ackNs -> rtts += sentNs to ackNs })

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
        assertTrue(rtts.isEmpty())
        reply(input, from, flags = 0)
        waitFor { rtts.size == 1 }
        assertTrue(acks.isEmpty())
        sender.update(PadState(buttons = PadState.B), 102L)
        val (next, nextFrom) = until(PadState(buttons = PadState.B))
        reply(next, nextFrom)
        waitFor { acks.size == 1 }
        assertEquals(102L, acks.single().first)
    }

    @Test fun everyMatchedAckIncludingIdleAndHeldHeartbeatsReportsRttOnceFromSendTime() {
        val now = AtomicLong(10_000L)
        val gotRtts = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val gotApplied = Collections.synchronizedList(mutableListOf<Triple<Long, Long, Long>>())
        val listener = InputSender("127.0.0.1", host.localPort, token,
            onAck = { sample, applied, ack -> gotApplied += Triple(sample, applied, ack) },
            clock = now::get, reliableEdges = false, attachId = 7L,
            onRtt = { sent, ack -> gotRtts += sent to ack })
        try {
            listener.start()
            val (idle, idleFrom) = receive()
            assertEquals(0L, idle.sampleNs)
            now.set(10_500L)
            reply(idle, idleFrom, flags = 0)
            reply(idle, idleFrom, flags = 0)
            waitFor { gotRtts.size == 1 }
            assertEquals(10_000L to 10_500L, gotRtts.single())
            assertTrue(gotApplied.isEmpty())
            now.set(20_000L)
            listener.update(PadState(buttons = PadState.A), 100L)
            val (press, pressFrom) = until(PadState(buttons = PadState.A))
            now.set(20_500L)
            reply(press, pressFrom)
            waitFor { gotRtts.size == 2 && gotApplied.size == 1 }
            assertEquals(20_000L to 20_500L, gotRtts[1])
            assertEquals(100L, gotApplied.single().first)
            now.set(8_020_000L)
            val (held, heldFrom) = receive()
            assertEquals(100L, held.sampleNs)
            assertTrue(InputWire.isNewer(held.seq, press.seq))
            now.set(8_020_700L)
            reply(held, heldFrom, flags = 0)
            reply(held, heldFrom, flags = 0)
            waitFor { gotRtts.size == 3 }
            assertEquals(8_020_000L to 8_020_700L, gotRtts[2])
            assertEquals(1, gotApplied.size)
        } finally { listener.close() }
    }

    @Test fun retryAcksEachReportRttButOnlyTheFirstWroteReportsAppliedLatency() {
        val gotRtts = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val gotApplied = Collections.synchronizedList(mutableListOf<Long>())
        val listener = InputSender("127.0.0.1", host.localPort, token,
            onAck = { sample, _, _ -> gotApplied += sample }, reliableEdges = true, attachId = 7L,
            onRtt = { sent, ack -> gotRtts += sent to ack })
        try {
            listener.update(PadState(buttons = PadState.A), 101L)
            listener.start()
            val (first, from) = receive()
            val (retry, retryFrom) = receive()
            reply(first, from, flags = 0)
            reply(retry, retryFrom)
            reply(first, from)
            reply(retry, retryFrom)
            waitFor { gotRtts.size == 2 && gotApplied.size == 1 }
            assertEquals(listOf(101L), gotApplied.toList())
        } finally { listener.close() }
    }

    @Test fun independentAudioPortIsAcceptedButForeignAddressAndAckPortAreRejected() {
        val gotAudio = Collections.synchronizedList(mutableListOf<Int>())
        val gotRtts = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val listener = InputSender("127.0.0.1", host.localPort, token, onAudio = { seq, _, _ -> gotAudio += seq },
            attachId = 7L, onRtt = { sent, ack -> gotRtts += sent to ack })
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { audioSocket ->
            DatagramSocket(InetSocketAddress("127.0.0.2", 0)).use { foreign ->
                try {
                    listener.start()
                    val (input, from) = receive()
                    val wrong = InputWire.encodeAudio(StreamToken.key(token) xor 1L, 1, 5L, 1, ByteArray(4))
                    val alien = InputWire.encodeAudio(StreamToken.key(token), 2, 5L, 1, ByteArray(4))
                    val ack = InputWire.encodeAck(InputAck(input.seq, input.sampleNs, 999L, input.attachId, 0))
                    audioSocket.send(DatagramPacket(wrong, wrong.size, from.socketAddress))
                    foreign.send(DatagramPacket(alien, alien.size, from.socketAddress))
                    foreign.send(DatagramPacket(ack, ack.size, from.socketAddress))
                    audioSocket.send(DatagramPacket(ack, ack.size, from.socketAddress))
                    Thread.sleep(40)
                    assertTrue(gotAudio.isEmpty())
                    assertTrue(gotRtts.isEmpty())
                    val audio = InputWire.encodeAudio(StreamToken.key(token), 3, 5L, 1, ByteArray(4))
                    audioSocket.send(DatagramPacket(audio, audio.size, from.socketAddress))
                    reply(input, from, flags = 0)
                    waitFor { gotAudio.size == 1 && gotRtts.size == 1 }
                    assertEquals(listOf(3), gotAudio.toList())
                } finally { listener.close() }
            }
        }
    }

    @Test fun blockedSendDoesNotBlockUiCancelOrAnAckThatRacesSendCompletion() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = object : DatagramSocket() {
            var sends = 0
            override fun send(packet: DatagramPacket) {
                super.send(packet)
                if (++sends == 1) {
                    entered.countDown()
                    if (!release.await(3, TimeUnit.SECONDS)) throw IOException("deadline")
                }
            }
        }
        val gotRtts = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val listener = InputSender("127.0.0.1", host.localPort, token, reliableEdges = true, attachId = 7L,
            socketFactory = { socket }, onRtt = { sent, ack -> gotRtts += sent to ack })
        val updated = CountDownLatch(1)
        val ui = Thread {
            listener.update(PadState(buttons = PadState.B), 102L)
            listener.cancel(103L)
            updated.countDown()
        }
        try {
            listener.update(PadState(buttons = PadState.A), 101L)
            listener.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val (press, from) = receive()
            reply(press, from)
            waitFor { gotRtts.size == 1 }
            assertEquals(1L, release.count)
            ui.start()
            assertTrue(updated.await(200, TimeUnit.MILLISECONDS))
            assertEquals(1L, listener.cancellations)
            release.countDown()
            val (cancel, cancelFrom) = receive()
            assertTrue(InputWire.isNewer(cancel.seq, press.seq))
            assertEquals(103L, cancel.sampleNs)
            assertEquals(StreamWire.INPUT_FLAG_NEUTRAL, cancel.flags)
            assertTrue(cancel.state.isNeutral)
            reply(cancel, cancelFrom, flags = 0)
            waitFor { gotRtts.size == 2 }
        } finally {
            release.countDown()
            if (ui.state != Thread.State.NEW) ui.join(1_000)
            listener.close()
        }
    }

    @Test fun sendIOExceptionRemovesOnlyFailedAttemptAndReliableEdgeStillRetries() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failed = AtomicReference<InputPacket>()
        val socket = object : DatagramSocket() {
            var fail = true
            override fun send(packet: DatagramPacket) {
                if (fail) {
                    fail = false
                    failed.set(InputWire.decode(packet.data, packet.length))
                    entered.countDown()
                    if (!release.await(3, TimeUnit.SECONDS)) throw IOException("deadline")
                    throw IOException("buffer full")
                }
                super.send(packet)
            }
        }
        val gotRtts = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val listener = InputSender("127.0.0.1", host.localPort, token, reliableEdges = true, attachId = 7L,
            socketFactory = { socket }, onRtt = { sent, ack -> gotRtts += sent to ack })
        try {
            listener.update(PadState(buttons = PadState.A), 101L)
            listener.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            release.countDown()
            val (retry, from) = receive()
            assertTrue(InputWire.isNewer(retry.seq, failed.get().seq))
            assertEquals(failed.get().sampleNs, retry.sampleNs)
            assertEquals(PadState.A, retry.state.buttons)
            reply(failed.get(), from)
            reply(retry, from, flags = 0)
            waitFor { gotRtts.size == 1 }
            listener.update(PadState.NEUTRAL, 102L)
            val (neutral, neutralFrom) = until(PadState.NEUTRAL)
            assertEquals(0, neutral.flags)
            assertEquals(102L, neutral.sampleNs)
            reply(neutral, neutralFrom, flags = 0)
        } finally {
            release.countDown()
            listener.close()
        }
    }

    @Test fun closingWhileRttCallbackIsBlockedSuppressesRetiredAppliedCallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val gotApplied = Collections.synchronizedList(mutableListOf<Long>())
        val socket = DatagramSocket()
        val listener = InputSender("127.0.0.1", host.localPort, token,
            onAck = { sample, _, _ -> gotApplied += sample }, reliableEdges = false, attachId = 7L,
            socketFactory = { socket }, onRtt = { _, _ ->
                entered.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            })
        val (press, from) = run {
            listener.update(PadState(buttons = PadState.A), 101L)
            listener.start()
            receive()
        }
        val closer = Thread { listener.close(); finished.countDown() }
        try {
            reply(press, from)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            closer.start()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(socket.isClosed)
            assertTrue(gotApplied.isEmpty())
            release.countDown()
            Thread.sleep(40)
            assertTrue(gotApplied.isEmpty())
        } finally {
            release.countDown()
            if (closer.state != Thread.State.NEW) closer.join(1_000)
            listener.close()
        }
    }

    @Test fun closeIsBoundedWhileUdpSendIsBlockedAndOwnsSocketShutdown() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val socket = object : DatagramSocket() {
            override fun send(packet: DatagramPacket) {
                entered.countDown()
                if (!released.await(3, TimeUnit.SECONDS)) throw IOException("deadline")
                throw IOException("closed")
            }
            override fun close() {
                super.close()
                released.countDown()
            }
        }
        val listener = InputSender("127.0.0.1", host.localPort, token, reliableEdges = true, attachId = 7L,
            socketFactory = { socket })
        val closed = CountDownLatch(1)
        val closer = Thread { listener.close(); closed.countDown() }
        try {
            listener.update(PadState(buttons = PadState.A), 101L)
            listener.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            closer.start()
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertEquals(1L, listener.cancellations)
            assertTrue(socket.isClosed)
        } finally {
            socket.close()
            if (closer.state != Thread.State.NEW) closer.join(1_000)
            listener.close()
        }
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
