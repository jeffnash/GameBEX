package com.gamebex.client.together.session

import com.gamebex.client.stream.guest.StreamOverlayStore
import com.gamebex.client.stream.proto.StreamWire
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.GuestView
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.MatchResult
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.Role
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.session.fakes.GuestLinkPair
import com.gamebex.client.together.session.fakes.GuestTestEmulators
import com.gamebex.client.together.session.fakes.GuestTestLibrary
import com.gamebex.client.together.session.fakes.GuestTestPlatform
import com.gamebex.client.together.session.fakes.GuestTestProbes
import com.gamebex.client.together.session.fakes.GuestTestTransport
import com.gamebex.client.together.session.fakes.engineTestScope
import com.gamebex.client.together.session.fakes.guestGame
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guest stores the host's RetroArch overlay for the viewer. */
class GuestEngineOverlayTest {
    private val offered = OfferedGame(title = "Mario Kart 64", folder = "n64", fileName = "mk64.z64", fileSize = 8_388_608)
    private val token = "00112233445566778899aabbccddeeff"

    @After
    fun clearStore() {
        StreamOverlayStore.clear()
    }

    private class Rig(
        val engine: GuestEngine,
        val transport: GuestTestTransport,
        val emulators: GuestTestEmulators,
        val library: GuestTestLibrary,
    )

    private fun rig(scope: TestScope, timing: TogetherTiming = TogetherTiming(countdownS = 5, launchWaitMs = 5_000L)): Rig {
        val engineScope = scope.engineTestScope()
        val transport = GuestTestTransport()
        val emulators = GuestTestEmulators()
        val library = GuestTestLibrary(guestGame(), offered).apply { matchValue = MatchResult.Missing(downloadable = false) }
        val engine = GuestEngine(
            transport, GuestTestProbes(), emulators, library, GuestTestPlatform(), timing, engineScope,
            engineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )
        return Rig(engine, transport, emulators, library)
    }

    private suspend fun TestScope.connect(rig: Rig): GuestLinkPair {
        rig.engine.join(NearbyHost("host-device", SESSION, "J", "Mario Kart 64", "n64", TogetherMode.STREAM, ADDRESS, 55_440))
        val pair = rig.transport.connected.await()
        runCurrent()
        assertEquals(GuestStep.WAITING_OFFER, rig.engine.view.value?.step)
        return pair
    }

    private suspend fun TestScope.open(rig: Rig): GuestLinkPair {
        val pair = connect(rig)
        pair.host.send(LinkMsg.Hello(TOGETHER_PROTO, Role.HOST, "host-device", "J", "1.0", streamProto = StreamWire.VERSION))
        pair.host.send(LinkMsg.Offer(SESSION, TogetherMode.STREAM, offered, null, ADDRESS))
        runCurrent()
        return pair
    }

    private suspend fun TestScope.awaitView(rig: Rig, step: GuestStep, predicate: (GuestView) -> Boolean = { true }): GuestView {
        runCurrent()
        return withTimeout(30_000L) { rig.engine.view.filterNotNull().first { it.step == step && predicate(it) } }
    }

    private fun ready(round: Int = 1) =
        LinkMsg.StreamReady(SESSION, round, StreamWire.VERSION, token, 55_441, 55_442, 1600, 720, true)

    private fun overlay(sessionId: String = SESSION, round: Int = 1, cfg: String = PAD_CFG) =
        LinkMsg.StreamOverlay(sessionId, round, "landscape", cfg)

    @Test
    fun theRoundsOverlayIsStoredForTheViewer() = runTest {
        val rig = rig(this)
        val pair = open(rig)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        pair.host.send(ready())
        pair.host.send(overlay())
        awaitView(rig, GuestStep.IN_GAME)
        val stored = StreamOverlayStore.current()!!
        assertEquals(1, stored.round)
        assertEquals(3, stored.layout.playPage()!!.descs.size)
        assertTrue(stored.layout.mapped().usableForPlay)
        assertEquals(GuestPhase.PLAYING, rig.engine.view.value?.phase)
    }

    @Test
    fun overlaysForAnotherSessionRoundOrEmptyCfgAreIgnored() = runTest {
        val rig = rig(this)
        val pair = open(rig)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        pair.host.send(ready())
        pair.host.send(overlay(round = 7))
        pair.host.send(overlay(sessionId = "other-session"))
        pair.host.send(overlay(cfg = "  "))
        runCurrent()
        assertNull(StreamOverlayStore.current())
        awaitView(rig, GuestStep.IN_GAME)
    }

    @Test
    fun aNewLaunchClearsThePreviousOverlay() = runTest {
        val rig = rig(this)
        val pair = open(rig)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        pair.host.send(ready())
        pair.host.send(overlay())
        awaitView(rig, GuestStep.IN_GAME)
        assertEquals(1, StreamOverlayStore.current()?.round)
        pair.host.send(LinkMsg.Launch(SESSION, 2))
        awaitView(rig, GuestStep.LAUNCHING)
        assertNull(StreamOverlayStore.current())
    }

    private companion object {
        const val SESSION = "overlay-session"
        const val ADDRESS = "10.42.1.20"
        const val PAD_CFG: String = """overlays = 1
overlay0_name = "landscape"
overlay0_normalized = "true"
overlay0_descs = 3
overlay0_desc0 = "b,0.88,0.92,radial,0.05,0.07"
overlay0_desc1 = "up,0.12,0.68,radial,0.05,0.08"
overlay0_desc2 = "start,0.56,0.91,rect,0.04,0.05"
"""
    }
}
