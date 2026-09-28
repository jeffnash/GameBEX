package com.gamebex.client.launch

import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** RetroArch state discovery for the Home save index. */
class SaveIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun states(root: File, core: String, vararg names: String) {
        val dir = File(root, core).apply { mkdirs() }
        names.forEach { File(dir, it).writeText("s") }
    }

    private fun game(
        folder: String = "gc",
        fileName: String = "Crazy Taxi (Europe) (En,Fr,De,Es).rvz",
        launchRelPath: String? = null,
        emulatorOverride: String? = null,
        coreOverride: String? = null,
    ) = GameEntity(
        title = "Crazy Taxi",
        folder = folder,
        volumeId = "primary",
        relPath = "gc/$fileName",
        fileName = fileName,
        sizeBytes = 1L,
        mtimeMs = 0L,
        ext = fileName.substringAfterLast('.', ""),
        launchRelPath = launchRelPath,
        emulatorOverride = emulatorOverride,
        coreOverride = coreOverride,
    )

    @Test fun baseNameStripsExtensionKeepingPunctuation() {
        assertEquals("Crazy Taxi (Europe) (En,Fr,De,Es)", RetroArchStates.baseName("Crazy Taxi (Europe) (En,Fr,De,Es).rvz"))
        assertEquals("R-Type III (Rev 1.2)", RetroArchStates.baseName("R-Type III (Rev 1.2).smc"))
        assertEquals("Tom & Jerry (U) [!]", RetroArchStates.baseName("Tom & Jerry (U) [!].zip"))
        assertEquals("noext", RetroArchStates.baseName("noext"))
    }

    @Test fun thumbnailsAndSlotTenAreNotStates() {
        // .state.auto.png is the thumbnail; .state10 is not a slot.
        assertNull(RetroArchStates.stateBaseOf("Crazy Taxi (Europe).state.auto.png"))
        assertNull(RetroArchStates.stateBaseOf("Crazy Taxi (Europe).state10"))
        assertNull(RetroArchStates.stateBaseOf("Crazy Taxi (Europe).stateful"))
        assertTrue(RetroArchStates.isState("Crazy Taxi (Europe).state.auto", "Crazy Taxi (Europe)"))
        assertTrue(RetroArchStates.isState("Crazy Taxi (Europe).state", "Crazy Taxi (Europe)"))
        assertTrue(RetroArchStates.isState("Crazy Taxi (Europe).state9", "Crazy Taxi (Europe)"))
    }

    @Test fun lookupFindsAutoStateWithThumbnail() {
        val root = tmp.newFolder("states")
        val name = "Crazy Taxi (Europe) (En,Fr,De,Es).state.auto"
        states(root, "dolphin-emu", name)
        File(root, "dolphin-emu/$name.png").writeText("png")
        File(root, "dolphin-emu/$name").setLastModified(5_000L)

        val info = RetroArchSaveIndex(root).lookup(game())!!

        assertEquals(1, info.count)
        assertEquals(5_000L, info.newestMs)
        assertEquals(File(root, "dolphin-emu/$name.png"), info.thumbnail)
    }

    @Test fun statesCountOnlyInTheGameOwnCoreFolder() {
        // Saves keyed by base name alone leaked across cores ("Tetris (World)"
        // on GB showed the NES Tetris saves).
        val root = tmp.newFolder("states")
        val base = "Super Mario 64 (Europe) (En,Fr,De)"
        states(root, "Mupen64Plus-Next", "$base.state.auto", "$base.state1")
        states(root, "other-core", "$base.state")
        File(root, "Mupen64Plus-Next/$base.state.auto").setLastModified(1_000L)
        File(root, "Mupen64Plus-Next/$base.state1").setLastModified(9_000L)
        File(root, "other-core/$base.state").setLastModified(5_000L)
        File(root, "Mupen64Plus-Next/$base.state1.png").writeText("png")

        val info = RetroArchSaveIndex(root).lookup(game(folder = "n64", fileName = "$base.z64"))!!

        assertEquals(2, info.count) // other-core's state never leaks in
        assertEquals(9_000L, info.newestMs)
        assertEquals(File(root, "Mupen64Plus-Next/$base.state1.png"), info.thumbnail)
    }

    @Test fun sameBaseNameOnAnotherConsoleShowsNoForeignSaves() {
        // GB "Tetris (World)" vs NES "Tetris (World)".
        val root = tmp.newFolder("states")
        val base = "Tetris (World)"
        states(root, "Gambatte", "$base.state.auto")
        states(root, "Mesen", "$base.state.auto", "$base.state1")

        val gb = RetroArchSaveIndex(root).lookup(game(folder = "gb", fileName = "$base.gb"))!!
        assertEquals(1, gb.count)

        val nes = RetroArchSaveIndex(root).lookup(game(folder = "nes", fileName = "$base.nes"))!!
        assertEquals(2, nes.count)
    }

    @Test fun coreOverrideRoutesToItsOwnCoreFolder() {
        val root = tmp.newFolder("states")
        val base = "Tetris (World)"
        states(root, "Gambatte", "$base.state.auto")
        states(root, "mGBA", "$base.state1")

        // The game boots through mGBA (core override): its saves live there.
        val info = RetroArchSaveIndex(root)
            .lookup(game(folder = "gb", fileName = "$base.gb", coreOverride = "mgba"))!!
        assertEquals(1, info.count)
    }

    @Test fun unknownCoreFallsBackToAnyFolder() {
        val root = tmp.newFolder("states")
        val base = "Tetris (World)"
        states(root, "Some Custom Core", "$base.state.auto")
        states(root, "Elsewhere", "$base.state1")

        val info = RetroArchSaveIndex(root)
            .lookup(game(folder = "gb", fileName = "$base.gb", coreOverride = "my_custom_core"))!!
        assertEquals(2, info.count)
    }

    @Test fun coreFolderNamesMatchRetroArch() {
        assertEquals("dolphin-emu", RetroArchStates.coreFolderOf("dolphin"))
        assertEquals("Mupen64Plus-Next", RetroArchStates.coreFolderOf("mupen64plus_next_gles3"))
        assertEquals("Snes9x", RetroArchStates.coreFolderOf("snes9x"))
        assertEquals("Gambatte", RetroArchStates.coreFolderOf("gambatte"))
        assertEquals("melonDS", RetroArchStates.coreFolderOf("melonds"))
        assertEquals("Flycast", RetroArchStates.coreFolderOf("flycast"))
        assertEquals("PPSSPP", RetroArchStates.coreFolderOf("ppsspp"))
        assertEquals("FinalBurn Neo", RetroArchStates.coreFolderOf("fbneo"))
        // States folders are the cores' RetroArch library_name values, not
        // the display names.
        assertEquals("DOSBox-pure", RetroArchStates.coreFolderOf("dosbox_pure"))
        assertEquals("Neko Project II kai", RetroArchStates.coreFolderOf("np2kai"))
        assertEquals("PX68K", RetroArchStates.coreFolderOf("px68k"))
        assertEquals("SAME_CDI", RetroArchStates.coreFolderOf("same_cdi"))
        assertEquals("x1", RetroArchStates.coreFolderOf("x1"))
        assertNull(RetroArchStates.coreFolderOf("my_custom_core"))
    }

    @Test fun dosBoxPureSavesAreFoundUnderTheRetroArchFolderName() {
        // RetroArch writes DOSBox Pure states under "DOSBox-pure" (its
        // library_name), never under the display name "DOSBox Pure". The
        // empty display-name folder pins the name: a wrong mapping would
        // resolve strictly into it and find nothing (the fallback cannot
        // rescue it), and Mesen's same-base saves must never leak in.
        val root = tmp.newFolder("states")
        val base = "Doom (USA)"
        states(root, "DOSBox-pure", "$base.state.auto")
        File(root, "DOSBox Pure").mkdirs()
        states(root, "Mesen", "$base.state1")

        val info = RetroArchSaveIndex(root)
            .lookup(game(folder = "msdos", fileName = "$base.zip"))!!

        assertEquals(1, info.count)
    }

    @Test fun knownCoreWithMissingFolderFallsBackToUnclaimedFolders() {
        // A known core whose states folder is not found on this device
        // (folder-name drift, first save under a renamed core) must fall
        // back to searching unclaimed folders — not report zero saves.
        val root = tmp.newFolder("states")
        val base = "Tetris (World)"
        states(root, "Gambatte-elsewhere", "$base.state.auto", "$base.state1")

        val info = RetroArchSaveIndex(root)
            .lookup(game(folder = "gb", fileName = "$base.gb"))!!

        assertEquals(2, info.count)
    }

    @Test fun presentCoreFolderStillExcludesForeignSameBaseStates() {
        // The fallback applies only when the game's own core folder is absent.
        // When it exists, same-base states in other cores' folders never leak in.
        val root = tmp.newFolder("states")
        val base = "Tetris (World)"
        states(root, "Gambatte", "Other Game (World).state.auto")
        states(root, "Mesen", "$base.state.auto", "$base.state1")

        assertNull(RetroArchSaveIndex(root).lookup(game(folder = "gb", fileName = "$base.gb")))
    }

    @Test fun emptyOwnCoreFolderStillExcludesForeignSameBaseStates() {
        // A core folder that exists but lists empty is still the game's own
        // folder — no fallback, no foreign adoption.
        val root = tmp.newFolder("states")
        val base = "Tetris (World)"
        File(root, "Gambatte").mkdirs()
        states(root, "Mesen", "$base.state.auto", "$base.state1")

        assertNull(RetroArchSaveIndex(root).lookup(game(folder = "gb", fileName = "$base.gb")))
    }

    @Test fun foreignKnownCoreFolderIsNeverAdoptedWhenOwnFolderIsMissing() {
        // A missing own folder must never read another KNOWN core's states —
        // GB "Tetris (World)" never adopts Mesen's saves.
        val root = tmp.newFolder("states")
        val base = "Tetris (World)"
        states(root, "Mesen", "$base.state.auto", "$base.state1")

        assertNull(RetroArchSaveIndex(root).lookup(game(folder = "gb", fileName = "$base.gb")))
    }

    @Test fun lookupIgnoresThumbnailAndOverslotFiles() {
        val root = tmp.newFolder("states")
        val base = "Tom & Jerry (U) [!]"
        states(root, "Gambatte", "$base.state.auto", "$base.state.auto.png", "$base.state10")

        val info = RetroArchSaveIndex(root).lookup(game(folder = "gb", fileName = "$base.gb"))!!

        // Only the .state.auto counts as a state; the .png is its
        // thumbnail (found), and .state10 is not a slot (not counted).
        assertEquals(1, info.count)
        assertEquals(File(root, "Gambatte/$base.state.auto.png"), info.thumbnail)
    }

    @Test fun lookupWithoutStatesIsNull() {
        val root = tmp.newFolder("states")
        states(root, "dolphin-emu", "Other Game (USA).state.auto")
        assertNull(RetroArchSaveIndex(root).lookup(game()))
    }

    @Test fun lookupUsesLaunchRelPathBaseWhenSet() {
        val root = tmp.newFolder("states")
        val base = "R-Type III (Rev 1.2)"
        states(root, "Snes9x", "$base.state.auto")

        val info = RetroArchSaveIndex(root).lookup(
            game(folder = "snes", fileName = "R-Type III (Rev 1.2).zip", launchRelPath = "snes/disc/$base.sfc"),
        )!!

        assertEquals(1, info.count)
    }

    @Test fun standaloneEmulatorsGetNoSaveIndex() {
        val root = tmp.newFolder("states")
        states(root, "LRPS2", "Gran Turismo 4 (USA).state.auto")
        // ps2 routes to NetherSX2, not RetroArch.
        assertNull(RetroArchSaveIndex(root).lookup(game(folder = "ps2", fileName = "Gran Turismo 4 (USA).iso")))
        // A RetroCore platform with a standalone emulator override: same.
        states(root, "dolphin-emu", "Crazy Taxi (Europe).state.auto")
        assertNull(
            RetroArchSaveIndex(root).lookup(
                game(fileName = "Crazy Taxi (Europe).rvz", emulatorOverride = Emu.DOLPHIN.pkg),
            ),
        )
        // The same game without the override still resolves.
        assertEquals(1, RetroArchSaveIndex(root).lookup(game(fileName = "Crazy Taxi (Europe).rvz"))!!.count)
    }

    @Test fun cacheHoldsUntilInvalidate() {
        val root = tmp.newFolder("states")
        val index = RetroArchSaveIndex(root)
        states(root, "dolphin-emu", "Crazy Taxi (Europe).state.auto")
        assertEquals(1, index.lookup(game(fileName = "Crazy Taxi (Europe).rvz"))!!.count)

        states(root, "dolphin-emu", "Crazy Taxi (Europe).state1", "Crazy Taxi (Europe).state2")
        assertEquals(1, index.lookup(game(fileName = "Crazy Taxi (Europe).rvz"))!!.count)

        index.invalidate()
        assertEquals(3, index.lookup(game(fileName = "Crazy Taxi (Europe).rvz"))!!.count)
    }

    @Test fun stateMatchingIsExactNotPrefix() {
        val root = tmp.newFolder("states")
        val base = "Sonic & Knuckles"
        // A longer base that shares the prefix must not leak in.
        states(root, "Genesis Plus GX", "$base & Sonic the Hedgehog 3 (USA).state.auto", "$base.stateful")

        assertNull(RetroArchSaveIndex(root).lookup(game(folder = "genesis", fileName = "$base.md")))
        states(root, "Genesis Plus GX", "$base.state")
        assertEquals(1, RetroArchSaveIndex(root).lookup(game(folder = "genesis", fileName = "$base.md"))!!.count)
    }

    @Test fun nonMatchingNamesNeverIndex() {
        assertFalse(RetroArchStates.isState("X.state.auto.png", "X"))
        assertFalse(RetroArchStates.isState("XY.state", "X"))
    }
}
