package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.fakes.FakeVirtualPad
import com.gamebex.client.stream.proto.InputAck
import com.gamebex.client.stream.proto.InputPacket
import com.gamebex.client.stream.proto.InputWire
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.stream.proto.StreamWire
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** InputServer on real loopback UDP with a fake pad. */
class InputServerTest {
    private val token = "00112233445566778899aabbccddeeff"
    private val key = StreamToken.key(token)
    private val pad = FakeVirtualPad()
    private val servers = mutableListOf<InputServer>()
    private val sender = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).apply { soTimeout = 2_000 }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        sender.close()
    }

    private fun server(guest: String = "127.0.0.1", timeoutMs: Long = 250L): InputServer =
        InputServer(pad, key, guest, timeoutMs = timeoutMs).also {
            servers += it
            it.open(0)
            assertTrue(it.attach(7L))
            pad.writes.clear()
            it.start()
        }

    private fun send(server: InputServer, seq: Int, state: PadState, flags: Int = 0, packetKey: Long = key, attachId: Long = 7L, socket: DatagramSocket = sender) {
        val bytes = InputWire.encode(InputPacket(flags, packetKey, seq, 1_000L + seq, state, attachId))
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), server.port))
    }

    private fun waitFor(what: String, timeoutMs: Long = 2_000L, check: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < end) {
            if (check()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private val jump = PadState(buttons = PadState.A, lx = 100)

    @Test
    fun appliesAPacketFromTheGuestAndAcksIt() {
        val s = server()
        send(s, 1, jump)
        waitFor("pad write") { pad.writes.isNotEmpty() }
        assertEquals(jump, pad.writes.last())
        val buffer = ByteArray(64)
        val reply = DatagramPacket(buffer, buffer.size)
        sender.receive(reply)
        val ack = InputWire.decodeAck(reply.data, reply.length)
        assertNotNull(ack)
        assertEquals(1, ack!!.seq)
        assertEquals(1_001L, ack.sampleNs)
        assertTrue(ack.wrote)
        assertEquals(1L, s.packets.get())
    }

    @Test
    fun rejectsAWrongKey() {
        val s = server()
        send(s, 1, jump, packetKey = key xor 1L)
        waitFor("rejection") { s.rejected.get() == 1L }
        assertTrue(pad.writes.isEmpty())
    }

    @Test
    fun rejectsPacketsFromAnyOtherAddress() {
        val s = server(guest = "127.0.0.2")
        send(s, 1, jump)
        waitFor("rejection") { s.rejected.get() == 1L }
        assertTrue(pad.writes.isEmpty())
    }

    @Test
    fun ignoresOlderSequenceNumbers() {
        val s = server()
        send(s, 5, jump)
        waitFor("first") { pad.writes.size == 1 }
        send(s, 4, PadState(buttons = PadState.B))
        waitFor("stale") { s.stale.get() == 1L }
        send(s, 6, PadState(buttons = PadState.X))
        waitFor("newer") { pad.writes.size == 2 }
        assertEquals(listOf(jump, PadState(buttons = PadState.X)), pad.writes.toList())
    }

    @Test
    fun repeatedStatesWriteOnce() {
        val s = server()
        send(s, 1, jump)
        send(s, 2, jump)
        send(s, 3, jump)
        val applied = ack()!!
        val heartbeat = ack()!!
        val heartbeat2 = ack()!!
        assertTrue(applied.wrote)
        assertFalse(heartbeat.wrote)
        assertFalse(heartbeat2.wrote)
        assertEquals(applied.appliedNs, heartbeat.appliedNs)
        assertEquals(applied.appliedNs, heartbeat2.appliedNs)
        assertEquals(listOf(jump), pad.writes.toList())
    }

    @Test
    fun releasesEverythingAfterSilence() {
        val s = server(timeoutMs = 100L)
        send(s, 1, jump)
        waitFor("press") { pad.writes.size == 1 }
        waitFor("release", timeoutMs = 1_000L) { pad.writes.size == 2 }
        assertEquals(PadState.NEUTRAL, pad.writes.last())
    }

    @Test
    fun neutralFlagReleasesAtOnce() {
        val s = server()
        send(s, 1, jump)
        waitFor("press") { pad.writes.size == 1 }
        send(s, 2, jump, flags = StreamWire.INPUT_FLAG_NEUTRAL)
        waitFor("release") { pad.writes.size == 2 }
        assertEquals(PadState.NEUTRAL, pad.writes.last())
    }

    @Test
    fun closeReleasesAHeldPad() {
        val s = server()
        send(s, 1, jump)
        waitFor("press") { pad.writes.size == 1 }
        s.close()
        assertEquals(PadState.NEUTRAL, pad.writes.last())
        assertTrue(!pad.closed)
    }

    @Test
    fun fallsBackToAFreePortWhenThePreferredOneIsTaken() {
        val holder = DatagramSocket(0)
        try {
            val s = InputServer(pad, key, "127.0.0.1").also { servers += it }
            s.open(holder.localPort)
            assertTrue(s.port != 0)
            assertTrue(s.port != holder.localPort)
        } finally {
            holder.close()
        }
    }

    @Test
    fun audioGoesBackToTheGuestOnlyAfterItsValidInput() {
        val s = server()
        val chunk = PcmChunk(7, 123L, 240, ByteArray(960) { it.toByte() })
        assertEquals(AudioSendResult.NO_ROUTE, s.sendAudio(chunk))
        send(s, 1, jump)
        waitFor("pad write") { pad.writes.isNotEmpty() }
        assertEquals(AudioSendResult.SENT, s.sendAudio(chunk))
        val buffer = ByteArray(2_048)
        repeat(5) {
            val p = DatagramPacket(buffer, buffer.size)
            sender.receive(p)
            val a = InputWire.decodeAudio(p.data, p.length) ?: return@repeat
            assertEquals(key, a.key)
            assertEquals(7, a.seq)
            assertEquals(123L, a.captureNs)
            assertEquals(960, a.pcm.size)
            return
        }
        throw AssertionError("no audio datagram")
    }

    private class ScriptedPad(private val fail: (Int, PadState) -> Boolean) : VirtualPad {
        override val backend = "scripted"
        val attempts = CopyOnWriteArrayList<PadState>()
        val writes = CopyOnWriteArrayList<PadState>()
        override fun write(state: PadState): Boolean {
            attempts += state
            if (fail(attempts.size, state)) return false
            writes += state
            return true
        }
        override fun close() = Unit
    }

    private fun scripted(pad: VirtualPad, timeoutMs: Long = 1_000L, ackDropEvery: Int = 0,
        socketFactory: () -> DatagramSocket = { DatagramSocket(null) },
        audioSocketFactory: () -> DatagramSocket = { DatagramSocket() }): InputServer =
        InputServer(pad, key, "127.0.0.1", timeoutMs = timeoutMs, cs6 = false, ackDropEvery = ackDropEvery,
            socketFactory = socketFactory, audioSocketFactory = audioSocketFactory).also {
            servers += it
            it.open(0)
            assertTrue(it.attach(7L))
            it.start()
        }

    private fun ack(socket: DatagramSocket = sender): InputAck? {
        val packet = DatagramPacket(ByteArray(128), 128)
        return try {
            socket.receive(packet)
            InputWire.decodeAck(packet.data, packet.length)
        } catch (_: SocketTimeoutException) { null }
    }

    @Test fun failedPressIsNotRememberedOrAcknowledgedButStillLearnsAudioRoute() {
        val device = ScriptedPad { n, _ -> n == 2 }
        val s = scripted(device)
        sender.soTimeout = 80
        send(s, 1, jump)
        waitFor("failed write") { s.writeFailures.get() == 1L }
        assertNull(ack())
        assertEquals(listOf(PadState.NEUTRAL), device.writes.toList())
        assertEquals(PadState.NEUTRAL, s.lastState)
        assertEquals(AudioSendResult.SENT, s.sendAudio(PcmChunk(1, 5L, 1, ByteArray(4))))
        val audio = DatagramPacket(ByteArray(128), 128)
        sender.receive(audio)
        assertNotNull(InputWire.decodeAudio(audio.data, audio.length))
        send(s, 1, jump)
        assertTrue(ack()!!.wrote)
        assertEquals(listOf(PadState.NEUTRAL, jump), device.writes.toList())
    }

    @Test fun failedReleaseIsRetriedByNewerNeutralHeartbeat() {
        val device = ScriptedPad { n, state -> n == 3 && state.isNeutral }
        val s = scripted(device)
        sender.soTimeout = 80
        send(s, 1, jump)
        assertTrue(ack()!!.wrote)
        send(s, 2, PadState.NEUTRAL)
        assertNull(ack())
        assertEquals(jump, s.lastState)
        send(s, 3, PadState.NEUTRAL)
        assertTrue(ack()!!.wrote)
        assertEquals(PadState.NEUTRAL, s.lastState)
        assertEquals(1L, s.writeFailures.get())
    }

    @Test fun invalidAndStaleFloodCannotPostponeWatchdogAndFailedWatchdogRetries() {
        val neutralFailures = AtomicInteger()
        val device = ScriptedPad { n, state -> n > 1 && state.isNeutral && neutralFailures.getAndIncrement() == 0 }
        val s = scripted(device, timeoutMs = 40L)
        send(s, 1, jump)
        assertNotNull(ack())
        val end = System.nanoTime() + 150_000_000L
        var seq = 2
        while (System.nanoTime() < end) {
            send(s, seq++, PadState(buttons = PadState.B), packetKey = key xor 1L)
            send(s, 1, jump)
            Thread.sleep(1)
        }
        waitFor("watchdog neutral") { device.writes.last().isNeutral }
        assertTrue(neutralFailures.get() >= 2)
        assertEquals(1L, s.writeFailures.get())
        assertEquals(listOf(PadState.NEUTRAL, jump, PadState.NEUTRAL), device.writes.toList())
    }

    @Test fun reopenRelearnsRouteAcceptsSeqOneAndSameViewerReconnectIsIdempotent() {
        val s = server()
        send(s, 500, jump)
        assertNotNull(ack())
        assertTrue(s.attach(7L))
        assertEquals(listOf(jump), pad.writes.toList())
        assertTrue(s.attach(8L))
        val chunk = PcmChunk(8, 5L, 1, ByteArray(4))
        assertEquals(AudioSendResult.NO_ROUTE, s.sendAudio(chunk))
        send(s, 501, jump)
        waitFor("old attachment rejected") { s.rejected.get() == 1L }
        s.detach(7L)
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { next ->
            next.soTimeout = 1_000
            send(s, 1, PadState(buttons = PadState.B), attachId = 8L, socket = next)
            assertEquals(8L, ack(next)!!.attachId)
            assertEquals(AudioSendResult.SENT, s.sendAudio(chunk))
            val audio = DatagramPacket(ByteArray(128), 128)
            next.receive(audio)
            assertEquals(8, InputWire.decodeAudio(audio.data, audio.length)!!.seq)
            s.detach(8L)
            assertEquals(AudioSendResult.NO_ROUTE, s.sendAudio(chunk))
        }
    }

    @Test fun packetsCannotAttachThemselvesAndSequenceWrapRejectsReorder() {
        val s = InputServer(pad, key, "127.0.0.1", cs6 = false, ackDropEvery = 0).also { servers += it }
        s.open(0)
        s.start()
        send(s, 1, jump)
        waitFor("no implicit attachment") { s.rejected.get() == 1L }
        assertTrue(pad.writes.isEmpty())
        assertTrue(s.attach(7L))
        send(s, Int.MAX_VALUE, jump)
        assertNotNull(ack())
        send(s, Int.MIN_VALUE, PadState.NEUTRAL)
        assertNotNull(ack())
        send(s, Int.MAX_VALUE, jump)
        waitFor("wrapped stale") { s.stale.get() == 1L }
        assertEquals(PadState.NEUTRAL, s.lastState)
    }

    @Test fun validRouteSendIOExceptionDropsWithoutRequestingTcpFallback() {
        val socket = object : DatagramSocket(null as java.net.SocketAddress?) {
            @Volatile var failAudio = false
            override fun send(packet: DatagramPacket) {
                if (failAudio && packet.data.take(4).toByteArray().contentEquals(StreamWire.AUDIO_UDP_MAGIC)) throw IOException("send failed")
                super.send(packet)
            }
        }
        val s = scripted(ScriptedPad { _, _ -> false }, audioSocketFactory = { socket })
        send(s, 1, jump)
        assertNotNull(ack())
        socket.failAudio = true
        assertEquals(AudioSendResult.DROPPED, s.sendAudio(PcmChunk(1, 5L, 1, ByteArray(4))))
        assertEquals(1L, s.audioDropped.get())
        assertEquals(0L, s.audioSent.get())
    }

    @Test fun blockedAudioDoesNotBlockInputWatchdogOrRouteReplacement() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val audioSocket = object : DatagramSocket() {
            override fun send(packet: DatagramPacket) {
                entered.countDown()
                if (!release.await(3, TimeUnit.SECONDS)) throw IOException("deadline")
                super.send(packet)
            }
        }
        val device = ScriptedPad { _, _ -> false }
        val s = scripted(device, timeoutMs = 80L, audioSocketFactory = { audioSocket })
        send(s, 1, jump)
        assertNotNull(ack())
        val result = AtomicReference<AudioSendResult>()
        val audioThread = Thread { result.set(s.sendAudio(PcmChunk(1, 5L, 1, ByteArray(4)))) }
        val replaced = CountDownLatch(1)
        val replacement = Thread {
            s.attach(8L)
            replaced.countDown()
        }
        try {
            audioThread.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            send(s, 2, PadState(buttons = PadState.B))
            assertNotNull(ack())
            assertEquals(PadState(buttons = PadState.B), s.lastState)
            waitFor("watchdog during blocked audio", timeoutMs = 1_000L) { s.lastState.isNeutral }
            assertEquals(1L, release.count)
            replacement.start()
            assertTrue(replaced.await(200, TimeUnit.MILLISECONDS))
            assertEquals(AudioSendResult.NO_ROUTE, s.sendAudio(PcmChunk(2, 6L, 1, ByteArray(4))))
            release.countDown()
            audioThread.join(1_000)
            assertFalse(audioThread.isAlive)
            assertEquals(AudioSendResult.SENT, result.get())
            val audio = DatagramPacket(ByteArray(128), 128)
            sender.receive(audio)
            assertNotNull(InputWire.decodeAudio(audio.data, audio.length))
            assertEquals(audioSocket.localPort, audio.port)
            assertTrue(audio.port != s.port)
        } finally {
            release.countDown()
            audioThread.join(1_000)
            if (replacement.state != Thread.State.NEW) replacement.join(1_000)
        }
    }

    @Test fun blockedAckDoesNotBlockInputWatchdogAttachmentOrCloseAndQueueIsBounded() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = object : DatagramSocket(null as java.net.SocketAddress?) {
            override fun send(packet: DatagramPacket) {
                entered.countDown()
                if (!release.await(3, TimeUnit.SECONDS)) throw IOException("deadline")
                throw IOException("closed")
            }
            override fun close() {
                super.close()
                release.countDown()
            }
        }
        val device = ScriptedPad { _, _ -> false }
        val s = scripted(device, timeoutMs = 80L, socketFactory = { socket })
        val replaced = CountDownLatch(1)
        val replacement = Thread { s.attach(8L); replaced.countDown() }
        val closed = CountDownLatch(1)
        val closer = Thread { s.close(); closed.countDown() }
        try {
            send(s, 1, jump)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(10) { send(s, it + 2, PadState(buttons = PadState.B)) }
            waitFor("input while ACK blocked") { s.packets.get() == 11L }
            // The ACK-sender thread lags the packet counter under load;
            // wait for the bounded-queue drops to land, then assert exact.
            waitFor("ack drops settle") { s.ackDropped.get() == 2L }
            assertEquals(2L, s.ackDropped.get())
            waitFor("watchdog while ACK blocked", timeoutMs = 1_000L) { s.lastState.isNeutral }
            assertEquals(1L, release.count)
            replacement.start()
            assertTrue(replaced.await(200, TimeUnit.MILLISECONDS))
            assertEquals(1L, release.count)
            closer.start()
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertTrue(socket.isClosed)
            assertTrue(s.lastState.isNeutral)
        } finally {
            socket.close()
            if (replacement.state != Thread.State.NEW) replacement.join(1_000)
            if (closer.state != Thread.State.NEW) closer.join(1_000)
        }
    }

    @Test fun audioRouteDeadlineIsRecheckedBeforeSendingAndDroppedDoesNotRequestTcp() {
        val now = AtomicLong(100L)
        val reads = AtomicInteger()
        val sends = AtomicInteger()
        val audioSocket = object : DatagramSocket() {
            override fun send(packet: DatagramPacket) { sends.incrementAndGet() }
        }
        val s = InputServer(pad, key, "127.0.0.1", clock = {
            if (Thread.currentThread().name == "audio-deadline" && reads.incrementAndGet() == 2) {
                now.addAndGet(1_000_000_001L)
            }
            now.get()
        }, cs6 = false, ackDropEvery = 0, audioSocketFactory = { audioSocket }).also { servers += it }
        s.open(0)
        assertTrue(s.attach(7L))
        s.start()
        send(s, 1, jump)
        assertNotNull(ack())
        val result = AtomicReference<AudioSendResult>()
        val audioThread = Thread({ result.set(s.sendAudio(PcmChunk(1, 5L, 1, ByteArray(4)))) }, "audio-deadline")
        audioThread.start()
        audioThread.join(1_000)
        assertFalse(audioThread.isAlive)
        assertEquals(AudioSendResult.DROPPED, result.get())
        assertEquals(0, sends.get())
        assertEquals(1L, s.audioDropped.get())
        assertEquals(AudioSendResult.NO_ROUTE, s.sendAudio(PcmChunk(2, 6L, 1, ByteArray(4))))
    }

    @Test fun cs6InputAndEfAudioUseIndependentOwnedSockets() {
        val inputSocket = object : DatagramSocket(null as java.net.SocketAddress?) {
            var tos = -1
            override fun setTrafficClass(tc: Int) { tos = tc }
            override fun getTrafficClass(): Int = tos
        }
        val audioSocket = object : DatagramSocket() {
            var tos = -1
            override fun setTrafficClass(tc: Int) { tos = tc }
            override fun getTrafficClass(): Int = tos
        }
        val s = InputServer(pad, key, "127.0.0.1", cs6 = true, ackDropEvery = 0,
            socketFactory = { inputSocket }, audioSocketFactory = { audioSocket }).also { servers += it }
        s.open(0)
        assertEquals(inputSocket.localPort, s.port)
        assertEquals(0xC0, inputSocket.tos)
        assertEquals(0xB8, audioSocket.tos)
        assertEquals(0xC0, s.actualTos)
        assertTrue(inputSocket.localPort != audioSocket.localPort)
        s.close()
        assertTrue(inputSocket.isClosed)
        assertTrue(audioSocket.isClosed)
    }

    @Test fun ackFaultDropsOnlySuccessfulAckAndRetryReportsWroteFalse() {
        val s = scripted(ScriptedPad { _, _ -> false }, ackDropEvery = 2)
        sender.soTimeout = 80
        send(s, 1, jump)
        assertTrue(ack()!!.wrote)
        send(s, 2, PadState.NEUTRAL)
        assertNull(ack())
        assertEquals(PadState.NEUTRAL, s.lastState)
        send(s, 3, PadState.NEUTRAL)
        assertFalse(ack()!!.wrote)
        assertEquals(1L, s.ackDropped.get())
        assertEquals(0L, s.writeFailures.get())
    }
}
