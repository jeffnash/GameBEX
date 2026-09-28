package com.gamebex.client.together.net

import com.gamebex.client.together.RaRoom
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaLanProbeTest {
    @Test fun parsesEveryFieldFrom688BytePacket() {
        val bytes = packet()
        val room = RaLanProbe.parse(bytes, bytes.size)
        assertEquals(
            RaRoom("Nick", "snes9x", "1.2", "Super Mario", 0x12345678L, 55435, "1.22.2"),
            room,
        )
    }

    @Test fun shortPacketIsRejected() {
        assertNull(RaLanProbe.parse(ByteArray(687), 687))
    }

    @Test fun wrongHeaderIsRejected() {
        val bytes = packet()
        bytes[0] = 'X'.code.toByte()
        assertNull(RaLanProbe.parse(bytes, bytes.size))
    }

    @Test fun fakeRetroArchReplyIsFound() = runBlocking {
        val responder = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val answer = packet()
        val job = async(Dispatchers.IO) {
            val requestBytes = ByteArray(32)
            val request = DatagramPacket(requestBytes, requestBytes.size)
            responder.receive(request)
            assertEquals(4, request.length)
            assertTrue(request.data.copyOf(4).contentEquals(byteArrayOf(0x52, 0x41, 0x4E, 0x51)))
            assertTrue(request.port != responder.localPort)
            responder.send(DatagramPacket(answer, answer.size, request.address, request.port))
        }
        try {
            assertEquals(
                RaRoom("Nick", "snes9x", "1.2", "Super Mario", 0x12345678L, 55435, "1.22.2"),
                LiveProbesImpl(responder.localPort).raRoom("127.0.0.1", 500),
            )
            job.await()
        } finally {
            responder.close()
            job.cancel()
        }
    }

    @Test fun replyFromDifferentSourceAddressIsIgnored() = runBlocking {
        val responder = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val wrongSource = DatagramSocket(0, InetAddress.getByName("127.0.0.2"))
        val answer = packet()
        val job = async(Dispatchers.IO) {
            val requestBytes = ByteArray(32)
            val request = DatagramPacket(requestBytes, requestBytes.size)
            responder.receive(request)
            wrongSource.send(DatagramPacket(answer, answer.size, request.address, request.port))
        }
        try {
            assertNull(LiveProbesImpl(responder.localPort).raRoom("127.0.0.1", 100))
            job.await()
        } finally {
            responder.close(); wrongSource.close(); job.cancel()
        }
    }

    private fun packet(): ByteArray {
        val bytes = ByteArray(688)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        b.put("RANS".toByteArray())
        b.putInt(0x12345678)
        b.putInt(55435)
        b.putInt(0)
        putString(bytes, 16, 32, "Nick")
        putString(bytes, 48, 32, "frontend")
        putString(bytes, 80, 32, "snes9x")
        putString(bytes, 112, 32, "1.2")
        putString(bytes, 144, 32, "1.22.2")
        putString(bytes, 176, 256, "Super Mario")
        putString(bytes, 432, 256, "")
        return bytes
    }

    private fun putString(target: ByteArray, offset: Int, width: Int, value: String) {
        val text = value.toByteArray(Charsets.UTF_8)
        text.copyInto(target, offset, 0, minOf(text.size, width - 1))
    }
}
