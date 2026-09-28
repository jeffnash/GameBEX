package com.gamebex.client.ui.presentation

import com.gamebex.client.data.Emu
import com.gamebex.client.launch.EmuRouter
import org.junit.Assert.assertEquals
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

    @Test fun failureOffersRetryInTheSameMode() {
        val s = launchStatusFor(7, EmuRouter.Outcome.Failed("ROM file is no longer available."), fresh = true)!!
        assertEquals(Tone.Attention, s.tone)
        assertEquals(LaunchAction.Retry, s.action)
        assertTrue("retry must stay fresh", s.fresh)
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
