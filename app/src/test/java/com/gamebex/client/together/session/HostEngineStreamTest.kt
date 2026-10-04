package com.gamebex.client.together.session

import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.stream.P2Prep
import com.gamebex.client.stream.StreamError
import com.gamebex.client.stream.StreamStarted
import com.gamebex.client.stream.StreamPlayer2
import com.gamebex.client.stream.StreamStatus
import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.fakes.FakeStreamHost
import com.gamebex.client.stream.fakes.FakeStreamPlayer2
import com.gamebex.client.stream.p2.StreamRaConfig
import com.gamebex.client.stream.proto.StreamWire
import com.gamebex.client.together.ByeReason
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.Role
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.TogetherAlert
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherLaunch
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.session.fakes.FakeDiscovery
import com.gamebex.client.together.session.fakes.FakeEmulators
import com.gamebex.client.together.session.fakes.FakeLibrary
import com.gamebex.client.together.session.fakes.FakeLinkTransport
import com.gamebex.client.together.session.fakes.FakePlatform
import com.gamebex.client.together.session.fakes.LinkPair
import com.gamebex.client.together.session.fakes.engineTestScope
import com.gamebex.client.together.session.fakes.sampleGame
import com.gamebex.client.together.session.fakes.sampleOffer
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host half of a STREAM session. */
class HostEngineStreamTest {
    private val n64 = GameEntity(id = 1, title = "Mario Kart 64", folder = "n64", volumeId = "v", relPath = "mk64.z64", fileName = "mk64.z64", sizeBytes = 1, mtimeMs = 1, ext = "z64")
    private val n64Offer = OfferedGame(title = "Mario Kart 64", folder = "n64", fileName = "mk64.z64", fileSize = 1)

    private class Harness(
        val engine: HostEngine,
        val transport: FakeLinkTransport,
        val discovery: FakeDiscovery,
        val emulators: RecordingEmulators,
        val stream: FakeStreamHost,
        val player2: FakeStreamPlayer2,
        val events: MutableList<String>,
        val platform: FakePlatform,
    )

    /** Records the order of stream start and emulator launch. */
    private class RecordingEmulators(val events: MutableList<String>, val inner: FakeEmulators = FakeEmulators()) : TogetherEmulators by inner {
        var onLaunch: ((TogetherLaunch) -> Unit)? = null
        var launchFailure: Throwable? = null

        override suspend fun launch(req: TogetherLaunch): EmuRouter.Outcome {
            events += "launch"
            onLaunch?.invoke(req)
            launchFailure?.let { throw it }
            return inner.launch(req)
        }
    }

    private fun harness(
        scope: TestScope,
        game: GameEntity = n64,
        offer: OfferedGame = n64Offer,
        stream: FakeStreamHost = FakeStreamHost(),
        player2: FakeStreamPlayer2 = FakeStreamPlayer2(P2Prep.RetroArchConfig(File("/sdcard/GameBEX/ra/retroarch-stream.cfg"))),
        modes: ((GameEntity) -> List<TogetherMode>)? = null,
        platform: FakePlatform = FakePlatform(),
        audioOverride: String? = null,
        captureGate: CompletableDeferred<Unit>? = null,
    ): Harness {
        val engineScope = scope.engineTestScope()
        val events = mutableListOf<String>()
        val recordingStream = object : com.gamebex.client.stream.StreamHost by stream {
            override suspend fun start(config: com.gamebex.client.stream.StreamConfig): StreamStarted {
                events += "stream"
                return stream.start(config)
            }

            override suspend fun gameLaunched() {
                events += "gameLaunched"
                stream.gameLaunched()
            }

            override suspend fun attachCapture() {
                events += "capture"
                captureGate?.await()
                stream.attachCapture()
            }
        }
        val recordingPlayer2 = object : StreamPlayer2 by player2 {
            override suspend fun prepare(game: GameEntity, emu: Emu, rsoundReady: Boolean): P2Prep {
                if (rsoundReady) events += "rsound"
                return player2.prepare(game, emu, rsoundReady)
            }
        }
        val transport = FakeLinkTransport()
        val discovery = FakeDiscovery()
        val emulators = RecordingEmulators(events)
        val engine = HostEngine(
            transport,
            emulators,
            FakeLibrary(gameValue = game, offerValue = offer),
            discovery,
            platform,
            TogetherTiming(helloMs = 50, byeMs = 50),
            engineScope,
            engineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
            recordingStream,
            recordingPlayer2,
            modes ?: com.gamebex.client.together.TogetherSupport::modesFor,
            { audioOverride },
        )
        return Harness(engine, transport, discovery, emulators, stream, player2, events, platform)
    }

    private suspend fun Harness.join(scope: TestScope, streamProto: Int? = StreamWire.VERSION): LinkPair {
        val pair = transport.pair()
        pair.guest.send(LinkMsg.Hello(TOGETHER_PROTO, Role.GUEST, "guest-device", "Sam", "1.0", streamProto = streamProto))
        scope.advanceUntilIdle()
        return pair
    }

    private suspend fun Harness.startGame(scope: TestScope): LinkPair {
        engine.hostGame(1)
        scope.advanceUntilIdle()
        val pair = join(scope)
        pair.guest.send(LinkMsg.GuestState(discovery.advertisements.last().sessionId, GuestPhase.READY))
        scope.advanceUntilIdle()
        engine.start()
        scope.runCurrent()
        scope.advanceTimeBy(100)
        scope.runCurrent()
        return pair
    }

    @Test
    fun theStreamStartsBeforeTheEmulatorAndTheGuestGetsStreamReady() = runTest {
        val h = harness(this)
        val pair = h.startGame(this)
        assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
        assertEquals(listOf("stream", "launch", "gameLaunched", "capture"), h.events)
        val config = h.stream.configs.single()
        assertEquals("10.42.1.21", config.guestAddress)
        assertTrue(StreamToken.isValid(config.token))
        assertEquals(File("/sdcard/GameBEX/ra/retroarch-stream.cfg"), h.emulators.inner.launches.single().p2Config)
        assertNull(h.emulators.inner.launches.single().core)
        val sent = pair.host.sent
        val launchAt = sent.indexOfFirst { it is LinkMsg.Launch }
        val readyAt = sent.indexOfFirst { it is LinkMsg.StreamReady }
        assertTrue(launchAt in 0 until readyAt)
        val ready = sent[readyAt] as LinkMsg.StreamReady
        assertEquals(config.token, ready.token)
        assertEquals(55441, ready.mediaPort)
        assertEquals(55442, ready.inputPort)
        assertEquals(StreamWire.VERSION, ready.proto)
        assertEquals(TogetherCopy.hostStreamWaiting, h.engine.view.value?.streamLine)
        assertEquals(TogetherMode.STREAM, (sent.first { it is LinkMsg.Offer } as LinkMsg.Offer).mode)
        assertNull((sent.first { it is LinkMsg.Offer } as LinkMsg.Offer).game.core)
        assertEquals(StreamWire.VERSION, (sent.first { it is LinkMsg.Hello } as LinkMsg.Hello).streamProto)
    }

    @Test
    fun retroArchOnlyEnablesRSoundAfterTheListenerIsReady() = runTest {
        val tmp = Files.createTempDirectory("gbx-stream-ready").toFile()
        try {
            val p2 = FakeStreamPlayer2().apply {
                prepareHandler = { _, _, ready ->
                    P2Prep.RetroArchConfig(StreamRaConfig.write(tmp, rsoundReady = ready)!!)
                }
            }
            val host = FakeStreamHost().apply { startValue = startValue.copy(rsound = true) }
            val h = harness(this, stream = host, player2 = p2)
            h.emulators.onLaunch = { req ->
                val text = req.p2Config!!.readText()
                assertTrue(text.contains("audio_driver = \"rsound\""))
                assertTrue(text.contains("audio_device = \"127.0.0.1\""))
            }
            h.startGame(this)
            assertEquals(listOf("stream", "rsound", "launch", "gameLaunched", "capture"), h.events)
            assertTrue(h.stream.configs.single().rsound)
            assertEquals(listOf(false, true), p2.audioReadiness)
            assertEquals(listOf(n64.id to Emu.RETROARCH, n64.id to Emu.RETROARCH), p2.prepared)
            assertEquals(StreamRaConfig.fileUnder(tmp), h.emulators.inner.launches.single().p2Config)
            assertEquals(1, h.stream.gameLaunches)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun anUnavailableRSoundListenerKeepsTheOriginalOpenSlConfig() = runTest {
        val tmp = Files.createTempDirectory("gbx-stream-open-sl").toFile()
        try {
            val p2 = FakeStreamPlayer2().apply {
                prepareHandler = { _, _, ready ->
                    P2Prep.RetroArchConfig(StreamRaConfig.write(tmp, rsoundReady = ready)!!)
                }
            }
            val h = harness(this, player2 = p2, audioOverride = "rsound")
            h.emulators.onLaunch = { req ->
                val text = req.p2Config!!.readText()
                assertTrue(text.contains("audio_driver = \"opensl\""))
                assertTrue(text.contains("audio_latency = \"32\""))
                assertFalse(text.contains("\"127.0.0.1\""))
            }
            h.startGame(this)
            assertTrue(h.stream.configs.single().rsound)
            assertFalse(h.stream.startValue.rsound)
            assertEquals(listOf(false), p2.audioReadiness)
            assertEquals(listOf("stream", "launch", "gameLaunched", "capture"), h.events)
            assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun aBlankAudioOverrideStillRequestsRSoundForRetroArch() = runTest {
        val host = FakeStreamHost().apply { startValue = startValue.copy(rsound = true) }
        val h = harness(this, stream = host, audioOverride = "")
        h.startGame(this)
        assertTrue(h.stream.configs.single().rsound)
        assertEquals(listOf(false, true), h.player2.audioReadiness)
    }

    @Test
    fun failedStreamStartNeverRewritesEvenIfItClaimsRSoundReadiness() = runTest {
        val h = harness(this, stream = FakeStreamHost(startValue = StreamStarted(ok = false, rsound = true, error = StreamError.UHID)))
        h.startGame(this)
        assertEquals(listOf(false), h.player2.audioReadiness)
        assertEquals(listOf("stream"), h.events)
        assertEquals(0, h.stream.gameLaunches)
        assertTrue(h.emulators.inner.launches.isEmpty())
    }

    @Test
    fun theOpenSlOverrideNeverRequestsOrEnablesRSound() = runTest {
        val host = FakeStreamHost().apply { startValue = startValue.copy(rsound = true) }
        val h = harness(this, stream = host, audioOverride = "opensl")
        h.startGame(this)
        assertFalse(h.stream.configs.single().rsound)
        assertEquals(listOf(false), h.player2.audioReadiness)
        assertEquals(listOf("stream", "launch", "gameLaunched", "capture"), h.events)
    }

    @Test
    fun nonRetroArchEmulatorsNeverRequestRSoundEvenWithTheOldDebugOverride() = runTest {
        for (emu in listOf(Emu.DOLPHIN, Emu.NETHER, Emu.PPSSPP)) {
            val game = n64.copy(emulatorOverride = emu.pkg)
            val host = FakeStreamHost().apply { startValue = startValue.copy(rsound = true) }
            val h = harness(this, game = game, stream = host, player2 = FakeStreamPlayer2(), audioOverride = "rsound", modes = { listOf(TogetherMode.STREAM) })
            h.startGame(this)
            assertFalse(h.stream.configs.single().rsound)
            assertEquals(listOf(game.id to emu), h.player2.prepared)
            assertEquals(listOf(false), h.player2.audioReadiness)
            assertNull(h.emulators.inner.launches.single().p2Config)
            h.engine.end()
            advanceUntilIdle()
        }
    }

    @Test
    fun theConnectionTimerStartsBeforeShareConsentCompletes() = runTest {
        val captureGate = CompletableDeferred<Unit>()
        val h = harness(this, captureGate = captureGate)
        h.startGame(this)
        assertEquals(1, h.stream.gameLaunches)
        assertEquals(0, h.stream.captureAttaches)
        assertEquals(listOf("stream", "launch", "gameLaunched", "capture"), h.events)
        captureGate.complete(Unit)
        runCurrent()
        assertEquals(1, h.stream.captureAttaches)
    }

    @Test
    fun theConnectionTimerWaitsForSuccessfulEmulatorLaunch() = runTest {
        val h = harness(this)
        val launchGate = CompletableDeferred<Unit>()
        h.emulators.inner.launchGate = launchGate
        h.startGame(this)
        assertEquals(0, h.stream.gameLaunches)
        assertEquals(0, h.stream.captureAttaches)
        launchGate.complete(Unit)
        runCurrent()
        assertEquals(1, h.stream.gameLaunches)
        assertEquals(1, h.stream.captureAttaches)
    }

    @Test
    fun aFailedReadyConfigRewriteStopsTheNewStreamAndNeverLaunches() = runTest {
        val p2 = FakeStreamPlayer2(P2Prep.RetroArchConfig(File("/sdcard/GameBEX/ra/retroarch-stream.cfg"))).apply {
            prepareHandler = { _, _, ready -> if (ready) P2Prep.Failed("Config rewrite failed") else prep }
        }
        val host = FakeStreamHost().apply { startValue = startValue.copy(rsound = true) }
        val h = harness(this, stream = host, player2 = p2)
        val pair = h.startGame(this)
        assertEquals(listOf("stream", "rsound"), h.events)
        assertEquals(listOf(false, true), p2.audioReadiness)
        assertEquals(1, h.stream.stops)
        assertEquals(0, h.stream.gameLaunches)
        assertTrue(h.emulators.inner.launches.isEmpty())
        assertTrue(pair.host.sent.none { it is LinkMsg.Launch || it is LinkMsg.StreamReady })
        assertEquals(TogetherCopy.launchFailed("Config rewrite failed"), h.engine.view.value?.message)
    }

    @Test
    fun aThrowingReadyConfigRewriteStillStopsTheNewStream() = runTest {
        val p2 = FakeStreamPlayer2(P2Prep.RetroArchConfig(File("/sdcard/GameBEX/ra/retroarch-stream.cfg"))).apply {
            prepareHandler = { _, _, ready ->
                if (ready) error("Config rewrite failed")
                prep
            }
        }
        val host = FakeStreamHost().apply { startValue = startValue.copy(rsound = true) }
        val h = harness(this, stream = host, player2 = p2)
        h.startGame(this)
        assertEquals(listOf("stream", "rsound"), h.events)
        assertEquals(1, h.stream.stops)
        assertEquals(0, h.stream.gameLaunches)
        assertEquals(0, h.stream.captureAttaches)
        assertTrue(h.emulators.inner.launches.isEmpty())
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
    }

    @Test
    fun aFailedEmulatorLaunchStopsTheStreamWithoutStartingTheConnectionTimer() = runTest {
        val h = harness(this)
        h.emulators.inner.launchOutcomes = ArrayDeque(listOf(EmuRouter.Outcome.Failed("Emulator launch failed")))
        h.startGame(this)
        assertEquals(listOf("stream", "launch"), h.events)
        assertEquals(1, h.stream.stops)
        assertEquals(0, h.stream.gameLaunches)
        assertEquals(0, h.stream.captureAttaches)
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
    }

    @Test
    fun aThrowingEmulatorLaunchStopsTheStreamWithoutStartingTheConnectionTimer() = runTest {
        val h = harness(this)
        h.emulators.launchFailure = IllegalStateException("Emulator launch failed")
        h.startGame(this)
        assertEquals(listOf("stream", "launch"), h.events)
        assertEquals(1, h.stream.stops)
        assertEquals(0, h.stream.gameLaunches)
        assertEquals(0, h.stream.captureAttaches)
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
    }

    @Test
    fun onlyTheGamesAppIsCapturedOnceTheGameIsInFront() = runTest {
        val h = harness(this)
        h.startGame(this)
        runCurrent()
        assertEquals(listOf("stream", "launch", "gameLaunched", "capture"), h.events)
        assertEquals(1, h.stream.captureAttaches)
    }

    @Test
    fun aStreamThatWontStartAsksForNoCapture() = runTest {
        val h = harness(this, stream = FakeStreamHost(startValue = StreamStarted(ok = false, error = StreamError.UHID)))
        h.startGame(this)
        runCurrent()
        assertEquals(0, h.stream.captureAttaches)
        assertEquals(0, h.stream.gameLaunches)
        assertEquals(listOf(false), h.player2.audioReadiness)
    }

    @Test
    fun aGuestWithoutTheStreamProtocolIsTurnedAway() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this, streamProto = null)
        h.engine.start()
        runCurrent()
        assertEquals(ByeReason.VERSION, pair.host.sent.filterIsInstance<LinkMsg.Bye>().single().reason)
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
        assertEquals("Update GameBEX on both phones to play together.", h.engine.view.value?.message)
        assertNull(h.engine.view.value?.guestName)
        assertTrue(h.stream.configs.isEmpty())
        assertEquals(0, h.stream.captureAttaches)
        assertEquals(0, h.stream.gameLaunches)
        assertTrue(h.player2.prepared.isEmpty())
        assertTrue(h.emulators.inner.launches.isEmpty())
        assertTrue(h.events.isEmpty())
        assertTrue(pair.host.sent.none { it is LinkMsg.Offer || it is LinkMsg.Launch || it is LinkMsg.StreamReady })
    }

    @Test
    fun aGuestWithAnotherStreamProtocolIsTurnedAway() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this, streamProto = StreamWire.VERSION + 1)
        h.engine.start()
        runCurrent()
        assertEquals(ByeReason.VERSION, pair.host.sent.filterIsInstance<LinkMsg.Bye>().single().reason)
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
        assertEquals("Update GameBEX on both phones to play together.", h.engine.view.value?.message)
        assertNull(h.engine.view.value?.guestName)
        assertTrue(h.stream.configs.isEmpty())
        assertEquals(0, h.stream.captureAttaches)
        assertEquals(0, h.stream.gameLaunches)
        assertTrue(h.player2.prepared.isEmpty())
        assertTrue(h.emulators.inner.launches.isEmpty())
        assertTrue(h.events.isEmpty())
        assertTrue(pair.host.sent.none { it is LinkMsg.Offer || it is LinkMsg.Launch || it is LinkMsg.StreamReady })
    }

    @Test
    fun aStreamThatWontStartKeepsTheGameClosed() = runTest {
        val h = harness(this, stream = FakeStreamHost(startValue = StreamStarted(ok = false, error = StreamError.UHID)))
        val pair = h.startGame(this)
        assertEquals(listOf("stream"), h.events)
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
        assertEquals(TogetherCopy.launchFailed(TogetherCopy.streamNoController), h.engine.view.value?.message)
        assertTrue(pair.host.sent.none { it is LinkMsg.Launch })
    }

    @Test
    fun aPlayerTwoFailureStopsBeforeTheStream() = runTest {
        val h = harness(this, player2 = FakeStreamPlayer2(P2Prep.Failed("Dolphin is busy")))
        h.startGame(this)
        assertTrue(h.events.isEmpty())
        assertEquals(listOf(false), h.player2.audioReadiness)
        assertEquals(0, h.stream.gameLaunches)
        assertEquals(TogetherCopy.launchFailed("Dolphin is busy"), h.engine.view.value?.message)
    }

    @Test
    fun aPlayerTwoGuideShowsOnceTheGameRuns() = runTest {
        val h = harness(this, player2 = FakeStreamPlayer2(P2Prep.Guide(listOf("Map port 2."))))
        h.startGame(this)
        assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
        assertEquals("Map port 2.", h.engine.view.value?.message)
        assertNull(h.emulators.inner.launches.single().p2Config)
    }

    @Test
    fun noSoundIsSaid() = runTest {
        val h = harness(this, stream = FakeStreamHost(startValue = StreamStarted(ok = true, mediaPort = 1, inputPort = 2, width = 1600, height = 720, audio = false)))
        h.startGame(this)
        assertEquals(TogetherCopy.streamNoSound, h.engine.view.value?.message)
    }

    @Test
    fun pollingShowsTheStreamLine() = runTest {
        val h = harness(this, stream = FakeStreamHost(statusValue = StreamStatus(running = true, guestConnected = true, fps = 60, bitrate = 7_900_000)))
        h.startGame(this)
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(TogetherCopy.hostStreamLine("Sam", 60, 7_900_000), h.engine.view.value?.streamLine)
        assertTrue(h.stream.statusCalls >= 1)
    }

    @Test
    fun aStreamThatStopsSaysSo() = runTest {
        val h = harness(this, stream = FakeStreamHost(statusValue = StreamStatus(running = false)))
        h.startGame(this)
        advanceTimeBy(2_100)
        runCurrent()
        assertNull(h.engine.view.value?.streamLine)
        assertEquals(TogetherCopy.streamStopped, h.engine.view.value?.message)
        assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
    }

    @Test
    fun theGuestLeavingRemovesPlayerTwo() = runTest {
        val h = harness(this)
        val pair = h.startGame(this)
        pair.guest.send(LinkMsg.Bye(ByeReason.LEFT))
        advanceUntilIdle()
        assertTrue(h.stream.stops >= 1)
        assertNull(h.engine.view.value?.streamLine)
    }

    @Test
    fun aLostLinkWarnsAtOnceAndKeepsTheStreamForTheGrace() = runTest {
        val h = harness(this, stream = FakeStreamHost(statusValue = StreamStatus(running = true, guestConnected = false)))
        h.platform.foregroundState.value = false
        val pair = h.startGame(this)
        pair.host.close()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, h.stream.stops)
        assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
        assertTrue(h.engine.view.value?.linkLost == true)
        assertEquals(TogetherCopy.streamLinkLostHost("Sam", 15), h.engine.view.value?.message)
        assertEquals(TogetherAlert.LinkLost("Sam", streamGraceS = 15), h.platform.alerts.last())
    }

    @Test
    fun theStreamStopsAfterTheGraceWhenTheGuestIsStillGone() = runTest {
        val h = harness(this, stream = FakeStreamHost(statusValue = StreamStatus(running = true, guestConnected = false)))
        val pair = h.startGame(this)
        pair.host.close()
        advanceTimeBy(15_500)
        runCurrent()
        assertTrue(h.stream.stops >= 1)
        assertNull(h.engine.view.value?.streamLine)
        assertEquals(TogetherCopy.linkLost("Sam"), h.engine.view.value?.message)
    }

    @Test
    fun theStreamStaysAfterTheGraceWhenTheGuestIsStillWatching() = runTest {
        val h = harness(this, stream = FakeStreamHost(statusValue = StreamStatus(running = true, guestConnected = true, fps = 50, bitrate = 4_000_000)))
        val pair = h.startGame(this)
        pair.host.close()
        advanceTimeBy(15_500)
        runCurrent()
        assertEquals(0, h.stream.stops)
        assertEquals(TogetherCopy.streamLinkLostStill("Sam"), h.engine.view.value?.message)
        assertEquals(TogetherCopy.hostStreamLine("Sam", 50, 4_000_000), h.engine.view.value?.streamLine)
    }

    @Test
    fun aGuestThatGoesQuietWhileConnectedWarnsTheHostAtOnce() = runTest {
        val quiet = StreamStatus(running = true, guestConnected = true, fps = 50, bitrate = 4_000_000, inputPackets = 100)
        val h = harness(this, stream = FakeStreamHost(statusValue = quiet))
        h.platform.foregroundState.value = false
        h.startGame(this)
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(TogetherCopy.streamPeerSilent("Sam"), h.engine.view.value?.message)
        assertEquals(TogetherAlert.PeerSilent("Sam"), h.platform.alerts.last())
        assertEquals(0, h.stream.stops)
        // The guest is back: the packet count moves again and the warning goes away.
        h.stream.statusValue = quiet.copy(inputPackets = 200)
        advanceTimeBy(2_100)
        runCurrent()
        assertNull(h.engine.view.value?.message)
    }

    @Test
    fun aGuestThatKeepsSendingIsNeverWarnedAbout() = runTest {
        val h = harness(this, stream = FakeStreamHost(statusValue = StreamStatus(running = true, guestConnected = true, inputPackets = 1)))
        h.startGame(this)
        for (i in 2..6) {
            h.stream.statusValue = StreamStatus(running = true, guestConnected = true, inputPackets = i * 40L)
            advanceTimeBy(2_000)
            runCurrent()
        }
        assertNull(h.engine.view.value?.message)
        assertTrue(h.platform.alerts.none { it is TogetherAlert.PeerSilent })
    }

    @Test
    fun aGuestWhoLeftTheViewerIsNotReportedAsQuiet() = runTest {
        val h = harness(this, stream = FakeStreamHost(statusValue = StreamStatus(running = true, guestConnected = false, inputPackets = 100)))
        h.startGame(this)
        advanceTimeBy(8_000)
        runCurrent()
        assertNull(h.engine.view.value?.message)
    }

    @Test
    fun endingDuringTheGraceStillStopsTheStream() = runTest {
        val h = harness(this)
        val pair = h.startGame(this)
        pair.host.close()
        advanceTimeBy(3_000)
        runCurrent()
        h.engine.end()
        advanceUntilIdle()
        assertEquals(HostStep.ENDED, h.engine.view.value?.step)
        assertTrue(h.stream.stops >= 1)
        assertEquals(1, h.player2.restores)
    }

    @Test
    fun endStopsTheStreamThenRestoresPlayerTwo() = runTest {
        val h = harness(this)
        h.startGame(this)
        h.engine.end()
        advanceUntilIdle()
        assertEquals(HostStep.ENDED, h.engine.view.value?.step)
        assertTrue(h.stream.stops >= 1)
        assertEquals(1, h.player2.restores)
        assertEquals(1, h.emulators.inner.endSessionCount)
    }

    @Test
    fun theHostCanSwitchToAnotherListedModeBeforeAGuestJoins() = runTest {
        val h = harness(this, game = sampleGame(), offer = sampleOffer(), modes = { listOf(TogetherMode.PSP_ADHOC, TogetherMode.STREAM) })
        h.engine.hostGame(1)
        advanceUntilIdle()
        assertEquals(TogetherMode.PSP_ADHOC, h.engine.view.value?.mode)
        assertEquals(listOf(TogetherMode.STREAM), h.engine.view.value?.altModes)
        h.engine.switchMode(TogetherMode.STREAM)
        advanceUntilIdle()
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
        assertEquals(TogetherMode.STREAM, h.engine.view.value?.mode)
        assertEquals(listOf(TogetherMode.PSP_ADHOC), h.engine.view.value?.altModes)
        assertEquals(TogetherMode.STREAM, h.discovery.advertisements.last().mode)
    }

    @Test
    fun switchingIsRefusedOnceAGuestJoinedOrForAnUnlistedMode() = runTest {
        val h = harness(this, game = sampleGame(), offer = sampleOffer(), modes = { listOf(TogetherMode.PSP_ADHOC, TogetherMode.STREAM) })
        h.engine.hostGame(1)
        advanceUntilIdle()
        h.engine.switchMode(TogetherMode.GB_LINK)
        advanceUntilIdle()
        assertEquals(TogetherMode.PSP_ADHOC, h.engine.view.value?.mode)
        h.join(this)
        h.engine.switchMode(TogetherMode.STREAM)
        advanceUntilIdle()
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
        assertEquals(TogetherMode.PSP_ADHOC, h.engine.view.value?.mode)
    }
}
