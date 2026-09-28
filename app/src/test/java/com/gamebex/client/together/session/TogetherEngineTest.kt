package com.gamebex.client.together.session

import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.together.session.fakes.EngineTestDiscovery
import com.gamebex.client.together.session.fakes.EngineTestEmulators
import com.gamebex.client.together.session.fakes.EngineTestLibrary
import com.gamebex.client.together.session.fakes.EngineTestPlatform
import com.gamebex.client.together.session.fakes.EngineTestProbes
import com.gamebex.client.together.session.fakes.EngineTestTransport
import com.gamebex.client.together.session.fakes.engineTestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
class TogetherEngineTest {
    @Test
    fun inviteUsesFoundOrderAndSkipsDismissedHosts() = runTest {
        val f = fixture(this)
        val first = host("first")
        val second = host("second")
        f.discovery.found.value = listOf(first, second)
        runCurrent()
        assertEquals(first, f.engine.invite.value)

        f.engine.dismissInvite()
        runCurrent()
        assertEquals(second, f.engine.invite.value)

        f.engine.dismissInvite()
        runCurrent()
        assertNull(f.engine.invite.value)
    }

    @Test
    fun joinedHostStaysSuppressedAfterSessionEnds() = runTest {
        val f = fixture(this)
        val target = host("joined")
        f.discovery.found.value = listOf(target)
        runCurrent()
        assertEquals(target, f.engine.invite.value)

        f.engine.join(target)
        assertEquals(GuestStep.CONNECTING, f.engine.guest.value?.step)
        runCurrent()
        assertNull(f.engine.invite.value)
        f.engine.end()
        runCurrent()
        f.emulators.endSessionObserved.await()
        f.engine.guest.filterNotNull().first { it.step == GuestStep.ENDED }
        assertEquals(GuestStep.ENDED, f.engine.guest.value?.step)
        assertNull(f.engine.invite.value)
    }

    @Test
    fun hostGameEndsGuestBeforeStartingHostSession() = runTest {
        val f = fixture(this, withHostGame = true)
        f.engine.join(host("remote"))
        runCurrent()
        assertTrue(
            f.engine.guest.value?.step == GuestStep.CONNECTING ||
                f.engine.guest.value?.step == GuestStep.WAITING_OFFER,
        )

        f.engine.hostGame(7L)
        assertEquals(HostStep.CHECKING, f.engine.host.value?.step)
        runCurrent()
        f.emulators.readinessObserved.await()

        assertEquals(GuestStep.ENDED, f.engine.guest.value?.step)
        assertEquals(1, f.emulators.endSessionCount)
        assertTrue(f.emulators.events.indexOf("endSession") < f.emulators.events.indexOf("readiness"))
        f.platform.cleanupSignals.receive()
        f.engine.end()
        f.platform.cleanupSignals.receive()
        runCurrent()
    }

    @Test
    fun firstIdleForegroundRestoresOnce() = runTest {
        val f = fixture(this)
        f.engine.onForeground()
        f.emulators.endSessionObserved.await()

        f.engine.onForeground()
        runCurrent()

        assertEquals(1, f.emulators.endSessionCount)
    }

    @Test
    fun invalidAddressAndLinkAreRejectedBeforeJoin() = runTest {
        val f = fixture(this)
        assertEquals(TogetherCopy.addressInvalid, f.engine.joinAddress("not-an-address"))
        assertEquals(TogetherCopy.linkInvalid, f.engine.joinLink("https://example.com"))
        assertNull(f.engine.guest.value)
        assertNull(f.engine.host.value)
    }

    @Test
    fun activeHostIgnoresJoinLink() = runTest {
        val f = fixture(this, withHostGame = true)
        f.engine.hostGame(7L)
        assertEquals(HostStep.CHECKING, f.engine.host.value?.step)
        f.engine.joinLink("gamebex://join?v=1&h=10.42.1.20&p=55440&n=Sam")
        assertNull(f.engine.guest.value)
        runCurrent()
        assertTrue(f.engine.host.value?.step != HostStep.ENDED)
        assertNull(f.engine.guest.value)
    }

    @Test
    fun activeGuestIgnoresJoinLink() = runTest {
        val f = fixture(this)
        f.engine.join(host("remote"))
        assertEquals("Player remote", f.engine.guest.value?.hostName)
        f.engine.joinLink("gamebex://join?v=1&h=10.42.1.20&p=55440&n=Sam")
        assertEquals(GuestStep.CONNECTING, f.engine.guest.value?.step)
        assertEquals("Player remote", f.engine.guest.value?.hostName)
    }

    @Test
    fun racingHostGameAndJoinDoesNotDeadlock() = runTest {
        val f = fixture(this, withHostGame = true)
        f.engine.join(host("remote"))
        runCurrent()
        f.engine.hostGame(7L)
        f.engine.join(host("other"))
        withTimeout(5_000) { runCurrent() }

        assertEquals(HostStep.ENDED, f.engine.host.value?.step)
        assertEquals(GuestStep.WAITING_OFFER, f.engine.guest.value?.step)
        advanceTimeBy(TogetherTiming().offerMs)
        runCurrent()
        assertEquals(GuestStep.ENDED, f.engine.guest.value?.step)
    }

    @Test
    fun idleSessionIgnoresActPickAndEnd() = runTest {
        val f = fixture(this)
        f.engine.act(TogetherAction.RECHECK)
        f.engine.pick(99)
        f.engine.end()
        runCurrent()
        assertNull(f.engine.host.value)
        assertNull(f.engine.guest.value)
    }

    @Test
    fun browsingForwardsFoundHostsAndRefreshesTheLanAddress() = runTest {
        val job = SupervisorJob()
        val engineScope = CoroutineScope(job + Dispatchers.Default)
        try {
            val f = fixture(
                engineScope,
                useTestScheduler = false,
                timing = TogetherTiming(addressRefreshMs = 40),
            )
            val seen = host("seen")
            assertTrue(f.engine.nearby === f.discovery.found)
            f.discovery.found.value = listOf(seen)
            assertEquals(listOf(seen), f.engine.nearby.value)
            assertNull(f.engine.myAddress.value)

            f.engine.startBrowsing()
            assertEquals(1, f.discovery.browseStarts)
            awaitReal(
                { f.engine.myAddress.value == "10.42.1.10" },
                { "browse left ${f.engine.myAddress.value}" },
            )

            f.engine.startBrowsing()
            assertEquals(2, f.discovery.browseStarts)
            f.platform.address = "10.0.0.2"
            f.engine.onForeground()
            awaitReal(
                { f.engine.myAddress.value == "10.0.0.2" },
                { "foreground left ${f.engine.myAddress.value}" },
            )

            f.platform.address = "10.0.0.3"
            awaitReal(
                { f.engine.myAddress.value == "10.0.0.3" },
                { "interval left ${f.engine.myAddress.value}" },
            )

            f.engine.stopBrowsing()
            assertEquals(1, f.discovery.browseStops)
            f.engine.stopBrowsing()
            assertEquals(2, f.discovery.browseStops)
            settle()
            val stopped = f.engine.myAddress.value
            f.platform.address = "10.0.0.4"
            withContext(Dispatchers.Default) { Thread.sleep(200) }
            assertEquals(stopped, f.engine.myAddress.value)
        } finally {
            job.cancel()
            job.join()
        }
    }

    @Test
    fun renameSavesTheCustomNameAndMyNameFollowsIt() = runTest {
        val f = fixture(this)
        f.engine.startBrowsing()
        runCurrent()
        assertEquals("Local", f.engine.myName.value)
        assertNull(f.engine.customName.value)

        f.engine.rename("  Sam's   Deck ")
        runCurrent()
        assertEquals("Sam's Deck", f.platform.custom)
        assertEquals("Sam's Deck", f.engine.customName.value)
        assertEquals("Sam's Deck", f.engine.myName.value)

        f.engine.rename(null)
        runCurrent()
        assertNull(f.platform.custom)
        assertNull(f.engine.customName.value)
        assertEquals("Local", f.engine.myName.value)
        f.engine.stopBrowsing()
    }

    @Test
    fun actPickAndEndReachOnlyTheActiveEngine() = runTest {
        val job = SupervisorJob()
        val engineScope = CoroutineScope(job + Dispatchers.Default)
        try {
            val f = fixture(engineScope, withHostGame = true, useTestScheduler = false)
            f.engine.hostGame(7L)
            awaitReal(
                { f.engine.host.value?.step == HostStep.WAITING },
                { "host did not reach WAITING; host=${f.engine.host.value?.step} guest=${f.engine.guest.value?.step}" },
            )
            f.engine.pick(99)
            f.engine.act(TogetherAction.RECHECK)
            settle()
            assertNull(f.engine.guest.value)
            assertEquals(HostStep.WAITING, f.engine.host.value?.step)
            f.engine.end()
            awaitReal(
                { f.engine.host.value?.step == HostStep.ENDED },
                { "host did not end; host=${f.engine.host.value?.step}" },
            )

            f.engine.join(host("remote"))
            awaitReal(
                { f.engine.guest.value?.step == GuestStep.WAITING_OFFER },
                { "guest did not reach WAITING_OFFER; guest=${f.engine.guest.value?.step} host=${f.engine.host.value?.step}" },
            )
            f.engine.pick(99)
            f.engine.act(TogetherAction.RECHECK)
            settle()
            assertEquals(HostStep.ENDED, f.engine.host.value?.step)
            f.engine.end()
            awaitReal(
                { f.engine.guest.value?.step == GuestStep.ENDED },
                { "guest did not end; guest=${f.engine.guest.value?.step} host=${f.engine.host.value?.step}" },
            )
            assertEquals(HostStep.ENDED, f.engine.host.value?.step)
        } finally {
            job.cancel()
            job.join()
        }
    }

    private suspend fun awaitReal(
        ready: () -> Boolean,
        detail: () -> String,
        timeoutNs: Long = 5_000_000_000L,
    ) {
        withContext(Dispatchers.Default) {
            val deadline = System.nanoTime() + timeoutNs
            while (!ready()) {
                if (System.nanoTime() >= deadline) error(detail())
                Thread.sleep(20)
            }
        }
    }

    private suspend fun settle() {
        withContext(Dispatchers.Default) { Thread.sleep(100) }
    }

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        withHostGame: Boolean = false,
        useTestScheduler: Boolean = true,
        timing: TogetherTiming = TogetherTiming(),
    ): Fixture {
        val game = GameEntity(
            id = 7L,
            title = "Test game",
            folder = "snes",
            volumeId = "volume",
            relPath = "Test.sfc",
            fileName = "Test.sfc",
            sizeBytes = 4,
            mtimeMs = 1,
            ext = "sfc",
        )
        val transport = EngineTestTransport()
        val discovery = EngineTestDiscovery()
        val emulators = EngineTestEmulators()
        val library = EngineTestLibrary(
            localGame = game.takeIf { withHostGame },
            offeredGame = OfferedGame("Test game", "snes", "Test.sfc", 4L, core = "snes9x")
                .takeIf { withHostGame },
        )
        val platform = EngineTestPlatform()
        val engineScope = if (useTestScheduler) scope.engineTestScope() else scope
        val dispatcher = engineScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
        val engine = TogetherEngine(
            transport = transport,
            probes = EngineTestProbes(),
            emulators = emulators,
            library = library,
            discovery = discovery,
            platform = platform,
            scope = engineScope,
            timing = timing,
            ioDispatcher = if (useTestScheduler && dispatcher != null) dispatcher else Dispatchers.IO,
        )
        return Fixture(engine, discovery, emulators, platform)
    }

    private fun host(id: String) = NearbyHost(
        deviceId = id,
        sessionId = "session-$id",
        name = "Player $id",
        gameTitle = "Game",
        folder = "snes",
        mode = TogetherMode.RA_NETPLAY,
        address = "10.42.1.20",
        port = 55_440,
    )

    private data class Fixture(
        val engine: TogetherEngine,
        val discovery: EngineTestDiscovery,
        val emulators: EngineTestEmulators,
        val platform: EngineTestPlatform,
    )
}
