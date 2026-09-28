package com.gamebex.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** BIOS checker: every FileState plus anyOf/required semantics. */
class BiosCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(bytes: ByteArray = "x".toByteArray()): File =
        tmp.newFile().apply { writeBytes(bytes) }

    @Test fun missingWhenAbsent() {
        val want = BiosTable.BiosFile("nope.bin")
        val r = BiosCheck.checkFile(want, listOf(tmp.root))
        assertEquals(BiosCheck.FileState.MISSING, r.state)
    }

    @Test fun presentUnverifiedWithoutPins() {
        File(tmp.root, "firmware.bin").writeBytes("junk".toByteArray())
        val r = BiosCheck.checkFile(BiosTable.BiosFile("firmware.bin"), listOf(tmp.root))
        assertEquals(BiosCheck.FileState.PRESENT_UNVERIFIED, r.state)
    }

    @Test fun sizeMismatchBeatsPresent() {
        File(tmp.root, "a.bin").writeBytes(ByteArray(10))
        val r = BiosCheck.checkFile(BiosTable.BiosFile("a.bin", sizeBytes = 11), listOf(tmp.root))
        assertEquals(BiosCheck.FileState.SIZE_MISMATCH, r.state)
    }

    @Test fun md5OkAndMismatch() {
        val f = file("hello".toByteArray())
        val good = BiosTable.BiosFile(f.name, md5 = "5d41402abc4b2a76b9719d911017c592")
        assertEquals(BiosCheck.FileState.OK, BiosCheck.checkFile(good, listOf(tmp.root)).state)
        val bad = BiosTable.BiosFile(f.name, md5 = "0".repeat(32))
        assertEquals(BiosCheck.FileState.MD5_MISMATCH, BiosCheck.checkFile(bad, listOf(tmp.root)).state)
    }

    @Test fun caseInsensitiveFallback() {
        File(tmp.root, "DC_BOOT.BIN").writeBytes(ByteArray(4))
        val r = BiosCheck.checkFile(BiosTable.BiosFile("dc_boot.bin"), listOf(tmp.root))
        assertEquals(BiosCheck.FileState.PRESENT_UNVERIFIED, r.state)
    }

    @Test fun normalizedFallbackIgnoresPunctuation() {
        File(tmp.root, "SCPH-39001.bin").writeBytes(ByteArray(4))
        val r = BiosCheck.checkFile(BiosTable.BiosFile("scph39001.bin"), listOf(tmp.root))
        assertEquals(BiosCheck.FileState.PRESENT_UNVERIFIED, r.state)
    }

    @Test fun nestedSubdirSatisfies() {
        val deep = File(tmp.root, "system/pcsx2/bios").apply { mkdirs() }
        File(deep, "scph39001.bin").writeBytes(ByteArray(4))
        val r = BiosCheck.checkFile(
            BiosTable.BiosFile("scph39001.bin"),
            listOf(File(tmp.root, "system")),
        )
        assertEquals(BiosCheck.FileState.PRESENT_UNVERIFIED, r.state)
    }

    @Test fun anyOfNeedsOne() {
        File(tmp.root, "b.bin").writeBytes(ByteArray(2))
        val need = BiosTable.BiosNeed(
            files = listOf(BiosTable.BiosFile("a.bin"), BiosTable.BiosFile("b.bin")),
            anyOf = true,
        )
        assertTrue(BiosCheck.checkNeed(need, listOf(tmp.root)).satisfied)
        val needAll = need.copy(anyOf = false)
        assertFalse(BiosCheck.checkNeed(needAll, listOf(tmp.root)).satisfied)
    }

    @Test fun optionalNeedsDontBlockReady() {
        val report = BiosCheck.checkFolder("gba", listOf(tmp.root))
        assertTrue(report.ready) // gba BIOS is optional
        val psx = BiosCheck.checkFolder("psx", listOf(tmp.root))
        assertFalse(psx.ready)
        assertEquals(3, psx.missingRequired.size)
    }

    @Test fun unknownFolderIsReady() {
        assertTrue(BiosCheck.checkFolder("snes", listOf(tmp.root)).ready)
    }

    @Test fun gbaPinSelfConsistent() {
        // The pinned gba md5 must be a valid md5 shape (typo guard).
        val f = BiosTable.TABLE.getValue("gba").single().files.single()
        assertEquals(32, f.md5!!.length)
        assertTrue(f.md5.matches(Regex("[0-9a-f]+")))
    }

    @Test fun corruptFirstDoesNotShadowValidSecond() {
        val d1 = tmp.newFolder("sys1")
        val d2 = tmp.newFolder("sys2")
        File(d1, "f.bin").writeBytes("junk".toByteArray())
        val good = "good-bytes".toByteArray()
        File(d2, "f.bin").writeBytes(good)
        val md5 = BiosCheck.md5(File(d2, "f.bin"))
        val want = BiosTable.BiosFile("f.bin", md5 = md5)
        val r = BiosCheck.checkFile(want, listOf(d1, d2))
        assertEquals(BiosCheck.FileState.OK, r.state)
        assertEquals(File(d2, "f.bin").absolutePath, r.found?.absolutePath)
    }

    @Test fun unreadablePinnedIsNotSatisfying() {
        val f = File(tmp.root, "locked.bin").apply { writeBytes("x".toByteArray()) }
        org.junit.Assume.assumeTrue("chmod refused", f.setReadable(false))
        try {
            val want = BiosTable.BiosFile("locked.bin", md5 = "d41d8cd98f00b204e9800998ecf8427e")
            val r = BiosCheck.checkFile(want, listOf(tmp.root))
            assertEquals(BiosCheck.FileState.UNREADABLE, r.state)
        } finally {
            f.setReadable(true)
        }
    }

    @Test fun unpinnedPrefersSizePlausibleCopy() {
        val d1 = tmp.newFolder("a")
        val d2 = tmp.newFolder("b")
        File(d1, "f.bin").writeBytes(ByteArray(4))
        File(d2, "f.bin").writeBytes(ByteArray(8))
        val want = BiosTable.BiosFile("f.bin", sizeBytes = 8)
        val r = BiosCheck.checkFile(want, listOf(d1, d2))
        assertEquals(BiosCheck.FileState.PRESENT_UNVERIFIED, r.state)
        assertEquals(File(d2, "f.bin").absolutePath, r.found?.absolutePath)
    }

    @Test fun privateOnlyReportedSeparately() {
        val sys = tmp.newFolder("sys")
        val priv = tmp.newFolder("priv")
        File(priv, "scph5501.bin").writeBytes(ByteArray(8))
        val report = BiosCheck.checkFolder("psx", listOf(sys), listOf(priv))
        assertFalse(report.ready)
        assertEquals(listOf("scph5501.bin"), report.privateOnly.map { it.name })
    }
}
