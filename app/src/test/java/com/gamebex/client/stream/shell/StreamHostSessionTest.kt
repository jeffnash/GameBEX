package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamError
import com.gamebex.client.stream.fakes.FakeAudioSource
import com.gamebex.client.stream.fakes.FakeMediaChannel
import com.gamebex.client.stream.fakes.FakeShellComponents
import com.gamebex.client.stream.fakes.FakeVirtualPad
import com.gamebex.client.stream.proto.PadState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Start order, failure mapping, teardown and watchdogs of one shell session. */
class StreamHostSessionTest {
    private val token = "00112233445566778899aabbccddeeff"
    private val now = AtomicLong(1_000_000_000L)
    private val sessions = mutableListOf<StreamHostSession>()

    @After
    fun tearDown() {
        sessions.forEach { it.stop() }
    }

    private fun config(token: String = this.token, guest: String = "127.0.0.1", level: Int = 0, audio: Boolean = true) =
        StreamConfig(token = token, guestAddress = guest, mediaPort = 55441, inputPort = 0, level = level, audio = audio)

    private fun session(
        components: FakeShellComponents,
        config: StreamConfig = config(),
        appWatchdog: Boolean = true,
    ) = StreamHostSession(config, components, clock = { now.get() }, appWatchdog = appWatchdog, watchdogTickMs = 10L)
        .also { sessions += it }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < end) {
            if (check()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun startsEveryPartAndReportsWhatIsBound() {
        val c = FakeShellComponents()
        val started = session(c).start()
        assertTrue(started.ok)
        assertEquals(55441, started.mediaPort)
        assertTrue(started.inputPort > 0)
        assertEquals(1600, started.width)
        assertEquals(720, started.height)
        assertTrue(started.audio)
        assertEquals("fake", started.padBackend)
        assertTrue(c.media.opened)
        assertTrue(c.media.started)
        assertTrue(c.media.audioOn)
        assertEquals(c.video, c.media.control)
        assertEquals(StreamCaps.LEVELS[0].maxBitrate, c.video.currentBitrate)
        assertEquals(c.media, c.video.sink)
        // Audio goes to the media connection while no guest input (and so no UDP route) has arrived yet.
        val chunk = PcmChunk(1, 0L, 240, ByteArray(960))
        c.audio!!.sink!!.onPcm(chunk)
        assertTrue(c.media.pcm.contains(chunk))
        assertEquals(1, c.padOpens)
    }

    @Test
    fun rsoundReadinessAndFallbackStatusComeFromTheStartedAudioSource() {
        val c = FakeShellComponents()
        var selected: StreamConfig? = null
        var launched = 0
        var stopped = false
        var mode = "rsound-waiting"
        var fallback: String? = null
        val sound = object : AudioSource {
            override val rsoundReady: Boolean get() = true
            override val audioMode: String get() = mode
            override val fallbackReason: String? get() = fallback
            override fun start(sink: AudioSink): Boolean = true
            override fun gameLaunched() { launched += 1 }
            override fun stop() { stopped = true }
        }
        val components = object : ShellComponents by c {
            override fun audioSource(config: StreamConfig): AudioSource {
                selected = config
                return sound
            }
        }
        val wanted = config().copy(rsound = true)
        val s = StreamHostSession(components = components, config = wanted, appWatchdog = false).also { sessions += it }
        val started = s.start()
        assertTrue(started.ok)
        assertTrue(started.audio)
        assertTrue(started.rsound)
        assertEquals(wanted, selected)
        assertEquals(0, launched)
        assertEquals("rsound-waiting", s.status().audioMode)
        s.gameLaunched()
        assertEquals(1, launched)
        mode = "loopback"
        fallback = "RetroArch did not connect within 5000 ms"
        assertEquals("loopback", s.status().audioMode)
        assertEquals(fallback, s.status().audioFallback)
        s.stop()
        s.gameLaunched()
        assertEquals(1, launched)
        assertTrue(stopped)
    }

    @Test
    fun ordinaryAudioNeverAdvertisesAnRsoundListener() {
        val c = FakeShellComponents()
        val s = session(c, config().copy(rsound = true))
        val started = s.start()
        assertTrue(started.audio)
        assertFalse(started.rsound)
        assertEquals("loopback", s.status().audioMode)
        assertNull(s.status().audioFallback)
    }

    @Test
    fun disabledAudioDoesNotEvenSelectAnRsoundSource() {
        val c = FakeShellComponents()
        var selected = false
        val components = object : ShellComponents by c {
            override fun audioSource(config: StreamConfig): AudioSource? {
                selected = true
                return c.audio
            }
        }
        val s = StreamHostSession(config(audio = false).copy(rsound = true), components, appWatchdog = false)
            .also { sessions += it }
        val started = s.start()
        assertTrue(started.ok)
        assertFalse(started.audio)
        assertFalse(started.rsound)
        assertFalse(selected)
        assertEquals("none", s.status().audioMode)
    }

    @Test
    fun startsAtTheRequestedLevel() {
        val c = FakeShellComponents()
        val started = session(c, config(level = 2)).start()
        assertTrue(started.ok)
        assertEquals(960, started.width)
        assertEquals(432, started.height)
        assertEquals(StreamCaps.LEVELS[2], c.video.level)
    }

    @Test
    fun appCaptureHandsTheEncoderSurfaceToTheAppsListenerEvenWhenItRegistersAfterStart() {
        val c = FakeShellComponents()
        val s = session(c, config(level = 1).copy(appCapture = true))
        assertTrue(s.start().ok)
        val got = mutableListOf<Pair<Int, Int>>()
        s.setCaptureListener { _, w, h -> got += w to h }
        assertEquals(listOf(1280 to 576), got)
        c.video.captureTarget?.onSurface(null, 1600, 720)
        assertEquals(listOf(1280 to 576, 1600 to 720), got)
    }

    @Test
    fun aDetachedCaptureListenerGetsNothingMore() {
        val c = FakeShellComponents()
        val s = session(c, config().copy(appCapture = true))
        assertTrue(s.start().ok)
        var calls = 0
        s.setCaptureListener { _, _, _ -> calls += 1 }
        s.setCaptureListener(null)
        c.video.captureTarget?.onSurface(null, 1280, 576)
        assertEquals(1, calls)
    }

    @Test
    fun captureRelayIsRequiredEvenWhenConfigDisablesAppCapture() {
        val c = FakeShellComponents()
        val s = session(c, config(level = 1).copy(appCapture = false))
        assertTrue(s.start().ok)
        assertNotNull(c.video.captureTarget)
        val got = mutableListOf<Pair<Int, Int>>()
        s.setCaptureListener { _, w, h -> got += w to h }
        assertEquals(listOf(1280 to 576), got)
    }

    @Test
    fun decliningCaptureStopsAllStreamResources() {
        val c = FakeShellComponents()
        val s = session(c, config().copy(appCapture = true))
        assertTrue(s.start().ok)
        s.stop()
        assertTrue(s.stopped)
        assertTrue(c.video.stopped)
        assertTrue(c.pad!!.closed)
    }

    @Test
    fun runsWithoutSoundWhenAudioIsRefused() {
        val c = FakeShellComponents(audio = FakeAudioSource(works = false))
        val started = session(c).start()
        assertTrue(started.ok)
        assertFalse(started.audio)
        assertFalse(c.media.audioOn)
    }

    @Test
    fun runsWithoutSoundWhenTheGuestAskedForNone() {
        val c = FakeShellComponents()
        val started = session(c, config(audio = false)).start()
        assertTrue(started.ok)
        assertFalse(started.audio)
        assertNull(c.audio!!.sink)
    }

    @Test
    fun rejectsABadTokenBeforeTouchingAnything() {
        val c = FakeShellComponents()
        val started = session(c, config(token = "nope")).start()
        assertFalse(started.ok)
        assertEquals(StreamError.BAD_CONFIG, started.error)
        assertEquals(0, c.padOpens)
    }

    @Test
    fun rejectsAPublicGuestAddress() {
        val c = FakeShellComponents()
        val started = session(c, config(guest = "8.8.8.8")).start()
        assertEquals(StreamError.BAD_CONFIG, started.error)
        assertEquals(0, c.padOpens)
    }

    @Test
    fun rejectsAnUnknownLevel() {
        val started = session(FakeShellComponents(), config(level = 9)).start()
        assertEquals(StreamError.BAD_CONFIG, started.error)
    }

    @Test
    fun noPadIsAUhidError() {
        val c = FakeShellComponents(pad = null)
        val started = session(c).start()
        assertEquals(StreamError.UHID, started.error)
        assertFalse(c.media.opened)
    }

    @Test
    fun aNetworkFailureRemovesThePad() {
        val pad = FakeVirtualPad()
        val media = FakeMediaChannel().apply { failOpen = true }
        val c = FakeShellComponents(pad = pad, media = media)
        val s = session(c)
        val started = s.start()
        assertEquals(StreamError.NETWORK, started.error)
        assertTrue(pad.closed)
        assertTrue(media.closed)
        assertTrue(s.stopped)
        assertEquals(StreamError.NETWORK, s.status().error)
    }

    @Test
    fun stopTearsDownEverythingOnce() {
        val c = FakeShellComponents()
        val s = session(c)
        val calls = AtomicInteger()
        s.onStopped = { calls.incrementAndGet() }
        s.start()
        s.stop()
        s.stop()
        assertTrue(c.video.stopped)
        assertTrue(c.audio!!.stopped)
        assertTrue(c.media.closed)
        assertTrue(c.pad!!.closed)
        assertEquals(PadState.NEUTRAL, c.pad.writes.last())
        assertEquals(1, calls.get())
        assertFalse(s.status().running)
    }

    @Test
    fun statusCopiesTheMediaStats() {
        val c = FakeShellComponents()
        c.media.statsValue = MediaStats(true, 59, 7_500_000, 1, 1234, 5, 41_000, 0L)
        val s = session(c)
        s.start()
        val status = s.status()
        assertTrue(status.running)
        assertTrue(status.guestConnected)
        assertEquals(59, status.fps)
        assertEquals(7_500_000, status.bitrate)
        assertEquals(1, status.level)
        assertEquals(1234L, status.framesSent)
        assertEquals(5L, status.framesDropped)
        assertEquals(41_000, status.guestVideoP95Us)
        assertTrue(status.audio)
        assertEquals("loopback", status.audioMode)
        assertNull(status.audioFallback)
    }

    @Test
    fun stopsWhenTheAppStopsPolling() {
        val c = FakeShellComponents()
        c.media.statsValue = MediaStats(true, 60, 8_000_000, 0, 0, 0, 0, now.get())
        val s = session(c)
        s.start()
        now.addAndGet((StreamCaps.APP_SILENT_STOP_MS - 1_000L) * 1_000_000L)
        s.status()
        Thread.sleep(50)
        assertFalse(s.stopped)
        now.addAndGet((StreamCaps.APP_SILENT_STOP_MS + 1_000L) * 1_000_000L)
        waitFor("app watchdog teardown") { c.pad!!.closed }
        assertTrue(s.stopped)
    }

    @Test
    fun stopsWhenNoGuestShowsUp() {
        val c = FakeShellComponents()
        val s = session(c, appWatchdog = false)
        s.start()
        now.addAndGet((StreamCaps.GUEST_ABSENT_STOP_MS - 1_000L) * 1_000_000L)
        Thread.sleep(50)
        assertFalse(s.stopped)
        now.addAndGet(2_000L * 1_000_000L)
        waitFor("guest watchdog teardown") { c.media.closed }
        assertTrue(s.stopped)
    }

    @Test
    fun aConnectedGuestKeepsTheStreamAlive() {
        val c = FakeShellComponents()
        c.media.statsValue = MediaStats(true, 60, 8_000_000, 0, 0, 0, 0, now.get())
        val s = session(c, appWatchdog = false)
        s.start()
        now.addAndGet((StreamCaps.GUEST_ABSENT_STOP_MS * 3) * 1_000_000L)
        Thread.sleep(50)
        assertFalse(s.stopped)
    }

    @Test
    fun aFailedVideoSourceStopsTheStream() {
        val c = FakeShellComponents()
        c.media.statsValue = MediaStats(true, 60, 8_000_000, 0, 0, 0, 0, now.get())
        val s = session(c, appWatchdog = false)
        s.start()
        c.media.statsValue = c.media.statsValue.copy(failure = "encoder died")
        waitFor("failure teardown") { c.pad!!.closed }
        assertTrue(s.stopped)
        assertEquals(StreamError.ENCODER, s.status().error)
    }

    @Test
    fun guestAddressesMustBePrivateIpv4() {
        listOf("127.0.0.1", "10.0.0.8", "172.16.4.2", "172.31.255.1", "10.42.1.20", "169.254.3.3").forEach {
            assertTrue(it, StreamHostSession.validGuest(it))
        }
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "", "1.2.3", "1.2.3.4.5", "256.1.1.1", "::1", "10.0.0.-1", "10.0.0.0001").forEach {
            assertFalse(it, StreamHostSession.validGuest(it))
        }
    }

    @Test
    fun shellMainParsesItsFlags() {
        val opts = StreamShellMain.parse(arrayOf("--token", token, "--guest", "127.0.0.1", "--no-audio", "--seconds", "30", "--probe"))
        assertEquals(token, opts["token"])
        assertEquals("127.0.0.1", opts["guest"])
        assertEquals("", opts["no-audio"])
        assertEquals("30", opts["seconds"])
        assertTrue(opts.containsKey("probe"))
    }
}
