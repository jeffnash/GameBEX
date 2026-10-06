package com.gamebex.client.data.local

import java.io.File
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** ArchiveHub: magic dispatch, round-trips, caps, split and RAR handling. */
class ArchiveHubTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun b(vararg xs: Int): ByteArray = xs.map { it.toByte() }.toByteArray()

    @Test fun sniffZipHeaders() {
        assertEquals(ArchiveHub.Kind.ZIP, ArchiveHub.sniff(b(0x50, 0x4B, 0x03, 0x04)))
        assertEquals(ArchiveHub.Kind.ZIP, ArchiveHub.sniff(b(0x50, 0x4B, 0x05, 0x06)))
        assertEquals(ArchiveHub.Kind.ZIP, ArchiveHub.sniff(b(0x50, 0x4B, 0x07, 0x08)))
    }

    @Test fun sniffSevenZ() {
        assertEquals(
            ArchiveHub.Kind.SEVEN_Z,
            ArchiveHub.sniff(b(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)),
        )
    }

    @Test fun sniffRar() {
        assertEquals(
            ArchiveHub.Kind.RAR,
            ArchiveHub.sniff(b(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)),
        )
        assertEquals(
            ArchiveHub.Kind.RAR,
            ArchiveHub.sniff(b(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01)),
        )
        // Short prefix alone stays unknown.
        assertEquals(ArchiveHub.Kind.UNKNOWN, ArchiveHub.sniff(b(0x52, 0x61, 0x72, 0x21)))
    }

    @Test fun sniffTar() {
        val hdr = ByteArray(ArchiveHub.HEADER_LEN)
        "ustar".toByteArray().copyInto(hdr, 257)
        assertEquals(ArchiveHub.Kind.TAR, ArchiveHub.sniff(hdr))
        assertEquals(ArchiveHub.Kind.UNKNOWN, ArchiveHub.sniff(ByteArray(ArchiveHub.HEADER_LEN)))
    }

    @Test fun sniffCompressors() {
        assertEquals(ArchiveHub.Kind.GZIP, ArchiveHub.sniff(b(0x1F, 0x8B, 0x08, 0x00)))
        assertEquals(ArchiveHub.Kind.BZIP2, ArchiveHub.sniff(b(0x42, 0x5A, 0x68, 0x39)))
        assertEquals(
            ArchiveHub.Kind.XZ,
            ArchiveHub.sniff(b(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)),
        )
    }

    @Test fun sniffUnknown() {
        assertEquals(ArchiveHub.Kind.UNKNOWN, ArchiveHub.sniff(byteArrayOf()))
        assertEquals(ArchiveHub.Kind.UNKNOWN, ArchiveHub.sniff(b(0x50, 0x4B)))
        assertEquals(ArchiveHub.Kind.UNKNOWN, ArchiveHub.sniff("garbage-text".toByteArray()))
    }

    @Test fun splitVolumesDetected() {
        assertTrue(ArchiveHub.isSplitVolume("game.7z.001"))
        assertTrue(ArchiveHub.isSplitVolume("game.z01"))
        assertTrue(ArchiveHub.isSplitVolume("game.r00"))
        assertTrue(ArchiveHub.isSplitVolume("game.part2.rar"))
        assertTrue(!ArchiveHub.isSplitVolume("game.rar"))
        assertTrue(!ArchiveHub.isSplitVolume("game.zip"))
        assertTrue(!ArchiveHub.isSplitVolume("game.7z"))
        assertTrue(!ArchiveHub.isSplitVolume("001.iso"))
    }

    @Test fun kindForReadsFile() {
        val sevenZ = File(tmp.root, "disc.7z")
        writeSevenZ(sevenZ, mapOf("a.cue" to "x".toByteArray()))
        assertEquals(ArchiveHub.Kind.SEVEN_Z, ArchiveHub.kindFor(sevenZ))
        val txt = File(tmp.root, "n.txt").apply { writeText("plain") }
        assertEquals(ArchiveHub.Kind.UNKNOWN, ArchiveHub.kindFor(txt))
    }

    @Test fun sevenZListsAndExtracts() {
        val sevenZ = File(tmp.root, "disc.7z")
        writeSevenZ(sevenZ, mapOf("game.cue" to "x".toByteArray(), "disc1/game.bin" to ByteArray(16)))
        val names = ArchiveHub.entries(sevenZ, ArchiveHub.Kind.SEVEN_Z)
        assertEquals(listOf("disc1/game.bin", "game.cue"), names.sorted())
        val out = File(tmp.root, "out").apply { mkdirs() }
        assertTrue(ArchiveHub.extract(sevenZ, out, ArchiveHub.Kind.SEVEN_Z, 10_000, 1_000_000))
        assertTrue(File(out, "game.cue").isFile)
        assertEquals(16, File(out, "disc1/game.bin").length())
    }

    @Test fun sevenZMislabeledZipStillExtracts() {
        // Crazy Taxi 2: sites ship 7z bytes in a .zip name.
        val sevenZ = File(tmp.root, "game.zip")
        writeSevenZ(sevenZ, mapOf("boot.cue" to "x".toByteArray()))
        assertEquals(ArchiveHub.Kind.SEVEN_Z, ArchiveHub.kindFor(sevenZ))
        val out = File(tmp.root, "out2").apply { mkdirs() }
        assertTrue(ArchiveHub.extract(sevenZ, out, ArchiveHub.Kind.SEVEN_Z, 10_000, 1_000_000))
        assertTrue(File(out, "boot.cue").isFile)
    }

    @Test fun sevenZTraversalRejected() {
        val sevenZ = File(tmp.root, "evil.7z")
        writeSevenZ(sevenZ, mapOf("../evil.bin" to ByteArray(4), "ok.cue" to "x".toByteArray()))
        val out = File(tmp.root, "out3").apply { mkdirs() }
        assertTrue(ArchiveHub.extract(sevenZ, out, ArchiveHub.Kind.SEVEN_Z, 10_000, 1_000_000))
        assertTrue(File(out, "ok.cue").isFile)
        assertTrue(!File(tmp.root, "evil.bin").exists())
    }

    @Test fun zipListsEntries() {
        val zip = File(tmp.root, "d.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("game.cue"))
            zos.write("x".toByteArray())
            zos.closeEntry()
        }
        assertEquals(listOf("game.cue"), ArchiveHub.entries(zip, ArchiveHub.Kind.ZIP))
        val out = File(tmp.root, "zout").apply { mkdirs() }
        assertTrue(ArchiveHub.extract(zip, out, ArchiveHub.Kind.ZIP, 10_000, 1_000_000))
        assertTrue(File(out, "game.cue").isFile)
    }

    @Test fun tarRoundTrip() {
        val tar = File(tmp.root, "d.tar")
        writeTar(tar, mapOf("game.cue" to "x".toByteArray(), "t/game.bin" to ByteArray(8)))
        assertEquals(ArchiveHub.Kind.TAR, ArchiveHub.kindFor(tar))
        assertEquals(
            listOf("game.cue", "t/game.bin"),
            ArchiveHub.entries(tar, ArchiveHub.Kind.TAR).sorted(),
        )
        val out = File(tmp.root, "tout").apply { mkdirs() }
        assertTrue(ArchiveHub.extract(tar, out, ArchiveHub.Kind.TAR, 10_000, 1_000_000))
        assertTrue(File(out, "t/game.bin").isFile)
    }

    @Test fun tarGzDispatchesToTar() {
        val tar = File(tmp.root, "d2.tar")
        writeTar(tar, mapOf("boot.cue" to "cue".toByteArray()))
        val tgz = File(tmp.root, "d2.tgz")
        GzipCompressorOutputStream(tgz.outputStream()).use { it.write(tar.readBytes()) }
        assertEquals(ArchiveHub.Kind.GZIP, ArchiveHub.kindFor(tgz))
        assertEquals(listOf("boot.cue"), ArchiveHub.entries(tgz, ArchiveHub.Kind.GZIP))
        val out = File(tmp.root, "gout").apply { mkdirs() }
        assertTrue(ArchiveHub.extract(tgz, out, ArchiveHub.Kind.GZIP, 10_000, 1_000_000))
        assertEquals("cue", File(out, "boot.cue").readText())
    }

    @Test fun singleGzYieldsOneFile() {
        val gz = File(tmp.root, "rom.gb.gz")
        GzipCompressorOutputStream(gz.outputStream()).use { it.write("rom-bytes".toByteArray()) }
        assertEquals(listOf("rom.gb"), ArchiveHub.entries(gz, ArchiveHub.Kind.GZIP))
        val out = File(tmp.root, "sout").apply { mkdirs() }
        assertTrue(ArchiveHub.extract(gz, out, ArchiveHub.Kind.GZIP, 10_000, 1_000_000))
        assertEquals("rom-bytes", File(out, "rom.gb").readText())
    }

    @Test fun singleBz2AndXz() {
        val bz2 = File(tmp.root, "r.gb.bz2")
        BZip2CompressorOutputStream(bz2.outputStream()).use { it.write("b".toByteArray()) }
        assertEquals(ArchiveHub.Kind.BZIP2, ArchiveHub.kindFor(bz2))
        val out = File(tmp.root, "bout").apply { mkdirs() }
        assertTrue(ArchiveHub.extract(bz2, out, ArchiveHub.Kind.BZIP2, 10_000, 1_000_000))
        assertEquals("b", File(out, "r.gb").readText())
        val xz = File(tmp.root, "r.gb.xz")
        XZCompressorOutputStream(xz.outputStream()).use { it.write("x".toByteArray()) }
        assertEquals(ArchiveHub.Kind.XZ, ArchiveHub.kindFor(xz))
        val out2 = File(tmp.root, "xout").apply { mkdirs() }
        assertTrue(ArchiveHub.extract(xz, out2, ArchiveHub.Kind.XZ, 10_000, 1_000_000))
        assertEquals("x", File(out2, "r.gb").readText())
    }

    @Test fun tarTraversalRejected() {
        val tar = File(tmp.root, "evil.tar")
        writeTar(tar, mapOf("../evil.bin" to ByteArray(4), "ok.cue" to "x".toByteArray()))
        val out = File(tmp.root, "etout").apply { mkdirs() }
        assertTrue(ArchiveHub.extract(tar, out, ArchiveHub.Kind.TAR, 10_000, 1_000_000))
        assertTrue(!File(tmp.root, "evil.bin").exists())
    }

    @Test fun capsTripFalse() {
        val zip = File(tmp.root, "c.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            repeat(3) { i ->
                zos.putNextEntry(java.util.zip.ZipEntry("f$i.bin"))
                zos.write(ByteArray(10))
                zos.closeEntry()
            }
        }
        val out = File(tmp.root, "cout").apply { mkdirs() }
        assertTrue(!ArchiveHub.extract(zip, out, ArchiveHub.Kind.ZIP, 2, 1_000_000))
        val out2 = File(tmp.root, "cout2").apply { mkdirs() }
        assertTrue(!ArchiveHub.extract(zip, out2, ArchiveHub.Kind.ZIP, 10_000, 5))
    }

    @Test fun rarIsHonestlyUnsupported() {
        val fake = File(tmp.root, "a.rar").apply { writeBytes(ByteArray(16)) }
        try {
            ArchiveHub.entries(fake, ArchiveHub.Kind.RAR)
            throw AssertionError("expected UnsupportedArchiveException")
        } catch (e: UnsupportedArchiveException) {
            assertTrue(e.message!!.contains("PC"))
        }
        try {
            ArchiveHub.extract(fake, tmp.root, ArchiveHub.Kind.RAR, 10_000, 1_000_000)
            throw AssertionError("expected UnsupportedArchiveException")
        } catch (e: UnsupportedArchiveException) {
            assertTrue(e.message!!.contains("RAR"))
        }
    }

    @Test fun declaredTotalSumsZipEntries() {
        val zip = File(tmp.root, "sized.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("a.bin"))
            zos.write(ByteArray(100))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("b.bin"))
            zos.write(ByteArray(300))
            zos.closeEntry()
        }
        assertEquals(400L, ArchiveHub.declaredTotal(zip, ArchiveHub.Kind.ZIP))
    }

    @Test fun declaredTotalNullForTarStreams() {
        val tar = File(tmp.root, "sized.tar")
        writeTar(tar, mapOf("a.bin" to ByteArray(100)))
        assertEquals(null, ArchiveHub.declaredTotal(tar, ArchiveHub.Kind.TAR))
    }

    @Test fun extractReportsMonotonicProgress() {
        val zip = File(tmp.root, "prog.zip")
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("big.bin"))
            zos.write(payload)
            zos.closeEntry()
        }
        val reports = mutableListOf<Pair<Long, Long?>>()
        val out = File(tmp.root, "pout").apply { mkdirs() }
        assertTrue(
            ArchiveHub.extract(zip, out, ArchiveHub.Kind.ZIP, 10_000, 10_000_000) { w, t ->
                reports += w to t
            },
        )
        assertTrue(reports.isNotEmpty())
        assertTrue(reports.all { it.second == 200_000L })
        val writes = reports.map { it.first }
        assertEquals(writes.sorted(), writes)
        assertEquals(200_000L, writes.last())
    }

    private fun writeSevenZ(dest: File, files: Map<String, ByteArray>) {
        SevenZOutputFile(dest).use {
            for ((name, bytes) in files) {
                val e = SevenZArchiveEntry()
                e.name = name
                it.putArchiveEntry(e)
                it.write(bytes)
                it.closeArchiveEntry()
            }
            it.finish()
        }
    }

    private fun writeTar(dest: File, files: Map<String, ByteArray>) {
        TarArchiveOutputStream(dest.outputStream()).use { tos ->
            for ((name, bytes) in files) {
                val e = TarArchiveEntry(name)
                e.size = bytes.size.toLong()
                tos.putArchiveEntry(e)
                tos.write(bytes)
                tos.closeArchiveEntry()
            }
            tos.finish()
        }
    }
}
