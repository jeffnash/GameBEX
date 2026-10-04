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

    private fun server(guest: String = "127.0.0.1", audio: Boolean = true, latencyControl: Boolean = false,
        clock: () -> Long = System::nanoTime): MediaServer {
        val s = MediaServer(StreamConfig(token = token, guestAddress = guest, mediaPort = 0, inputPort = 0),
            clock = clock, tickMs = 10L, queueDeadlineMs = 0, latencyControl = latencyControl, videoWriteStallMs = 0)
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

        fun hello(version: Int = StreamWire.VERSION, tokenHex: String = token, attachId: Long = 7L): Welcome? {
            val bytes = StreamHandshake.encodeHello(StreamToken.bytes(tokenHex), attachId)
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
        val w = Client(s.port).hello(version = StreamWire.VERSION + 1)
        assertEquals(StreamWire.STATUS_BAD_VERSION, w!!.status)
    }

    @Test
    fun v1PrefixGetsBadVersionWithoutWaitingForAttachmentBytes() {
        val s = server()
        val c = Client(s.port)
        val bytes = StreamHandshake.encodeHello(StreamToken.bytes(token), 7L).copyOf(24)
        bytes[4] = 1
        c.socket.getOutputStream().write(bytes)
        val welcome = StreamHandshake.decodeWelcome(StreamHandshake.readExactly(c.input, StreamWire.WELCOME_SIZE))!!
        assertEquals(StreamWire.STATUS_BAD_VERSION, welcome.status)
        assertFalse(s.stats().guestConnected)
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
        val info = c.read() as MediaFrame.Info
        assertEquals(0, info.level)
        assertArrayEquals(csd, (c.read() as MediaFrame.VideoConfig).csd)
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
    fun forwardsKeyframeRequestsAndAcceptsDeliveryReports() {
        val s = server()
        val c = Client(s.port)
        c.hello()
        waitFor("connect request") { video.keyframeRequests >= 1 }
        Thread.sleep(300)
        c.send(MediaFrame.KeyframeRequest)
        waitFor("guest request") { video.keyframeRequests >= 2 }
        c.send(MediaFrame.Delivery(com.gamebex.client.stream.proto.DeliveryWire.Report(1L, 1, System.nanoTime(), 500_000_000L, 0L, 0, -1L, 0, 0)))
        c.send(MediaFrame.Ping(99, 1L))
        assertEquals(99, c.next<MediaFrame.Pong>().id)
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
    fun immediateCloseAfterGuestRegistrationStillSendsBye() {
        repeat(20) {
            val s = server()
            val c = Client(s.port)
            assertEquals(StreamWire.STATUS_OK, c.hello()!!.status)
            waitFor("guest") { s.stats().guestConnected }
            s.close()
            assertEquals(StreamWire.BYE_STOPPING, c.next<MediaFrame.Bye>().reason)
            assertNull(c.readOrNull())
            assertFalse(s.stats().guestConnected)
        }
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
    fun attachmentCallbackPrecedesWriterAndRejectsWrongToken() {
        val s = server()
        val ids = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val callbackStats = java.util.concurrent.atomic.AtomicBoolean(false)
        s.onGuestAttached = { id ->
            callbackStats.set(!s.stats().guestConnected)
            ids += id
        }
        Client(s.port).hello(tokenHex = "ffeeddccbbaa99887766554433221100", attachId = 11L)
        assertTrue(ids.isEmpty())
        val c = Client(s.port)
        c.hello(attachId = 13L)
        c.next<MediaFrame.VideoConfig>()
        assertEquals(listOf(13L), ids.toList())
        assertTrue(callbackStats.get())
    }

    @Test
    fun onlyCurrentExplicitLeaveDetachesAndTransientLossDoesNot() {
        val s = server()
        val detached = java.util.Collections.synchronizedList(mutableListOf<Long>())
        s.onGuestDetached = { detached += it }
        val first = Client(s.port)
        first.hello(attachId = 101L)
        first.next<MediaFrame.VideoConfig>()
        first.close()
        waitFor("transient loss") { !s.stats().guestConnected }
        assertTrue(detached.isEmpty())
        val second = Client(s.port)
        second.hello(attachId = 101L)
        second.next<MediaFrame.VideoConfig>()
        second.send(MediaFrame.Bye(StreamWire.BYE_LEAVING))
        waitFor("explicit detach") { detached.size == 1 }
        assertEquals(listOf(101L), detached.toList())
    }

    @Test
    fun sameIdReconnectWaitsForOldExplicitDetachBeforeAttachingAgain() {
        val s = server()
        val active = java.util.concurrent.atomic.AtomicLong(0L)
        val attached = java.util.concurrent.atomic.AtomicInteger()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val detachEntered = java.util.concurrent.CountDownLatch(1)
        val releaseDetach = java.util.concurrent.CountDownLatch(1)
        s.onGuestAttached = { id ->
            active.set(id)
            attached.incrementAndGet()
            events += "attach:$id"
        }
        s.onGuestDetached = { id ->
            detachEntered.countDown()
            check(releaseDetach.await(3, java.util.concurrent.TimeUnit.SECONDS))
            active.compareAndSet(id, 0L)
            events += "detach:$id"
        }
        val first = Client(s.port)
        first.hello(attachId = 101L)
        first.next<MediaFrame.VideoConfig>()
        first.send(MediaFrame.Bye(StreamWire.BYE_LEAVING))
        assertTrue(detachEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        val second = Client(s.port)
        try {
            assertEquals(StreamWire.STATUS_OK, second.hello(attachId = 101L)!!.status)
            assertEquals(1, attached.get())
            assertEquals(101L, active.get())
            assertFalse(s.stats().guestConnected)
        } finally {
            releaseDetach.countDown()
        }
        second.next<MediaFrame.VideoConfig>()
        assertEquals(2, attached.get())
        assertEquals(101L, active.get())
        assertEquals(listOf("attach:101", "detach:101", "attach:101"), events.toList())
        assertTrue(s.stats().guestConnected)
    }

    @Test
    fun replacementDoesNotDetachNewViewerAndOldPingHasNoReply() {
        val s = server()
        val detached = java.util.Collections.synchronizedList(mutableListOf<Long>())
        s.onGuestDetached = { detached += it }
        val first = Client(s.port)
        first.hello(attachId = 11L)
        first.next<MediaFrame.VideoConfig>()
        val second = Client(s.port)
        second.hello(attachId = 12L)
        second.next<MediaFrame.VideoConfig>()
        runCatching { first.send(MediaFrame.Ping(88, 1L)) }
        runCatching { first.send(MediaFrame.Bye(StreamWire.BYE_LEAVING)) }
        assertEquals(StreamWire.BYE_REPLACED, first.next<MediaFrame.Bye>().reason)
        assertNull(first.readOrNull())
        second.send(MediaFrame.Ping(99, 1L))
        assertEquals(99, second.next<MediaFrame.Pong>().id)
        assertTrue(detached.isEmpty())
        assertTrue(s.stats().guestConnected)
    }

    @Test
    fun formatBundleUsesActualLevelAndOldEpochIdrCannotRecover() {
        val s = server()
        val c = Client(s.port)
        c.hello()
        c.next<MediaFrame.VideoConfig>()
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 2)
        s.onFormat(EncodedFormat(StreamCaps.LEVELS[3], 2_000_000, csd, 42L))
        val info = c.read() as MediaFrame.Info
        assertEquals(3, info.level)
        assertEquals(1280, info.width)
        assertEquals(576, info.height)
        assertEquals(2_000_000, info.bitrate)
        assertArrayEquals(csd, (c.read() as MediaFrame.VideoConfig).csd)
        assertEquals(3, s.stats().level)
        val before = s.framesDropped.get()
        val now = System.nanoTime()
        s.onFrame(EncodedFrame(50, true, now, now, byteArrayOf(1), 41L))
        s.onFrame(EncodedFrame(51, false, now, now, byteArrayOf(1), 42L))
        s.onFrame(EncodedFrame(52, true, now, now, byteArrayOf(1), 42L))
        assertEquals(52, c.next<MediaFrame.Video>().seq)
        assertEquals(before + 2L, s.framesDropped.get())
        s.onFormat(EncodedFormat(StreamCaps.LEVELS[0], 8_000_000, csd, 41L))
        assertEquals(3, s.stats().level)
    }

    @Test
    fun deliveryRejectsWrongEpochDuplicateAndRegressingWindow() {
        val s = server()
        val c = Client(s.port)
        c.hello()
        c.next<MediaFrame.VideoConfig>()
        val report = com.gamebex.client.stream.proto.DeliveryWire.Report(1L, 1, 1_000_000_000L,
            500_000_000L, 1000L, 1, 13_000_000L, 0, 1)
        c.send(MediaFrame.Delivery(report))
        c.send(MediaFrame.Ping(1, 0L))
        c.next<MediaFrame.Pong>()
        assertEquals(13_000, s.stats().guestVideoP95Us)
        assertEquals(0L, s.deliveryRejected.get())
        c.send(MediaFrame.Delivery(report))
        c.send(MediaFrame.Delivery(report.copy(codecEpoch = 2L, seq = 2)))
        c.send(MediaFrame.Delivery(report.copy(seq = 2, endNs = 999_999_999L)))
        c.send(MediaFrame.Ping(2, 0L))
        c.next<MediaFrame.Pong>()
        assertEquals(3L, s.deliveryRejected.get())
        assertEquals(StreamCaps.LEVELS[0].maxBitrate, video.currentBitrate)
    }

    @Test
    fun enabledDeliveryControlConsumesWindowOnceAndStaleCannotRaise() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000_000L)
        val s = server(latencyControl = true, clock = now::get)
        val c = Client(s.port)
        c.hello()
        c.next<MediaFrame.VideoConfig>()
        val report = com.gamebex.client.stream.proto.DeliveryWire.Report(1L, 1, 1_000_000_000L,
            500_000_000L, 1000L, 1, 13_000_000L, 0, 1)
        c.send(MediaFrame.Delivery(report))
        c.send(MediaFrame.Ping(1, 0L))
        c.next<MediaFrame.Pong>()
        Thread.sleep(30)
        assertEquals(8_000_000, video.currentBitrate)
        now.set(2_000_000_000L)
        c.send(MediaFrame.Delivery(report.copy(seq = 2, endNs = 2_000_000_000L)))
        waitFor("delivery bitrate cut") { video.currentBitrate == 6_800_000 }
        now.set(30_000_000_000L)
        c.send(MediaFrame.Ping(2, 0L))
        c.next<MediaFrame.Pong>()
        Thread.sleep(30)
        assertEquals(6_800_000, video.currentBitrate)
    }

    @Test
    fun configAndRetireCountAllQueuedDropsAndTcpAudioFrames() {
        val s = server()
        val c = Client(s.port, receiveBuffer = 1024)
        c.hello()
        c.next<MediaFrame.VideoConfig>()
        video.emit(seq = 1, key = true, size = 1_900_000)
        Thread.sleep(100)
        video.emit(seq = 2, key = false)
        video.emit(seq = 3, key = false)
        s.onFormat(EncodedFormat(StreamCaps.LEVELS[0], 8_000_000, byteArrayOf(1), 2L))
        assertEquals(2L, s.stats().videoConfigDiscarded)
        repeat(25) { s.onPcm(PcmChunk(it, 1L, 240, ByteArray(960))) }
        assertEquals(1200L, s.stats().tcpAudioEvictedFrames)
        val now = System.nanoTime()
        s.onFrame(EncodedFrame(4, true, now, now, byteArrayOf(1), 2L))
        s.onFrame(EncodedFrame(5, false, now, now, byteArrayOf(1), 2L))
        s.close()
        assertTrue(s.stats().videoRetiredDiscarded >= 2L)
        assertTrue(s.stats().tcpAudioEvictedFrames >= 6000L)
        assertTrue(s.stats().framesDropped >= 4L)
    }

    @Test
    fun absentAndZeroEpochVideoIsAccountedButNeverSent() {
        val s = server()
        val c = Client(s.port)
        c.hello()
        c.next<MediaFrame.VideoConfig>()
        val now = System.nanoTime()
        s.onFrame(EncodedFrame(8, true, now, now, byteArrayOf(1), 0L))
        assertEquals(1L, s.stats().videoConfigDiscarded)
        c.send(MediaFrame.Ping(8, 0L))
        assertEquals(8, c.next<MediaFrame.Pong>().id)
    }

    @Test
    fun fallsBackToAFreePort() {
        val holder = java.net.ServerSocket(0)
        try {
            val s = MediaServer(StreamConfig(token = token, guestAddress = "127.0.0.1"),
                queueDeadlineMs = 0, latencyControl = false, videoWriteStallMs = 0)
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
        c.next<MediaFrame.VideoConfig>()
        c.socket.soTimeout = 200
        val nothing = try {
            c.read()
        } catch (_: SocketTimeoutException) {
            null
        }
        assertNull(nothing)
    }
}
