package com.gamebex.client.ui.presentation

import com.gamebex.client.data.Emu
import com.gamebex.client.data.Platform
import com.gamebex.client.data.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** System readiness derivation: every state names real probe evidence. */
class SystemReadinessTest {
    private fun plat(route: Route, folder: String = "gb", display: String = "Game Boy") =
        Platform("slug-$folder", folder, display, setOf("zip"), route)

    @Test fun blockedIsLimitedWithReason() {
        val item = deriveSystemReadiness(
            plat(Route.Blocked("no Android route")), emuInstalled = false,
            emuVersionName = null, emuVersionCode = 0, pinnedVersionCode = 0,
            cores = null, prep = null,
        )
        assertEquals(SystemReadinessState.LimitedSupport, item.state)
        assertEquals("GameBEX can't play Game Boy games yet.", item.detail)
        assertNull(item.primaryAction)
    }

    @Test fun missingSystemFilesNeedSetupWithFetchAction() {
        val route = Route.RetroCore("dolphin")
        val item = deriveSystemReadiness(
            plat(route, folder = "gc", display = "GameCube"), emuInstalled = true,
            emuVersionName = "1.22.2", emuVersionCode = 1, pinnedVersionCode = 0,
            cores = setOf("dolphin"), prep = null, missingSystemFiles = setOf("dolphin"),
        )
        assertEquals(SystemReadinessState.NeedsSetup, item.state)
        assertEquals(SystemAction.FetchSystemFiles("dolphin"), item.primaryAction)
        val ready = deriveSystemReadiness(
            plat(route, folder = "gc", display = "GameCube"), emuInstalled = true,
            emuVersionName = "1.22.2", emuVersionCode = 1, pinnedVersionCode = 0,
            cores = setOf("dolphin"), prep = null, missingSystemFiles = setOf("ppsspp"),
        )
        assertEquals(SystemReadinessState.Ready, ready.state)
    }

    @Test fun missingRetroArchIsMissingRuntime() {
        val item = deriveSystemReadiness(
            plat(Route.RetroCore("gambatte")), emuInstalled = false,
            emuVersionName = null, emuVersionCode = 0, pinnedVersionCode = 0,
            cores = null, prep = null,
        )
        assertEquals(SystemReadinessState.MissingRuntime, item.state)
        assertEquals(SystemAction.Install(Emu.RETROARCH.pkg), item.primaryAction)
    }

    @Test fun missingCoreIsNeedsSetup() {
        val item = deriveSystemReadiness(
            plat(Route.RetroCore("gambatte")), emuInstalled = true,
            emuVersionName = "1.22", emuVersionCode = 99, pinnedVersionCode = 0,
            cores = setOf("mgba"), prep = null,
        )
        assertEquals(SystemReadinessState.NeedsSetup, item.state)
        assertEquals(SystemAction.OpenCoreUpdater, item.primaryAction)
        assertEquals("Install the Gambatte core (Online Updater).", item.detail)
        assertEquals("RetroArch · Gambatte", item.runtimeLabel)
    }

    @Test fun presentCoreIsReady() {
        val item = deriveSystemReadiness(
            plat(Route.RetroCore("gambatte")), emuInstalled = true,
            emuVersionName = "1.22", emuVersionCode = 99, pinnedVersionCode = 0,
            cores = setOf("gambatte"), prep = PrepSummary(2, 2, 0, 0, 0),
        )
        assertEquals(SystemReadinessState.Ready, item.state)
        assertEquals("2 games", item.gamesLine)
    }

    @Test fun unknownCoresIsNeedsSetupNotReady() {
        val item = deriveSystemReadiness(
            plat(Route.RetroCore("gambatte")), emuInstalled = true,
            emuVersionName = "1.22", emuVersionCode = 99, pinnedVersionCode = 0,
            cores = null, prep = null,
        )
        assertEquals(SystemReadinessState.NeedsSetup, item.state)
    }

    @Test fun olderStandaloneIsUpdateAvailable() {
        val item = deriveSystemReadiness(
            plat(Route.Standalone(Emu.PPSSPP)), emuInstalled = true,
            emuVersionName = "1.0", emuVersionCode = 5, pinnedVersionCode = 10,
            cores = null, prep = null,
        )
        assertEquals(SystemReadinessState.UpdateAvailable, item.state)
        assertEquals(SystemAction.Install(Emu.PPSSPP.pkg), item.primaryAction)
    }

    @Test fun biosNeedFlipsReadyToNeedsSetupWithGuide() {
        val item = deriveSystemReadiness(
            plat(Route.Standalone(Emu.NETHER), folder = "ps2", display = "PlayStation 2"),
            emuInstalled = true, emuVersionName = "1.0", emuVersionCode = 10,
            pinnedVersionCode = 10, cores = null,
            prep = PrepSummary(3, 2, 1, 0, 0),
        )
        assertEquals(SystemReadinessState.NeedsSetup, item.state)
        assertEquals("3 games · 1 needs BIOS", item.gamesLine)
        assertNotNull(item.biosGuide)
    }

    @Test fun biosGuideKnownAndUnknown() {
        val ps2 = biosGuideFor("ps2")
        assertNotNull(ps2)
        assertTrue(ps2!!.startsWith("Copy "))
        assertNull(biosGuideFor("no-such-folder"))
    }

    @Test fun everyEmuHasOverlayGuide() {
        for (emu in Emu.entries) {
            val guide = overlayGuideFor(emu)
            assertNotNull("missing overlay guide for $emu", guide)
            assertTrue("empty overlay guide for $emu", guide!!.isNotBlank())
        }
    }

    @Test fun overlayGuideNamesRealToggle() {
        assertTrue(overlayGuideFor(Emu.RETROARCH)!!.contains("Hide Overlay When Controller Is Connected"))
        assertTrue(overlayGuideFor(Emu.PPSSPP)!!.contains("On-Screen Touch Controls"))
        assertTrue(overlayGuideFor(Emu.MELONDS)!!.contains("Hide console buttons when a controller is connected"))
        assertTrue(overlayGuideFor(Emu.AZAHAR)!!.contains("Show Controller Overlay"))
        assertTrue(overlayGuideFor(Emu.DOLPHIN)!!.contains("Overlay Controls"))
        assertTrue(overlayGuideFor(Emu.NETHER)!!.contains("Hide With External Controller"))
        assertTrue(overlayGuideFor(Emu.VITA3K)!!.contains("Show On-Screen Controls"))
        assertTrue(overlayGuideFor(Emu.SCUMMVM)!!.contains("Show On-screen control"))
        assertTrue(overlayGuideFor(Emu.MAME4DROID)!!.contains("Hide touch controls in-game"))
        assertTrue(overlayGuideFor(Emu.FLYCAST)!!.contains("Virtual Gamepad"))
        assertTrue(overlayGuideFor(Emu.J2MELOADER)!!.contains("Hide buttons"))
    }

    @Test fun retroCoreItemCarriesRetroArchOverlayGuide() {
        val item = deriveSystemReadiness(
            plat(Route.RetroCore("gambatte")), emuInstalled = true,
            emuVersionName = "1.22", emuVersionCode = 99, pinnedVersionCode = 0,
            cores = setOf("gambatte"), prep = null,
        )
        assertEquals(overlayGuideFor(Emu.RETROARCH), item.overlayGuide)
    }

    @Test fun standaloneItemCarriesItsEmuOverlayGuide() {
        val item = deriveSystemReadiness(
            plat(Route.Standalone(Emu.NETHER)), emuInstalled = true,
            emuVersionName = "1.0", emuVersionCode = 10, pinnedVersionCode = 10,
            cores = null, prep = null,
        )
        assertEquals(overlayGuideFor(Emu.NETHER), item.overlayGuide)
    }

    @Test fun blockedItemHasNoOverlayGuide() {
        val item = deriveSystemReadiness(
            plat(Route.Blocked("no Android route")), emuInstalled = false,
            emuVersionName = null, emuVersionCode = 0, pinnedVersionCode = 0,
            cores = null, prep = null,
        )
        assertNull(item.overlayGuide)
    }

    @Test fun everyEmuHasGraphicsGuide() {
        for (emu in Emu.entries) {
            val guide = graphicsGuideFor(emu)
            assertTrue("empty graphics guide for $emu", guide.isNotBlank())
        }
    }

    @Test fun graphicsGuideNamesRealSettings() {
        assertTrue(graphicsGuideFor(Emu.RETROARCH).contains("Managed automatically"))
        assertTrue(graphicsGuideFor(Emu.PPSSPP).contains("Rendering Resolution 2x"))
        assertTrue(graphicsGuideFor(Emu.MELONDS).contains("Internal resolution 2x"))
        assertTrue(graphicsGuideFor(Emu.AZAHAR).contains("Internal Resolution 2x"))
        assertTrue(graphicsGuideFor(Emu.DOLPHIN).contains("Stretch"))
        assertTrue(graphicsGuideFor(Emu.NETHER).contains("Upscale Multiplier 2x"))
        assertTrue(graphicsGuideFor(Emu.VITA3K).contains("stretch the display area"))
        assertTrue(graphicsGuideFor(Emu.SCUMMVM).contains("Aspect ratio correction"))
        assertTrue(graphicsGuideFor(Emu.MAME4DROID).contains("fit to screen"))
        assertTrue(graphicsGuideFor(Emu.FLYCAST).contains("Widescreen ON"))
        assertTrue(graphicsGuideFor(Emu.J2MELOADER).contains("filtering OFF"))
    }

    @Test fun standaloneItemCarriesItsEmuGraphicsGuide() {
        val item = deriveSystemReadiness(
            plat(Route.Standalone(Emu.DOLPHIN)), emuInstalled = true,
            emuVersionName = "1.0", emuVersionCode = 10, pinnedVersionCode = 10,
            cores = null, prep = null,
        )
        assertEquals(graphicsGuideFor(Emu.DOLPHIN), item.graphicsGuide)
    }

    @Test fun blockedItemHasNoGraphicsGuide() {
        val item = deriveSystemReadiness(
            plat(Route.Blocked("no Android route")), emuInstalled = false,
            emuVersionName = null, emuVersionCode = 0, pinnedVersionCode = 0,
            cores = null, prep = null,
        )
        assertNull(item.graphicsGuide)
    }

    @Test fun everyEmuHasControllerGuide() {
        for (emu in Emu.entries) {
            val guide = controllerGuideFor(emu)
            assertTrue("$emu guide names the connect-first rule", guide.contains("launch", true))
            assertTrue("$emu guide is non-blank", guide.isNotBlank())
        }
    }

    @Test fun dolphinGuideNamesPortPick() {
        assertTrue(controllerGuideFor(Emu.DOLPHIN).contains("Port 1"))
    }
}

class NeedsAttentionTest {
    private fun plat(route: Route, folder: String = "gb", display: String = "Game Boy") =
        Platform("slug-$folder", folder, display, setOf("zip"), route)

    @org.junit.Test fun unconfiguredSystemWithoutGamesIsNotAttention() {
        org.junit.Assert.assertFalse(needsAttention(SystemReadinessState.NeedsSetup, gameCount = 0))
        org.junit.Assert.assertFalse(needsAttention(SystemReadinessState.MissingRuntime, gameCount = 0))
    }

    @org.junit.Test fun unconfiguredSystemWithGamesIsAttention() {
        org.junit.Assert.assertTrue(needsAttention(SystemReadinessState.NeedsSetup, gameCount = 3))
        org.junit.Assert.assertTrue(needsAttention(SystemReadinessState.UpdateAvailable, gameCount = 1))
        org.junit.Assert.assertFalse(needsAttention(SystemReadinessState.Ready, gameCount = 3))
    }

    @Test fun biosCountsPluralizeCorrectly() {
        fun detail(n: Int) = deriveSystemReadiness(
            plat(Route.Standalone(Emu.NETHER), folder = "ps2", display = "PlayStation 2"),
            emuInstalled = true, emuVersionName = "1.0", emuVersionCode = 10,
            pinnedVersionCode = 10, cores = null,
            prep = PrepSummary(n + 1, 1, n, 0, 0),
        )
        assertEquals("1 game needs BIOS files.", detail(1).detail)
        assertEquals("2 games need BIOS files.", detail(2).detail)
        assertEquals("3 games · 2 need BIOS", detail(2).gamesLine)
        val core = deriveSystemReadiness(
            plat(Route.RetroCore("gambatte")), emuInstalled = true,
            emuVersionName = "1.22", emuVersionCode = 99, pinnedVersionCode = 0,
            cores = setOf("gambatte"), prep = PrepSummary(1, 0, 1, 0, 0),
        )
        assertEquals("1 game needs BIOS files.", core.detail)
        assertEquals("1 game · 1 needs BIOS", core.gamesLine)
    }

    @Test fun blockedDetailNeverShowsRouteNotes() {
        for (p in com.gamebex.client.data.PlatformRegistry.all.filter { it.route is Route.Blocked }) {
            val item = deriveSystemReadiness(
                p, emuInstalled = false, emuVersionName = null, emuVersionCode = 0,
                pinnedVersionCode = 0, cores = null, prep = null,
            )
            assertEquals(com.gamebex.client.ui.UserErrors.unsupportedSystem(p.display), item.detail)
        }
    }

    @Test fun sharedFoldersMergeIntoOneSystemRow() {
        val rows = systemPlatforms()
        assertEquals(rows.size, rows.map { it.folder }.distinct().size)
        assertEquals(com.gamebex.client.data.PlatformRegistry.folders.size, rows.size)
        assertEquals("MSX / MSX2", rows.single { it.folder == "msx" }.display)
        assertEquals("Neo Geo", rows.single { it.folder == "neogeo" }.display)
        assertEquals("microsoft-msx", rows.single { it.folder == "msx" }.slug)
        assertEquals("Game Boy", rows.single { it.folder == "gb" }.display)
    }

    @Test fun subtitleCountsOnlySystemsWithGames() {
        assertEquals("5 of 6 systems ready · Controller: none", systemsSubtitle(6, 5, "Controller: none"))
        assertEquals("1 of 1 system ready · C", systemsSubtitle(1, 1, "C"))
        assertEquals("No games yet · C", systemsSubtitle(0, 0, "C"))
        assertEquals("9 of 9 systems ready · 1 limited · C", systemsSubtitle(10, 9, "C", limitedWithGames = 1))
    }
}

/**
 * The item derivation shared by the System screen and the header warning
 * count: one function, so the two can never disagree.
 */
class SystemReadinessItemsTest {
    private val prep = mapOf("gb" to PrepSummary(1, 0, 0, 0, 0))

    @Test fun unprobedCoresReadAsUnknownNeverAsMissing() {
        // The System screen passes cores = null before the probe lands (and
        // DeviceStatusImpl does when the probe fails). A failed probe must
        // not flag every core as missing and inflate the ⚠ count.
        val items = systemReadinessItems(
            cores = null, prepMap = prep, missingSystemFiles = emptySet(),
            probe = { EmuSnap(true, "1.22", 99) },
        )
        val gb = items.first { it.folder == "gb" }
        assertEquals("Core check unavailable — verify in RetroArch.", gb.detail)
        assertEquals(SystemAction.OpenCoreUpdater, gb.primaryAction)
    }

    @Test fun probedCoresFlagOnlyTheMissingCore() {
        val items = systemReadinessItems(
            cores = setOf("mgba"), prepMap = prep, missingSystemFiles = emptySet(),
            probe = { EmuSnap(true, "1.22", 99) },
        )
        val gb = items.first { it.folder == "gb" }
        assertEquals("Install the Gambatte core (Online Updater).", gb.detail)
    }

    @Test fun probeFeedsRuntimeStatePerPackage() {
        val items = systemReadinessItems(
            cores = setOf("gambatte"), prepMap = emptyMap(), missingSystemFiles = emptySet(),
            probe = { pkg -> EmuSnap(installed = pkg == Emu.RETROARCH.pkg, versionName = "1.22", versionCode = 99) },
        )
        // RetroArch routes resolve through the RetroArch probe…
        val gb = items.first { it.folder == "gb" }
        assertEquals("RetroArch · Gambatte", gb.runtimeLabel)
        // …and a package the probe can't see reads as not installed.
        assertTrue(items.any { it.state == SystemReadinessState.MissingRuntime })
    }
}
