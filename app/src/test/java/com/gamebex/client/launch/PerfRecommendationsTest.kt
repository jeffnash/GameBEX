package com.gamebex.client.launch

import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfRecommendationsTest {
    private fun plan(emu: Emu, tier: DeviceTier, core: String? = null) = PerfRecommendations.forEmu(emu, core, tier)!!

    @Test fun netherLowTierCoversTheOnDeviceFindings() {
        val recs = plan(Emu.NETHER, DeviceTier.LOW).recs.associateBy { it.setting }
        assertEquals("On", recs.getValue("Enable Frame Limit").value)
        assertEquals(RecKind.MANUAL, recs.getValue("Enable Frame Limit").kind)
        assertEquals(RecKind.TRY_IF_SLOW, recs.getValue("EE Cycle Rate").kind)
        assertTrue(recs.getValue("EE Cycle Rate").value.startsWith("-1"))
        assertEquals(RecKind.TRY_IF_SLOW, recs.getValue("Hardware Download Mode").kind)
        assertEquals("Vulkan", recs.getValue("GPU Renderer").value)
        assertEquals("On", recs.getValue("MTVU").value)
        assertTrue(recs.getValue("Upscale Multiplier").value.startsWith("Native"))
        assertEquals(RecKind.NOTE, recs.getValue("Full speed").kind)
        // GameBEX writes no NetherSX2 settings.
        assertFalse(plan(Emu.NETHER, DeviceTier.LOW).applicable)
    }

    @Test fun netherHigherTiersDropTheSlowPathTradeoffs() {
        val recs = plan(Emu.NETHER, DeviceTier.HIGH).recs.map { it.setting }
        assertFalse("EE Cycle Rate" in recs)
        assertFalse("Hardware Download Mode" in recs)
        assertTrue("Enable Frame Limit" in recs)
    }

    @Test fun dolphinAutoRowsDependOnTier() {
        val low = plan(Emu.DOLPHIN, DeviceTier.LOW)
        assertTrue(low.applicable)
        assertEquals(
            setOf(PerfRecommendations.DOLPHIN_BACKEND, PerfRecommendations.DOLPHIN_RES),
            low.recs.filter { it.kind == RecKind.AUTO }.mapNotNull { it.key }.toSet(),
        )
        val mid = plan(Emu.DOLPHIN, DeviceTier.MID)
        assertEquals(listOf(PerfRecommendations.DOLPHIN_BACKEND), mid.recs.filter { it.kind == RecKind.AUTO }.map { it.key })
    }

    @Test fun platformPlansRouteFirstThenAlternates() {
        val gc = PerfRecommendations.forPlatform(PlatformRegistry.byFolder.getValue("gc"), DeviceTier.LOW)
        assertEquals(listOf(Emu.DOLPHIN, Emu.RETROARCH), gc.map { it.emu })
        assertNull(gc.first().core)
        assertEquals("dolphin", gc.last().core)
        val psp = PerfRecommendations.forPlatform(PlatformRegistry.byFolder.getValue("psp"), DeviceTier.LOW)
        assertEquals(listOf(Emu.PPSSPP, Emu.RETROARCH), psp.map { it.emu })
        assertNull(psp.first().core)
        assertEquals("ppsspp", psp.last().core)
        val ps2 = PerfRecommendations.forPlatform(PlatformRegistry.byFolder.getValue("ps2"), DeviceTier.LOW)
        assertEquals(listOf(Emu.NETHER, Emu.ARMSX2), ps2.map { it.emu })
        // Blocked systems get nothing.
        assertTrue(PerfRecommendations.forPlatform(PlatformRegistry.byFolder.getValue("ps3"), DeviceTier.LOW).isEmpty())
    }

    @Test fun retroArchKeepsThreadedVideoOffAndStaysCompact() {
        val saturn = plan(Emu.RETROARCH, DeviceTier.LOW, "mednafen_saturn")
        assertTrue(saturn.recs.any { it.setting == "Threaded Video" && it.value == "Off" && it.kind == RecKind.MANAGED })
        assertFalse(saturn.applicable)
        for (core in listOf("snes9x", "mednafen_psx", "ppsspp", "flycast", "citra", "dolphin", "mednafen_saturn")) {
            for (t in DeviceTier.entries) assertTrue(plan(Emu.RETROARCH, t, core).recs.size <= 5)
        }
        assertNotNull(PerfRecommendations.forEmu(Emu.RETROARCH, "snes9x", DeviceTier.HIGH))
    }

    @Test fun lightEmulatorsNeedNothing() {
        assertNull(PerfRecommendations.forEmu(Emu.SCUMMVM, null, DeviceTier.LOW))
        assertNull(PerfRecommendations.forEmu(Emu.J2MELOADER, null, DeviceTier.LOW))
        // Only Dolphin is ever writable.
        for (e in Emu.entries) for (t in DeviceTier.entries) {
            val p = PerfRecommendations.forEmu(e, null, t) ?: continue
            assertEquals(e == Emu.DOLPHIN, p.applicable)
        }
    }
}
