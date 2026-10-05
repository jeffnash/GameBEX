package com.gamebex.client.together.session

import com.gamebex.client.stream.StreamEndpoint
import com.gamebex.client.stream.proto.StreamWire
import com.gamebex.client.together.ByeReason
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.GuestView
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.MatchResult
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.Readiness
import com.gamebex.client.together.Role
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherCopy
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guest half of a STREAM session. */
class GuestEngineStreamTest {
    private val offered = OfferedGame(title = "Mario Kart 64", folder = "n64", fileName = "mk64.z64", fileSize = 8_388_608)
    private val token = "00112233445566778899aabbccddeeff"

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

    private fun ready(round: Int = 1, proto: Int = StreamWire.VERSION, tokenHex: String = token) =
        LinkMsg.StreamReady(SESSION, round, proto, tokenHex, 55_441, 55_442, 1600, 720, true)

    @Test
    fun aStreamGuestNeedsNoGameAndSaysItSpeaksStream() = runTest {
        val rig = rig(this)
        val pair = open(rig)
        val view = awaitView(rig, GuestStep.READY)
        assertEquals(TogetherMode.STREAM, view.mode)
        assertEquals(GuestPhase.READY, view.phase)
        val hello = pair.guest.sent.filterIsInstance<LinkMsg.Hello>().single()
        assertEquals(StreamWire.VERSION, hello.streamProto)
    }

    private suspend fun TestScope.assertVersionRejection(rig: Rig) {
        val ended = awaitView(rig, GuestStep.ENDED)
        assertEquals("Update GameBEX on both phones to play together.", ended.message)
        assertTrue(rig.emulators.launches.isEmpty())
        rig.engine.act(TogetherAction.OPEN_STREAM)
        runCurrent()
        assertEquals(GuestStep.ENDED, rig.engine.view.value?.step)
        assertEquals("Update GameBEX on both phones to play together.", rig.engine.view.value?.message)
        assertTrue(rig.emulators.launches.isEmpty())
    }

    @Test
    fun aHostVersionRefusalEndsWithoutOpeningTheViewer() = runTest {
        val rig = rig(this)
        val pair = connect(rig)
        pair.host.send(LinkMsg.Bye(ByeReason.VERSION))
        pair.host.close()
        assertVersionRejection(rig)
    }

    @Test
    fun aHostWithoutTheStreamProtocolEndsWithoutOpeningTheViewer() = runTest {
        val rig = rig(this)
        val pair = connect(rig)
        pair.host.send(LinkMsg.Hello(TOGETHER_PROTO, Role.HOST, "host-device", "J", "1.0", streamProto = null))
        assertVersionRejection(rig)
    }

    @Test
    fun aHostWithAnotherStreamProtocolEndsWithoutOpeningTheViewer() = runTest {
        val rig = rig(this)
        val pair = connect(rig)
        pair.host.send(LinkMsg.Hello(TOGETHER_PROTO, Role.HOST, "host-device", "J", "1.0", streamProto = StreamWire.VERSION + 1))
        assertVersionRejection(rig)
    }

    @Test
    fun aPhoneThatCantWatchSaysWhy() = runTest {
        val rig = rig(this)
        rig.emulators.readinessValue = Readiness.Blocked(TogetherCopy.streamNeedsAndroid10)
        open(rig)
        val view = awaitView(rig, GuestStep.ACTION_NEEDED)
        assertEquals(TogetherCopy.streamNeedsAndroid10, view.problem?.text)
    }

    @Test
    fun launchWaitsForStreamReadyWithoutACountdown() = runTest {
        val rig = rig(this)
        val pair = open(rig)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        val launching = awaitView(rig, GuestStep.LAUNCHING)
        assertNull(launching.countdown)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(rig.emulators.launches.isEmpty())
        pair.host.send(ready())
        awaitView(rig, GuestStep.IN_GAME)
        val launch = rig.emulators.launches.single()
        assertEquals(Role.GUEST, launch.role)
        assertEquals(TogetherMode.STREAM, launch.mode)
        assertNull(launch.core)
        assertEquals(StreamEndpoint(ADDRESS, 55_441, 55_442, token, 1600, 720, true, "J"), launch.stream)
    }

    @Test
    fun streamEndpointCarriesSystemAndBackendToTheViewer() = runTest {
        val rig = rig(this)
        val pair = open(rig)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        pair.host.send(ready().copy(system = "n64", backend = "retroarch"))
        awaitView(rig, GuestStep.IN_GAME)
        val launch = rig.emulators.launches.single()
        assertEquals("n64", launch.stream?.system)
        assertEquals("retroarch", launch.stream?.backend)
    }

    @Test
    fun noStreamReadyInTimeIsSaidPlainly() = runTest {
        val rig = rig(this)
        val pair = open(rig)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        advanceTimeBy(5_100)
        val view = awaitView(rig, GuestStep.ACTION_NEEDED)
        assertEquals(TogetherCopy.launchFailed(TogetherCopy.streamNotReady), view.problem?.text)
        assertTrue(rig.emulators.launches.isEmpty())
    }

    @Test
    fun aStreamReadyWithAnotherProtocolOrABadTokenIsIgnored() = runTest {
        val rig = rig(this)
        val pair = open(rig)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        pair.host.send(ready(proto = StreamWire.VERSION + 1))
        pair.host.send(ready(tokenHex = "not-a-token"))
        pair.host.send(ready(round = 7))
        advanceTimeBy(5_100)
        awaitView(rig, GuestStep.ACTION_NEEDED)
        assertTrue(rig.emulators.launches.isEmpty())
    }

    @Test
    fun openStreamReopensTheViewerWithTheSameEndpoint() = runTest {
        val rig = rig(this)
        val pair = open(rig)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        pair.host.send(ready())
        awaitView(rig, GuestStep.IN_GAME)
        rig.engine.act(TogetherAction.OPEN_STREAM)
        runCurrent()
        assertEquals(2, rig.emulators.launches.size)
        assertEquals(rig.emulators.launches[0].stream, rig.emulators.launches[1].stream)
        assertEquals(GuestStep.IN_GAME, rig.engine.view.value?.step)
    }

    private companion object {
        const val SESSION = "stream-session"
        const val ADDRESS = "10.42.1.20"
    }
}
