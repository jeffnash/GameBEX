package com.gamebex.client.ui.presentation

import com.gamebex.client.data.Emu
import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.launch.RetroCoreFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quick-play outcome → status panel: one action, semantic tone, no bare text. */
class LaunchStatusTest {
    @Test fun launchedClearsThePanel() {
        assertNull(launchStatusFor(7, EmuRouter.Outcome.Launched("RetroArch (snes9x)", "pkg")))
    }

    @Test fun missingEmulatorOffersInstall() {
        val s = launchStatusFor(7, EmuRouter.Outcome.MissingEmu(Emu.RETROARCH))!!
        assertEquals("RetroArch isn't installed.", s.text)
        assertEquals(Tone.Attention, s.tone)
        assertEquals(LaunchAction.Install(Emu.RETROARCH), s.action)
        assertEquals("Install", s.action!!.label())
        assertEquals(7L, s.gameId)
    }

    @Test fun blockedIsRedAndRoutesToDetails() {
        val s = launchStatusFor(7, EmuRouter.Outcome.Blocked("Missing BIOS."))!!
        assertEquals(Tone.Blocked, s.tone)
        assertEquals(LaunchAction.OpenDetails, s.action)
        assertEquals("Open details", s.action!!.label())
    }

    @Test fun needsSetupIsAmberAndRoutesToDetails() {
        val s = launchStatusFor(7, EmuRouter.Outcome.NeedsSetup("Import it first."))!!
        assertEquals(Tone.Attention, s.tone)
        assertEquals(LaunchAction.OpenDetails, s.action)
    }

    @Test fun retroArchTimeoutOffersOpenRuntimeAndRetryNotTheInstaller() {
        val s = launchStatusFor(
            7, EmuRouter.Outcome.NeedsSetup(RetroCoreFetcher.INSTALL_SETUP_GUIDANCE),
            fresh = true, retroCore = true,
        )!!
        assertEquals(RetroCoreFetcher.INSTALL_SETUP_GUIDANCE, s.text)
        assertEquals(LaunchAction.OpenRetroArch, s.action)
        assertEquals("Open RetroArch", s.action!!.label())
        assertEquals(LaunchAction.Retry, s.secondaryAction)
        assertTrue(s.fresh)
        assertFalse(s.busy)
        assertEquals(LaunchAction.OpenDetails, launchStatusFor(
            7, EmuRouter.Outcome.NeedsSetup("Import it first."), retroCore = true,
        )!!.action)
        assertEquals(LaunchAction.OpenDetails, launchStatusFor(
            7, EmuRouter.Outcome.NeedsSetup(RetroCoreFetcher.INSTALL_SETUP_GUIDANCE),
        )!!.action)
    }

    @Test fun failureOffersRetryInTheSameMode() {
        val s = launchStatusFor(7, EmuRouter.Outcome.Failed("ROM file is no longer available."), fresh = true)!!
        assertEquals(Tone.Attention, s.tone)
        assertEquals(LaunchAction.Retry, s.action)
        assertTrue("retry must stay fresh", s.fresh)
    }

    @Test fun coreProgressExposesBytesStageAndStallRecovery() {
        val p = LaunchStatus.coreInstall(7, CoreInstallProgress("snes9x", 1_048_576, 4_194_304), fresh = true)
        assertEquals("Downloading 1.0 of 4.0 MB", p.text)
        assertEquals(0.25f, p.coreProgress!!.fraction!!, 0f)
        assertTrue(p.busy)
        assertTrue(p.canCancel)
        assertTrue(p.fresh)
        assertNull(p.action)
        val installing = LaunchStatus.coreInstall(7, p.coreProgress.copy(installing = true))
        assertEquals("Installing in RetroArch…", installing.text)
        assertNull(installing.coreProgress!!.fraction)
        val stalled = LaunchStatus.coreInstall(7, p.coreProgress.copy(stalled = true))
        assertEquals("Still working…", stalled.text)
        assertEquals(LaunchAction.Retry, stalled.action)
    }

    @Test fun watchdogStartsAtPressAndRepeatedCallbacksDoNotHideAnEightSecondStall() = runTest {
        val seen = mutableListOf<LaunchStatus?>()
        val finish = CompletableDeferred<EmuRouter.Outcome>()
        lateinit var report: (Long, Long) -> Unit
        val call = async {
            LocalLauncher.withCoreProgress(7, "mupen64plus_next_gles3", true, { seen += it }) {
                report = it
                finish.await()
            }
        }
        testScheduler.runCurrent()
        assertEquals("Installing the N64 core…", seen.last()!!.text)
        testScheduler.advanceTimeBy(7_999)
        testScheduler.runCurrent()
        assertFalse(seen.last()!!.coreProgress!!.stalled)
        report(0, 0) // Identical callback is not forward progress.
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals("Still working…", seen.last()!!.text)
        assertEquals(LaunchAction.Retry, seen.last()!!.action)
        report(1_048_576, 0)
        assertEquals("Downloading 1.0 MB", seen.last()!!.text)
        assertNull(seen.last()!!.coreProgress!!.fraction)
        assertFalse(seen.last()!!.coreProgress!!.stalled)
        testScheduler.advanceTimeBy(7_999)
        testScheduler.runCurrent()
        assertFalse(seen.last()!!.coreProgress!!.stalled)
        report(-1, -1)
        assertEquals("Installing in RetroArch…", seen.last()!!.text)
        assertTrue(seen.last()!!.fresh)
        testScheduler.advanceTimeBy(8_000)
        testScheduler.runCurrent()
        assertTrue(seen.last()!!.coreProgress!!.stalled)
        finish.complete(EmuRouter.Outcome.NeedsSetup(RetroCoreFetcher.INSTALL_SETUP_GUIDANCE))
        call.await()
        val count = seen.size
        testScheduler.advanceTimeBy(16_000)
        testScheduler.runCurrent()
        report(2, 4) // A stale backend callback after completion cannot revive progress.
        assertEquals(count, seen.size)
    }

    @Test fun nonRetroArchRoutesNeverPretendToInstallACore() = runTest {
        val seen = mutableListOf<LaunchStatus?>()
        LocalLauncher.withCoreProgress(7, null, false, { seen += it }) { report ->
            report(1, 2)
            testScheduler.advanceTimeBy(9_000)
            EmuRouter.Outcome.NeedsSetup("Import it first.")
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun retryWaitsForActualCancellationCleanupAndIgnoresOldCallbacksInTheSameMode() = runTest {
        val seen = mutableListOf<LaunchStatus?>()
        val modes = mutableListOf<Pair<Long, Boolean>>()
        val cleanup = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<(LaunchStatus?) -> Unit>()
        var cancellations = 0
        val owner = LocalLaunchJob({ this }, { id, fresh, report ->
            modes += id to fresh
            callbacks += report
            report(LaunchStatus.coreInstall(id, CoreInstallProgress("snes9x"), fresh))
            try {
                awaitCancellation()
            } finally {
                cancellations++
                withContext(NonCancellable) { cleanup.await() }
                report(LaunchStatus.progress(id, "Old cleanup", fresh))
            }
        }, { seen += it })
        owner.play(7, true)
        owner.play(7, false) // The job reservation happens at the press, not a later dispatch.
        testScheduler.runCurrent()
        assertEquals(listOf(7L to true), modes)
        owner.dismiss()
        testScheduler.runCurrent()
        assertEquals(1, cancellations)
        assertEquals(LaunchAction.Retry, seen.last()!!.action)
        assertFalse(seen.last()!!.busy)
        owner.retry()
        testScheduler.runCurrent()
        assertTrue(seen.last()!!.canCancel) // Retry waiting for cleanup is cancellable too.
        owner.dismiss()
        owner.retry()
        testScheduler.runCurrent()
        assertEquals(1, modes.size)
        cleanup.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf(7L to true, 7L to true), modes)
        callbacks.first()(LaunchStatus.progress(7, "Late callback", true))
        assertEquals("Installing the Snes9x core…", seen.last()!!.text)
        owner.dismiss()
        testScheduler.runCurrent()
    }

    @Test fun spontaneousBackendCancellationSettlesBusyAndAllowsExactModeRetry() = runTest {
        val seen = mutableListOf<LaunchStatus?>()
        val modes = mutableListOf<Pair<Long, Boolean>>()
        val owner = LocalLaunchJob({ this }, { id, fresh, report ->
            modes += id to fresh
            report(LaunchStatus.coreInstall(id, CoreInstallProgress("snes9x"), fresh))
            if (modes.size == 1) throw CancellationException("backend cancelled")
            report(null)
        }, { seen += it })
        owner.play(7, true)
        testScheduler.runCurrent()
        assertFalse(seen.last()!!.busy)
        assertEquals(LaunchAction.Retry, seen.last()!!.action)
        assertTrue(seen.last()!!.fresh)
        owner.retry()
        testScheduler.runCurrent()
        assertEquals(listOf(7L to true, 7L to true), modes)
        assertNull(seen.last())
    }

    @Test fun stalledRetryCancelsTheBackendBeforeReplacingIt() = runTest {
        var calls = 0
        var cancelled = false
        var current: LaunchStatus? = null
        val owner = LocalLaunchJob({ this }, { id, fresh, report ->
            calls++
            if (calls == 2) assertTrue(cancelled)
            report(LaunchStatus.coreInstall(id, CoreInstallProgress("snes9x", stalled = true), fresh))
            try { awaitCancellation() } finally { cancelled = true }
        }, { current = it })
        owner.play(7, true)
        testScheduler.runCurrent()
        owner.retry()
        testScheduler.runCurrent()
        assertEquals(2, calls)
        assertTrue(current!!.fresh)
        owner.dismiss()
        testScheduler.runCurrent()
    }

    @Test fun settledStatesNeverBusyAndProgressIsNeutral() {
        val outcomes = listOf(
            EmuRouter.Outcome.MissingEmu(Emu.RETROARCH),
            EmuRouter.Outcome.Blocked("x"),
            EmuRouter.Outcome.NeedsSetup("x"),
            EmuRouter.Outcome.Failed("x"),
        )
        for (o in outcomes) assertEquals(false, launchStatusFor(1, o)!!.busy)
        val p = LaunchStatus.progress(1, "Launching…")
        assertTrue(p.busy)
        assertEquals(Tone.Neutral, p.tone)
        assertNull(p.action)
    }
}
