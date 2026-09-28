package com.gamebex.client.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loose disc dumps fold into one library row per game (pure decision). */
class ScanGroupingTest {
    private fun listed(folder: String, names: List<String>, refs: Map<String, List<String>?>) =
        names - PrepLogic.scanSkips(folder, names, refs)

    @Test fun cueHidesItsTracks() {
        val names = listOf(
            "Game (USA).cue", "Game (USA) (Track 1).bin", "Game (USA) (Track 2).bin",
            "Other.chd",
        )
        val refs = mapOf("Game (USA).cue" to listOf("Game (USA) (Track 1).bin", "game (usa) (track 2).BIN"))
        assertEquals(listOf("Game (USA).cue", "Other.chd"), listed("psx", names, refs))
    }

    @Test fun gdiAndCcdHideTheirParts() {
        val gdi = listOf("Sonic.gdi", "track01.bin", "track02.raw", "track03.bin")
        assertEquals(
            listOf("Sonic.gdi"),
            listed("dreamcast", gdi, mapOf("Sonic.gdi" to listOf("track01.bin", "track02.raw", "track03.bin"))),
        )
        val ccd = listOf("Game.ccd", "Game.img", "Game.sub")
        assertEquals(listOf("Game.ccd"), listed("psx", ccd, mapOf("Game.ccd" to listOf("Game.img", "Game.sub"))))
    }

    @Test fun m3uHidesListedSheets() {
        val names = listOf(
            "FF7.m3u",
            "Final Fantasy VII (USA) (Disc 1).cue", "Final Fantasy VII (USA) (Disc 1).bin",
            "Final Fantasy VII (USA) (Disc 2).cue", "Final Fantasy VII (USA) (Disc 2).bin",
        )
        val refs = mapOf(
            "FF7.m3u" to listOf("Final Fantasy VII (USA) (Disc 1).cue", "./Final Fantasy VII (USA) (Disc 2).cue"),
            "Final Fantasy VII (USA) (Disc 1).cue" to listOf("Final Fantasy VII (USA) (Disc 1).bin"),
            "Final Fantasy VII (USA) (Disc 2).cue" to listOf("Final Fantasy VII (USA) (Disc 2).bin"),
        )
        assertEquals(listOf("FF7.m3u"), listed("psx", names, refs))
    }

    @Test fun samTitleM3uCoversUnlistedDiscs() {
        val names = listOf("Riven (USA).m3u", "Riven (USA) (Disc 1).chd", "Riven (USA) (Disc 2).chd")
        // Unreadable playlist: the same-title rule still folds the discs.
        assertEquals(listOf("Riven (USA).m3u"), listed("psx", names, mapOf("Riven (USA).m3u" to null)))
    }

    @Test fun discSiblingsWithoutPlaylistKeepDiscOne() {
        val names = listOf(
            "Metal Gear Solid (USA) (Disc 2).cue", "Metal Gear Solid (USA) (Disc 2).bin",
            "Metal Gear Solid (USA) (Disc 1).cue", "Metal Gear Solid (USA) (Disc 1).bin",
            "Crash Bandicoot (USA).cue", "Crash Bandicoot (USA).bin",
        )
        val refs = mapOf(
            "Metal Gear Solid (USA) (Disc 1).cue" to listOf("Metal Gear Solid (USA) (Disc 1).bin"),
            "Metal Gear Solid (USA) (Disc 2).cue" to listOf("Metal Gear Solid (USA) (Disc 2).bin"),
            "Crash Bandicoot (USA).cue" to listOf("Crash Bandicoot (USA).bin"),
        )
        assertEquals(
            listOf("Metal Gear Solid (USA) (Disc 1).cue", "Crash Bandicoot (USA).cue"),
            listed("psx", names, refs),
        )
    }

    @Test fun tosecDiskOfNGroups() {
        val names = listOf("Monkey Island (1990)(Lucasfilm)(Disk 1 of 4).adf", "Monkey Island (1990)(Lucasfilm)(Disk 2 of 4).adf")
        assertEquals(listOf(names[0]), listed("amiga", names, emptyMap()))
    }

    @Test fun samebaseSheetPairKeepsOne() {
        val names = listOf("Game.cue", "Game.ccd", "Game.bin")
        val refs = mapOf("Game.cue" to listOf("Game.bin"), "Game.ccd" to listOf("Game.bin"))
        assertEquals(listOf("Game.cue"), listed("psx", names, refs))
    }

    @Test fun untaggedAndUnrelatedFilesStay() {
        val names = listOf("Tetris (World).gb", "Dr. Mario (World).gb", "Discworld (USA).cue", "Discworld (USA).bin")
        val refs = mapOf("Discworld (USA).cue" to listOf("Discworld (USA).bin"))
        assertEquals(listOf("Tetris (World).gb", "Dr. Mario (World).gb", "Discworld (USA).cue"), listed("psx", names, refs))
        // Nested / unsafe refs never hide files in this listing.
        assertTrue(PrepLogic.scanSkips("psx", listOf("A.m3u", "a.cue"), mapOf("A.m3u" to listOf("sub/a.cue"))).isEmpty())
    }

    @Test fun discGroupKey() {
        assertEquals("final fantasy vii (usa)", PrepLogic.discGroupKey("Final Fantasy VII (USA) (Disc 1).cue"))
        assertNull(PrepLogic.discGroupKey("Discworld (USA).cue"))
    }

    @Test fun prepStepOnlyForDiscArcadeOrBiosSystems() {
        assertTrue(PrepLogic.hasPrepStep("psx"))
        assertTrue(PrepLogic.hasPrepStep("mame"))
        assertFalse(PrepLogic.hasPrepStep("snes"))
        assertFalse(PrepLogic.hasPrepStep("gb"))
    }
}
