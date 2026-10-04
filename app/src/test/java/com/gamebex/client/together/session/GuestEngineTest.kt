package com.gamebex.client.together.session

import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.CoreNames
import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.together.ByeReason
import com.gamebex.client.together.CoreBuild
import com.gamebex.client.together.DownloadResult
import com.gamebex.client.together.DownloadState
import com.gamebex.client.together.GameChoice
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.LinkEvent
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.LinkTransport
import com.gamebex.client.together.MatchResult
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.Readiness
import com.gamebex.client.together.CoreResult
import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherAlert
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherPlatform
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.session.fakes.GuestLinkPair
import com.gamebex.client.together.session.fakes.GuestTestEmulators
import com.gamebex.client.together.session.fakes.GuestTestLibrary
import com.gamebex.client.together.session.fakes.GuestTestPlatform
import com.gamebex.client.together.session.fakes.GuestTestProbes
import com.gamebex.client.together.session.fakes.GuestTestTransport
import com.gamebex.client.together.session.fakes.engineTestScope
import com.gamebex.client.together.session.fakes.guestGame
import com.gamebex.client.together.session.fakes.guestOffer
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuestEngineTest {
    @Test
    fun joinIsSynchronousAndOfferNeedsHostHello() = runTest {
        val rig = rig(this)
        rig.engine.join(target())
        assertEquals(GuestStep.CONNECTING, rig.engine.view.value?.step)

        val pair = rig.transport.connected.await()
        runCurrent()
        val hello = pair.host.events.receiveMessage() as LinkMsg.Hello
        assertEquals(Role.GUEST, hello.role)

        val offered = guestOffer()
        pair.host.send(LinkMsg.Offer(SESSION, TogetherMode.PSP_ADHOC, offered, hostAddress = ADDRESS))
        runCurrent()
        assertEquals(GuestStep.WAITING_OFFER, rig.engine.view.value?.step)

        sendOffer(pair, TogetherMode.PSP_ADHOC, offered)
        val ready = awaitView(rig, GuestStep.READY)
        assertEquals("Host", ready.hostName)
    }

    @Test
    fun offerRejectsInjectedAndMismatchedHostAddress() = runTest {
        val rig = rig(this)
        rig.engine.join(target())
        val pair = rig.transport.connected.await()
        runCurrent()
        pair.host.send(LinkMsg.Hello(TOGETHER_PROTO, Role.HOST, "host-device", "Ada", "1.0"))
        runCurrent()
        assertEquals("Ada", rig.engine.view.value?.hostName)

        val injected = "10.42.1.20\nsavefile_directory = \"/evil\""
        pair.host.send(LinkMsg.Offer(SESSION, TogetherMode.PSP_ADHOC, guestOffer(), hostAddress = injected))
        runCurrent()
        assertEquals(GuestStep.WAITING_OFFER, rig.engine.view.value?.step)
        assertNull(rig.engine.view.value?.gameTitle)

        pair.host.send(LinkMsg.Offer(SESSION, TogetherMode.PSP_ADHOC, guestOffer(), hostAddress = "8.8.8.8"))
        runCurrent()
        assertEquals(GuestStep.WAITING_OFFER, rig.engine.view.value?.step)
        assertNull(rig.engine.view.value?.gameTitle)
        assertTrue(rig.emulators.launches.isEmpty())
    }

    @Test
    fun lateEndFromSessionADoesNotMarkSessionBEnded() = runTest {
        val transport = object : LinkTransport {
            var connects = 0
            lateinit var first: GuestLinkPair
            val secondEntered = CompletableDeferred<Unit>()
            val secondRelease = CompletableDeferred<Unit>()

            override suspend fun listen(preferredPort: Int) = error("unused")

            override suspend fun connect(address: String, port: Int, timeoutMs: Long): com.gamebex.client.together.Link {
                val next = GuestLinkPair()
                connects += 1
                if (connects == 1) {
                    first = next
                    return next.guest
                }
                secondEntered.complete(Unit)
                secondRelease.await()
                return next.guest
            }
        }
        val emulators = object : TogetherEmulators by GuestTestEmulators() {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var block = false

            override suspend fun endSession() {
                if (block) {
                    block = false
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        val engineScope = this.engineTestScope()
        val offered = guestOffer()
        val engine = GuestEngine(
            transport,
            GuestTestProbes(),
            emulators,
            GuestTestLibrary(guestGame(), offered),
            GuestTestPlatform(),
            timing(),
            engineScope,
            engineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )
        engine.join(target())
        runCurrent()
        sendOffer(transport.first, TogetherMode.PSP_ADHOC, offered)
        withTimeout(10_000) { engine.view.filterNotNull().first { it.step == GuestStep.READY } }

        emulators.block = true
        engine.end()
        runCurrent()
        emulators.entered.await()

        engine.join(target())
        assertEquals(GuestStep.CONNECTING, engine.view.value?.step)
        emulators.release.complete(Unit)
        runCurrent()
        transport.secondEntered.await()
        assertEquals(GuestStep.CONNECTING, engine.view.value?.step)

        transport.secondRelease.complete(Unit)
        runCurrent()
    }

    @Test
    fun missingDownloadUsesStartedKnownIdsAndDoneSelectsTheDownloadedGame() = runTest {
        val game = guestGame(id = 5, folder = "snes")
        val offered = guestOffer(folder = "snes", core = "snes9x", sourceRefJson = "source-ref")
        val rig = rig(this, game = game, offered = offered)
        rig.library.matchValue = MatchResult.Missing(downloadable = true)
        rig.library.sourceValue = "Store"
        rig.library.downloadValue = DownloadResult.Started(73, setOf(2L, 4L))
        val pair = open(rig, this, TogetherMode.RA_NETPLAY, offered)

        val missing = awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_GAME)
        assertEquals(TogetherAction.DOWNLOAD, missing.problem?.action)

        rig.engine.act(TogetherAction.DOWNLOAD)
        awaitView(rig, GuestStep.DOWNLOADING)
        rig.library.downloadStates.send(DownloadState.Progress(50))
        rig.library.downloadStates.send(DownloadState.Done(game.id))
        awaitView(rig, GuestStep.READY)

        assertEquals(offered, rig.library.downloadCall)
        assertEquals(73L, rig.library.downloadStateJob)
        assertEquals(setOf(2L, 4L), rig.library.downloadStateKnownIds)

        pair.host.send(LinkMsg.Launch(SESSION, 1))
        awaitView(rig, GuestStep.IN_GAME)
        assertEquals(game.id, rig.emulators.launches.last().game.id)
    }

    @Test
    fun raMismatchDoesNotOfferUseAnywayButPspMismatchCanProceedAfterConfirmation() = runTest {
        val raGame = guestGame(folder = "snes")
        val raOffer = guestOffer(folder = "snes", core = "snes9x")
        val ra = rig(this, game = raGame, offered = raOffer)
        ra.library.matchValue = MatchResult.Mismatch(raGame.id)
        open(ra, this, TogetherMode.RA_NETPLAY, raOffer)

        val mismatch = awaitView(ra, GuestStep.ACTION_NEEDED, GuestPhase.MISMATCH)
        assertNull(mismatch.problem?.action)

        val pspGame = guestGame()
        val pspOffer = guestOffer()
        val psp = rig(this, game = pspGame, offered = pspOffer)
        psp.library.matchValue = MatchResult.Mismatch(pspGame.id)
        open(psp, this, TogetherMode.PSP_ADHOC, pspOffer)
        val pspMismatch = awaitView(psp, GuestStep.ACTION_NEEDED, GuestPhase.MISMATCH)
        assertEquals(TogetherAction.USE_ANYWAY, pspMismatch.problem?.action)

        psp.engine.act(TogetherAction.USE_ANYWAY)
        awaitView(psp, GuestStep.READY)
    }

    @Test
    fun matchingMissingCoreRequestsCopyFromHost() = runTest {
        val game = guestGame(folder = "snes", coreOverride = "other-core")
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val build = coreBuild(byteArrayOf(1, 2, 3))
        val rig = rig(this, game = game, offered = offered)
        rig.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        open(rig, this, TogetherMode.RA_NETPLAY, offered, build)

        val view = awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        assertEquals(TogetherAction.COPY_CORE, view.problem?.action)
        assertEquals(TogetherCopy.needsCoreFromHost("Host", CoreNames.display("snes9x")), view.problem?.text)
    }

    @Test
    fun offAllowlistCoreIsNotSideloaded() = runTest {
        val game = guestGame(folder = "snes", coreOverride = "other-core")
        val offered = guestOffer(folder = "snes", core = "fceumm")
        val build = coreBuild(byteArrayOf(1, 2, 3)).copy(core = "fceumm")
        val rig = rig(this, game = game, offered = offered)
        rig.emulators.readinessValue = Readiness.NeedsCore("fceumm")
        open(rig, this, TogetherMode.RA_NETPLAY, offered, build)

        runCurrent()
        assertEquals(GuestStep.WAITING_OFFER, rig.engine.view.value?.step)
        assertTrue(rig.emulators.installCalls.isEmpty())
        assertTrue(rig.emulators.fetchCalls.isEmpty())
        assertTrue(rig.emulators.receivedCoreNames.isEmpty())
        assertTrue(rig.logs.any { it.contains("rejected offer core") })
    }

    @Test
    fun installCoreShowsFetchProgressThenInstallingOrFailed() = runTest {
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val started = rig(this, game = guestGame(folder = "snes"), offered = offered)
        started.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        started.emulators.fetchGate = CompletableDeferred()
        val pair = open(started, this, TogetherMode.RA_NETPLAY, offered)
        val needed = awaitView(started, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_CORE)
        assertEquals(TogetherAction.INSTALL_CORE, needed.problem?.action)
        assertEquals(TogetherCopy.needsCore(CoreNames.display("snes9x")), needed.problem?.text)

        started.engine.act(TogetherAction.INSTALL_CORE)
        val fetching = awaitView(started, GuestStep.INSTALLING_CORE, GuestPhase.INSTALLING_CORE) { it.coreInstall?.read == 1L }
        assertEquals(100, fetching.progress)
        assertEquals("snes9x", started.emulators.fetchCalls.single().first)
        started.emulators.fetchGate?.complete(Unit)
        val installing = awaitView(started, GuestStep.INSTALLING_CORE, GuestPhase.INSTALLING_CORE) { it.coreInstall == null }
        assertNull(installing.message)
        assertNull(installing.progress)
        runCurrent()
        val sent = pair.guest.sent.filterIsInstance<LinkMsg.GuestState>().last { it.phase == GuestPhase.INSTALLING_CORE }
        assertNull(sent.detail)

        val failedOffer = guestOffer(folder = "snes", core = "snes9x")
        val failed = rig(this, game = guestGame(folder = "snes"), offered = failedOffer)
        failed.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        failed.emulators.fetchResult = CoreResult.Failed("blocked")
        open(failed, this, TogetherMode.RA_NETPLAY, failedOffer)
        awaitView(failed, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_CORE)
        failed.engine.act(TogetherAction.INSTALL_CORE)
        val blocked = awaitView(failed, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_CORE) { it.problem?.text == "blocked" }
        assertEquals(TogetherAction.INSTALL_CORE, blocked.problem?.action)
    }

    @Test
    fun confirmedCoreInstallAutomaticallyRechecksWithoutAnotherForegroundEvent() = runTest {
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val h = rig(this, game = guestGame(folder = "snes"), offered = offered)
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        h.emulators.fetchGate = CompletableDeferred()
        open(h, this, TogetherMode.RA_NETPLAY, offered)
        awaitView(h, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_CORE)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        h.emulators.readinessValue = Readiness.Ready
        h.emulators.fetchGate!!.complete(Unit)
        awaitView(h, GuestStep.INSTALLING_CORE) { it.coreInstall == null }
        advanceTimeBy(1_000L)
        runCurrent()
        awaitView(h, GuestStep.READY)
    }

    @Test
    fun failedCoreInstallPreservesTheOpenRetroArchRecoveryAction() = runTest {
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val h = rig(this, game = guestGame(folder = "snes"), offered = offered)
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        h.emulators.fetchResult = CoreResult.Failed("Open RetroArch once to finish setup.", TogetherAction.OPEN_RETROARCH)
        open(h, this, TogetherMode.RA_NETPLAY, offered)
        awaitView(h, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_CORE)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(TogetherAction.OPEN_RETROARCH, h.engine.view.value?.problem?.action)
    }

    @Test
    fun foregroundReprepsImmediatelyAndDelaysCoreInstallResume() = runTest {
        val blocked = rig(this)
        blocked.emulators.readinessValue = Readiness.Blocked("hold")
        open(blocked, this, TogetherMode.PSP_ADHOC)
        awaitView(blocked, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_SETUP)
        blocked.emulators.readinessValue = Readiness.Ready
        blocked.engine.onForeground()
        awaitView(blocked, GuestStep.READY)

        val installingOffer = guestOffer(folder = "snes", core = "snes9x")
        val installing = rig(this, game = guestGame(folder = "snes"), offered = installingOffer)
        installing.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        open(installing, this, TogetherMode.RA_NETPLAY, installingOffer)
        awaitView(installing, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_CORE)
        installing.engine.act(TogetherAction.INSTALL_CORE)
        awaitView(installing, GuestStep.INSTALLING_CORE) { it.coreInstall == null }
        installing.engine.onForeground()
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(GuestStep.INSTALLING_CORE, installing.engine.view.value?.step)
        advanceTimeBy(1)
        awaitView(installing, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_CORE)
    }

    @Test
    fun coreInstallPublishesBeforeAnyCallbackAndForegroundOrRepeatedActionsKeepOwnership() = runTest {
        lateinit var backend: ControlledCoreEmulators
        val h = rig(this, game = guestGame(folder = "snes"), offered = guestOffer(folder = "snes", core = "snes9x"),
            emulatorForEngine = { ControlledCoreEmulators(it).also { controlled -> backend = controlled } })
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        val pair = open(h, this, TogetherMode.RA_NETPLAY)
        h.engine.act(TogetherAction.INSTALL_CORE)
        val busy = awaitView(h, GuestStep.INSTALLING_CORE)
        assertEquals(CoreInstallProgress("snes9x"), busy.coreInstall)
        assertNull(busy.problem)
        assertEquals(1, backend.callbacks.size)
        repeat(3) { h.engine.act(TogetherAction.INSTALL_CORE) }
        h.engine.act(TogetherAction.RECHECK)
        h.engine.onForeground()
        h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, backend.callbacks.size)
        assertEquals(1, backend.readinessCalls)
        assertEquals(busy.coreInstall, h.engine.view.value?.coreInstall)
        assertTrue(pair.guest.sent.filterIsInstance<LinkMsg.GuestState>().any {
            it.phase == GuestPhase.INSTALLING_CORE && it.progress == 0 && it.detail == null
        })
        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        runCurrent()
        assertEquals(GuestStep.ACTION_NEEDED, h.engine.view.value?.step)
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, h.engine.view.value?.problem?.action)
        assertNull(h.engine.view.value?.coreInstall)
        h.engine.onForeground()
        runCurrent()
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, h.engine.view.value?.problem?.action)
    }

    @Test
    fun coreInstallPreservesBytesBoundsPercentAndDoesNotResetStallForIdenticalCallbacks() = runTest {
        lateinit var backend: ControlledCoreEmulators
        val h = rig(this, game = guestGame(folder = "snes"), offered = guestOffer(folder = "snes", core = "snes9x"),
            emulatorForEngine = { ControlledCoreEmulators(it).also { controlled -> backend = controlled } })
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        val pair = open(h, this, TogetherMode.RA_NETPLAY)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        val callback = backend.callbacks.single()
        callback(2_097_152L, 8_388_608L)
        runCurrent()
        assertEquals("Downloading 2.0 of 8.0 MB", h.engine.view.value?.coreInstall?.text)
        assertEquals(25, h.engine.view.value?.progress)
        advanceTimeBy(7_999L)
        callback(2_097_152L, 8_388_608L)
        runCurrent()
        assertFalse(h.engine.view.value!!.coreInstall!!.stalled)
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(h.engine.view.value!!.coreInstall!!.stalled)
        callback(2_097_152L, 8_388_608L)
        runCurrent()
        assertTrue(h.engine.view.value!!.coreInstall!!.stalled)
        callback(3_145_728L, 0L)
        runCurrent()
        assertEquals("Downloading 3.0 MB", h.engine.view.value?.coreInstall?.text)
        assertFalse(h.engine.view.value!!.coreInstall!!.stalled)
        assertNull(h.engine.view.value?.coreInstall?.fraction)
        callback(Long.MAX_VALUE, Long.MAX_VALUE)
        runCurrent()
        assertEquals(100, h.engine.view.value?.progress)
        callback(Long.MAX_VALUE, 100L)
        runCurrent()
        assertEquals(100L, h.engine.view.value?.coreInstall?.read)
        callback(-5L, -2L)
        runCurrent()
        assertEquals(0L, h.engine.view.value?.coreInstall?.read)
        assertEquals(0L, h.engine.view.value?.coreInstall?.total)
        callback(-1L, -1L)
        runCurrent()
        assertTrue(h.engine.view.value!!.coreInstall!!.installing)
        assertNull(h.engine.view.value?.progress)
        assertNull(h.engine.view.value?.coreInstall?.fraction)
        advanceTimeBy(7_999L)
        callback(-1L, -1L)
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(h.engine.view.value!!.coreInstall!!.stalled)
        assertTrue(pair.guest.sent.filterIsInstance<LinkMsg.GuestState>().filter { it.phase == GuestPhase.INSTALLING_CORE }
            .all { it.progress == null || it.progress in 0..100 })
        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        runCurrent()
    }

    @Test
    fun stalledRetryWaitsForNonCancellablePredecessorAndIgnoresOldCallbacksAndFinally() = runTest {
        lateinit var backend: ControlledCoreEmulators
        val gate = CompletableDeferred<Unit>()
        val h = rig(this, game = guestGame(folder = "snes"), offered = guestOffer(folder = "snes", core = "snes9x"),
            emulatorForEngine = { ControlledCoreEmulators(it).also { controlled ->
                backend = controlled
                controlled.fetch = { call, callback ->
                    if (call == 1) withContext(NonCancellable) {
                        gate.await()
                        callback(99, 100)
                        CoreResult.Failed("old outcome", TogetherAction.OPEN_RETROARCH)
                    } else {
                        callback(4, 10)
                        awaitCancellation()
                    }
                }
            } })
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        open(h, this, TogetherMode.RA_NETPLAY)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        advanceTimeBy(8_000L)
        runCurrent()
        assertTrue(h.engine.view.value!!.coreInstall!!.stalled)
        h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
        backend.callbacks[0](8, 10)
        runCurrent()
        val replacement = h.engine.view.value?.coreInstall
        assertEquals(CoreInstallProgress("snes9x"), replacement)
        assertEquals(1, backend.callbacks.size)
        repeat(3) { h.engine.act(TogetherAction.RETRY_CORE_INSTALL) }
        runCurrent()
        assertEquals(replacement, h.engine.view.value?.coreInstall)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, backend.callbacks.size)
        assertEquals(4L, h.engine.view.value?.coreInstall?.read)
        assertNull(h.engine.view.value?.problem)
        backend.callbacks[0](-1, -1)
        runCurrent()
        assertFalse(h.engine.view.value!!.coreInstall!!.installing)
        assertEquals(4L, h.engine.view.value?.coreInstall?.read)
        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        runCurrent()
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, h.engine.view.value?.problem?.action)
    }

    @Test
    fun eightSecondsWithoutFirstCallbackOffersRetryAndCancel() = runTest {
        lateinit var backend: ControlledCoreEmulators
        val h = rig(this, game = guestGame(folder = "snes"), offered = guestOffer(folder = "snes", core = "snes9x"),
            emulatorForEngine = { ControlledCoreEmulators(it).also { controlled -> backend = controlled } })
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        open(h, this, TogetherMode.RA_NETPLAY)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        advanceTimeBy(7_999L)
        runCurrent()
        assertFalse(h.engine.view.value!!.coreInstall!!.stalled)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals("Still working…", h.engine.view.value?.coreInstall?.text)
        h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
        runCurrent()
        assertEquals(2, backend.callbacks.size)
        assertFalse(h.engine.view.value!!.coreInstall!!.stalled)
        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        runCurrent()
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, h.engine.view.value?.problem?.action)
        assertNull(h.engine.view.value?.coreInstall)
    }

    @Test
    fun alreadyQueuedOldResultAndFinallyCannotClearReplacementProgress() = runTest {
        lateinit var h: Rig
        lateinit var backend: ControlledCoreEmulators
        h = rig(this, game = guestGame(folder = "snes"), offered = guestOffer(folder = "snes", core = "snes9x"),
            emulatorForEngine = { ControlledCoreEmulators(it).also { controlled ->
                backend = controlled
                controlled.fetch = { call, callback ->
                    if (call == 1) {
                        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
                        h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
                        CoreResult.Failed("stale result", TogetherAction.OPEN_RETROARCH)
                    } else {
                        callback(6, 10)
                        awaitCancellation()
                    }
                }
            } })
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        open(h, this, TogetherMode.RA_NETPLAY)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(2, backend.callbacks.size)
        assertEquals(6L, h.engine.view.value?.coreInstall?.read)
        assertNull(h.engine.view.value?.problem)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(GuestStep.INSTALLING_CORE, h.engine.view.value?.step)
        assertEquals(6L, h.engine.view.value?.coreInstall?.read)
        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        runCurrent()
    }

    @Test
    fun cancellationBeforeBackendEntryCannotStrandBusyMarkerOrEraseRetry() = runTest {
        lateinit var backend: ControlledCoreEmulators
        val h = rig(this, game = guestGame(folder = "snes"), offered = guestOffer(folder = "snes", core = "snes9x"),
            emulatorForEngine = { ControlledCoreEmulators(it).also { controlled -> backend = controlled } })
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        open(h, this, TogetherMode.RA_NETPLAY)
        h.engine.act(TogetherAction.INSTALL_CORE)
        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        runCurrent()
        assertTrue(backend.callbacks.isEmpty())
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, h.engine.view.value?.problem?.action)
        assertNull(h.engine.view.value?.coreInstall)
        h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
        runCurrent()
        assertEquals(1, backend.callbacks.size)
        assertEquals(CoreInstallProgress("snes9x"), h.engine.view.value?.coreInstall)
        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
        runCurrent()
        assertEquals(2, backend.callbacks.size)
        assertEquals(CoreInstallProgress("snes9x"), h.engine.view.value?.coreInstall)
        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        runCurrent()
    }

    @Test
    fun backendCancellationSettlesRetryAndThrownFailuresPreserveRecovery() = runTest {
        lateinit var backend: ControlledCoreEmulators
        val h = rig(this, game = guestGame(folder = "snes"), offered = guestOffer(folder = "snes", core = "snes9x"),
            emulatorForEngine = { ControlledCoreEmulators(it).also { controlled ->
                backend = controlled
                controlled.fetch = { call, _ ->
                    when (call) {
                        1 -> throw CancellationException("backend cancelled")
                        2 -> throw IllegalStateException("install crashed")
                        else -> CoreResult.Failed("Open RetroArch once.", TogetherAction.OPEN_RETROARCH)
                    }
                }
            } })
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        open(h, this, TogetherMode.RA_NETPLAY)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, h.engine.view.value?.problem?.action)
        assertNull(h.engine.view.value?.coreInstall)
        h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
        runCurrent()
        assertEquals("install crashed", h.engine.view.value?.problem?.text)
        assertNull(h.engine.view.value?.coreInstall)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(3, backend.callbacks.size)
        assertEquals(TogetherAction.OPEN_RETROARCH, h.engine.view.value?.problem?.action)
        h.engine.act(TogetherAction.OPEN_RETROARCH)
        runCurrent()
        assertEquals("Couldn't open RetroArch. Open it from your launcher, then check again.", h.engine.view.value?.message)
        assertEquals(TogetherAction.OPEN_RETROARCH, h.engine.view.value?.problem?.action)
        h.emulators.readinessValue = Readiness.Ready
        h.engine.onForeground()
        awaitView(h, GuestStep.READY)
    }

    @Test
    fun openRetroArchThrowReportsRecoveryWithoutLosingAction() = runTest {
        val h = rig(this, game = guestGame(folder = "snes"), offered = guestOffer(folder = "snes", core = "snes9x"),
            platformForEngine = { platform -> object : TogetherPlatform by platform {
                override fun openRetroArch(): Boolean = throw IllegalStateException("launcher missing")
            } })
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        h.emulators.fetchResult = CoreResult.Failed("Open RetroArch once.", TogetherAction.OPEN_RETROARCH)
        open(h, this, TogetherMode.RA_NETPLAY)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        h.engine.act(TogetherAction.OPEN_RETROARCH)
        runCurrent()
        assertEquals("Couldn't open RetroArch. Open it from your launcher, then check again.", h.engine.view.value?.message)
        assertEquals(TogetherAction.OPEN_RETROARCH, h.engine.view.value?.problem?.action)
    }

    @Test
    fun confirmedCoreInstallInBackgroundWaitsForForegroundReadinessLaunch() = runTest {
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val h = rig(this, game = guestGame(folder = "snes"), offered = offered)
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        h.emulators.fetchGate = CompletableDeferred()
        open(h, this, TogetherMode.RA_NETPLAY)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        h.platform.foreground.value = false
        h.emulators.readinessValue = Readiness.Ready
        h.emulators.fetchGate!!.complete(Unit)
        runCurrent()
        assertNull(h.engine.view.value?.coreInstall)
        advanceTimeBy(9_000L)
        runCurrent()
        assertEquals(GuestStep.INSTALLING_CORE, h.engine.view.value?.step)
        h.platform.foreground.value = true
        h.engine.onForeground()
        runCurrent()
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(GuestStep.INSTALLING_CORE, h.engine.view.value?.step)
        advanceTimeBy(1L)
        awaitView(h, GuestStep.READY)
    }

    @Test
    fun oldInstallCallbacksResultAndCleanupCannotMutateNewSessionOrOverlapItsInstall() = runTest {
        lateinit var backend: ControlledCoreEmulators
        val gate = CompletableDeferred<Unit>()
        val h = rig(this, game = guestGame(folder = "snes"), offered = guestOffer(folder = "snes", core = "snes9x"),
            emulatorForEngine = { ControlledCoreEmulators(it).also { controlled ->
                backend = controlled
                controlled.fetch = { call, callback ->
                    if (call == 1) withContext(NonCancellable) {
                        gate.await()
                        callback(-1, -1)
                        CoreResult.Started
                    } else {
                        callback(3, 10)
                        awaitCancellation()
                    }
                }
            } })
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        open(h, this, TogetherMode.RA_NETPLAY)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        h.engine.join(target())
        backend.callbacks[0](9, 10)
        runCurrent()
        assertEquals(GuestStep.WAITING_OFFER, h.engine.view.value?.step)
        sendOffer(h.transport.pair, TogetherMode.RA_NETPLAY, h.offered)
        runCurrent()
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(1, backend.callbacks.size)
        assertEquals(CoreInstallProgress("snes9x"), h.engine.view.value?.coreInstall)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, backend.callbacks.size)
        assertEquals(3L, h.engine.view.value?.coreInstall?.read)
        assertNull(h.engine.view.value?.problem)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(GuestStep.INSTALLING_CORE, h.engine.view.value?.step)
        assertEquals(3L, h.engine.view.value?.coreInstall?.read)
        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        runCurrent()
    }

    @Test
    fun synchronousNewJoinDuringCompletionForegroundReadCannotScheduleOldReadiness() = runTest {
        val h = rig(this, game = guestGame(folder = "snes"), offered = guestOffer(folder = "snes", core = "snes9x"))
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        h.emulators.fetchGate = CompletableDeferred()
        open(h, this, TogetherMode.RA_NETPLAY)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        h.platform.onForegroundRead.set { h.engine.prepareJoin(target()) }
        h.emulators.fetchGate!!.complete(Unit)
        runCurrent()
        advanceTimeBy(9_000L)
        runCurrent()
        assertEquals(GuestStep.CONNECTING, h.engine.view.value?.step)
        assertNull(h.engine.view.value?.coreInstall)
    }

    @Test
    fun needsSetupDetailIsCappedAndOtherPhasesOmitIt() = runTest {
        val long = "x".repeat(200)
        val setup = rig(this)
        setup.emulators.readinessValue = Readiness.Blocked(long)
        val setupPair = open(setup, this, TogetherMode.PSP_ADHOC)
        awaitView(setup, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_SETUP)
        runCurrent()
        val setupState = setupPair.guest.sent.filterIsInstance<LinkMsg.GuestState>().last { it.phase == GuestPhase.NEEDS_SETUP }
        assertEquals(long.take(120), setupState.detail)
        assertTrue(
            setupPair.guest.sent.filterIsInstance<LinkMsg.GuestState>().any {
                it.phase == GuestPhase.CHECKING && it.detail == null
            },
        )

        val core = rig(this)
        core.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        val corePair = open(core, this, TogetherMode.PSP_ADHOC)
        awaitView(core, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_CORE)
        runCurrent()
        val coreState = corePair.guest.sent.filterIsInstance<LinkMsg.GuestState>().last { it.phase == GuestPhase.NEEDS_CORE }
        assertNull(coreState.detail)
    }

    @Test
    fun copiedCoreIsVerifiedBeforeInstallAndCachedCoreSkipsRequest() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val build = coreBuild(bytes)
        val game = guestGame(folder = "snes", coreOverride = "other-core")
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val rig = rig(this, game = game, offered = offered)
        rig.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        val dest = File.createTempFile("together-core", ".so").also { it.delete() }
        rig.emulators.coreDestination = dest
        val pair = open(rig, this, TogetherMode.RA_NETPLAY, offered, build)
        awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        rig.engine.act(TogetherAction.COPY_CORE)
        awaitView(rig, GuestStep.CORE_SYNC)
        pair.host.events.receiveMessage { it is LinkMsg.CoreRequest }

        pair.host.send(LinkMsg.CoreBegin(SESSION, 9, build))
        val transferFile = fileWithBytes(bytes)
        pair.host.sendBlob(9, transferFile)
        transferFile.delete()
        pair.host.send(LinkMsg.CoreEnd(SESSION, 9))
        awaitView(rig, GuestStep.INSTALLING_CORE)

        assertEquals(bytes.toList(), dest.readBytes().toList())
        assertEquals(Triple(dest, build, game), rig.emulators.installCalls.single())
        dest.delete()
        File(dest.path + ".part").delete()

        val cached = rig(this, game = game, offered = offered)
        cached.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        cached.emulators.received = true
        val cachedPair = open(cached, this, TogetherMode.RA_NETPLAY, offered, build)
        awaitView(cached, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        cached.engine.act(TogetherAction.COPY_CORE)
        awaitView(cached, GuestStep.INSTALLING_CORE)
        assertTrue(cachedPair.guest.sent.none { it is LinkMsg.CoreRequest })
        assertEquals(build, cached.emulators.installCalls.single().second)
    }

    @Test
    fun copiedCoreRecoveryPreservesOpenRetroArchAndOrdinaryFailureStillOffersCopy() = runTest {
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val build = coreBuild(byteArrayOf(1, 2, 3))
        val h = rig(this, game = guestGame(folder = "snes"), offered = offered)
        h.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        h.emulators.received = true
        h.emulators.installResult = CoreResult.Failed("Open RetroArch once.", TogetherAction.OPEN_RETROARCH)
        open(h, this, TogetherMode.RA_NETPLAY, core = build)
        h.engine.act(TogetherAction.COPY_CORE)
        runCurrent()
        assertEquals(GuestPhase.CORE_SYNC, h.engine.view.value?.phase)
        assertEquals(TogetherAction.OPEN_RETROARCH, h.engine.view.value?.problem?.action)
        h.emulators.installResult = CoreResult.Failed("copy failed")
        h.engine.act(TogetherAction.COPY_CORE)
        runCurrent()
        assertEquals(TogetherAction.COPY_CORE, h.engine.view.value?.problem?.action)
        assertNull(h.engine.view.value?.coreInstall)
    }

    @Test
    fun unsafeCoreBeginAndBadHashBecomeCopyCoreProblems() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val build = coreBuild(bytes)
        val game = guestGame(folder = "snes")
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val unsafe = rig(this, game = game, offered = offered)
        unsafe.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        val unsafePair = open(unsafe, this, TogetherMode.RA_NETPLAY, offered, build)
        awaitView(unsafe, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        unsafe.engine.act(TogetherAction.COPY_CORE)
        awaitView(unsafe, GuestStep.CORE_SYNC)
        unsafePair.host.events.receiveMessage { it is LinkMsg.CoreRequest }
        unsafePair.host.send(LinkMsg.CoreBegin(SESSION, 4, CoreBuild("../unsafe", build.sha256, build.sizeBytes)))
        val unsafeFailure = awaitView(unsafe, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        assertEquals(TogetherAction.COPY_CORE, unsafeFailure.problem?.action)

        val bad = rig(this, game = game, offered = offered)
        bad.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        val badDestination = File.createTempFile("together-bad-core", ".so").also { it.delete() }
        bad.emulators.coreDestination = badDestination
        val badPair = open(bad, this, TogetherMode.RA_NETPLAY, offered, build.copy(sha256 = "0".repeat(64)))
        awaitView(bad, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        bad.engine.act(TogetherAction.COPY_CORE)
        awaitView(bad, GuestStep.CORE_SYNC)
        badPair.host.events.receiveMessage { it is LinkMsg.CoreRequest }
        val expectedWrongHash = build.copy(sha256 = "0".repeat(64))
        badPair.host.send(LinkMsg.CoreBegin(SESSION, 5, expectedWrongHash))
        val badFile = fileWithBytes(bytes)
        badPair.host.sendBlob(5, badFile)
        badFile.delete()
        badPair.host.send(LinkMsg.CoreEnd(SESSION, 5))
        val badHash = awaitView(bad, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        assertEquals(TogetherAction.COPY_CORE, badHash.problem?.action)
        assertFalse(badDestination.exists())
        assertFalse(File(badDestination.path + ".part").exists())
    }

    @Test
    fun coreTransferInactivityAbortsPartialFile() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val build = coreBuild(bytes)
        val game = guestGame(folder = "snes")
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val rig = rig(
            this,
            game = game,
            offered = offered,
            timing = timing(coreInactivityMs = 100L),
        )
        rig.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        val dest = File.createTempFile("together-timeout-core", ".so").also { it.delete() }
        rig.emulators.coreDestination = dest
        val pair = open(rig, this, TogetherMode.RA_NETPLAY, offered, build)
        awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        rig.engine.act(TogetherAction.COPY_CORE)
        awaitView(rig, GuestStep.CORE_SYNC)
        pair.host.events.receiveMessage { it is LinkMsg.CoreRequest }
        pair.host.send(LinkMsg.CoreBegin(SESSION, 6, build))
        runCurrent()
        val partial = fileWithBytes(byteArrayOf(1, 2))
        pair.host.sendBlob(6, partial)
        partial.delete()
        awaitView(rig, GuestStep.CORE_SYNC) { it.progress == 66 }
        assertTrue(File(dest.path + ".part").exists())

        advanceTimeBy(100)
        runCurrent()
        val failed = awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        assertEquals(TogetherAction.COPY_CORE, failed.problem?.action)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun coreUnavailableInstallsWhenMissingButAllowsPlayWhenOnlyDifferent() = runTest {
        val build = coreBuild(byteArrayOf(1, 2, 3))
        val game = guestGame(folder = "snes")
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val missing = rig(this, game = game, offered = offered)
        missing.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        val missingPair = open(missing, this, TogetherMode.RA_NETPLAY, offered, build)
        awaitView(missing, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        missing.engine.act(TogetherAction.COPY_CORE)
        awaitView(missing, GuestStep.CORE_SYNC)
        missingPair.host.events.receiveMessage { it is LinkMsg.CoreRequest }
        missingPair.host.send(LinkMsg.CoreUnavailable(SESSION, "snes9x"))
        val needsCore = awaitView(missing, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_CORE)
        assertEquals(TogetherAction.INSTALL_CORE, needsCore.problem?.action)

        val differs = rig(this, game = game, offered = offered)
        val differsPair = open(differs, this, TogetherMode.RA_NETPLAY, offered, build)
        awaitView(differs, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        differs.engine.act(TogetherAction.COPY_CORE)
        awaitView(differs, GuestStep.CORE_SYNC)
        differsPair.host.events.receiveMessage { it is LinkMsg.CoreRequest }
        differsPair.host.send(LinkMsg.CoreUnavailable(SESSION, "snes9x"))
        val ready = awaitView(differs, GuestStep.READY)
        assertEquals(TogetherCopy.coreSyncUnavailable("Host"), ready.message)
    }

    @Test
    fun raLaunchCountsDownAndUsesTheOfferedCoreWithoutHostLive() = runTest {
        val game = guestGame(folder = "snes", coreOverride = "bsnes")
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val rig = rig(this, game = game, offered = offered, timing = timing(countdownS = 2))
        val pair = open(rig, this, TogetherMode.RA_NETPLAY, offered)
        awaitView(rig, GuestStep.READY)

        pair.host.send(LinkMsg.Launch(SESSION, 1))
        runCurrent()
        assertEquals(GuestStep.LAUNCHING, rig.engine.view.value?.step)
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(rig.emulators.launches.isEmpty())
        advanceTimeBy(1)
        awaitView(rig, GuestStep.IN_GAME)

        assertEquals("snes9x", rig.emulators.launches.single().core)
        assertTrue(pair.host.sent.none { it is LinkMsg.HostLive })
    }

    @Test
    fun pspLaunchWaitsForServerOrLaunchDeadline() = runTest {
        val timeoutRig = rig(
            this,
            timing = timing(launchWaitMs = 300L, watchIntervalMs = 100L, probeTimeoutMs = 1L),
        )
        val timeoutPair = open(timeoutRig, this, TogetherMode.PSP_ADHOC)
        awaitView(timeoutRig, GuestStep.READY)
        timeoutPair.host.send(LinkMsg.Launch(SESSION, 1))
        runCurrent()
        advanceTimeBy(299)
        runCurrent()
        assertTrue(timeoutRig.emulators.launches.isEmpty())
        advanceTimeBy(1)
        awaitView(timeoutRig, GuestStep.IN_GAME)
        assertTrue(timeoutRig.probes.tcpCalls > 0)
        assertTrue(timeoutRig.probes.tcpPorts.all { it == 27312 })

        val serverRig = rig(
            this,
            timing = timing(launchWaitMs = 500L, watchIntervalMs = 100L, probeTimeoutMs = 1L),
        )
        serverRig.probes.tcp = true
        val serverPair = open(serverRig, this, TogetherMode.PSP_ADHOC)
        awaitView(serverRig, GuestStep.READY)
        serverPair.host.send(LinkMsg.Launch(SESSION, 1))
        runCurrent()
        val startedAt = testScheduler.currentTime
        advanceTimeBy(99)
        runCurrent()
        assertTrue(serverRig.emulators.launches.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, serverRig.emulators.launches.size)
        assertTrue(testScheduler.currentTime - startedAt < 500L)
        assertTrue(serverRig.probes.tcpCalls > 0)
        assertTrue(serverRig.probes.tcpPorts.all { it == 27312 })
    }

    @Test
    fun backgroundLaunchAlertsAndWaitsForForeground() = runTest {
        val rig = rig(this)
        rig.platform.foreground.value = false
        val pair = open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        runCurrent()

        assertTrue(rig.platform.alerts.any { it is TogetherAlert.TapToStart })
        assertTrue(rig.emulators.launches.isEmpty())
        rig.platform.foreground.value = true
        awaitView(rig, GuestStep.IN_GAME)
    }

    @Test
    fun launchWhileLaunchingRestartsAndDropsTheOldResult() = runTest {
        val rig = rig(this)
        val staleGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        rig.emulators.launchGates += staleGate
        rig.emulators.launchGates += newGate
        rig.emulators.launchOutcomes += EmuRouter.Outcome.Failed("stale launch")
        rig.emulators.launchOutcomes += EmuRouter.Outcome.Launched("emu", "pkg")
        val pair = open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)

        pair.host.send(LinkMsg.Launch(SESSION, 1))
        runCurrent()
        withTimeout(5_000) { rig.emulators.launchStarted.receive() }
        pair.host.send(LinkMsg.Launch(SESSION, 2))
        runCurrent()
        withTimeout(5_000) { rig.emulators.launchStarted.receive() }

        // Round 1's failure lands while the view is LAUNCHING for round 2, so only
        // the epoch/round check stands between it and the view.
        staleGate.complete(Unit)
        runCurrent()
        assertEquals(GuestStep.LAUNCHING, rig.engine.view.value?.step)
        assertNull(rig.engine.view.value?.problem)

        newGate.complete(Unit)
        val playing = awaitView(rig, GuestStep.IN_GAME)
        assertNull(playing.problem)
    }

    @Test
    fun aFinishedSecondLaunchDoesNotClearTheFirstLaunchMarker() = runTest {
        val rig = rig(this)
        val firstGate = CompletableDeferred<Unit>()
        rig.emulators.launchGates += firstGate
        val pair = open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)

        pair.host.send(LinkMsg.Launch(SESSION, 1))
        runCurrent()
        withTimeout(5_000) { rig.emulators.launchStarted.receive() }
        pair.host.send(LinkMsg.Launch(SESSION, 2))
        runCurrent()
        withTimeout(5_000) { rig.emulators.launchStarted.receive() }
        awaitView(rig, GuestStep.IN_GAME)

        rig.engine.end()
        runCurrent()
        assertFalse(rig.emulators.trace.contains("endSession"))
        firstGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("write-start", "write-start", "write-done", "write-done", "endSession"), rig.emulators.trace)
    }

    @Test(timeout = 20_000)
    fun endRacingAResumingLaunchNeverLaunchesAfterEndSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = GuestTestTransport()
            val emulators = GuestTestEmulators()
            val platform = GuestTestPlatform()
            val engine = GuestEngine(
                transport, GuestTestProbes(), emulators, GuestTestLibrary(), platform, timing(), scope,
                Dispatchers.IO, logSink = {},
            )
            engine.join(target())
            val pair = withTimeout(5_000) { transport.connected.await() }
            sendOffer(pair, TogetherMode.RA_NETPLAY, guestOffer())
            withTimeout(5_000) { engine.view.first { it?.step == GuestStep.READY } }

            // The launch step reads foreground just before its critical section.
            // Hold it there, on its own thread, while End runs to completion.
            val resumed = CountDownLatch(1)
            val release = CountDownLatch(1)
            platform.onForegroundRead.set {
                resumed.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            pair.host.send(LinkMsg.Launch(SESSION, 1))
            assertTrue(resumed.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { engine.endAndAwait() }
            assertEquals(1, emulators.endSessionCount)

            release.countDown()
            assertNull(withTimeoutOrNull(500) { emulators.launchStarted.receive() })
            assertTrue(emulators.launches.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retroarchRoomAlertOccursOncePerRound() = runTest {
        val rig = rig(this, timing = timing(watchIntervalMs = 100L, countdownS = 1))
        rig.probes.room = com.gamebex.client.together.RaRoom("Guest", "snes9x", "1", "game", 1, 55435, "1")
        val pair = open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)

        pair.host.send(LinkMsg.Launch(SESSION, 1))
        awaitView(rig, GuestStep.LAUNCHING)
        advanceTimeBy(1_000)
        awaitView(rig, GuestStep.IN_GAME)
        runCurrent()
        advanceTimeBy(100)
        awaitView(rig, GuestStep.IN_GAME) { it.message == TogetherCopy.roomOpen("Host") }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, rig.platform.alerts.count { it is TogetherAlert.RoomOpen })

        pair.host.send(LinkMsg.Launch(SESSION, 2))
        runCurrent()
        awaitView(rig, GuestStep.LAUNCHING)
        advanceTimeBy(1_000)
        awaitView(rig, GuestStep.IN_GAME)
        runCurrent()
        advanceTimeBy(100)
        awaitView(rig, GuestStep.IN_GAME) { it.message == TogetherCopy.roomOpen("Host") }
        assertEquals(2, rig.platform.alerts.count { it is TogetherAlert.RoomOpen })
    }

    @Test
    fun byeEndsInGameButClosedKeepsTheGuestInGame() = runTest {
        val ended = rig(this)
        val byePair = open(ended, this, TogetherMode.RA_NETPLAY)
        awaitView(ended, GuestStep.READY)
        byePair.host.send(LinkMsg.Launch(SESSION, 1))
        awaitView(ended, GuestStep.IN_GAME)
        byePair.host.send(LinkMsg.Bye(ByeReason.LEFT))
        val byeView = awaitView(ended, GuestStep.ENDED)
        assertEquals(TogetherCopy.byeText(ByeReason.LEFT, "Host"), byeView.message)
        assertEquals(1, ended.platform.stopServiceCount)

        val lost = rig(this)
        val lostPair = open(lost, this, TogetherMode.RA_NETPLAY)
        awaitView(lost, GuestStep.READY)
        lostPair.host.send(LinkMsg.Launch(SESSION, 1))
        awaitView(lost, GuestStep.IN_GAME)
        lostPair.host.close()
        val lostView = awaitView(lost, GuestStep.IN_GAME) { it.linkLost }
        assertTrue(lostView.linkLost)
        assertTrue(lost.platform.alerts.isEmpty())
    }

    @Test
    fun endedDownloadCannotChangeTheGuestView() = runTest {
        val offered = guestOffer(folder = "snes", core = "snes9x", sourceRefJson = "source-ref")
        val rig = rig(this, game = guestGame(folder = "snes"), offered = offered)
        rig.library.matchValue = MatchResult.Missing(downloadable = true)
        rig.library.sourceValue = "Store"
        rig.library.downloadValue = DownloadResult.Started(4, emptySet())
        open(rig, this, TogetherMode.RA_NETPLAY, offered)
        awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_GAME)
        rig.engine.act(TogetherAction.DOWNLOAD)
        awaitView(rig, GuestStep.DOWNLOADING)
        rig.engine.end()
        awaitView(rig, GuestStep.ENDED)
        rig.library.downloadStates.trySend(DownloadState.Done(9))
        runCurrent()
        assertEquals(GuestStep.ENDED, rig.engine.view.value?.step)
    }

    @Test
    fun gameboyNoMatchShowsChoicesAndPickBecomesReady() = runTest {
        val game = guestGame(id = 2, folder = "gb")
        val offered = guestOffer(folder = "gb", core = "gambatte")
        val rig = rig(this, game = game, offered = offered, timing = timing(launchWaitMs = 5_000L))
        rig.library.matchValue = MatchResult.Missing(false)
        rig.library.choicesValue = listOf(GameChoice(2, "Blue", "gb"))
        val pair = open(rig, this, TogetherMode.GB_LINK, offered)
        val picking = awaitView(rig, GuestStep.PICKING_GAME)
        assertEquals(listOf(GameChoice(2, "Blue", "gb")), picking.choices)

        rig.engine.pick(2)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(rig.emulators.launches.isEmpty())
        pair.host.send(LinkMsg.HostLive(SESSION, 1))
        runCurrent()
        assertEquals(GuestStep.IN_GAME, rig.engine.view.value?.step)
        assertEquals(game.id, rig.emulators.launches.single().game.id)
        assertEquals("gambatte", rig.emulators.launches.single().core)
        assertEquals(0, rig.probes.tcpCalls)
        assertTrue(rig.probes.tcpPorts.none { it == 56400 })
    }

    @Test
    fun staleGameBoyPickClearsAndDoesNotReuseTheMissingId() = runTest {
        val game = guestGame(id = 2, folder = "gb")
        val offered = guestOffer(folder = "gb", core = "gambatte")
        val rig = rig(this, game = game, offered = offered)
        rig.library.matchValue = MatchResult.Missing(false)
        rig.library.choicesValue = listOf(GameChoice(2, "Blue", "gb"))
        open(rig, this, TogetherMode.GB_LINK, offered)
        awaitView(rig, GuestStep.PICKING_GAME)

        rig.library.choicesValue = emptyList()
        rig.engine.pick(99)
        val missing = awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_GAME)
        assertEquals(TogetherCopy.noGbGames, missing.problem?.text)

        rig.library.gameValue = guestGame(id = 99, folder = "gb", title = "Stale")
        rig.library.matchValue = MatchResult.Found(2)
        rig.engine.act(TogetherAction.RECHECK)
        val again = awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_GAME)
        assertEquals(TogetherCopy.noGbGames, again.problem?.text)
        assertTrue(rig.emulators.launches.isEmpty())
    }

    @Test
    fun connectFailureEndsWithCantReach() = runTest {
        val rig = rig(this)
        rig.transport.failConnect = java.io.IOException("down")
        rig.engine.join(target())
        val ended = awaitView(rig, GuestStep.ENDED)
        assertEquals(TogetherCopy.cantReach("Host"), ended.message)
    }

    @Test
    fun offerTimeoutEndsWithCantReach() = runTest {
        val rig = rig(this, timing = timing(offerMs = 50))
        rig.engine.join(target())
        runCurrent()
        advanceTimeBy(50)
        val ended = awaitView(rig, GuestStep.ENDED)
        assertEquals(TogetherCopy.cantReach("Host"), ended.message)
    }

    @Test
    fun secondEndAfterEndedIsIgnored() = runTest {
        val rig = rig(this)
        open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)
        rig.engine.end()
        val ended = awaitView(rig, GuestStep.ENDED)
        val ends = rig.emulators.endSessionCount
        rig.engine.end()
        runCurrent()
        assertEquals(GuestStep.ENDED, rig.engine.view.value?.step)
        assertEquals(ended.message, rig.engine.view.value?.message)
        assertEquals(ends, rig.emulators.endSessionCount)
    }

    @Test
    fun unexpectedPingWhileReadyIsIgnored() = runTest {
        val rig = rig(this)
        val pair = open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Ping(1))
        runCurrent()
        assertEquals(GuestStep.READY, rig.engine.view.value?.step)
        assertTrue(rig.emulators.launches.isEmpty())
    }

    @Test
    fun foundMatchWithMissingGameFallsThroughToChoices() = runTest {
        val game = guestGame(id = 1, folder = "gb")
        val offered = guestOffer(folder = "gb", core = "gambatte")
        val rig = rig(this, game = game, offered = offered)
        rig.library.matchValue = MatchResult.Found(2)
        rig.library.choicesValue = listOf(GameChoice(2, "Blue", "gb"))
        open(rig, this, TogetherMode.GB_LINK, offered)
        val picking = awaitView(rig, GuestStep.PICKING_GAME)
        assertEquals(listOf(GameChoice(2, "Blue", "gb")), picking.choices)
    }

    @Test
    fun openActionsWhileReadyDoNothing() = runTest {
        val rig = rig(this)
        open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)
        rig.engine.act(TogetherAction.OPEN_SYSTEM)
        rig.engine.act(TogetherAction.OPEN_PPSSPP)
        runCurrent()
        assertEquals(GuestStep.READY, rig.engine.view.value?.step)
        assertTrue(rig.emulators.launches.isEmpty())
    }

    @Test
    fun failedLaunchBecomesNeedsSetupAndTruncatesDetail() = runTest {
        val reason = "x".repeat(180)
        val rig = rig(this)
        rig.emulators.launchOutcomes += EmuRouter.Outcome.Failed(reason)
        val pair = open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        val needed = awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_SETUP)
        assertEquals(TogetherCopy.launchFailed(reason), needed.problem?.text)
        assertEquals(TogetherAction.RECHECK, needed.problem?.action)
        runCurrent()
        val state = pair.guest.sent.filterIsInstance<LinkMsg.GuestState>().last()
        assertEquals(GuestPhase.NEEDS_SETUP, state.phase)
        assertEquals(reason.take(120), state.detail)
    }

    @Test
    fun guestEndSendsByeLeftClearsAlertsAndStopsService() = runTest {
        val rig = rig(this)
        val pair = open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)
        rig.engine.end()
        awaitView(rig, GuestStep.ENDED)
        assertEquals(1, rig.platform.cancelAlertsCount)
        assertEquals(1, rig.platform.stopServiceCount)
        assertEquals(1, rig.emulators.endSessionCount)
        assertEquals(listOf(ByeReason.LEFT), pair.guest.sent.filterIsInstance<LinkMsg.Bye>().map { it.reason })
        rig.engine.end()
        runCurrent()
        assertEquals(listOf(ByeReason.LEFT), pair.guest.sent.filterIsInstance<LinkMsg.Bye>().map { it.reason })
    }

    @Test
    fun receivedByeDoesNotSendByeBack() = runTest {
        val rig = rig(this)
        val pair = open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Bye(ByeReason.LEFT))
        awaitView(rig, GuestStep.ENDED)
        runCurrent()
        assertTrue(pair.guest.sent.none { it is LinkMsg.Bye })
        assertEquals(1, rig.platform.cancelAlertsCount)
        assertEquals(1, rig.platform.stopServiceCount)
    }

    @Test
    fun launchWhilePickingIsIgnored() = runTest {
        val game = guestGame(id = 1, folder = "gb")
        val offered = guestOffer(folder = "gb", core = "gambatte")
        val rig = rig(this, game = game, offered = offered)
        rig.library.matchValue = MatchResult.Missing(false)
        rig.library.choicesValue = listOf(GameChoice(2, "Blue", "gb"))
        val pair = open(rig, this, TogetherMode.GB_LINK, offered)
        awaitView(rig, GuestStep.PICKING_GAME)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        runCurrent()
        assertEquals(GuestStep.PICKING_GAME, rig.engine.view.value?.step)
        assertTrue(rig.emulators.launches.isEmpty())
    }

    @Test
    fun emptyDownloadableGameBoyListOffersADownload() = runTest {
        val offered = guestOffer(folder = "gb", core = "gambatte", sourceRefJson = "ref")
        val rig = rig(this, game = guestGame(id = 1, folder = "gb"), offered = offered)
        rig.library.matchValue = MatchResult.Missing(false)
        rig.library.choicesValue = emptyList()
        rig.library.sourceValue = "Store"
        open(rig, this, TogetherMode.GB_LINK, offered)
        val missing = awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_GAME)
        assertEquals(TogetherCopy.missingDownloadable(offered.title, "Store"), missing.problem?.text)
        assertEquals(TogetherAction.DOWNLOAD, missing.problem?.action)
    }

    @Test
    fun failedGuestStateSendLeavesTheView() = runTest {
        val rig = rig(this)
        rig.emulators.launchOutcomes += EmuRouter.Outcome.Failed("blocked")
        val pair = open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)
        pair.guest.failNextSend = true
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        val needed = awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_SETUP)
        runCurrent()
        assertEquals(GuestStep.ACTION_NEEDED, needed.step)
        assertTrue(pair.guest.sent.none { it is LinkMsg.GuestState && it.phase == GuestPhase.NEEDS_SETUP })
    }

    @Test
    fun closedFromPreviousSessionDoesNotClearTheNewJoin() = runTest {
        val rig = rig(this, timing = timing(offerMs = 50))
        rig.engine.join(target())
        val first = rig.transport.connected.await()
        runCurrent()
        assertEquals(GuestStep.WAITING_OFFER, rig.engine.view.value?.step)
        first.guest.close()
        rig.engine.prepareJoin(target())
        assertEquals(GuestStep.CONNECTING, rig.engine.view.value?.step)
        runCurrent()
        assertEquals(GuestStep.CONNECTING, rig.engine.view.value?.step)
        rig.engine.commitJoin(target())
        runCurrent()
        assertEquals(GuestStep.WAITING_OFFER, rig.engine.view.value?.step)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(GuestStep.ENDED, rig.engine.view.value?.step)
    }

    @Test
    fun connectFailedOrByeFromPreviousSessionDoesNotEndTheNewJoin() = runTest {
        val release = CompletableDeferred<Unit>()
        val transport = object : LinkTransport {
            var calls = 0
            override suspend fun listen(preferredPort: Int) = error("unused")
            override suspend fun connect(address: String, port: Int, timeoutMs: Long): com.gamebex.client.together.Link {
                calls += 1
                if (calls == 1) {
                    release.await()
                    throw java.io.IOException("down")
                }
                return GuestLinkPair().guest
            }
        }
        val engineScope = this.engineTestScope()
        val engine = GuestEngine(
            transport,
            GuestTestProbes(),
            GuestTestEmulators(),
            GuestTestLibrary(guestGame(), guestOffer()),
            GuestTestPlatform(),
            timing(),
            engineScope,
            engineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )
        engine.join(target())
        runCurrent()
        engine.prepareJoin(target())
        release.complete(Unit)
        runCurrent()
        assertEquals(GuestStep.CONNECTING, engine.view.value?.step)

        val bye = rig(this)
        bye.engine.join(target())
        val pair = bye.transport.connected.await()
        runCurrent()
        pair.host.send(LinkMsg.Bye(ByeReason.LEFT))
        bye.engine.prepareJoin(target())
        runCurrent()
        assertEquals(GuestStep.CONNECTING, bye.engine.view.value?.step)
    }

    @Test
    fun hostileCoresAreNotCopiedOrInstalled() = runTest {
        for (name in listOf("../x", "foo/bar", "fceumm")) {
            val offered = guestOffer(folder = "snes", core = name)
            val copied = rig(this, game = guestGame(folder = "snes"), offered = offered)
            open(copied, this, TogetherMode.RA_NETPLAY, offered)
            assertEquals(GuestStep.WAITING_OFFER, copied.engine.view.value?.step)
            assertTrue(copied.emulators.fetchCalls.isEmpty())
            assertTrue(copied.emulators.installCalls.isEmpty())
            assertTrue(copied.emulators.receivedCoreNames.none { it == name })

            val installOffer = guestOffer(folder = "snes", core = "snes9x")
            val installed = rig(this, game = guestGame(folder = "snes"), offered = installOffer)
            installed.emulators.readinessValue = Readiness.NeedsCore(name)
            open(installed, this, TogetherMode.RA_NETPLAY, installOffer)
            awaitView(installed, GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_CORE)
            installed.engine.act(TogetherAction.INSTALL_CORE)
            runCurrent()
            assertTrue(installed.emulators.fetchCalls.none { it.first == name })
        }
    }

    @Test
    fun coreBeginForADifferentBuildThanTheOfferIsRefused() = runTest {
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val game = guestGame(folder = "snes", coreOverride = "other-core")
        val rig = rig(this, game = game, offered = offered)
        rig.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        val pair = open(rig, this, TogetherMode.RA_NETPLAY, offered, coreBuild(byteArrayOf(1, 2, 3)))
        awaitView(rig, GuestStep.ACTION_NEEDED, GuestPhase.CORE_SYNC)
        rig.engine.act(TogetherAction.COPY_CORE)
        pair.host.events.receiveMessage { it is LinkMsg.CoreRequest }
        // The offer promised snes9x; a CoreBegin naming another core is not that build.
        pair.host.send(LinkMsg.CoreBegin(SESSION, 3, coreBuild(byteArrayOf(1, 2, 3)).copy(core = "fceumm")))
        runCurrent()
        val refused = rig.engine.view.value!!
        assertEquals(GuestStep.ACTION_NEEDED, refused.step)
        assertEquals(TogetherCopy.coreCopyFailed, refused.problem?.text)
        assertTrue(rig.emulators.receivedCoreNames.isEmpty())
        assertTrue(rig.emulators.installCalls.isEmpty())
    }

    @Test
    fun endWaitsForAnInFlightLaunchWrite() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = rig(this)
        rig.emulators.launchGates += gate
        val pair = open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        runCurrent()
        withTimeout(5_000) { rig.emulators.launchStarted.receive() }
        rig.engine.end()
        runCurrent()
        assertFalse(rig.emulators.trace.contains("endSession"))
        gate.complete(Unit)
        runCurrent()
        assertTrue(rig.emulators.trace.indexOf("endSession") > rig.emulators.trace.indexOf("write-done"))

        val stuck = CompletableDeferred<Unit>()
        val late = rig(this)
        late.emulators.launchGates += stuck
        val latePair = open(late, this, TogetherMode.RA_NETPLAY)
        awaitView(late, GuestStep.READY)
        latePair.host.send(LinkMsg.Launch(SESSION, 1))
        runCurrent()
        withTimeout(5_000) { late.emulators.launchStarted.receive() }
        late.engine.end()
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(late.emulators.trace.contains("endSession"))
        advanceTimeBy(1)
        runCurrent()
        assertTrue(late.emulators.trace.contains("endSession"))
        assertFalse(late.emulators.trace.contains("write-done"))
    }

    @Test
    fun endContinuesAfterAStepThrows() = runTest {
        val rig = rig(this)
        open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)
        rig.emulators.throwOnEnd = true
        rig.engine.end()
        runCurrent()
        assertEquals(1, rig.emulators.endSessionCount)
        assertEquals(1, rig.platform.cancelAlertsCount)
        assertEquals(GuestStep.ENDED, rig.engine.view.value?.step)
    }

    @Test
    fun unsafeCoreBuildIsLogged() = runTest {
        val offered = guestOffer(folder = "snes", core = "snes9x")
        val rig = rig(this, game = guestGame(folder = "snes"), offered = offered)
        open(rig, this, TogetherMode.RA_NETPLAY, offered, coreBuild(byteArrayOf(1)).copy(sha256 = "zz"))
        runCurrent()
        assertTrue(rig.logs.any { it.contains("unsafe core build") })
    }

    @Test
    fun openOnIoClosesTheResourceWhenConnectListenOrAcceptIsCancelled() = runTest {
        val rig = rig(this)
        val closed = mutableListOf<String>()
        for (op in listOf("connect", "listen", "accept")) {
            val started = CompletableDeferred<Unit>()
            val job = launch {
                rig.engine.openOnIo<String>({ mark ->
                    mark(op)
                    started.complete(Unit)
                    awaitCancellation()
                    op
                }) { resource -> closed.add(resource) }
            }
            started.await()
            job.cancelAndJoin()
        }
        assertEquals(listOf("connect", "listen", "accept"), closed)
    }

    @Test
    fun thrownInputSetsConsumerMessageAndKeepsRunning() = runTest {
        val rig = rig(this)
        rig.platform.failStartService = true
        open(rig, this, TogetherMode.PSP_ADHOC)
        assertEquals("Something went wrong.", rig.engine.view.value?.message)
        rig.engine.end()
        runCurrent()
        assertEquals(GuestStep.ENDED, rig.engine.view.value?.step)
    }

    @Test
    fun privateHostAddressDifferentFromRemoteUsesTheDialedAddress() = runTest {
        val rig = rig(this, timing = timing(launchWaitMs = 20, watchIntervalMs = 5))
        rig.probes.tcp = true
        rig.engine.join(target())
        val pair = rig.transport.connected.await()
        runCurrent()
        sendOffer(pair, TogetherMode.PSP_ADHOC, guestOffer(), hostAddress = "10.1.2.3")
        awaitView(rig, GuestStep.READY)
        pair.host.send(LinkMsg.Launch(SESSION, 1))
        runCurrent()
        advanceTimeBy(5)
        runCurrent()
        assertEquals(ADDRESS, rig.emulators.launches.single().hostAddress)
    }

    @Test
    fun customNameIsSentInTheGuestHello() = runTest {
        val rig = rig(this)
        rig.platform.name = "Sam's Deck"
        rig.engine.join(target())
        val pair = rig.transport.connected.await()
        runCurrent()
        assertEquals("Sam's Deck", (pair.host.events.receiveMessage { it is LinkMsg.Hello } as LinkMsg.Hello).name)
    }

    @Test
    fun publicRemoteIsRejectedEvenWithAPrivateHostAddress() = runTest {
        val rig = rig(this)
        rig.transport.remoteAddress = "8.8.8.8"
        rig.engine.join(target())
        val pair = rig.transport.connected.await()
        runCurrent()
        sendOffer(pair, TogetherMode.PSP_ADHOC, guestOffer(), hostAddress = ADDRESS)
        runCurrent()
        assertEquals(GuestStep.WAITING_OFFER, rig.engine.view.value?.step)
        assertNull(rig.engine.view.value?.gameTitle)
        assertTrue(rig.logs.any { it.contains("rejected offer") })
        assertEquals(0, rig.platform.startServiceCount)
    }

    @Test
    fun byeOnlyEndsTheSessionThatReceivedIt() = runTest {
        val rig = rig(this)
        val pair = open(rig, this, TogetherMode.RA_NETPLAY)
        awaitView(rig, GuestStep.READY)
        // A new join publishes after the Bye passed the owner check but before
        // the old session writes ENDED.
        rig.platform.onForegroundRead.set { rig.engine.prepareJoin(target()) }
        pair.host.send(LinkMsg.Bye(ByeReason.LEFT))
        runCurrent()
        assertNull(rig.platform.onForegroundRead.get())
        assertEquals(1, rig.emulators.endSessionCount)
        val view = rig.engine.view.value!!
        assertEquals(GuestStep.CONNECTING, view.step)
        assertNull(view.message)
    }

    @Test
    fun publicHostAddressIsRejectedEvenWhenItMatchesRemote() = runTest {
        val rig = rig(this)
        rig.transport.remoteAddress = "8.8.8.8"
        rig.engine.join(target())
        val pair = rig.transport.connected.await()
        runCurrent()
        pair.host.send(LinkMsg.Hello(TOGETHER_PROTO, Role.HOST, "host-device", "Host", "1.0"))
        pair.host.send(LinkMsg.Offer(SESSION, TogetherMode.PSP_ADHOC, guestOffer(), hostAddress = "8.8.8.8"))
        runCurrent()
        assertEquals(GuestStep.WAITING_OFFER, rig.engine.view.value?.step)
        assertNull(rig.engine.view.value?.gameTitle)
        assertTrue(rig.logs.any { it.contains("rejected offer") })
    }

    @Test
    fun loopbackHostAddressRequiresALoopbackRemote() = runTest {
        val rejected = rig(this)
        rejected.engine.join(target())
        val rejectedPair = rejected.transport.connected.await()
        runCurrent()
        rejectedPair.host.send(LinkMsg.Hello(TOGETHER_PROTO, Role.HOST, "host-device", "Host", "1.0"))
        rejectedPair.host.send(LinkMsg.Offer(SESSION, TogetherMode.PSP_ADHOC, guestOffer(), hostAddress = "127.0.0.1"))
        runCurrent()
        assertEquals(GuestStep.WAITING_OFFER, rejected.engine.view.value?.step)
        assertNull(rejected.engine.view.value?.gameTitle)

        val accepted = rig(this)
        accepted.transport.remoteAddress = "127.0.0.1"
        accepted.engine.join(target())
        val acceptedPair = accepted.transport.connected.await()
        runCurrent()
        sendOffer(acceptedPair, TogetherMode.PSP_ADHOC, guestOffer(), hostAddress = "127.0.0.1")
        awaitView(accepted, GuestStep.READY)
    }
    private suspend fun open(
        rig: Rig,
        scope: TestScope,
        mode: TogetherMode,
        offered: OfferedGame = rig.offered,
        core: CoreBuild? = null,
    ): GuestLinkPair {
        rig.engine.join(target())
        assertEquals(GuestStep.CONNECTING, rig.engine.view.value?.step)
        val pair = rig.transport.connected.await()
        scope.runCurrent()
        sendOffer(pair, mode, offered, core)
        scope.runCurrent()
        return pair
    }

    private suspend fun sendOffer(
        pair: GuestLinkPair,
        mode: TogetherMode,
        offered: OfferedGame,
        core: CoreBuild? = null,
        hostAddress: String = ADDRESS,
    ) {
        pair.host.send(LinkMsg.Hello(TOGETHER_PROTO, Role.HOST, "host-device", "Host", "1.0"))
        pair.host.send(LinkMsg.Offer(SESSION, mode, offered, core, hostAddress))
    }

    private suspend fun TestScope.awaitView(
        rig: Rig,
        step: GuestStep,
        phase: GuestPhase? = null,
        predicate: (com.gamebex.client.together.GuestView) -> Boolean = { true },
    ): com.gamebex.client.together.GuestView {
        runCurrent()
        return withTimeout(10_000L) {
            rig.engine.view.filterNotNull().first {
                it.step == step && (phase == null || it.phase == phase) && predicate(it)
            }
        }
    }

    private suspend fun GuestTestLinkReceiveChannel.receiveMessage(
        predicate: (LinkMsg) -> Boolean = { true },
    ): LinkMsg = withTimeout(10_000L) {
        while (true) {
            val event = receive()
            if (event is LinkEvent.Message && predicate(event.msg)) return@withTimeout event.msg
        }
        error("unreachable")
    }

    private fun rig(
        scope: CoroutineScope,
        game: GameEntity = guestGame(),
        offered: OfferedGame = guestOffer(),
        timing: TogetherTiming = timing(),
        emulatorForEngine: (GuestTestEmulators) -> TogetherEmulators = { it },
        platformForEngine: (GuestTestPlatform) -> TogetherPlatform = { it },
    ): Rig {
        val engineScope = scope.engineTestScope()
        val transport = GuestTestTransport()
        val probes = GuestTestProbes()
        val emulators = GuestTestEmulators()
        val library = GuestTestLibrary(game, offered)
        val platform = GuestTestPlatform()
        val logs = mutableListOf<String>()
        val engine = GuestEngine(
            transport, probes, emulatorForEngine(emulators), library, platformForEngine(platform), timing, engineScope,
            engineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
            logSink = { logs += it },
        )
        return Rig(engine, transport, probes, emulators, library, platform, offered, logs)
    }

    private fun target() = NearbyHost(
        deviceId = "host-device",
        sessionId = SESSION,
        name = "Host",
        gameTitle = "Sample game",
        folder = "psp",
        mode = TogetherMode.PSP_ADHOC,
        address = ADDRESS,
        port = 55_440,
    )

    private fun coreBuild(bytes: ByteArray): CoreBuild {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return CoreBuild("snes9x", digest, bytes.size.toLong())
    }

    private fun fileWithBytes(bytes: ByteArray): File = File.createTempFile("together-rom", ".bin").also { it.writeBytes(bytes) }

    private fun timing(
        offerMs: Long = 60_000L,
        countdownS: Int = 0,
        launchWaitMs: Long = 500L,
        watchIntervalMs: Long = 100L,
        probeTimeoutMs: Long = 1L,
        coreRequestMs: Long = 500L,
        coreInactivityMs: Long = 500L,
    ) = TogetherTiming(
        offerMs = offerMs,
        countdownS = countdownS,
        launchWaitMs = launchWaitMs,
        watchIntervalMs = watchIntervalMs,
        probeTimeoutMs = probeTimeoutMs,
        coreRequestMs = coreRequestMs,
        coreInactivityMs = coreInactivityMs,
    )

    private class ControlledCoreEmulators(private val delegate: GuestTestEmulators) : TogetherEmulators by delegate {
        val callbacks = mutableListOf<(Long, Long) -> Unit>()
        var readinessCalls = 0
        var fetch: suspend (Int, (Long, Long) -> Unit) -> CoreResult = { _, _ -> awaitCancellation() }

        override suspend fun readiness(mode: TogetherMode, role: Role, game: GameEntity, core: String?): Readiness {
            readinessCalls += 1
            return delegate.readiness(mode, role, game, core)
        }

        override suspend fun fetchAndInstallCore(core: String, game: GameEntity, onProgress: (Long, Long) -> Unit): CoreResult {
            callbacks += onProgress
            return fetch(callbacks.size, onProgress)
        }
    }

    private data class Rig(
        val engine: GuestEngine,
        val transport: GuestTestTransport,
        val probes: GuestTestProbes,
        val emulators: GuestTestEmulators,
        val library: GuestTestLibrary,
        val platform: GuestTestPlatform,
        val offered: OfferedGame,
        val logs: MutableList<String>,
    )

    private companion object {
        const val SESSION = "guest-session"
        const val ADDRESS = "10.42.1.20"
    }
}

private typealias GuestTestLinkReceiveChannel = ReceiveChannel<LinkEvent>
