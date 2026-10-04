package com.gamebex.client.stream

import com.gamebex.client.stream.fakes.FakeAudioSource
import com.gamebex.client.stream.fakes.FakeShellComponents
import com.gamebex.client.stream.fakes.FakeVideoSource
import com.gamebex.client.stream.fakes.FakeVirtualPad
import com.gamebex.client.stream.guest.ClockSync
import com.gamebex.client.stream.guest.InputSender
import com.gamebex.client.stream.guest.StreamClient
import com.gamebex.client.stream.proto.MediaFrame
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.stream.shell.MediaServer
import com.gamebex.client.stream.shell.StreamHostSession
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell side (MediaServer, session, input receiver) against the guest
 * side (client, input sender) on loopback, with fake capture and a fake
 * pad: the protocol end to end without a phone.
 */
class StreamLoopbackTest {
    private val token = StreamToken.generate()
    private val pad = FakeVirtualPad()
    private val video = FakeVideoSource(StreamCaps.LEVELS[0])
    private val audio = FakeAudioSource()
    private val events = LinkedBlockingQueue<Any>()
    private val states: MutableList<StreamClient.State> = Collections.synchronizedList(mutableListOf())
    private val clock = ClockSync()
    private lateinit var session: StreamHostSession
    private var client: StreamClient? = null
    private var input: InputSender? = null
    private var inputPort = 0

    @After
    fun tearDown() {
        input?.close()
        client?.close()
        session.stop()
    }

    private fun start() {
        val config = StreamConfig(token = token, guestAddress = "127.0.0.1", mediaPort = 0, inputPort = 0)
        val components = object : com.gamebex.client.stream.shell.ShellComponents by FakeShellComponents(pad = pad, video = video, audio = audio) {
            override fun mediaChannel(config: StreamConfig) = MediaServer(config)
        }
        session = StreamHostSession(config, components, appWatchdog = false)
        val started = session.start()
        assertTrue(started.ok)
        val endpoint = StreamEndpoint("127.0.0.1", started.mediaPort, started.inputPort, token, started.width, started.height, started.audio, "J")
        client = StreamClient(endpoint, object : StreamClient.Listener {
            override fun onState(state: StreamClient.State) {
                states += state
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
            override fun onInfo(info: MediaFrame.Info) = Unit
            override fun onPong(t0: Long, t1: Long, t2: Long, t3: Long) {
                clock.add(t0, t1, t2, t3)
            }
        }, { MediaFrame.Report(0, 0, 0, 0, 0) }).also { it.start() }
        inputPort = started.inputPort
        input = InputSender("127.0.0.1", started.inputPort, token).also { it.start() }
    }

    private inline fun <reified T> next(): T {
        val end = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < end) {
            val e = events.poll(20, TimeUnit.MILLISECONDS) ?: continue
            if (e is T) return e
        }
        throw AssertionError("no ${T::class.simpleName}")
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
    fun videoAudioInputAndTeardownWorkEndToEnd() {
        start()
        waitFor("connected") { synchronized(states) { states.any { it is StreamClient.State.Connected } } }
        waitFor("guest seen by host") { session.status().guestConnected }
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0x01)
        video.sink!!.onConfig(csd)
        assertArrayEquals(csd, next<MediaFrame.VideoConfig>().csd)
        waitFor("keyframe asked for") { video.keyframeRequests >= 1 }
        video.emit(seq = 1, key = true)
        video.emit(seq = 2, key = false)
        assertEquals(1, next<MediaFrame.Video>().seq)
        assertEquals(2, next<MediaFrame.Video>().seq)
        audio.emit(seq = 1)
        assertEquals(1, next<MediaFrame.Audio>().seq)
        waitFor("clock offset") { clock.ready }
        assertTrue(clock.rttNs in 0 until 1_000_000_000L)

        input!!.update(PadState(buttons = PadState.A))
        waitFor("player 2 presses A") { pad.writes.lastOrNull() == PadState(buttons = PadState.A) }
        input!!.update(PadState.NEUTRAL)
        waitFor("player 2 lets go") { pad.writes.lastOrNull() == PadState.NEUTRAL }

        client!!.close()
        waitFor("host sees the guest leave") { !session.status().guestConnected }
        session.stop()
        assertTrue(pad.closed)
        assertEquals(PadState.NEUTRAL, pad.writes.last())
    }

    @Test
    fun inputWithAnotherTokenNeverReachesPlayerTwo() {
        start()
        val stranger = InputSender("127.0.0.1", inputPort, StreamToken.generate())
        stranger.start()
        stranger.update(PadState(buttons = PadState.B))
        Thread.sleep(200)
        stranger.close()
        assertTrue(synchronized(pad.writes) { pad.writes.none { it.buttons and PadState.B != 0 } })
    }
}
