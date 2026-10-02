package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.fakes.FakeVirtualPad
import com.gamebex.client.stream.proto.InputPacket
import com.gamebex.client.stream.proto.InputWire
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.stream.proto.StreamWire
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            it.start()
        }

    private fun send(server: InputServer, seq: Int, state: PadState, flags: Int = 0, packetKey: Long = key) {
        val bytes = InputWire.encode(InputPacket(flags, packetKey, seq, 1_000L + seq, state))
        sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), server.port))
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
        waitFor("three packets") { s.packets.get() == 3L }
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
        assertFalse(s.sendAudio(chunk))
        send(s, 1, jump)
        waitFor("pad write") { pad.writes.isNotEmpty() }
        assertTrue(s.sendAudio(chunk))
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
}
