package com.gamebex.client.data.local

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/** Identified format with no on-device reader (RAR: no Android library). */
class UnsupportedArchiveException(message: String) : Exception(message)

/**
 * Central archive dispatcher: identifies by magic bytes — never the
 * extension, sites mislabel 7z as .zip — and routes to the right reader.
 * ZIP/7Z/TAR/GZ/BZ2/XZ extract on-device; RAR is detected but has no
 * reader (commons-compress ships none; junrar is RAR4-only and dead);
 * UNKNOWN means corrupt or foreign. Single-stream compressors (.gz/.bz2/
 * .xz) tar-probe first, so .tgz works and plain .gz yields one file.
 */
object ArchiveHub {
    enum class Kind { ZIP, SEVEN_Z, RAR, TAR, GZIP, BZIP2, XZ, UNKNOWN }

    /** Header probe length: TAR's ustar magic sits at offset 257. */
    const val HEADER_LEN = 262

    /** Extensions routed to archive prep on disc folders (magic decides). */
    val ROUTE_EXTS = setOf(
        "zip", "7z", "rar", "tar", "tgz", "tbz", "txz",
        "gz", "bz2", "xz", "001", "z01", "r00",
    )

    const val RAR_MESSAGE =
        "RAR archives can't be extracted on-device (no Android reader for the format) — " +
            "extract on a PC, then re-scan."

    /** Format by magic bytes. Pure for tests. */
    fun sniff(magic: ByteArray): Kind {
        if (magic.size >= 6 && magic[0] == 0x37.toByte() && magic[1] == 0x7A.toByte() &&
            magic[2] == 0xBC.toByte() && magic[3] == 0xAF.toByte() &&
            magic[4] == 0x27.toByte() && magic[5] == 0x1C.toByte()
        ) return Kind.SEVEN_Z
        if (magic.size >= 7 && magic[0] == 0x52.toByte() && magic[1] == 0x61.toByte() &&
            magic[2] == 0x72.toByte() && magic[3] == 0x21.toByte() &&
            magic[4] == 0x1A.toByte() && magic[5] == 0x07.toByte()
        ) return Kind.RAR
        if (magic.size >= 6 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() &&
            magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte() &&
            magic[4] == 0x5A.toByte() && magic[5] == 0x00.toByte()
        ) return Kind.XZ
        if (magic.size >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()) return Kind.GZIP
        if (magic.size >= 4 && magic[0] == 0x42.toByte() && magic[1] == 0x5A.toByte() &&
            magic[2] == 0x68.toByte() && magic[3] in 0x31.toByte()..0x39.toByte()
        ) return Kind.BZIP2
        if (magic.size >= HEADER_LEN && magic[257] == 0x75.toByte() && magic[258] == 0x73.toByte() &&
            magic[259] == 0x74.toByte() && magic[260] == 0x61.toByte() && magic[261] == 0x72.toByte()
        ) return Kind.TAR
        if (magic.size >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            (magic[2] == 0x03.toByte() || magic[2] == 0x05.toByte() || magic[2] == 0x07.toByte())
        ) return Kind.ZIP
        return Kind.UNKNOWN
    }

    fun readHeader(file: File): ByteArray? = runCatching {
        file.inputStream().use { ins ->
            val buf = ByteArray(HEADER_LEN)
            var off = 0
            while (off < HEADER_LEN) {
                val n = ins.read(buf, off, HEADER_LEN - off)
                if (n < 0) break
                off += n
            }
            if (off <= 0) null else buf.copyOf(off)
        }
    }.getOrNull()

    fun kindFor(file: File): Kind = readHeader(file)?.let(::sniff) ?: Kind.UNKNOWN

    private val SPLIT_RE = Regex("""\.(001|z01|r00|part\d+\.rar)$""", RegexOption.IGNORE_CASE)

    /** Multi-volume part (.7z.001/.z01/.r00/.partN.rar): join on a PC. Pure. */
    fun isSplitVolume(name: String): Boolean = SPLIT_RE.containsMatchIn(name)

    /**
     * Entry name → safe outDir-relative path, null to skip (macOS
     * droppings, blank) or reject (traversal). Shared by the planner and
     * the extractor so both name the same files.
     */
    fun normalizeEntry(name: String): String? {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        if (leaf.isBlank() || leaf.startsWith("._") || name.startsWith("__MACOSX/")) return null
        val raw = name.replace('\\', '/').split('/')
        if (raw.any { it == ".." }) return null // traversal: reject, don't scrub
        val parts = raw.filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    /** Output name for a single-stream decompress (rom.gb.gz → rom.gb). */
    fun innerNameFor(file: File): String = file.nameWithoutExtension.ifBlank { "output.bin" }

    /** Non-directory entry names. Throws [UnsupportedArchiveException] for RAR. */
    fun entries(file: File, kind: Kind): List<String> = when (kind) {
        Kind.ZIP -> ZipFile(file).use { zf ->
            zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        }
        Kind.SEVEN_Z -> SevenZFile.Builder().setFile(file).get().use { zf ->
            val out = mutableListOf<String>()
            while (true) {
                val e = zf.nextEntry ?: break
                if (!e.isDirectory) out.add(e.name)
            }
            out
        }
        Kind.TAR -> tarNames({ file.inputStream().buffered() })
            ?: throw java.io.IOException("not a tar archive")
        Kind.GZIP, Kind.BZIP2, Kind.XZ ->
            tarNames({ openDecompressor(file, kind) }) ?: listOf(innerNameFor(file))
        Kind.RAR -> throw UnsupportedArchiveException(RAR_MESSAGE)
        Kind.UNKNOWN -> throw IllegalArgumentException("unknown archive kind")
    }

    /**
     * Extracts with caps + zip-slip/conflict discipline. False = safety
     * caps hit (caller routes to needs-manual); throws otherwise.
     */
    fun extract(file: File, outDir: File, kind: Kind, maxEntries: Int, maxBytes: Long): Boolean {
        outDir.mkdirs()
        val base = outDir.canonicalPath
        val written = mutableSetOf<String>()
        val budget = Budget()
        val write = fun(rel: String, open: () -> InputStream): Boolean =
            writeOne(outDir, base, rel, written, budget, maxEntries, maxBytes, open)
        when (kind) {
            Kind.ZIP -> ZipFile(file).use { zf ->
                val it = zf.entries()
                while (it.hasMoreElements()) {
                    val e = it.nextElement()
                    if (e.isDirectory) continue
                    if (!write(e.name) { zf.getInputStream(e) }) return false
                }
            }
            Kind.SEVEN_Z -> SevenZFile.Builder().setFile(file).get().use { zf ->
                while (true) {
                    val e = zf.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (!write(e.name) { zf.getInputStream(e) }) return false
                }
            }
            Kind.TAR -> {
                var sawTar = false
                var ok = true
                val parsed = eachTarEntry({ file.inputStream().buffered() }, { name, ins ->
                    sawTar = true
                    ok = write(name) { NoClose(ins) }
                    ok
                })
                if (!parsed || !sawTar) throw java.io.IOException("not a tar archive")
                if (!ok) return false
            }
            Kind.GZIP, Kind.BZIP2, Kind.XZ -> {
                var sawTar = false
                var ok = true
                val parsed = eachTarEntry({ openDecompressor(file, kind) }, { name, ins ->
                    sawTar = true
                    ok = write(name) { NoClose(ins) }
                    ok
                })
                if (parsed && sawTar) {
                    if (!ok) return false
                } else {
                    // Plain single stream, not a tar: one output file.
                    openDecompressor(file, kind).use { ins ->
                        if (!write(innerNameFor(file)) { ins }) return false
                    }
                }
            }
            Kind.RAR -> throw UnsupportedArchiveException(RAR_MESSAGE)
            Kind.UNKNOWN -> throw IllegalArgumentException("unknown archive kind")
        }
        return true
    }

    private class Budget(var count: Int = 0, var total: Long = 0)

    /**
     * Close-shield: tar hands out its own archive stream per entry, so the
     * shared writer must not close it mid-walk (zip/7z mint fresh streams
     * per entry and are unaffected). Extends raw InputStream so close()
     * stays a no-op by default.
     */
    private class NoClose(private val wrapped: InputStream) : InputStream() {
        override fun read(): Int = wrapped.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = wrapped.read(b, off, len)
        override fun skip(n: Long): Long = wrapped.skip(n)
    }

    private fun writeOne(
        outDir: File,
        base: String,
        rawName: String,
        written: MutableSet<String>,
        budget: Budget,
        maxEntries: Int,
        maxBytes: Long,
        open: () -> InputStream,
    ): Boolean {
        val rel = normalizeEntry(rawName) ?: return true
        if (++budget.count > maxEntries) return false
        val dest = File(outDir, rel)
        val canon = runCatching { dest.canonicalPath }.getOrNull() ?: return true
        if (!canon.startsWith("$base${File.separator}")) return true // zip-slip: skip
        if (!written.add(canon)) return false // conflicting entries
        dest.parentFile?.mkdirs()
        try {
            open().use { ins ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        budget.total += n
                        if (budget.total > maxBytes) return false
                    }
                }
            }
        } catch (e: CancellationException) {
            runCatching { dest.delete() }
            throw e
        } catch (_: Exception) {
            runCatching { dest.delete() }
            return false
        }
        return true
    }

    private fun openDecompressor(file: File, kind: Kind): InputStream {
        val raw = file.inputStream().buffered()
        return when (kind) {
            Kind.GZIP -> GzipCompressorInputStream(raw)
            Kind.BZIP2 -> BZip2CompressorInputStream(raw)
            Kind.XZ -> XZCompressorInputStream(raw)
            else -> throw IllegalArgumentException("not a compressor kind: $kind")
        }
    }

    /**
     * Tar walk over [open]; false when the stream isn't a tar (caller
     * falls back to single-file mode). Entry streams are single-pass:
     * [each] must consume immediately. Stops early when [each] is false.
     */
    private fun eachTarEntry(open: () -> InputStream, each: (String, InputStream) -> Boolean): Boolean {
        try {
            open().use { raw ->
                TarArchiveInputStream(raw).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        if (e.isDirectory) continue
                        if (!each(e.name, tar)) return true
                    }
                    return true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
    }

    private fun tarNames(open: () -> InputStream): List<String>? {
        val out = mutableListOf<String>()
        if (!eachTarEntry(open) { name, _ -> out.add(name); true }) return null
        return out.ifEmpty { null }
    }
}
