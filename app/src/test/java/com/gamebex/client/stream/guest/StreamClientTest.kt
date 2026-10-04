package com.gamebex.client.stream.guest

import com.gamebex.client.stream.StreamEndpoint
import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.proto.MediaFrame
import com.gamebex.client.stream.proto.MediaFrames
import com.gamebex.client.stream.proto.StreamHandshake
import com.gamebex.client.stream.proto.StreamWire
import com.gamebex.client.stream.proto.Welcome
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guest media client against a scripted loopback host. */
class StreamClientTest {
    private val token = "00112233445566778899aabbccddeeff"
    private val server = ServerSocket(0)
    private val clients = mutableListOf<StreamClient>()
    private val states: MutableList<StreamClient.State> = Collections.synchronizedList(mutableListOf())
    private val events = LinkedBlockingQueue<Any>()
    private val receivedVideos = LinkedBlockingQueue<Pair<Int, Long>>()

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.close()
    }

    private val listener = object : StreamClient.Listener {
        override fun onState(state: StreamClient.State) {
            states += state
            events += state
        }
        override fun onConfig(csd: ByteArray) {
            events += MediaFrame.VideoConfig(csd)
        }
        override fun onVideo(frame: MediaFrame.Video, receivedNs: Long) {
            events += frame
        }
        override fun onVideo(frame: MediaFrame.Video, receivedNs: Long, codecEpoch: Long) {
            receivedVideos += frame.seq to codecEpoch
            onVideo(frame, receivedNs)
        }
        override fun onAudio(frame: MediaFrame.Audio) {
            events += frame
        }
        override fun onInfo(info: MediaFrame.Info) {
            events += info
        }
        override fun onPong(t0: Long, t1: Long, t2: Long, t3: Long) {
            events += MediaFrame.Pong(0, t0, t1, t2)
        }
    }

    private fun client(windowMs: Long = 10_000L): StreamClient {
        val endpoint = StreamEndpoint("127.0.0.1", server.localPort, 0, token, 1600, 720, true, "J")
        return StreamClient(endpoint, listener, reconnectWindowMs = windowMs)
            .also {
                clients += it
                it.start()
            }
    }

    /** One accepted guest on the host side. */
    private inner class Host(val socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val out = socket.getOutputStream()

        fun handshake(status: Int = StreamWire.STATUS_OK): ByteArray {
            val hello = StreamHandshake.decodeHello(StreamHandshake.readExactly(input, StreamWire.HELLO_SIZE))!!
            out.write(StreamHandshake.encodeWelcome(Welcome(StreamWire.VERSION, status, 1600, 720, 48_000, 2, System.nanoTime())))
            return hello.token
        }

        fun send(frame: MediaFrame) = out.write(MediaFrames.encode(frame))

        fun next(): MediaFrame = MediaFrames.read(input)!!
    }

    private fun accept(): Host = Host(server.accept().apply { soTimeout = 3_000 })

    private inline fun <reified T> await(): T {
        val end = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < end) {
            val e = events.poll(50, TimeUnit.MILLISECONDS) ?: continue
            if (e is T) return e
        }
        throw AssertionError("no ${T::class.simpleName}")
    }

    @Test
    fun sendsTheTokenAndReportsConnected() {
        client()
        val host = accept()
        assertArrayEquals(StreamToken.bytes(token), host.handshake())
        val connected = await<StreamClient.State.Connected>()
        assertEquals(1600, connected.welcome.width)
        assertEquals(StreamClient.State.Connecting, states.first())
    }

    @Test
    fun deliversConfigVideoAudioAndInfo() {
        client()
        val host = accept()
        host.handshake()
        host.send(MediaFrame.VideoConfig(byteArrayOf(1, 2, 3)))
        host.send(MediaFrame.Video(true, 7, 10L, 20L, 30L, byteArrayOf(9)))
        host.send(MediaFrame.Audio(3, 40L, 480, ByteArray(1_920)))
        host.send(MediaFrame.Info(1, 1280, 576, 4_000_000))
        assertArrayEquals(byteArrayOf(1, 2, 3), await<MediaFrame.VideoConfig>().csd)
        assertEquals(7, await<MediaFrame.Video>().seq)
        assertEquals(3, await<MediaFrame.Audio>().seq)
        assertEquals(1280, await<MediaFrame.Info>().width)
    }

    @Test
    fun pingsRightAfterConnectingAndPassesPongsOn() {
        client()
        val host = accept()
        host.handshake()
        val ping = host.next()
        assertTrue(ping is MediaFrame.Ping)
        val p = ping as MediaFrame.Ping
        host.send(MediaFrame.Pong(p.id, p.t0, 100L, 200L))
        val pong = await<MediaFrame.Pong>()
        assertEquals(p.t0, pong.t0)
        assertEquals(200L, pong.t2)
    }

    @Test
    fun keyframeRequestsReachTheHost() {
        val c = client()
        val host = accept()
        host.handshake()
        await<StreamClient.State.Connected>()
        c.requestKeyframe()
        var seen = false
        repeat(20) { if (!seen && host.next() == MediaFrame.KeyframeRequest) seen = true }
        assertTrue(seen)
    }

    @Test
    fun aRefusedTokenEndsTheStream() {
        client()
        accept().handshake(StreamWire.STATUS_BAD_TOKEN)
        assertEquals(StreamClient.EndReason.BAD_TOKEN, await<StreamClient.State.Ended>().reason)
    }

    @Test
    fun byeFromTheHostEndsTheStream() {
        client()
        val host = accept()
        host.handshake()
        host.send(MediaFrame.Bye(StreamWire.BYE_STOPPING))
        assertEquals(StreamClient.EndReason.HOST_STOPPED, await<StreamClient.State.Ended>().reason)
    }

    @Test
    fun beingReplacedEndsTheStream() {
        client()
        val host = accept()
        host.handshake()
        host.send(MediaFrame.Bye(StreamWire.BYE_REPLACED))
        assertEquals(StreamClient.EndReason.REPLACED, await<StreamClient.State.Ended>().reason)
    }

    @Test
    fun aDroppedConnectionReconnects() {
        client()
        val first = accept()
        first.handshake()
        await<StreamClient.State.Connected>()
        first.socket.close()
        await<StreamClient.State.Reconnecting>()
        val second = accept()
        second.handshake()
        assertNotNull(await<StreamClient.State.Connected>())
    }

    @Test
    fun givesUpAfterTheReconnectWindow() {
        client(windowMs = 300L)
        val first = accept()
        first.handshake()
        await<StreamClient.State.Connected>()
        server.close()
        first.socket.close()
        assertEquals(StreamClient.EndReason.LOST, await<StreamClient.State.Ended>().reason)
    }

    @Test
    fun leavingSaysByeToTheHost() {
        val c = client()
        val host = accept()
        host.handshake()
        await<StreamClient.State.Connected>()
        c.close()
        var reason = -1
        repeat(20) {
            if (reason < 0) {
                val f = runCatching { host.next() }.getOrNull()
                if (f is MediaFrame.Bye) reason = f.reason
            }
        }
        assertEquals(StreamWire.BYE_LEAVING, reason)
    }

    private class ManualTicker {
        val ready = CountDownLatch(1)
        val action = AtomicReference<(() -> Unit)?>()
        val retired = LinkedBlockingQueue<() -> Unit>()
        fun create(tick: () -> Unit): AutoCloseable {
            action.set(tick)
            ready.countDown()
            return AutoCloseable { retired += tick; action.compareAndSet(tick, null) }
        }
        fun tick() = action.get()!!.invoke()
    }

    private fun manualClient(now: AtomicLong, timer: ManualTicker, pending: () -> Int = { 0 },
        counters: (String, Long) -> Unit = { _, _ -> }): StreamClient {
        val endpoint = StreamEndpoint("127.0.0.1", server.localPort, 0, token, 1600, 720, true, "J")
        return StreamClient(endpoint, listener, decoderPending = pending, clock = now::get,
            onCounter = counters, tickerFactory = timer::create).also { clients += it; it.start() }
    }

    private fun Host.nextDelivery(): MediaFrame.Delivery {
        repeat(20) {
            val frame = next()
            if (frame is MediaFrame.Delivery) return frame
        }
        throw AssertionError("no delivery")
    }

    @Test
    fun deliveryAtDeterministic500msEvenWhenReaderHasNoIncomingFrames() {
        val now = AtomicLong(10_000_000_000L)
        val timer = ManualTicker()
        manualClient(now, timer, pending = { 7 })
        val host = accept()
        host.handshake()
        await<StreamClient.State.Connected>()
        assertTrue(timer.ready.await(1, TimeUnit.SECONDS))
        host.send(MediaFrame.VideoConfig(byteArrayOf(1)))
        await<MediaFrame.VideoConfig>()
        now.addAndGet(499_999_999L)
        timer.tick()
        assertTrue(host.next() is MediaFrame.Ping)
        host.socket.soTimeout = 100
        assertTrue(runCatching { host.next() }.isFailure)
        host.socket.soTimeout = 3_000
        now.incrementAndGet()
        timer.tick()
        val report = host.nextDelivery().report
        assertEquals(1L, report.codecEpoch)
        assertEquals(500_000_000L, report.spanNs)
        assertEquals(0, report.frames)
        assertEquals(0L, report.bytes)
        assertEquals(-1L, report.relativeP95Ns)
        assertEquals(7, report.pending)
        now.addAndGet(500_000_000L)
        timer.tick()
        assertEquals(2, host.nextDelivery().report.seq)
    }

    @Test
    fun videoIsDeliveredOnlyAfterConfigAndEpochIsPerTcpConnection() {
        val now = AtomicLong(10_000_000_000L)
        val timer = ManualTicker()
        val counters = LinkedBlockingQueue<String>()
        manualClient(now, timer, counters = { name, _ -> counters += name })
        val first = accept()
        first.handshake()
        await<StreamClient.State.Connected>()
        assertTrue(timer.ready.await(1, TimeUnit.SECONDS))
        first.send(MediaFrame.Video(true, 99, 1L, 2L, 3L, byteArrayOf(9)))
        first.send(MediaFrame.Info(1, 1600, 720, 4_000_000))
        await<MediaFrame.Info>()
        assertEquals("v_before_config", counters.poll(1, TimeUnit.SECONDS))
        assertTrue(receivedVideos.isEmpty())
        first.send(MediaFrame.VideoConfig(byteArrayOf(1)))
        await<MediaFrame.VideoConfig>()
        first.send(MediaFrame.Video(true, 1, 1L, 2L, 3L, byteArrayOf(9)))
        assertEquals(1, await<MediaFrame.Video>().seq)
        assertEquals(1 to 1L, receivedVideos.poll(1, TimeUnit.SECONDS))
        now.addAndGet(500_000_000L)
        timer.tick()
        assertEquals(1L, first.nextDelivery().report.codecEpoch)
        first.send(MediaFrame.VideoConfig(byteArrayOf(2)))
        await<MediaFrame.VideoConfig>()
        now.addAndGet(500_000_000L)
        timer.tick()
        val changed = first.nextDelivery().report
        assertEquals(2L, changed.codecEpoch)
        assertEquals(2, changed.seq)
        first.socket.close()
        await<StreamClient.State.Reconnecting>()
        val retired = timer.retired.poll(1, TimeUnit.SECONDS)!!
        val second = accept()
        second.handshake()
        await<StreamClient.State.Connected>()
        second.send(MediaFrame.VideoConfig(byteArrayOf(3)))
        await<MediaFrame.VideoConfig>()
        second.send(MediaFrame.Info(1, 1600, 720, 4_000_000))
        await<MediaFrame.Info>()
        now.addAndGet(500_000_000L)
        retired()
        timer.tick()
        val reconnected = second.nextDelivery().report
        assertEquals(1L, reconnected.codecEpoch)
        assertEquals(1, reconnected.seq)
        assertEquals(0, reconnected.frames)
        assertEquals(0L, reconnected.bytes)
    }
}
