package com.gamebex.client.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Sheet validation: missingRefs across cue/gdi/m3u (pure file logic). */
class PrepPipelineTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun cueRefsValid() {
        File(tmp.root, "t.bin").writeBytes(ByteArray(8))
        val cue = File(tmp.root, "t.cue").apply {
            writeText("FILE \"t.bin\" BINARY\n  TRACK 01 MODE2/2352\n")
        }
        assertTrue(PrepPipeline.missingRefs(cue).isEmpty())
    }

    @Test fun cueRefsMissingNamed() {
        val cue = File(tmp.root, "t.cue").apply {
            writeText("FILE \"ghost.bin\" BINARY\n  TRACK 01 MODE2/2352\n")
        }
        assertEquals(listOf("ghost.bin"), PrepPipeline.missingRefs(cue))
    }

    @Test fun m3uSkipsComments() {
        File(tmp.root, "d1.cue").writeText("x")
        val m3u = File(tmp.root, "g.m3u").apply { writeText("# multi\n\nd1.cue\nd2.cue\n") }
        assertEquals(listOf("d2.cue"), PrepPipeline.missingRefs(m3u))
    }

    @Test fun gdiTrackFiles() {
        File(tmp.root, "track03.bin").writeBytes(ByteArray(4))
        val gdi = File(tmp.root, "g.gdi").apply {
            writeText("3\n1 0 4 2048 track01.bin 0\n2 150 0 2352 track02.raw 0\n3 1234 4 2048 track03.bin 0\n")
        }
        assertEquals(listOf("track01.bin", "track02.raw"), PrepPipeline.missingRefs(gdi))
    }

    @Test fun cueRefsResolveNextToSubdirSheet() {
        val sub = File(tmp.root, "disc1").apply { mkdirs() }
        File(sub, "t.bin").writeBytes(ByteArray(8))
        val cue = File(sub, "t.cue").apply {
            writeText("FILE \"t.bin\" BINARY\n  TRACK 01 MODE2/2352\n")
        }
        assertTrue(PrepPipeline.missingRefs(cue).isEmpty())
    }

    @Test fun emptyCueHasNoRefs() {
        val cue = File(tmp.root, "e.cue").apply { writeText("") }
        assertEquals(emptyList<String>(), PrepPipeline.parseSheetRefs(cue))
    }

    @Test fun m3uOverBrokenCueFails() {
        val cue = File(tmp.root, "b.cue").apply {
            writeText("FILE \"ghost.bin\" BINARY\n  TRACK 01 MODE2/2352\n")
        }
        File(tmp.root, "g.m3u").writeText("b.cue\n")
        assertEquals(listOf("ghost.bin"), PrepPipeline.missingDeepRefs(File(tmp.root, "g.m3u")))
        assertTrue(cue.isFile)
    }

    @Test fun quotedGdiParsesWhole() {
        File(tmp.root, "track 01.bin").writeBytes(ByteArray(4))
        val gdi = File(tmp.root, "g.gdi").apply {
            writeText("1\n1 0 4 2048 \"track 01.bin\" 0\n")
        }
        assertTrue(PrepPipeline.missingRefs(gdi).isEmpty())
    }

    @Test fun unsafeRefsRejected() {
        val cue = File(tmp.root, "t.cue").apply {
            writeText("FILE \"../evil.bin\" BINARY\n  TRACK 01 MODE2/2352\n")
        }
        assertEquals(listOf("../evil.bin"), PrepPipeline.missingRefs(cue))
    }

    @Test fun cyclicM3uTerminates() {
        File(tmp.root, "a.m3u").writeText("b.m3u\n")
        File(tmp.root, "b.m3u").writeText("a.m3u\n")
        assertTrue(PrepPipeline.missingDeepRefs(File(tmp.root, "a.m3u")).isEmpty())
    }

    @Test fun emuDirsCoreRouteSeesRaSystem() {
        val dirs = PrepPipeline.emuSystemDirs(true, "gambatte", "gb", File("/sdcard"))
        assertEquals(
            listOf(
                File("/sdcard/RetroArch/system"),
                File("/sdcard/Android/data/com.retroarch.aarch64/files/system"),
            ),
            dirs,
        )
    }

    @Test fun emuDirsFlycastAddsDc() {
        val dirs = PrepPipeline.emuSystemDirs(true, "flycast", "dreamcast", File("/sdcard"))
        assertEquals(4, dirs.size)
        assertTrue(dirs.contains(File("/sdcard/RetroArch/system/dc")))
    }

    @Test fun emuDirsStandaloneWithNeedsSeesStaging() {
        // Staged standalone firmware satisfies readiness (the checker
        // can't see the emu's own configured folders).
        val dirs = PrepPipeline.emuSystemDirs(false, null, "ps2", File("/sdcard"))
        assertEquals(listOf(File("/sdcard/GameBEX/bios/ps2")), dirs)
    }

    @Test fun emuDirsStandaloneWithoutNeedsSeesNothing() {
        assertTrue(PrepPipeline.emuSystemDirs(false, null, "snes", File("/sdcard")).isEmpty())
    }

    @Test fun manifestRoundTrip() {
        val stage = File(tmp.root, "stage").apply { mkdirs() }
        File(stage, "game.iso").writeBytes(ByteArray(4))
        val sub = File(stage, "sub").apply { mkdirs() }
        File(sub, "track.bin").writeBytes(ByteArray(4))
        val out = File(tmp.root, "out").apply { mkdirs() }
        val manifest = PrepPipeline.collectManifest(stage)
        assertEquals(listOf("game.iso", "sub/track.bin"), manifest)
        PrepPipeline.writeMarker(out, "1:2", manifest)
        assertEquals(manifest, PrepPipeline.parseMarkerManifest(File(out, ".gamebex_prep").readText()))
    }

    @Test fun fingerprintOnlyMarkerHasNoManifest() {
        assertTrue(PrepPipeline.parseMarkerManifest("136310039:1700000000000").isEmpty())
        assertTrue(PrepPipeline.parseMarkerManifest(null).isEmpty())
    }

    @Test fun manifestParseDropsUnsafeEntries() {
        val parsed = PrepPipeline.parseMarkerManifest("1:2\nsub/ok.bin\n../evil\n/abs\n\n..\\win")
        assertEquals(listOf("sub/ok.bin"), parsed)
    }

    @Test fun clearOwnedDeletesOnlyManifest() {
        val out = File(tmp.root, "out").apply { mkdirs() }
        File(out, "game.iso").writeBytes(ByteArray(4))
        val sub = File(out, "sub").apply { mkdirs() }
        File(sub, "track.bin").writeBytes(ByteArray(4))
        File(out, "foreign.txt").writeText("user data")
        val fsub = File(out, "mine").apply { mkdirs() }
        File(fsub, "keep.bin").writeBytes(ByteArray(4))
        PrepPipeline.clearOwned(out, listOf("game.iso", "sub/track.bin"))
        assertTrue(out.isDirectory)
        assertTrue(!File(out, "game.iso").exists())
        assertTrue(!File(out, "sub").exists())
        assertEquals("user data", File(out, "foreign.txt").readText())
        assertTrue(File(fsub, "keep.bin").isFile())
    }

    @Test fun deepM3uChainFailsExplicit() {
        // Six nested sheets: past the depth cap, must fail — never READY.
        for (i in 1..6) File(tmp.root, "d$i.m3u").writeText("d${i + 1}.m3u\n")
        File(tmp.root, "d7.m3u").writeText("ghost.bin\n")
        val missing = PrepPipeline.missingDeepRefs(File(tmp.root, "d1.m3u"))
        assertTrue(missing.isNotEmpty())
        assertTrue(missing.any { it.contains("too deep to validate") })
    }

    private fun dcCue(root: File, t3Sectors: Int, rootExtent: Int, magic: Boolean = true): File {
        File(root, "t1.bin").writeBytes(ByteArray(2352 * 4))
        File(root, "t2.bin").writeBytes(ByteArray(2352 * 2))
        val t3 = ByteArray(2352 * t3Sectors)
        val pvdOff = 16 * 2352 + 16
        if (pvdOff + 2048 > t3.size) {
            File(root, "t3.bin").writeBytes(t3)
            return File(root, "g.cue").apply {
                writeText(
                    "FILE \"t1.bin\" BINARY\n  TRACK 01 MODE1/2352\n" +
                        "FILE \"t2.bin\" BINARY\n  TRACK 02 AUDIO\n" +
                        "FILE \"t3.bin\" BINARY\n  TRACK 03 MODE1/2352\n",
                )
            }
        }
        t3[pvdOff] = 1
        val sig = if (magic) "CD001" else "XX001"
        sig.forEachIndexed { i, c -> t3[pvdOff + 1 + i] = c.code.toByte() }
        t3[pvdOff + 6] = 1
        t3[pvdOff + 158] = (rootExtent and 0xFF).toByte()
        t3[pvdOff + 159] = ((rootExtent shr 8) and 0xFF).toByte()
        t3[pvdOff + 160] = ((rootExtent shr 16) and 0xFF).toByte()
        t3[pvdOff + 161] = ((rootExtent shr 24) and 0xFF).toByte()
        File(root, "t3.bin").writeBytes(t3)
        return File(root, "g.cue").apply {
            writeText(
                "FILE \"t1.bin\" BINARY\n  TRACK 01 MODE1/2352\n" +
                    "FILE \"t2.bin\" BINARY\n  TRACK 02 AUDIO\n" +
                    "FILE \"t3.bin\" BINARY\n  TRACK 03 MODE1/2352\n",
            )
        }
    }

    @Test fun cdrNumberedCueNeedsGdi() {
        val cue = dcCue(tmp.root, t3Sectors = 40, rootExtent = 45020)
        assertTrue(PrepPipeline.dreamcastCueNeedsGdi(cue))
    }

    @Test fun gdNumberedCueKeepsCue() {
        val cue = dcCue(tmp.root, t3Sectors = 40, rootExtent = 45154)
        assertTrue(!PrepPipeline.dreamcastCueNeedsGdi(cue))
    }

    @Test fun cueNeedsGdiFailsOpen() {
        // No ISO magic: unknown layout, boot the cue as before.
        assertTrue(!PrepPipeline.dreamcastCueNeedsGdi(dcCue(tmp.root, 40, 45020, magic = false)))
        // Truncated HD track: unreadable PVD, boot the cue as before.
        assertTrue(!PrepPipeline.dreamcastCueNeedsGdi(dcCue(tmp.root, 4, 45020)))
        // Missing track file: boot the cue as before.
        val cue = File(tmp.root, "m.cue").apply {
            writeText("FILE \"t1.bin\" BINARY\n  TRACK 01 MODE1/2352\nFILE \"ghost.bin\" BINARY\n  TRACK 02 MODE1/2352\n")
        }
        assertTrue(!PrepPipeline.dreamcastCueNeedsGdi(cue))
        assertTrue(!PrepPipeline.dreamcastCueNeedsGdi(File(tmp.root, "nope.cue")))
    }

    @Test fun write45000GdiDeclaresHdAt45000() {
        val cue = dcCue(tmp.root, t3Sectors = 40, rootExtent = 45020)
        val gdi = PrepPipeline.write45000Gdi(cue)
        assertTrue(gdi != null && gdi.isFile)
        assertEquals(
            "3\n1 0 4 2352 t1.bin 0\n2 4 0 2352 t2.bin 0\n3 45000 4 2352 t3.bin 0\n",
            gdi!!.readText(),
        )
        assertTrue(PrepPipeline.missingRefs(gdi).isEmpty())
        // Idempotent: same cue, same bytes.
        assertEquals(gdi.readText(), PrepPipeline.write45000Gdi(cue)!!.readText())
    }

    @Test fun write45000GdiRejectsRaggedLayout() {
        dcCue(tmp.root, t3Sectors = 40, rootExtent = 45020)
        File(tmp.root, "t1.bin").writeBytes(ByteArray(100))
        val cue = File(tmp.root, "g.cue")
        assertTrue(PrepPipeline.write45000Gdi(cue) == null)
    }

    @Test fun ccdRefsResolveImageAndSub() {
        File(tmp.root, "g.img").writeBytes(ByteArray(8))
        File(tmp.root, "g.sub").writeBytes(ByteArray(8))
        val ccd = File(tmp.root, "g.ccd").apply { writeText("[CloneCD]\n") }
        assertEquals(listOf("g.img", "g.sub"), PrepPipeline.parseSheetRefs(ccd))
        assertTrue(PrepPipeline.missingRefs(ccd).isEmpty())
    }

    @Test fun ccdRefsSubOptionalBinFallback() {
        File(tmp.root, "g.bin").writeBytes(ByteArray(8))
        val ccd = File(tmp.root, "g.ccd").apply { writeText("[CloneCD]\n") }
        assertEquals(listOf("g.bin"), PrepPipeline.parseSheetRefs(ccd))
        assertTrue(PrepPipeline.missingRefs(ccd).isEmpty())
    }

    @Test fun ccdRefsMissingImageReported() {
        val ccd = File(tmp.root, "g.ccd").apply { writeText("[CloneCD]\n") }
        assertEquals(listOf("g.img"), PrepPipeline.missingRefs(ccd))
    }

    @Test fun cueAudioProblemsFlagsCompressed() {
        val cue = File(tmp.root, "g.cue").apply {
            writeText(
                "FILE \"t1.bin\" BINARY\n  TRACK 01 MODE1/2352\n" +
                    "FILE \"t2.mp3\" MP3\n  TRACK 02 AUDIO\n",
            )
        }
        assertEquals(listOf("t2.mp3"), PrepPipeline.cueAudioProblems(cue))
        File(tmp.root, "ok.cue").writeText("FILE \"t1.bin\" BINARY\n  TRACK 01 MODE1/2352\n")
        assertTrue(PrepPipeline.cueAudioProblems(File(tmp.root, "ok.cue")).isEmpty())
    }

    @Test fun cueAudioProblemsRecurseM3u() {
        File(tmp.root, "g.cue").writeText("FILE \"t.mp3\" MP3\n  TRACK 01 AUDIO\n")
        val m3u = File(tmp.root, "all.m3u").apply { writeText("g.cue\n") }
        assertEquals(listOf("t.mp3"), PrepPipeline.cueAudioProblems(m3u))
        File(tmp.root, "ghost.m3u").writeText("nope.cue\n")
        assertTrue(PrepPipeline.cueAudioProblems(File(tmp.root, "ghost.m3u")).isEmpty())
    }

    @Test fun chdVersionAtReadsHeader() {
        fun chd(version: Int): File {
            val h = "MComprHD".toByteArray() + ByteArray(4) + ByteArray(4)
            h[12] = ((version shr 24) and 0xFF).toByte()
            h[13] = ((version shr 16) and 0xFF).toByte()
            h[14] = ((version shr 8) and 0xFF).toByte()
            h[15] = (version and 0xFF).toByte()
            return File(tmp.root, "v$version.chd").apply { writeBytes(h + ByteArray(64)) }
        }
        assertEquals(5, PrepPipeline.chdVersionAt(chd(5)))
        assertEquals(4, PrepPipeline.chdVersionAt(chd(4)))
        File(tmp.root, "junk.chd").writeBytes(ByteArray(64))
        assertTrue(PrepPipeline.chdVersionAt(File(tmp.root, "junk.chd")) == null)
        File(tmp.root, "tiny.chd").writeBytes(ByteArray(4))
        assertTrue(PrepPipeline.chdVersionAt(File(tmp.root, "tiny.chd")) == null)
    }

}
