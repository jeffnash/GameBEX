package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.fakes.FakeVideoSource
import com.gamebex.client.stream.proto.MediaFrame
import com.gamebex.client.stream.proto.MediaFrames
import com.gamebex.client.stream.proto.StreamHandshake
import com.gamebex.client.stream.proto.StreamWire
import com.gamebex.client.stream.proto.Welcome
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MediaServer on real loopback TCP with a fake video source. */
class MediaServerTest {
    private val token = "00112233445566778899aabbccddeeff"
    private val video = FakeVideoSource(StreamCaps.LEVELS[0])
    private val servers = mutableListOf<MediaServer>()
    private val clients = mutableListOf<Client>()

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.close() }
    }

    private fun server(guest: String = "127.0.0.1", audio: Boolean = true): MediaServer {
        val s = MediaServer(StreamConfig(token = token, guestAddress = guest, mediaPort = 0, inputPort = 0))
        servers += s
        s.open(0)
        video.start(StreamCaps.LEVELS[0], StreamCaps.LEVELS[0].maxBitrate, s)
        s.start(video, StreamCaps.LEVELS[0], StreamCaps.LEVELS[0].maxBitrate, audio)
        return s
    }

    private inner class Client(port: Int, receiveBuffer: Int = 0) {
        val socket = Socket().apply {
            if (receiveBuffer > 0) this.receiveBufferSize = receiveBuffer
            soTimeout = 3_000
            connect(InetSocketAddress("127.0.0.1", port))
        }
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

        init {
            clients += this
        }

        fun hello(version: Int = StreamWire.VERSION, tokenHex: String = token): Welcome? {
            val bytes = StreamHandshake.encodeHello(StreamToken.bytes(tokenHex))
            bytes[4] = version.toByte()
            return try {
                socket.getOutputStream().write(bytes)
                StreamHandshake.decodeWelcome(StreamHandshake.readExactly(input, StreamWire.WELCOME_SIZE))
            } catch (_: IOException) {
                null
            }
        }

        fun send(frame: MediaFrame) = socket.getOutputStream().write(MediaFrames.encode(frame))

        fun read(): MediaFrame? = MediaFrames.read(input)

        /** The next frame, or null at end of stream or a reset connection. */
        fun readOrNull(): MediaFrame? = try {
            read()
        } catch (_: IOException) {
            null
        }

        /** Reads until a frame of type [T] arrives; other frames are skipped. */
        inline fun <reified T : MediaFrame> next(): T {
            while (true) {
                val f = read() ?: throw EOFException("closed before ${T::class.simpleName}")
                if (f is T) return f
            }
        }

        fun close() = runCatching { socket.close() }
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < end) {
            if (check()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun welcomesTheGuestWithSizeAndAudio() {
        val s = server()
        val w = Client(s.port).hello()
        assertNotNull(w)
        assertEquals(StreamWire.STATUS_OK, w!!.status)
        assertEquals(1600, w.width)
        assertEquals(720, w.height)
        assertEquals(48_000, w.audioRate)
        assertEquals(2, w.audioChannels)
        waitFor("guest") { s.stats().guestConnected }
    }

    @Test
    fun noAudioMeansRateZero() {
        val s = server(audio = false)
        assertEquals(0, Client(s.port).hello()!!.audioRate)
    }

    @Test
    fun aWrongTokenIsRefusedAndClosed() {
        val s = server()
        val c = Client(s.port)
        val w = c.hello(tokenHex = "ffeeddccbbaa99887766554433221100")
        assertEquals(StreamWire.STATUS_BAD_TOKEN, w!!.status)
        assertNull(c.readOrNull())
        assertFalse(s.stats().guestConnected)
    }

    @Test
    fun aNewerProtocolIsRefused() {
        val s = server()
        val w = Client(s.port).hello(version = 2)
        assertEquals(StreamWire.STATUS_BAD_VERSION, w!!.status)
    }

    @Test
    fun anyOtherAddressIsDroppedWithoutAWelcome() {
        val s = server(guest = "127.0.0.2")
        assertNull(Client(s.port).hello())
        waitFor("rejection") { s.rejectedConnections.get() == 1L }
    }

    @Test
    fun sendsConfigThenWaitsForAKeyframe() {
        val s = server()
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3)
        s.onConfig(csd)
        val c = Client(s.port)
        c.hello()
        assertArrayEquals(csd, c.next<MediaFrame.VideoConfig>().csd)
        val info = c.next<MediaFrame.Info>()
        assertEquals(0, info.level)
        waitFor("keyframe request") { video.keyframeRequests >= 1 }
        video.emit(seq = 1, key = false)
        video.emit(seq = 2, key = true)
        video.emit(seq = 3, key = false)
        val first = c.next<MediaFrame.Video>()
        assertEquals(2, first.seq)
        assertTrue(first.key)
        assertTrue(first.sendNs >= first.encodedNs)
        assertEquals(3, c.next<MediaFrame.Video>().seq)
        assertEquals(1L, s.framesDropped.get())
    }

    @Test
    fun answersPingsWithHostTimes() {
        val s = server()
        val c = Client(s.port)
        c.hello()
        val t0 = System.nanoTime()
        c.send(MediaFrame.Ping(7, t0))
        val pong = c.next<MediaFrame.Pong>()
        assertEquals(7, pong.id)
        assertEquals(t0, pong.t0)
        assertTrue(pong.t1 <= pong.t2)
    }

    @Test
    fun forwardsKeyframeRequestsAndReports() {
        val s = server()
        val c = Client(s.port)
        c.hello()
        waitFor("connect request") { video.keyframeRequests >= 1 }
        Thread.sleep(300)
        c.send(MediaFrame.KeyframeRequest)
        waitFor("guest request") { video.keyframeRequests >= 2 }
        c.send(MediaFrame.Report(100, 99, 98, 41_000, 0))
        waitFor("report") { s.stats().guestVideoP95Us == 41_000 }
    }

    @Test
    fun carriesAudio() {
        val s = server()
        val c = Client(s.port)
        c.hello()
        waitFor("guest") { s.stats().guestConnected }
        s.onPcm(PcmChunk(5, 123L, 480, ByteArray(1_920) { 1 }))
        val a = c.next<MediaFrame.Audio>()
        assertEquals(5, a.seq)
        assertEquals(123L, a.captureNs)
        assertEquals(480, a.frames)
        assertEquals(1_920, a.pcm.size)
    }

    @Test
    fun aStalledGuestMakesTheServerDropVideoAndLowerTheBitrate() {
        val s = server()
        val c = Client(s.port, receiveBuffer = 8 * 1024)
        c.hello()
        waitFor("guest") { s.stats().guestConnected }
        video.emit(seq = 1, key = true, size = 256 * 1024)
        for (i in 2..60) {
            video.emit(seq = i, key = false, size = 256 * 1024)
            Thread.sleep(10)
        }
        waitFor("drops") { s.framesDropped.get() > 0 }
        waitFor("bitrate cut") { video.currentBitrate in 1 until StreamCaps.LEVELS[0].maxBitrate }
        assertTrue(video.keyframeRequests >= 2)
        assertTrue(s.stats().bitrate < StreamCaps.LEVELS[0].maxBitrate)
    }

    @Test
    fun aSecondConnectionReplacesTheFirst() {
        val s = server()
        val first = Client(s.port)
        first.hello()
        waitFor("first") { s.stats().guestConnected }
        val second = Client(s.port)
        assertEquals(StreamWire.STATUS_OK, second.hello()!!.status)
        assertEquals(StreamWire.BYE_REPLACED, first.next<MediaFrame.Bye>().reason)
        assertNull(first.readOrNull())
        assertTrue(s.stats().guestConnected)
    }

    @Test
    fun closeSaysByeAndEndsTheConnection() {
        val s = server()
        val c = Client(s.port)
        c.hello()
        waitFor("guest") { s.stats().guestConnected }
        s.close()
        assertEquals(StreamWire.BYE_STOPPING, c.next<MediaFrame.Bye>().reason)
        assertNull(c.readOrNull())
        assertFalse(s.stats().guestConnected)
    }

    @Test
    fun aGuestThatLeavesIsNoticed() {
        val s = server()
        val c = Client(s.port)
        c.hello()
        waitFor("guest") { s.stats().guestConnected }
        val before = s.stats().guestChangedNs
        c.send(MediaFrame.Bye(StreamWire.BYE_LEAVING))
        waitFor("gone") { !s.stats().guestConnected }
        assertTrue(s.stats().guestChangedNs > before)
    }

    @Test
    fun aFailedSourceIsReportedInStats() {
        val s = server()
        s.onFailure("encoder died")
        assertEquals("encoder died", s.stats().failure)
    }

    @Test
    fun fallsBackToAFreePort() {
        val holder = java.net.ServerSocket(0)
        try {
            val s = MediaServer(StreamConfig(token = token, guestAddress = "127.0.0.1"))
            servers += s
            s.open(holder.localPort)
            assertTrue(s.port > 0)
            assertTrue(s.port != holder.localPort)
        } finally {
            holder.close()
        }
    }

    @Test
    fun readTimeoutMeansNothingWasSent() {
        val s = server()
        val c = Client(s.port)
        c.hello()
        c.next<MediaFrame.Info>()
        c.socket.soTimeout = 200
        val nothing = try {
            c.read()
        } catch (_: SocketTimeoutException) {
            null
        }
        assertNull(nothing)
    }
}
