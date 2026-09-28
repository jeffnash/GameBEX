package com.gamebex.client.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prep logic: cue parse, M3U pick/gen, shortnames, zip plans. */
class PrepLogicTest {
    @Test fun parseCueQuotedAndBare() {
        val cue = """
            FILE "Game (Track 1).bin" BINARY
              TRACK 01 MODE2/2352
            FILE Game_Track2.wav WAVE
              TRACK 02 AUDIO
            REM comment
            garbage line
        """.trimIndent()
        val tracks = PrepLogic.parseCue(cue)
        assertEquals(2, tracks.size)
        assertEquals("Game (Track 1).bin", tracks[0].file)
        assertEquals("BINARY", tracks[0].type)
        assertEquals("Game_Track2.wav", tracks[1].file)
        assertEquals("WAVE", tracks[1].type)
    }

    @Test fun parseCueSkipsUnterminatedQuote() {
        assertTrue(PrepLogic.parseCue("FILE \"oops.bin BINARY").isEmpty())
    }

    @Test fun m3uRoundTrip() {
        val body = PrepLogic.genM3u(listOf("disc1.cue", "disc2.cue"))
        assertEquals("disc1.cue\ndisc2.cue\n", body)
    }

    @Test fun shortnames() {
        assertTrue(PrepLogic.isArcadeShortname("galaga.zip"))
        assertTrue(PrepLogic.isArcadeShortname("dkong.zip"))
        assertTrue(PrepLogic.isArcadeShortname("neogeo.zip"))
        assertFalse(PrepLogic.isArcadeShortname("Galaga (World).zip"))
        assertFalse(PrepLogic.isArcadeShortname("galaga 88.zip"))
        assertFalse(PrepLogic.isArcadeShortname("Street Fighter II.zip"))
    }

    @Test fun zipPlanSingleCue() {
        val p = PrepLogic.planZip(listOf("game.cue", "game.bin"))
        assertTrue(p.isDiscSet)
        assertEquals(listOf("game.cue"), p.sheets)
        assertEquals("game.cue", PrepLogic.pickBootSheet(p.sheets))
    }

    @Test fun zipPlanMultiDiscNeedsM3u() {
        val p = PrepLogic.planZip(listOf("d1.cue", "d1.bin", "d2.cue", "d2.bin"))
        assertTrue(p.isDiscSet)
        assertNull(PrepLogic.pickBootSheet(p.sheets))
    }

    @Test fun zipPlanExistingM3uWins() {
        val p = PrepLogic.planZip(listOf("b.cue", "a.m3u", "a.bin"))
        assertEquals("a.m3u", PrepLogic.pickBootSheet(p.sheets))
    }

    @Test fun zipPlanSingleIso() {
        val p = PrepLogic.planZip(listOf("game.iso"))
        assertTrue(p.isDiscSet)
        assertEquals(listOf("game.iso"), p.loose)
    }

    @Test fun zipPlanNotADisc() {
        val p = PrepLogic.planZip(listOf("readme.txt", "cover.png"))
        assertFalse(p.isDiscSet)
    }

    @Test fun discIndexOrdering() {
        val names = listOf("Game (Disc 2).cue", "Game (Disc 10).cue", "Game (Disc 1).cue")
        assertEquals(
            listOf("Game (Disc 1).cue", "Game (Disc 2).cue", "Game (Disc 10).cue"),
            names.sortedBy { PrepLogic.discIndex(it) },
        )
    }

    @Test fun discIndexPrefersDiscMarkerOverCdToken() {
        assertEquals(1, PrepLogic.discIndex("Amiga CD32 Collection (Disc 1).cue"))
        assertEquals(2, PrepLogic.discIndex("Game (CD2).cue"))
        assertEquals(99, PrepLogic.discIndex("Game.cue"))
    }

    @Test fun bootTargetStrayImageDoesNotHideMultiDiscSet() {
        val sheets = listOf("Game (Disc 1).cue", "Game (Disc 2).cue")
        assertNull(PrepLogic.pickBootTarget(sheets, listOf("bonus.iso"), emptyList()))
    }

    @Test fun bootTargetLoneImageWithoutSheets() {
        assertEquals("game.iso", PrepLogic.pickBootTarget(emptyList(), listOf("game.iso"), emptyList()))
    }

    @Test fun bootTargetLoneCartOnlyWhenNoDisc() {
        assertEquals("game.gba", PrepLogic.pickBootTarget(emptyList(), emptyList(), listOf("game.gba")))
        assertEquals("game.cue", PrepLogic.pickBootTarget(listOf("game.cue"), emptyList(), listOf("x.gba")))
    }

    @Test fun zipPlanPspCsoNeedsNoSheet() {
        val p = PrepLogic.planZip(listOf("game.cso"), PrepLogic.directBootFor("psp"))
        assertTrue(p.isDiscSet)
        assertEquals(listOf("game.cso"), p.loose)
    }

    @Test fun zipPlanGcRvzNeedsNoSheet() {
        val p = PrepLogic.planZip(listOf("game.rvz"), PrepLogic.directBootFor("gc"))
        assertTrue(p.isDiscSet)
        assertEquals(listOf("game.rvz"), p.loose)
    }

    @Test fun zipPlanDreamcastCdiNeedsNoSheet() {
        val p = PrepLogic.planZip(listOf("game.cdi"), PrepLogic.directBootFor("dreamcast"))
        assertTrue(p.isDiscSet)
    }

    @Test fun zipPlanCsoStillManualOutsidePsp() {
        val p = PrepLogic.planZip(listOf("game.cso"), PrepLogic.directBootFor("psx"))
        assertFalse(p.isDiscSet)
    }

    @Test fun zipPlanMultiLooseIsDiscSet() {
        val p = PrepLogic.planZip(listOf("d1.rvz", "d2.rvz"), PrepLogic.directBootFor("gc"))
        assertTrue(p.isDiscSet)
        assertEquals(2, p.loose.size)
    }

    @Test fun zipPlanKeepsSubdirPaths() {
        val p = PrepLogic.planZip(listOf("disc1/game.cue", "disc1/game.bin"))
        assertTrue(p.isDiscSet)
        assertEquals(listOf("disc1/game.cue"), p.sheets)
        assertEquals("disc1/game.cue", PrepLogic.pickBootSheet(p.sheets))
    }

    @Test fun jaguarCartridgesBootAsIs() {
        val carts = PrepLogic.cartReadyFor("atarijaguar")
        assertTrue(carts.containsAll(listOf("j64", "jag", "rom", "abs", "cof", "prg")))
        assertFalse("cue" in carts || "bin" in carts || "zip" in carts || "cdi" in carts)
    }

    @Test fun psxExeBootsAsIs() {
        // Homebrew boots direct (no sheet), loose and inside zips alike.
        assertTrue("exe" in PrepLogic.directBootFor("psx"))
        assertFalse("bin" in PrepLogic.cartReadyFor("psx"))
    }

    @Test fun manualNotesAreFormatSpecific() {
        assertTrue(PrepLogic.manualNoteFor("bin").contains(".cue"))
        assertTrue(PrepLogic.manualNoteFor("cdi").contains("isn't bootable"))
        assertTrue(PrepLogic.manualNoteFor("xyz").contains(".xyz"))
    }

    @Test fun prepMarkerFreshness() {
        val finger = PrepLogic.prepFinger(136310039L, 1700000000000L)
        assertEquals("136310039:1700000000000", finger)
        assertTrue(PrepLogic.prepMarkerFresh(finger, finger))
        assertTrue(PrepLogic.prepMarkerFresh("$finger\n", finger))
        assertTrue(!PrepLogic.prepMarkerFresh(null, finger))
        assertTrue(!PrepLogic.prepMarkerFresh("1:2", finger))
        assertTrue(!PrepLogic.prepMarkerFresh("136310039:1700000000001", finger))
    }

    @Test fun prepMarkerFreshnessIgnoresManifestLines() {
        val finger = PrepLogic.prepFinger(136310039L, 1700000000000L)
        assertTrue(PrepLogic.prepMarkerFresh("$finger\ngame.iso\nsub/track.bin", finger))
        assertTrue(!PrepLogic.prepMarkerFresh("1:2\ngame.iso", finger))
    }

    @Test fun parseCueTracksPairsModes() {
        val cue = """
            FILE "Game (Track 1).bin" BINARY
              TRACK 01 MODE1/2352
                INDEX 01 00:00:00
            FILE "Game (Track 2).bin" BINARY
              TRACK 02 AUDIO
            FILE "Game (Track 3).bin" BINARY
              TRACK 03 MODE1/2352
        """.trimIndent()
        assertEquals(
            listOf(
                PrepLogic.CueDataTrack("Game (Track 1).bin", "MODE1/2352"),
                PrepLogic.CueDataTrack("Game (Track 2).bin", "AUDIO"),
                PrepLogic.CueDataTrack("Game (Track 3).bin", "MODE1/2352"),
            ),
            PrepLogic.parseCueTracks(cue),
        )
    }

    @Test fun parseCueTracksSkipsModelessFiles() {
        val cue = "FILE \"lonely.bin\" BINARY\nFILE \"t.bin\" BINARY\n  TRACK 01 MODE1/2048\n"
        assertEquals(
            listOf(PrepLogic.CueDataTrack("t.bin", "MODE1/2048")),
            PrepLogic.parseCueTracks(cue),
        )
        assertTrue(PrepLogic.parseCueTracks("").isEmpty())
    }

    @Test fun sectorSizeForMode() {
        assertEquals(2352, PrepLogic.sectorSizeForMode("MODE1/2352"))
        assertEquals(2048, PrepLogic.sectorSizeForMode("MODE1/2048"))
        assertEquals(2352, PrepLogic.sectorSizeForMode("AUDIO"))
        assertEquals(2352, PrepLogic.sectorSizeForMode("MODE2/2352"))
        assertEquals(2352, PrepLogic.sectorSizeForMode("weird"))
    }

    @Test fun gdiTypeForMode() {
        assertEquals(0, PrepLogic.gdiTypeForMode("AUDIO"))
        assertEquals(4, PrepLogic.gdiTypeForMode("MODE1/2352"))
        assertEquals(4, PrepLogic.gdiTypeForMode("MODE1/2048"))
    }

    @Test fun genGdiQuotesSpacedNames() {
        val text = PrepLogic.genGdi(
            listOf(
                PrepLogic.GdiTrack("t 1.bin", 0, 4, 2352),
                PrepLogic.GdiTrack("t2.bin", 14564, 0, 2352),
                PrepLogic.GdiTrack("t 3.bin", 45000, 4, 2352),
            ),
        )
        assertEquals(
            "3\n" +
                "1 0 4 2352 \"t 1.bin\" 0\n" +
                "2 14564 0 2352 t2.bin 0\n" +
                "3 45000 4 2352 \"t 3.bin\" 0\n",
            text,
        )
    }

    @Test fun rootExtentFromPvd() {
        val pvd = ByteArray(2048)
        pvd[0] = 1
        "CD001".forEachIndexed { i, c -> pvd[1 + i] = c.code.toByte() }
        pvd[6] = 1
        val extent = 45020
        pvd[158] = (extent and 0xFF).toByte()
        pvd[159] = ((extent shr 8) and 0xFF).toByte()
        assertEquals(45020, PrepLogic.rootExtentFromPvd(pvd))
        assertNull(PrepLogic.rootExtentFromPvd(ByteArray(100)))
        pvd[1] = 'X'.code.toByte()
        assertNull(PrepLogic.rootExtentFromPvd(pvd))
    }

    @Test fun needs45000GdiBounds() {
        assertFalse(PrepLogic.needs45000Gdi(44999))
        assertTrue(PrepLogic.needs45000Gdi(45000))
        assertTrue(PrepLogic.needs45000Gdi(45149))
        assertFalse(PrepLogic.needs45000Gdi(45150))
        assertFalse(PrepLogic.needs45000Gdi(545703))
    }

    @Test fun unbootableNotes() {
        assertTrue(PrepLogic.unbootableNoteFor("psx", "ecm")!!.contains("unecm"))
        assertTrue(PrepLogic.unbootableNoteFor("psx", "mdf")!!.contains("BIN/CUE"))
        assertTrue(PrepLogic.unbootableNoteFor("psx", "psf")!!.contains("music"))
        assertTrue(PrepLogic.unbootableNoteFor("atarijaguar", "cue")!!.contains("cartridge"))
        assertTrue(PrepLogic.unbootableNoteFor("dreamcast", "dat")!!.contains("cue/gdi"))
        assertTrue(PrepLogic.unbootableNoteFor("psp", "prx")!!.contains("plugin"))
        assertTrue(PrepLogic.unbootableNoteFor("gc", "json")!!.contains("JSON"))
        assertTrue(PrepLogic.unbootableNoteFor("amiga", "rp9")!!.contains("ADF"))
        assertTrue(PrepLogic.unbootableNoteFor("msx", "wav")!!.contains("blueMSX"))
        assertNull(PrepLogic.unbootableNoteFor("psx", "cue"))
        assertNull(PrepLogic.unbootableNoteFor("dreamcast", "cue"))
        assertNull(PrepLogic.unbootableNoteFor("gc", "iso"))
        assertTrue(PrepLogic.unbootableNoteFor("psx", "ECM")!!.contains("unecm"))
    }

    @Test fun nkitNames() {
        assertTrue(PrepLogic.isNkitName("Game.nkit.iso"))
        assertTrue(PrepLogic.isNkitName("GAME.NKIT.GCZ"))
        assertFalse(PrepLogic.isNkitName("Game.iso"))
        assertFalse(PrepLogic.isNkitName("nkit.iso"))
    }

    @Test fun unsupportedCueAudioFlagsCompressed() {
        val cue = """
            FILE "t1.bin" BINARY
              TRACK 01 MODE1/2352
            FILE "t2.wav" WAVE
              TRACK 02 AUDIO
            FILE "t3.mp3" MP3
              TRACK 03 AUDIO
            FILE "t4.flac" FLAC
              TRACK 04 AUDIO
        """.trimIndent()
        assertEquals(listOf("t3.mp3", "t4.flac"), PrepLogic.unsupportedCueAudio(cue))
        assertTrue(PrepLogic.unsupportedCueAudio("FILE \"a.bin\" BINARY\n  TRACK 01 MODE1/2352\n").isEmpty())
    }

    @Test fun preferSheetsDedupesSameDisc() {
        assertEquals(
            listOf("game.gdi"),
            PrepLogic.preferSheets(listOf("game.cue", "game.gdi"), "dreamcast"),
        )
        assertEquals(
            listOf("game.cue"),
            PrepLogic.preferSheets(listOf("game.cue", "game.ccd"), "psx"),
        )
        assertEquals(
            listOf("d1.cue", "d2.cue"),
            PrepLogic.preferSheets(listOf("d1.cue", "d2.ccd", "d2.cue"), "psx"),
        )
        // Different subdirs are different discs — both survive.
        assertEquals(
            listOf("a/game.cue", "b/game.cue"),
            PrepLogic.preferSheets(listOf("a/game.cue", "b/game.cue"), "psx"),
        )
        // Playlists pass through untouched.
        assertEquals(
            listOf("all.m3u", "game.cue"),
            PrepLogic.preferSheets(listOf("all.m3u", "game.cue", "game.ccd"), "psx"),
        )
    }

    @Test fun zipCartCandidates() {
        val entries = listOf("game.j64", "readme.txt", "cover.jpg", "game.ecm")
        assertEquals(listOf("game.j64"), PrepLogic.zipCartCandidates(entries, "atarijaguar"))
        assertEquals(
            listOf("game.elf"),
            PrepLogic.zipCartCandidates(listOf("game.elf", "meta.dat"), "dreamcast"),
        )
        // Sheets, images, direct-boot, and known-unbootable never count as carts.
        assertTrue(
            PrepLogic.zipCartCandidates(
                listOf("g.cue", "g.img", "g.ecm", "g.nrg", "homebrew.exe"),
                "psx",
            ).isEmpty(),
        )
    }

    @Test fun chdVersionParses() {
        fun header(version: Int): ByteArray {
            val h = "MComprHD".toByteArray() + ByteArray(4) + ByteArray(4)
            h[12] = ((version shr 24) and 0xFF).toByte()
            h[13] = ((version shr 16) and 0xFF).toByte()
            h[14] = ((version shr 8) and 0xFF).toByte()
            h[15] = (version and 0xFF).toByte()
            return h
        }
        assertEquals(5, PrepLogic.chdVersion(header(5)))
        assertEquals(4, PrepLogic.chdVersion(header(4)))
        assertNull(PrepLogic.chdVersion(ByteArray(16)))
        assertNull(PrepLogic.chdVersion(ByteArray(8)))
    }

    @Test fun directBootExtras() {
        assertTrue(PrepLogic.directBootFor("psx").containsAll(setOf("pbp", "exe", "psexe", "toc")))
        assertTrue(PrepLogic.directBootFor("saturn").contains("toc"))
        assertTrue(PrepLogic.directBootFor("tg-cd").contains("toc"))
        assertTrue(PrepLogic.directBootFor("dreamcast").contains("cdi"))
    }

}
