package com.gamebex.client.ui.presentation

import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlayStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
    @Test fun readyIsGreenAndBlockersAreRed() {
        assertEquals(Tone.Ready, readinessOf(PlayStates.Status.Ready).tone)
        assertEquals(Tone.Blocked, readinessOf(PlayStates.Status.NeedsVolume).tone)
        assertEquals(Tone.Attention, readinessOf(PlayStates.Status.NeedsBios(listOf("scph5501.bin"))).tone)
    }

    @Test fun readinessNeverLeaksPackageIds() {
        val r = readinessOf(PlayStates.Status.NeedsRuntime(Emu.RETROARCH))
        assertTrue(r.label, !r.label.contains("com."))
    }

    @Test fun emulatorLabelHonorsOverrides() {
        assertEquals("None", emulatorLabel(null))
        assertEquals("None", emulatorLabel("no-such-system"))
        assertEquals(Emu.RETROARCH.label, emulatorLabel("gba", emulatorOverride = Emu.RETROARCH.pkg))
        assertEquals("RetroArch · Opera", emulatorLabel("3do"))
        assertEquals("RetroArch · Custom Core", emulatorLabel("3do", coreOverride = "custom_core"))
        assertEquals("RetroArch · Beetle PSX", emulatorLabel("psx"))
    }
}
