package com.gamebex.client.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

/** CRC32 fingerprints: streaming hash, zip central-directory CRC, caps, cache lines. */
class RomHashTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
            }
        }
        return f
    }

    private fun crcOf(b: ByteArray): String = CRC32().apply { update(b) }.value.let(RomHash::formatCrc)

    @Test fun crcCheckValue() {
        val f = File(tmp.root, "check.bin").apply { writeText("123456789") }
        assertEquals("CBF43926", RomHash.crc32(f))
    }

    @Test fun streamedCrcMatchesWholeBufferAcrossChunks() {
        val bytes = ByteArray(200_000) { (it * 31 + 7).toByte() }
        val f = File(tmp.root, "big.sfc").apply { writeBytes(bytes) }
        assertEquals(crcOf(bytes), RomHash.crc32(f))
        assertEquals("00000000".length, RomHash.formatCrc(0x1L).length)
        assertEquals("00000001", RomHash.formatCrc(0x1L))
    }

    @Test fun capSkipsOversizedFiles() {
        val f = File(tmp.root, "disc.iso").apply { writeBytes(ByteArray(2048)) }
        assertNull(RomHash.crc32(f, maxBytes = 1024))
        assertNull(RomHash.fingerprint(f, maxBytes = 1024))
        assertNull(RomHash.crc32(File(tmp.root, "missing.bin")))
    }

    @Test fun singleEntryZipUsesInnerRom() {
        val rom = ByteArray(5000) { it.toByte() }
        val z = zip("Game (USA).zip", mapOf("Game (USA).sfc" to rom))
        val fp = RomHash.fingerprint(z)!!
        assertEquals(crcOf(rom), fp.crc)
        assertEquals(5000L, fp.size)
        assertEquals("Game (USA).sfc", fp.name)
    }

    @Test fun singleEntryZipReadsCentralDirectoryEvenPastCap() {
        // The inner CRC is free: the zip itself may exceed the hash cap.
        val rom = ByteArray(4096) { 3 }
        val z = zip("g.zip", mapOf("dir/g.gba" to rom, "__MACOSX/._g.gba" to ByteArray(4)))
        val fp = RomHash.fingerprint(z, maxBytes = 1)!!
        assertEquals(crcOf(rom), fp.crc)
        assertEquals("g.gba", fp.name)
    }

    @Test fun multiEntryZipHashesTheArchive() {
        val z = zip("galaga.zip", mapOf("a.rom" to ByteArray(10) { 1 }, "b.rom" to ByteArray(10) { 2 }))
        assertNull(RomHash.zipSingleEntry(z))
        val fp = RomHash.fingerprint(z)!!
        assertEquals(crcOf(z.readBytes()), fp.crc)
        assertEquals(z.length(), fp.size)
        assertEquals("galaga.zip", fp.name)
    }

    @Test fun notAZipFallsBackToFileCrc() {
        val f = File(tmp.root, "mislabeled.zip").apply { writeText("7z not zip") }
        val fp = RomHash.fingerprint(f)!!
        assertEquals(crcOf(f.readBytes()), fp.crc)
        assertEquals("mislabeled.zip", fp.name)
    }

    @Test(expected = CancellationException::class)
    fun cancellationPropagates() {
        val f = File(tmp.root, "c.bin").apply { writeBytes(ByteArray(10)) }
        RomHash.crc32(f) { throw CancellationException("stop") }
    }

    @Test fun cacheLineRoundTripsAndInvalidatesOnChange() {
        val fp = RomFingerprint("CBF43926", 9, "Game: Deluxe.sfc")
        val line = RomHash.encode(100, 200, fp)
        assertEquals(fp, RomHash.decode(line, 100, 200))
        assertNull(RomHash.decode(line, 101, 200))
        assertNull(RomHash.decode(line, 100, 201))
        assertNull(RomHash.decode("garbage", 100, 200))
        assertNull(RomHash.decode(null, 100, 200))
        assertNull(RomHash.decode("100:200:XYZ:9:a", 100, 200))
    }
}
