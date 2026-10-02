package com.gamebex.client.together.session

import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.stream.P2Prep
import com.gamebex.client.stream.StreamError
import com.gamebex.client.stream.StreamStarted
import com.gamebex.client.stream.StreamStatus
import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.fakes.FakeStreamHost
import com.gamebex.client.stream.fakes.FakeStreamPlayer2
import com.gamebex.client.together.ByeReason
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.Role
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
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        override suspend fun launch(req: TogetherLaunch): EmuRouter.Outcome {
            events += "launch"
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
    ): Harness {
        val engineScope = scope.engineTestScope()
        val events = mutableListOf<String>()
        val recordingStream = object : com.gamebex.client.stream.StreamHost by stream {
            override suspend fun start(config: com.gamebex.client.stream.StreamConfig): StreamStarted {
                events += "stream"
                return stream.start(config)
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
            player2,
            modes ?: com.gamebex.client.together.TogetherSupport::modesFor,
        )
        return Harness(engine, transport, discovery, emulators, stream, player2, events, platform)
    }

    private suspend fun Harness.join(scope: TestScope, streamProto: Int? = 1): LinkPair {
        val pair = transport.pair()
        pair.guest.send(LinkMsg.Hello(1, Role.GUEST, "guest-device", "Sam", "1.0", streamProto = streamProto))
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
        assertEquals(listOf("stream", "launch"), h.events)
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
        assertEquals(1, ready.proto)
        assertEquals(TogetherCopy.hostStreamWaiting, h.engine.view.value?.streamLine)
        assertEquals(TogetherMode.STREAM, (sent.first { it is LinkMsg.Offer } as LinkMsg.Offer).mode)
        assertNull((sent.first { it is LinkMsg.Offer } as LinkMsg.Offer).game.core)
        assertEquals(1, (sent.first { it is LinkMsg.Hello } as LinkMsg.Hello).streamProto)
    }

    @Test
    fun aGuestWithoutTheStreamProtocolIsTurnedAway() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this, streamProto = null)
        assertEquals(ByeReason.VERSION, (pair.host.sent.last() as LinkMsg.Bye).reason)
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
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
