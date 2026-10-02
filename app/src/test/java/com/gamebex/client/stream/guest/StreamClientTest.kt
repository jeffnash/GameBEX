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
        return StreamClient(endpoint, listener, { MediaFrame.Report(1, 1, 1, 30_000, 0) }, reconnectWindowMs = windowMs)
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
            out.write(StreamHandshake.encodeWelcome(Welcome(1, status, 1600, 720, 48_000, 2, System.nanoTime())))
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
}
