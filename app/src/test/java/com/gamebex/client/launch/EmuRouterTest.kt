package com.gamebex.client.launch

import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.PrepPipeline
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fetch-or-boot: an unanswered probe must fetch, never boot blind. */
class EmuRouterTest {
    @Test fun fetchesOnPositiveAbsence() {
        assertEquals(true, EmuRouter.shouldFetchCore(setOf("snes9x"), "flycast", true))
    }

    @Test fun bootsWhenPresent() {
        assertEquals(false, EmuRouter.shouldFetchCore(setOf("flycast"), "flycast", true))
    }

    @Test fun matchIsCaseInsensitive() {
        assertEquals(false, EmuRouter.shouldFetchCore(setOf("Flycast"), "flycast", true))
    }

    @Test fun nullProbeFetchesInsteadOfBlindBoot() {
        assertEquals(true, EmuRouter.shouldFetchCore(null, "flycast", true))
    }

    @Test fun ppssppAndFlycastNeedTheNamedViewUri() {
        assertEquals(true, EmuRouter.needsNamedViewUri(Emu.PPSSPP))
        assertEquals(true, EmuRouter.needsNamedViewUri(Emu.FLYCAST))
        assertEquals(false, EmuRouter.needsNamedViewUri(Emu.DOLPHIN))
        assertEquals(false, EmuRouter.needsNamedViewUri(Emu.MELONDS))
        assertEquals(false, EmuRouter.needsNamedViewUri(Emu.AZAHAR))
        assertEquals(false, EmuRouter.needsNamedViewUri(Emu.RETROARCH))
    }

    @Test fun noRetroArchMeansNoFetch() {
        assertEquals(false, EmuRouter.shouldFetchCore(null, "flycast", false))
        assertEquals(false, EmuRouter.shouldFetchCore(emptySet(), "flycast", false))
    }

    @Test fun rawDiscArchivesGateToPrep() {
        // Every archive type on every disc folder — never raw to an emu.
        for (ext in listOf("zip", "7z", "rar", "001", "tgz")) {
            for (folder in listOf("ps2", "psp", "gc", "wii", "psx", "dreamcast", "segacd")) {
                assertEquals(
                    "gate $folder/$ext",
                    true,
                    EmuRouter.needsArchivePrep(folder, ext, PrepPipeline.PREP_NONE),
                )
            }
        }
    }

    @Test fun gatePassesNonArchivesAndPrepared() {
        // Bootable images, cartridges, and prepared rows launch as before.
        assertEquals(false, EmuRouter.needsArchivePrep("ps2", "iso", PrepPipeline.PREP_NONE))
        assertEquals(false, EmuRouter.needsArchivePrep("psp", "cso", PrepPipeline.PREP_NONE))
        assertEquals(false, EmuRouter.needsArchivePrep("gc", "rvz", PrepPipeline.PREP_NONE))
        assertEquals(false, EmuRouter.needsArchivePrep("gb", "zip", PrepPipeline.PREP_NONE))
        assertEquals(false, EmuRouter.needsArchivePrep("mame", "zip", PrepPipeline.PREP_NONE))
        assertEquals(false, EmuRouter.needsArchivePrep("ps2", "zip", PrepPipeline.PREP_READY))
        assertEquals(false, EmuRouter.needsArchivePrep("ps2", "zip", PrepPipeline.PREP_FAILED))
    }

    @Test fun gateMatchesCaseInsensitively() {
        assertEquals(true, EmuRouter.needsArchivePrep("ps2", "ZIP", PrepPipeline.PREP_NONE))
    }

    // Start fresh is offered only where the launch config is ours (RetroArch).
    @Test fun startFreshOnlyOnRetroArchRoute() {
        assertEquals(true, EmuRouter.canStartFresh("snes", null))
        assertEquals(true, EmuRouter.canStartFresh("psp", Emu.RETROARCH.pkg))
        // Standalone override on a RetroCore platform can't honor it.
        assertEquals(false, EmuRouter.canStartFresh("psp", Emu.PPSSPP.pkg))
        // Standalone / guided routes and unknown folders never offer it.
        assertEquals(false, EmuRouter.canStartFresh("ps2", null))
        assertEquals(false, EmuRouter.canStartFresh("psvita", null))
        assertEquals(false, EmuRouter.canStartFresh("no-such-folder", null))
    }

    // GC/Wii default to the Dolphin app, but the RetroArch alternate boots
    // the dolphin core through RA, so fresh works with that override only.
    @Test fun startFreshOnRetroArchAlternate() {
        assertEquals(false, EmuRouter.canStartFresh("gc", null))
        assertEquals(false, EmuRouter.canStartFresh("gc", Emu.DOLPHIN.pkg))
        assertEquals(true, EmuRouter.canStartFresh("gc", Emu.RETROARCH.pkg))
        assertEquals(true, EmuRouter.canStartFresh("wii", Emu.RETROARCH.pkg))
        assertEquals(false, EmuRouter.canStartFresh("psp", null))
        assertEquals(false, EmuRouter.canStartFresh("psp", Emu.PPSSPP.pkg))
        assertEquals(true, EmuRouter.canStartFresh("psp", Emu.RETROARCH.pkg))
        // A Standalone platform without an RA alternate never offers it.
        assertEquals(false, EmuRouter.canStartFresh("ps2", Emu.RETROARCH.pkg))
    }
}
