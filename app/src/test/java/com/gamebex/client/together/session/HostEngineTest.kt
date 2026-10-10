package com.gamebex.client.together.session

import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.together.ByeReason
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.LinkEvent
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.Readiness
import com.gamebex.client.together.Role
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.CoreResult
import com.gamebex.client.together.TogetherAlert
import com.gamebex.client.together.net.JoinLink
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherLibrary
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.TogetherPlatform
import com.gamebex.client.together.CoreBuild
import com.gamebex.client.together.CoreFile
import com.gamebex.client.together.session.fakes.FakeDiscovery
import com.gamebex.client.together.session.fakes.FakeEmulators
import com.gamebex.client.together.session.fakes.FakeLinkTransport
import com.gamebex.client.together.session.fakes.FakePlatform
import com.gamebex.client.together.session.fakes.FakeLibrary
import com.gamebex.client.together.session.fakes.LinkPair
import com.gamebex.client.together.session.fakes.engineTestScope
import com.gamebex.client.together.session.fakes.sampleGame
import com.gamebex.client.together.session.fakes.sampleOffer
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostEngineTest {
    @Test
    fun happyPathSendsLaunchOnlyAfterHostLaunched() = runTest {
        val platform = FakePlatform()
        platform.playingEntered = kotlinx.coroutines.CompletableDeferred()
        val h = harness(this, platform = platform)
        h.engine.hostGame(1)
        advanceUntilIdle()
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)

        val pair = h.join(this)
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        h.emulators.launchGate = kotlinx.coroutines.CompletableDeferred()
        h.engine.start()
        runCurrent()
        assertTrue(pair.host.sent.none { it is LinkMsg.Launch })
        h.emulators.launchGate?.complete(Unit)
        platform.playingEntered?.await()
        runCurrent()

        assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
        val launches = pair.host.sent.filterIsInstance<LinkMsg.Launch>()
        assertEquals(listOf(1), launches.map { it.round })
    }

    @Test
    fun hostGameSetsCheckingAfterEnded() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        h.engine.end()
        advanceUntilIdle()
        assertEquals(HostStep.ENDED, h.engine.view.value?.step)

        h.engine.hostGame(1)
        assertEquals(HostStep.CHECKING, h.engine.view.value?.step)
        advanceUntilIdle()
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
    }

    @Test
    fun secondGuestGetsFullAndFirstRemainsConnected() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val first = h.join(this, "first")
        val second = h.transport.pair("10.42.1.22")
        second.guest.send(LinkMsg.Hello(TOGETHER_PROTO, Role.GUEST, "guest-2", "second", "1.0"))
        advanceUntilIdle()

        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
        assertEquals(ByeReason.FULL, (second.host.sent.last() as LinkMsg.Bye).reason)
        assertTrue(first.host.sent.none { it is LinkMsg.Bye && it.reason == ByeReason.FULL })
    }

    @Test
    fun protoMismatchGetsVersionBye() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.transport.pair()
        pair.guest.send(LinkMsg.Hello(999, Role.GUEST, "guest", "guest", "1.0"))
        advanceUntilIdle()

        assertEquals(ByeReason.VERSION, (pair.host.sent.last() as LinkMsg.Bye).reason)
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
    }

    @Test
    fun failedRoundOneReturnsJoinedAndFailedRoundTwoStaysInGame() = runTest {
        val h = harness(this)
        h.emulators.launchOutcomes = ArrayDeque(
            listOf(
                EmuRouter.Outcome.Failed("first"),
                EmuRouter.Outcome.Failed("second"),
            ),
        )
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.READY))
        advanceUntilIdle()

        h.emulators.launchCompleted = kotlinx.coroutines.CompletableDeferred()
        h.engine.start()
        h.emulators.launchCompleted?.await()
        runCurrent()
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
        assertTrue(pair.host.sent.none { it is LinkMsg.Launch })

        h.emulators.launchCompleted = kotlinx.coroutines.CompletableDeferred()
        h.engine.start()
        h.emulators.launchCompleted?.await()
        runCurrent()
        assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
        assertTrue(h.engine.view.value?.message?.contains("second") == true)
        val launches = h.emulators.launches.size
        h.emulators.launchCompleted = kotlinx.coroutines.CompletableDeferred()
        h.engine.playAgain()
        h.emulators.launchCompleted?.await()
        runCurrent()
        assertEquals(launches + 1, h.emulators.launches.size)
    }

    @Test
    fun guestStateUpdatesInJoinedLaunchingAndInGame() = runTest {
        val platform = FakePlatform()
        platform.playingEntered = kotlinx.coroutines.CompletableDeferred()
        val h = harness(this, platform = platform)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.NEEDS_GAME, "detail", 4))
        advanceUntilIdle()
        assertEquals(GuestPhase.NEEDS_GAME, h.engine.view.value?.guestPhase)
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        h.emulators.launchGate = kotlinx.coroutines.CompletableDeferred()
        h.engine.start()
        runCurrent()
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.PLAYING))
        runCurrent()
        assertEquals(HostStep.LAUNCHING, h.engine.view.value?.step)
        assertEquals(GuestPhase.PLAYING, h.engine.view.value?.guestPhase)
        h.emulators.launchCompleted = kotlinx.coroutines.CompletableDeferred()
        h.emulators.launchGate?.complete(Unit)
        h.emulators.launchCompleted?.await()
        platform.playingEntered?.await()
        runCurrent()
        assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.PLAYING))
        runCurrent()
        assertEquals(GuestPhase.PLAYING, h.engine.view.value?.guestPhase)
    }

    @Test
    fun removeSendsRemovedWaitsAndDropsLateClosed() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        h.engine.removeGuest()
        advanceUntilIdle()

        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
        assertEquals(ByeReason.REMOVED, (pair.host.sent.last() as LinkMsg.Bye).reason)
        assertEquals(1, h.discovery.advertisements.size)
        assertEquals(0, h.discovery.stopCount)
        h.engine.end()
        advanceUntilIdle()
        assertNull(h.engine.view.value?.message)
    }

    @Test
    fun removeThenSecondJoinSucceeds() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        h.join(this, "first")
        h.engine.removeGuest()
        advanceUntilIdle()

        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
        h.join(this, "second")
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
        assertEquals("second", h.engine.view.value?.guestName)
    }

    @Test
    fun lateEndFromSessionADoesNotMarkSessionBEnded() = runTest {
        val library = SecondGameLibrary()
        val emulators = EndGateEmulators()
        val engineScope = this.engineTestScope()
        val engine = HostEngine(
            FakeLinkTransport(),
            emulators,
            library,
            FakeDiscovery(),
            FakePlatform(),
            TogetherTiming(helloMs = 50, byeMs = 50),
            engineScope,
            engineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )
        engine.hostGame(1)
        advanceUntilIdle()
        assertEquals(HostStep.WAITING, engine.view.value?.step)

        emulators.block = true
        engine.end()
        runCurrent()
        emulators.entered.await()

        engine.hostGame(1)
        assertEquals(HostStep.CHECKING, engine.view.value?.step)
        emulators.release.complete(Unit)
        runCurrent()
        library.secondEntered.await()
        assertEquals(HostStep.CHECKING, engine.view.value?.step)

        library.secondRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals(HostStep.WAITING, engine.view.value?.step)
    }

    @Test
    fun closedInGameShowsLinkLostWithoutForegroundAlert() = runTest {
        val platform = FakePlatform()
        platform.playingEntered = kotlinx.coroutines.CompletableDeferred()
        val h = harness(this, platform = platform)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        h.engine.start()
        h.emulators.launchCompleted = kotlinx.coroutines.CompletableDeferred()
        h.emulators.launchCompleted?.await()
        platform.playingEntered?.await()
        runCurrent()
        pair.host.close()
        advanceUntilIdle()

        assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
        assertTrue(h.engine.view.value?.linkLost == true)
        assertTrue(h.discovery.advertisements.size == 1)
        assertTrue(h.platform.alerts.isEmpty())
    }

    @Test
    fun endDuringSetupClosesResourcesAndService() = runTest {
        val platform = FakePlatform()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        platform.serviceEntered = entered
        platform.serviceRelease = release
        val h = harness(this, platform = platform)
        h.engine.hostGame(1)
        val endThread = Thread {
            kotlinx.coroutines.runBlocking {
                entered.await()
                h.engine.end()
                release.complete(Unit)
            }
        }
        endThread.start()
        advanceUntilIdle()
        endThread.join()
        advanceUntilIdle()
        assertEquals(1, h.platform.stopServiceCount)
        assertEquals(1, h.emulators.endSessionCount)
        assertEquals(1, h.transport.listener.closeCount)
        assertEquals(1, h.discovery.stopCount)
        assertEquals(1, h.platform.cancelAlertsCount)
    }

    @Test
    fun endIsIdempotent() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        h.engine.end()
        h.engine.end()
        advanceUntilIdle()

        assertEquals(1, h.platform.stopServiceCount)
        assertEquals(1, h.emulators.endSessionCount)
    }

    @Test
    fun gbLinkSendsHeadStartButPspAndRaDoNot() = runTest {
        val gbPlatform = FakePlatform()
        gbPlatform.playingEntered = kotlinx.coroutines.CompletableDeferred()
        val h = harness(
            this,
            sampleGame().copy(folder = "gb", coreOverride = "gambatte"),
            sampleOffer().copy(folder = "gb", core = "gambatte"),
            platform = gbPlatform,
        )
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        h.emulators.launchCompleted = kotlinx.coroutines.CompletableDeferred()
        h.engine.start()
        h.emulators.launchCompleted?.await()
        gbPlatform.playingEntered?.await()
        runCurrent()
        advanceTimeBy(7_999)
        runCurrent()
        assertTrue(pair.host.sent.none { it is LinkMsg.HostLive })
        advanceTimeBy(1)
        runCurrent()
        assertTrue(pair.host.sent.any { it is LinkMsg.HostLive && it.round == 1 })

        val pspPlatform = FakePlatform()
        pspPlatform.playingEntered = kotlinx.coroutines.CompletableDeferred()
        val psp = harness(this, platform = pspPlatform)
        psp.engine.hostGame(1)
        advanceUntilIdle()
        val pspPair = psp.join(this)
        pspPair.guest.send(LinkMsg.GuestState(psp.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        psp.emulators.launchCompleted = kotlinx.coroutines.CompletableDeferred()
        psp.engine.start()
        psp.emulators.launchCompleted?.await()
        pspPlatform.playingEntered?.await()
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(pspPair.host.sent.none { it is LinkMsg.HostLive })

        val raPlatform = FakePlatform()
        raPlatform.playingEntered = kotlinx.coroutines.CompletableDeferred()
        val ra = harness(
            this,
            sampleGame().copy(
                folder = "snes",
                coreOverride = "snes9x",
                relPath = "snes/sample.zip",
                fileName = "sample.zip",
                ext = "zip",
            ),
            sampleOffer().copy(folder = "snes", core = "snes9x", fileName = "sample.zip"),
            platform = raPlatform,
        )
        ra.engine.hostGame(1)
        advanceUntilIdle()
        val raPair = ra.join(this)
        raPair.guest.send(LinkMsg.GuestState(ra.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        ra.emulators.launchCompleted = kotlinx.coroutines.CompletableDeferred()
        ra.engine.start()
        ra.emulators.launchCompleted?.await()
        raPlatform.playingEntered?.await()
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(raPair.host.sent.none { it is LinkMsg.HostLive })
    }

    @Test
    fun coreRequestSendsCoreBeginBlobEndOrUnavailable() = runTest {
        val file = File.createTempFile("host-core", ".zip")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val game = sampleGame().copy(folder = "snes", coreOverride = "snes9x")
        val offer = sampleOffer().copy(folder = "snes", core = "snes9x")
        val h = harness(this, game, offer)
        h.emulators.coreFile = CoreFile(CoreBuild("snes9x", "a".repeat(64), 3), file)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        pair.guest.send(LinkMsg.CoreRequest(h.sessionId(), "snes9x"))
        advanceUntilIdle()
        assertTrue(pair.host.sent.any { it is LinkMsg.CoreBegin })
        assertEquals(listOf(1), pair.host.sentBlobs)
        assertTrue(pair.host.sent.any { it is LinkMsg.CoreEnd })
        pair.guest.send(LinkMsg.CoreRequest(h.sessionId(), "other"))
        advanceUntilIdle()
        assertTrue(pair.host.sent.any { it is LinkMsg.CoreUnavailable })
        val noCore = harness(this, game, offer)
        noCore.engine.hostGame(1)
        advanceUntilIdle()
        val noCorePair = noCore.join(this)
        noCorePair.guest.send(LinkMsg.CoreRequest(noCore.sessionId(), "snes9x"))
        advanceUntilIdle()
        assertTrue(noCorePair.host.sent.any { it is LinkMsg.CoreUnavailable })
        file.delete()
    }

    @Test
    fun silentGuestIsClosedAfterHelloTimeout() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.transport.pair()
        advanceUntilIdle()
        assertTrue(pair.guest.events.tryReceive().getOrNull() is LinkEvent.Closed)
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
    }

    @Test
    fun unexpectedPingWhileJoinedIsIgnored() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
        pair.guest.send(LinkMsg.Ping(7))
        advanceUntilIdle()
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
        assertTrue(pair.host.sent.none { it is LinkMsg.Launch })
    }
    @Test
    fun waitingPublishesAddressQrAdvertisementAndAcceptsAGuest() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()

        val view = h.engine.view.value
        assertEquals(HostStep.WAITING, view?.step)
        assertEquals("10.42.1.20", view?.myAddress)
        assertEquals(55440, view?.port)
        assertEquals(JoinLink.buildLink("10.42.1.20", 55440, "Host"), view?.qrText)
        assertEquals(TogetherCopy.ongoingWaiting, h.platform.startedText)
        assertEquals(1, h.discovery.advertisements.size)
        assertEquals(55440, h.transport.listener.port)

        h.join(this)
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
    }

    @Test
    fun missingGameMissingOfferAndMissingAddressBecomeNotReady() = runTest {
        val missingGame = harness(this)
        missingGame.library.gameValue = null
        missingGame.engine.hostGame(1)
        advanceUntilIdle()
        assertEquals(HostStep.NOT_READY, missingGame.engine.view.value?.step)
        assertEquals(TogetherCopy.gameFileMissing, missingGame.engine.view.value?.problem?.text)
        assertEquals(TogetherAction.RECHECK, missingGame.engine.view.value?.problem?.action)

        val missingOffer = harness(this)
        missingOffer.library.offerValue = null
        missingOffer.engine.hostGame(1)
        advanceUntilIdle()
        assertEquals(HostStep.NOT_READY, missingOffer.engine.view.value?.step)
        assertEquals(TogetherCopy.gameFileMissing, missingOffer.engine.view.value?.problem?.text)
        assertEquals(0, missingOffer.platform.startServiceCount)

        val missingAddress = harness(this, platform = FakePlatform(address = null))
        missingAddress.engine.hostGame(1)
        advanceUntilIdle()
        assertEquals(HostStep.NOT_READY, missingAddress.engine.view.value?.step)
        assertEquals(TogetherCopy.noWifi, missingAddress.engine.view.value?.problem?.text)
        assertEquals(TogetherAction.RECHECK, missingAddress.engine.view.value?.problem?.action)
        assertEquals(0, missingAddress.discovery.advertisements.size)
    }

    @Test
    fun blockedReadinessUsesProblemFor() = runTest {
        val h = harness(this)
        h.emulators.readinessValue = Readiness.Blocked("no bios")
        h.engine.hostGame(1)
        advanceUntilIdle()

        assertEquals(HostStep.NOT_READY, h.engine.view.value?.step)
        assertEquals("no bios", h.engine.view.value?.problem?.text)
        assertEquals(TogetherAction.RECHECK, h.engine.view.value?.problem?.action)
    }

    @Test
    fun unsupportedGameIgnoresForegroundAndOpenActions() = runTest {
        val h = harness(this, game = sampleGame().copy(folder = "switch"))
        h.engine.hostGame(1)
        advanceUntilIdle()
        assertEquals(HostStep.NOT_READY, h.engine.view.value?.step)
        assertEquals(TogetherCopy.notSupported, h.engine.view.value?.message)
        assertNull(h.engine.view.value?.problem)

        h.engine.onForeground()
        h.engine.act(TogetherAction.OPEN_SYSTEM)
        h.engine.act(TogetherAction.OPEN_PPSSPP)
        advanceUntilIdle()

        assertEquals(HostStep.NOT_READY, h.engine.view.value?.step)
        assertEquals(TogetherCopy.notSupported, h.engine.view.value?.message)
        assertEquals(0, h.discovery.advertisements.size)
    }

    @Test
    fun failedAdvertiseClosesTheListenerAndRecheckKeepsTheSession() = runTest {
        val h = harness(this)
        h.discovery.failAdvertise = true
        h.engine.hostGame(1)
        advanceUntilIdle()
        val failedListener = h.transport.listener
        val sessionId = h.discovery.advertisements.single().sessionId
        assertEquals(1, failedListener.closeCount)
        assertEquals(HostStep.NOT_READY, h.engine.view.value?.step)
        assertEquals(TogetherCopy.noWifi, h.engine.view.value?.problem?.text)

        h.engine.act(TogetherAction.RECHECK)
        advanceUntilIdle()

        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
        assertEquals(sessionId, h.discovery.advertisements.last().sessionId)
        assertTrue(h.transport.listener !== failedListener)
        assertEquals(TogetherCopy.ongoingWaiting, h.platform.startedText)
    }

    @Test
    fun installCoreReportsProgressThenStartedOrFailed() = runTest {
        val game = sampleGame().copy(folder = "snes", fileName = "sample.sfc", relPath = "sample.sfc", ext = "sfc")
        val started = harness(this, game = game)
        started.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        started.emulators.fetchGate = kotlinx.coroutines.CompletableDeferred()
        started.engine.hostGame(1)
        advanceUntilIdle()
        assertEquals(TogetherAction.INSTALL_CORE, started.engine.view.value?.problem?.action)

        started.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(TogetherCopy.fetchingCore(100), started.engine.view.value?.message)
        assertEquals(CoreInstallProgress("snes9x", read = 1, total = 1), started.engine.view.value?.coreInstall)
        started.engine.onForeground()
        started.engine.act(TogetherAction.OPEN_SYSTEM)
        started.engine.act(TogetherAction.OPEN_PPSSPP)
        runCurrent()
        assertEquals(HostStep.NOT_READY, started.engine.view.value?.step)
        assertEquals(TogetherCopy.fetchingCore(100), started.engine.view.value?.message)
        started.emulators.readinessValue = Readiness.Ready
        started.emulators.fetchGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(HostStep.WAITING, started.engine.view.value?.step)
        assertNull(started.engine.view.value?.message)
        assertNull(started.engine.view.value?.coreInstall)
        assertNull(started.engine.view.value?.problem)

        val failed = harness(this, game = game)
        failed.emulators.readinessValue = Readiness.NeedsCore("snes9x")
        failed.emulators.fetchResult = CoreResult.Failed("blocked")
        failed.engine.hostGame(1)
        advanceUntilIdle()
        failed.engine.act(TogetherAction.INSTALL_CORE)
        advanceUntilIdle()
        assertEquals("blocked", failed.engine.view.value?.problem?.text)
        assertEquals(TogetherAction.INSTALL_CORE, failed.engine.view.value?.problem?.action)
        assertNull(failed.engine.view.value?.coreInstall)
        assertNull(failed.engine.view.value?.message)
    }

    @Test
    fun streamInstallCoreFetchesTheRetroArchCore() = runTest {
        val game = sampleGame().copy(folder = "gba", fileName = "sample.gba", relPath = "sample.gba", ext = "gba")
        val h = coreInstallHarness(this, modes = listOf(TogetherMode.STREAM), game = game)
        h.emulators.readinessValue = Readiness.NeedsCore("mgba")
        h.engine.hostGame(1)
        advanceUntilIdle()
        assertEquals(TogetherAction.INSTALL_CORE, h.engine.view.value?.problem?.action)

        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals("mgba", h.engine.view.value?.coreInstall?.core)
        assertEquals(listOf("mgba"), h.emulators.coreArgs)

        h.emulators.calls.single().result.complete(CoreResult.Started)
        h.emulators.readinessValue = Readiness.Ready
        advanceUntilIdle()
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
    }

    @Test
    fun installCorePublishesSpinnerBeforeAnyBackendProgressAndRepeatsReshowIt() = runTest {
        val h = coreInstallHarness(this)
        h.engine.hostGame(1)
        runCurrent()
        var progressAtBackendEntry: CoreInstallProgress? = null
        h.emulators.onFetch = { progressAtBackendEntry = h.engine.view.value?.coreInstall }

        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()

        assertEquals(CoreInstallProgress("snes9x"), progressAtBackendEntry)
        assertEquals(progressAtBackendEntry, h.engine.view.value?.coreInstall)
        assertEquals(TogetherCopy.fetchingCore(0), h.engine.view.value?.message)
        assertNull(h.engine.view.value?.problem)
        h.engine.act(TogetherAction.OPEN_RETROARCH)
        runCurrent()
        assertTrue(h.engine.view.value?.message?.contains("Couldn't open RetroArch") == true)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(1, h.emulators.calls.size)
        assertEquals(CoreInstallProgress("snes9x"), h.engine.view.value?.coreInstall)
        assertEquals(TogetherCopy.fetchingCore(0), h.engine.view.value?.message)
        h.engine.end()
        runCurrent()
    }

    @Test
    fun installCoreKeepsByteProgressAndSwitchesToInstallingStage() = runTest {
        val h = coreInstallHarness(this)
        h.engine.hostGame(1)
        runCurrent()
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        val call = h.emulators.calls.single()

        call.progress(512, 0)
        runCurrent()
        assertEquals(CoreInstallProgress("snes9x", read = 512), h.engine.view.value?.coreInstall)
        call.progress(1_024, 4_096)
        runCurrent()
        assertEquals(CoreInstallProgress("snes9x", read = 1_024, total = 4_096), h.engine.view.value?.coreInstall)
        assertEquals(TogetherCopy.fetchingCore(25), h.engine.view.value?.message)
        call.progress(-1, -1)
        runCurrent()
        assertEquals(
            CoreInstallProgress("snes9x", read = 1_024, total = 4_096, installing = true),
            h.engine.view.value?.coreInstall,
        )
        assertEquals(TogetherCopy.installingCoreHeadline, h.engine.view.value?.message)
        h.engine.end()
        runCurrent()
    }

    @Test
    fun cancelMidInstallClearsOwnershipAndRetryStartsANewInstall() = runTest {
        val h = coreInstallHarness(this)
        h.engine.hostGame(1)
        runCurrent()
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        h.emulators.calls.single().progress(-1, -1)
        runCurrent()

        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        runCurrent()

        assertTrue(h.emulators.calls.single().finished)
        assertNull(h.engine.view.value?.coreInstall)
        assertNull(h.engine.view.value?.message)
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, h.engine.view.value?.problem?.action)
        h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
        runCurrent()
        assertEquals(2, h.emulators.calls.size)
        assertEquals(CoreInstallProgress("snes9x"), h.engine.view.value?.coreInstall)
        h.emulators.readinessValue = Readiness.Ready
        h.emulators.calls.last().result.complete(CoreResult.Started)
        runCurrent()
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
        assertNull(h.engine.view.value?.coreInstall)
    }

    @Test
    fun backendCancellationFinallyClearsOwnershipAndAllowsAnotherInstall() = runTest {
        val h = coreInstallHarness(this)
        h.engine.hostGame(1)
        runCurrent()
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        h.emulators.calls.single().result.completeExceptionally(CancellationException("cancelled install"))
        runCurrent()

        assertNull(h.engine.view.value?.coreInstall)
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, h.engine.view.value?.problem?.action)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(2, h.emulators.calls.size)
        assertNotNull(h.engine.view.value?.coreInstall)
        h.engine.end()
        runCurrent()
    }

    @Test
    fun staleCancelledInstallCallbacksAndFinallyCannotClearReplacement() = runTest {
        val h = coreInstallHarness(this)
        h.emulators.keepFirstAlive = true
        h.engine.hostGame(1)
        runCurrent()
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        val old = h.emulators.calls.single()
        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
        runCurrent()
        val replacement = h.emulators.calls.last()
        replacement.progress(25, 100)
        runCurrent()
        val expected = h.engine.view.value?.coreInstall

        old.progress(-1, -1)
        old.result.complete(CoreResult.Failed("stale failure", TogetherAction.OPEN_RETROARCH))
        runCurrent()

        assertTrue(old.finished)
        assertFalse(replacement.finished)
        assertEquals(expected, h.engine.view.value?.coreInstall)
        assertEquals(TogetherCopy.fetchingCore(25), h.engine.view.value?.message)
        assertNull(h.engine.view.value?.problem)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(2, h.emulators.calls.size)
        replacement.result.complete(CoreResult.Failed("current failure"))
        runCurrent()
        assertNull(h.engine.view.value?.coreInstall)
        assertEquals("current failure", h.engine.view.value?.problem?.text)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(3, h.emulators.calls.size)
        h.engine.end()
        runCurrent()
    }

    @Test
    fun alreadyQueuedStaleResultCannotReleaseAReplacementInstall() = runTest {
        val h = coreInstallHarness(this)
        h.engine.hostGame(1)
        runCurrent()
        h.emulators.onFetch = {
            h.emulators.onFetch = null
            // The old worker can finish before the actor processes Cancel/Retry,
            // leaving its result and finally messages behind the new actions.
            h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
            h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
            h.emulators.calls.single().result.complete(CoreResult.Failed("queued stale result"))
        }
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()

        assertEquals(2, h.emulators.calls.size)
        assertTrue(h.emulators.calls.first().finished)
        assertFalse(h.emulators.calls.last().finished)
        assertEquals(CoreInstallProgress("snes9x"), h.engine.view.value?.coreInstall)
        assertNull(h.engine.view.value?.problem)
        h.emulators.calls.last().progress(-1, -1)
        runCurrent()
        assertEquals(true, h.engine.view.value?.coreInstall?.installing)
        h.engine.end()
        runCurrent()
    }

    @Test
    fun failedInstallCallbacksCannotOverwriteSameEpochRetryProgress() = runTest {
        val h = coreInstallHarness(this)
        h.engine.hostGame(1)
        runCurrent()
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        val old = h.emulators.calls.single()
        old.result.complete(CoreResult.Failed("first failure"))
        runCurrent()
        h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
        runCurrent()
        val replacement = h.emulators.calls.last()
        replacement.progress(30, 100)
        runCurrent()

        old.progress(-1, -1)
        old.progress(100, 100)
        runCurrent()

        assertEquals(CoreInstallProgress("snes9x", read = 30, total = 100), h.engine.view.value?.coreInstall)
        assertEquals(TogetherCopy.fetchingCore(30), h.engine.view.value?.message)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(2, h.emulators.calls.size)
        h.engine.end()
        runCurrent()
    }

    @Test
    fun endedSessionInstallCannotClearANewSessionsInstall() = runTest {
        val h = coreInstallHarness(this)
        h.emulators.keepFirstAlive = true
        h.engine.hostGame(1)
        runCurrent()
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        val old = h.emulators.calls.single()
        h.engine.end()
        runCurrent()
        assertEquals(HostStep.ENDED, h.engine.view.value?.step)
        assertNull(h.engine.view.value?.coreInstall)
        h.engine.hostGame(1)
        runCurrent()
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        val replacement = h.emulators.calls.last()
        replacement.progress(40, 80)
        runCurrent()

        old.progress(80, 80)
        old.result.complete(CoreResult.Started)
        runCurrent()

        assertTrue(old.finished)
        assertEquals(2, h.emulators.calls.size)
        assertEquals(CoreInstallProgress("snes9x", read = 40, total = 80), h.engine.view.value?.coreInstall)
        assertEquals(HostStep.NOT_READY, h.engine.view.value?.step)
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        assertEquals(2, h.emulators.calls.size)
        h.engine.end()
        runCurrent()
    }

    @Test
    fun progressStallsAfterEightSecondsAndNewProgressResetsTheTimer() = runTest {
        val h = coreInstallHarness(this)
        h.engine.hostGame(1)
        runCurrent()
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        val call = h.emulators.calls.single()
        advanceTimeBy(7_999)
        runCurrent()
        assertEquals(false, h.engine.view.value?.coreInstall?.stalled)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(true, h.engine.view.value?.coreInstall?.stalled)
        assertEquals("Still working…", h.engine.view.value?.message)

        call.progress(10, 100)
        runCurrent()
        assertEquals(false, h.engine.view.value?.coreInstall?.stalled)
        assertEquals(TogetherCopy.fetchingCore(10), h.engine.view.value?.message)
        advanceTimeBy(7_999)
        runCurrent()
        call.progress(-1, -1)
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(false, h.engine.view.value?.coreInstall?.stalled)
        assertEquals(true, h.engine.view.value?.coreInstall?.installing)
        advanceTimeBy(7_999)
        runCurrent()
        assertEquals(true, h.engine.view.value?.coreInstall?.stalled)

        h.engine.act(TogetherAction.RETRY_CORE_INSTALL)
        runCurrent()
        assertEquals(2, h.emulators.calls.size)
        assertTrue(call.finished)
        assertEquals(CoreInstallProgress("snes9x"), h.engine.view.value?.coreInstall)
        h.engine.act(TogetherAction.CANCEL_CORE_INSTALL)
        runCurrent()
        assertNull(h.engine.view.value?.coreInstall)
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, h.engine.view.value?.problem?.action)
    }

    @Test
    fun confirmedInstallRechecksReadinessInsteadOfAssumingHostingIsReady() = runTest {
        val h = coreInstallHarness(this)
        h.engine.hostGame(1)
        runCurrent()
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        h.emulators.calls.single().result.complete(CoreResult.Started)
        runCurrent()

        assertEquals(2, h.emulators.readinessCalls)
        assertEquals(HostStep.NOT_READY, h.engine.view.value?.step)
        assertEquals(TogetherAction.INSTALL_CORE, h.engine.view.value?.problem?.action)
        assertNull(h.engine.view.value?.message)
        assertNull(h.engine.view.value?.coreInstall)
    }

    @Test
    fun failedInstallUsesRecoveryActionAndOpenRetroArchShowsFailureOrOpens() = runTest {
        val h = coreInstallHarness(this)
        h.engine.hostGame(1)
        runCurrent()
        h.engine.act(TogetherAction.INSTALL_CORE)
        runCurrent()
        h.emulators.calls.single().result.complete(CoreResult.Failed("file access", TogetherAction.OPEN_RETROARCH))
        runCurrent()
        assertEquals("file access", h.engine.view.value?.problem?.text)
        assertEquals(TogetherAction.OPEN_RETROARCH, h.engine.view.value?.problem?.action)
        assertNull(h.engine.view.value?.coreInstall)
        h.engine.act(TogetherAction.OPEN_RETROARCH)
        runCurrent()
        assertTrue(h.engine.view.value?.message?.contains("Couldn't open RetroArch") == true)

        var opens = 0
        val successPlatform = object : TogetherPlatform by FakePlatform() {
            override fun openRetroArch(): Boolean {
                opens += 1
                return true
            }
        }
        val opened = coreInstallHarness(this, platform = successPlatform)
        opened.engine.hostGame(1)
        runCurrent()
        opened.engine.act(TogetherAction.OPEN_RETROARCH)
        runCurrent()
        assertEquals(1, opens)
        assertNull(opened.engine.view.value?.message)
    }

    @Test
    fun byeInGameAlertsWhenBackgroundAndClosedLaunchIsDropped() = runTest {
        val platform = FakePlatform()
        platform.playingEntered = kotlinx.coroutines.CompletableDeferred()
        val playing = harness(this, platform = platform)
        playing.engine.hostGame(1)
        advanceUntilIdle()
        val pair = playing.join(this)
        pair.guest.send(LinkMsg.GuestState(playing.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        playing.emulators.launchGate = kotlinx.coroutines.CompletableDeferred()
        playing.engine.start()
        runCurrent()
        playing.emulators.launchGate?.complete(Unit)
        platform.playingEntered?.await()
        runCurrent()
        assertEquals(HostStep.IN_GAME, playing.engine.view.value?.step)

        platform.foregroundState.value = false
        pair.guest.send(LinkMsg.Bye(ByeReason.LEFT))
        advanceUntilIdle()
        assertEquals(HostStep.IN_GAME, playing.engine.view.value?.step)
        assertEquals(true, playing.engine.view.value?.linkLost)
        assertEquals(TogetherCopy.peerLeft("Guest"), playing.engine.view.value?.message)
        assertEquals(listOf(TogetherAlert.PeerLeft("Guest")), playing.platform.alerts)

        val launching = harness(this)
        launching.engine.hostGame(1)
        advanceUntilIdle()
        val joined = launching.join(this)
        joined.guest.send(LinkMsg.GuestState(launching.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        launching.emulators.launchGate = kotlinx.coroutines.CompletableDeferred()
        launching.engine.start()
        runCurrent()
        assertEquals(HostStep.LAUNCHING, launching.engine.view.value?.step)
        joined.guest.close()
        runCurrent()
        assertEquals(HostStep.WAITING, launching.engine.view.value?.step)
        assertEquals(TogetherCopy.linkLost("Guest"), launching.engine.view.value?.message)
        launching.emulators.launchGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(HostStep.WAITING, launching.engine.view.value?.step)
        assertTrue(joined.host.sent.none { it is LinkMsg.Launch })
    }

    @Test
    fun closedWhileJoinedReturnsToWaiting() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        assertEquals(HostStep.JOINED, h.engine.view.value?.step)
        pair.guest.close()
        advanceUntilIdle()
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
        assertEquals(false, h.engine.view.value?.linkLost)
        assertNull(h.engine.view.value?.guestName)
        assertEquals(TogetherCopy.linkLost("Guest"), h.engine.view.value?.message)
        assertTrue(h.platform.alerts.isEmpty())
    }

    @Test
    fun closedInGameAlertsWhenBackground() = runTest {
        val platform = FakePlatform()
        platform.playingEntered = kotlinx.coroutines.CompletableDeferred()
        platform.foregroundState.value = false
        val h = harness(this, platform = platform)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        h.emulators.launchGate = kotlinx.coroutines.CompletableDeferred()
        h.engine.start()
        runCurrent()
        h.emulators.launchGate?.complete(Unit)
        platform.playingEntered?.await()
        runCurrent()
        pair.guest.close()
        advanceUntilIdle()
        assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
        assertEquals(true, h.engine.view.value?.linkLost)
        assertEquals(TogetherCopy.linkLost("Guest"), h.engine.view.value?.message)
        assertEquals(listOf(TogetherAlert.LinkLost("Guest")), h.platform.alerts)
    }

    @Test
    fun playAgainLaunchesAnotherRoundWithoutCanStart() = runTest {
        val platform = FakePlatform()
        platform.playingEntered = kotlinx.coroutines.CompletableDeferred()
        val h = harness(this, platform = platform)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        h.emulators.launchGate = kotlinx.coroutines.CompletableDeferred()
        h.engine.start()
        runCurrent()
        h.emulators.launchGate?.complete(Unit)
        platform.playingEntered?.await()
        runCurrent()

        assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
        assertEquals(false, h.engine.view.value?.canStart)
        assertEquals(TogetherCopy.ongoingPlaying("Guest", "Sample game"), platform.lastUpdate)
        assertEquals(1, h.discovery.stopCount)

        platform.playingEntered = kotlinx.coroutines.CompletableDeferred()
        h.emulators.launchGate = kotlinx.coroutines.CompletableDeferred()
        h.engine.playAgain()
        runCurrent()
        h.emulators.launchGate?.complete(Unit)
        platform.playingEntered?.await()
        runCurrent()

        val launches = pair.host.sent.filterIsInstance<LinkMsg.Launch>()
        assertEquals(listOf(1, 2), launches.map { it.round })
        assertEquals(2, h.emulators.launches.size)
        assertEquals(1, h.discovery.stopCount)
    }

    @Test
    fun endThenHostGameBeforeEndIsProcessedKeepsTheNewSession() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
        val seen = mutableListOf<HostStep>()
        val watch = launch { h.engine.view.collect { view -> if (view != null) seen += view.step } }
        runCurrent()
        seen.clear()
        h.engine.end()
        h.engine.hostGame(1)
        assertEquals(HostStep.CHECKING, h.engine.view.value?.step)
        runCurrent()
        watch.cancel()
        assertFalse(seen.contains(HostStep.ENDED))
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)
    }

    @Test
    fun endWaitsForAnInFlightLaunch() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        h.emulators.launchGate = gate
        h.engine.start()
        runCurrent()
        assertEquals(1, h.emulators.launches.size)
        h.engine.end()
        runCurrent()
        assertEquals(0, h.emulators.endSessionCount)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, h.emulators.endSessionCount)
        assertEquals(HostStep.ENDED, h.engine.view.value?.step)
    }

    @Test
    fun aFinishedSecondLaunchDoesNotClearTheFirstLaunchMarker() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val first = h.join(this, "first")
        first.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        val firstGate = CompletableDeferred<Unit>()
        h.emulators.launchGate = firstGate
        h.engine.start()
        runCurrent()
        assertEquals(1, h.emulators.launches.size)
        first.guest.close()
        runCurrent()
        assertEquals(HostStep.WAITING, h.engine.view.value?.step)

        h.emulators.launchGate = null
        val second = h.join(this, "second")
        second.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        h.engine.start()
        runCurrent()
        assertEquals(2, h.emulators.launches.size)
        assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)

        h.engine.end()
        runCurrent()
        assertEquals(0, h.emulators.endSessionCount)
        firstGate.complete(Unit)
        runCurrent()
        assertEquals(1, h.emulators.endSessionCount)
        assertEquals(HostStep.ENDED, h.engine.view.value?.step)
    }

    @Test
    fun customNameIsAdvertisedAndSentInHelloAndARenameReachesTheNextSession() = runTest {
        val platform = FakePlatform(name = "Sam's Deck")
        val h = harness(this, platform = platform)
        h.engine.hostGame(1)
        advanceUntilIdle()
        assertEquals("Sam's Deck", h.discovery.advertisements.last().name)
        val pair = h.join(this)
        assertEquals("Sam's Deck", pair.host.sent.filterIsInstance<LinkMsg.Hello>().single().name)
        h.engine.end()
        advanceUntilIdle()

        platform.name = "Couch"
        h.engine.hostGame(1)
        advanceUntilIdle()
        assertEquals("Couch", h.discovery.advertisements.last().name)
    }

    @Test
    fun endDoesNotWaitPastTwoSecondsForAStuckLaunch() = runTest {
        val h = harness(this)
        h.engine.hostGame(1)
        advanceUntilIdle()
        val pair = h.join(this)
        pair.guest.send(LinkMsg.GuestState(h.sessionId(), GuestPhase.READY))
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        h.emulators.launchGate = gate
        h.engine.start()
        runCurrent()
        h.engine.end()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(0, h.emulators.endSessionCount)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, h.emulators.endSessionCount)
        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun endDuringBlockedStartServiceStopsTheServiceAndEnds() = runTest {
        val io = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        try {
            val platform = FakePlatform()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            platform.serviceEntered = entered
            platform.serviceRelease = release
            val engineScope = this.engineTestScope()
            val engine = HostEngine(
                FakeLinkTransport(),
                FakeEmulators(),
                FakeLibrary(gameValue = sampleGame(), offerValue = sampleOffer()),
                FakeDiscovery(),
                platform,
                TogetherTiming(helloMs = 50, byeMs = 50),
                engineScope,
                io,
            )
            engine.hostGame(1)
            repeat(50) {
                runCurrent()
                if (entered.isCompleted) return@repeat
                Thread.sleep(20)
            }
            assertTrue(entered.isCompleted)
            engine.end()
            repeat(50) {
                runCurrent()
                if (engine.view.value?.step == HostStep.ENDED) return@repeat
                Thread.sleep(20)
            }
            assertEquals(HostStep.ENDED, engine.view.value?.step)
            assertEquals(0, platform.stopServiceCount)
            release.complete(Unit)
            repeat(50) {
                runCurrent()
                if (platform.stopServiceCount > 0) return@repeat
                Thread.sleep(20)
            }
            assertEquals(1, platform.stopServiceCount)
            assertEquals(HostStep.ENDED, engine.view.value?.step)
        } finally {
            io.close()
        }
    }

    @Test
    fun endContinuesAfterAStepThrows() = runTest {
        val platform = FakePlatform()
        val emulators = object : TogetherEmulators by FakeEmulators() {
            override suspend fun endSession() {
                throw IllegalStateException("end")
            }
        }
        val engineScope = this.engineTestScope()
        val engine = HostEngine(
            FakeLinkTransport(),
            emulators,
            FakeLibrary(gameValue = sampleGame(), offerValue = sampleOffer()),
            FakeDiscovery(),
            platform,
            TogetherTiming(helloMs = 50, byeMs = 50),
            engineScope,
            engineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )
        engine.hostGame(1)
        advanceUntilIdle()
        engine.end()
        advanceUntilIdle()
        assertEquals(1, platform.cancelAlertsCount)
        assertEquals(HostStep.ENDED, engine.view.value?.step)
    }

    private fun coreInstallHarness(
        scope: kotlinx.coroutines.CoroutineScope,
        platform: TogetherPlatform = FakePlatform(),
        modes: List<TogetherMode> = listOf(TogetherMode.RA_NETPLAY),
        game: com.gamebex.client.data.local.GameEntity =
            sampleGame().copy(folder = "snes", fileName = "sample.sfc", relPath = "sample.sfc", ext = "sfc"),
    ): CoreInstallHarness {
        val engineScope = scope.engineTestScope()
        val emulators = ControlledCoreInstallEmulators()
        val engine = HostEngine(
            FakeLinkTransport(),
            emulators,
            FakeLibrary(gameValue = game),
            FakeDiscovery(),
            platform,
            TogetherTiming(helloMs = 50, byeMs = 50),
            engineScope,
            engineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
            modesFor = { modes },
        )
        return CoreInstallHarness(engine, emulators)
    }

    private data class CoreInstallHarness(val engine: HostEngine, val emulators: ControlledCoreInstallEmulators)

    private fun Harness.sessionId(): String =
        discovery.advertisements.last().sessionId

    private suspend fun Harness.join(scope: kotlinx.coroutines.test.TestScope, name: String = "Guest"): LinkPair {
        val pair = transport.pair()
        pair.guest.send(LinkMsg.Hello(TOGETHER_PROTO, Role.GUEST, "guest-device-$name", name, "1.0"))
        scope.advanceUntilIdle()
        return pair
    }


    private fun harness(
        scope: kotlinx.coroutines.CoroutineScope,
        game: com.gamebex.client.data.local.GameEntity = sampleGame(),
        offer: com.gamebex.client.together.OfferedGame = sampleOffer(),
        discovery: FakeDiscovery = FakeDiscovery(),
        platform: FakePlatform = FakePlatform(),
    ): Harness {
        val engineScope = scope.engineTestScope()
        val transport = FakeLinkTransport()
        val library = FakeLibrary(gameValue = game, offerValue = offer)
        val emulators = FakeEmulators()
        val engine = HostEngine(
            transport,
            emulators,
            library,
            discovery,
            platform,
            com.gamebex.client.together.TogetherTiming(helloMs = 50, byeMs = 50),
            engineScope,
            engineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )
        return Harness(engine, transport, discovery, platform, library, emulators)
    }

    private data class Harness(
        val engine: HostEngine,
        val transport: FakeLinkTransport,
        val discovery: FakeDiscovery,
        val platform: FakePlatform,
        val library: FakeLibrary,
        val emulators: FakeEmulators,
    )
}

private class EndGateEmulators : TogetherEmulators by FakeEmulators() {
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

private class ControlledCoreInstallEmulators : TogetherEmulators by FakeEmulators() {
    var readinessValue: Readiness = Readiness.NeedsCore("snes9x")
    var readinessCalls = 0
    var keepFirstAlive = false
    var onFetch: (() -> Unit)? = null
    val calls = mutableListOf<Call>()
    val coreArgs = mutableListOf<String>()

    class Call(val progress: (Long, Long) -> Unit) {
        val result = CompletableDeferred<CoreResult>()
        var finished = false
    }

    override suspend fun readiness(
        mode: TogetherMode,
        role: Role,
        game: com.gamebex.client.data.local.GameEntity,
        core: String?,
    ): Readiness {
        readinessCalls += 1
        return readinessValue
    }

    override suspend fun fetchAndInstallCore(
        core: String,
        game: com.gamebex.client.data.local.GameEntity,
        onProgress: (Long, Long) -> Unit,
    ): CoreResult {
        val call = Call(onProgress)
        calls += call
        coreArgs += core
        onFetch?.invoke()
        try {
            return if (keepFirstAlive && calls.size == 1) {
                withContext(NonCancellable) { call.result.await() }
            } else {
                call.result.await()
            }
        } finally {
            call.finished = true
        }
    }
}

private class SecondGameLibrary(private val inner: FakeLibrary = FakeLibrary()) : TogetherLibrary by inner {
    val secondEntered = CompletableDeferred<Unit>()
    val secondRelease = CompletableDeferred<Unit>()
    private var calls = 0

    override suspend fun game(gameId: Long): com.gamebex.client.data.local.GameEntity? {
        calls += 1
        if (calls > 1) {
            secondEntered.complete(Unit)
            secondRelease.await()
        }
        return inner.game(gameId)
    }
}
