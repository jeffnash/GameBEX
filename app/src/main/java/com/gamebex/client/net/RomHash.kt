package com.gamebex.client.net

import android.content.Context
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * ROM identity for ScreenScraper checksum matching: CRC32 + byte size +
 * file name of the ROM itself. Zips holding exactly one file use that
 * entry's CRC straight from the central directory (free, no inflate);
 * everything else streams the file once. Never loads a file whole.
 */
data class RomFingerprint(
    /** Upper-case 8-hex-digit CRC32. */
    val crc: String,
    val size: Long,
    val name: String,
)

object RomHash {
    /** Disc images past this are skipped (name search only): hashing costs more than it saves. */
    const val MAX_HASH_BYTES = 512L * 1024 * 1024
    private const val BUF = 64 * 1024

    fun formatCrc(v: Long): String = "%08X".format(v and 0xFFFFFFFFL)

    /**
     * Streaming CRC32; null when unreadable or larger than [maxBytes].
     * [checkCancel] runs per chunk and may throw CancellationException,
     * which always propagates.
     */
    fun crc32(file: File, maxBytes: Long = MAX_HASH_BYTES, checkCancel: () -> Unit = {}): String? {
        if (!file.isFile) return null
        if (file.length() > maxBytes) return null
        return try {
            val crc = CRC32()
            val buf = ByteArray(BUF)
            var total = 0L
            file.inputStream().use { input ->
                while (true) {
                    checkCancel()
                    val n = input.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                    total += n
                    // Grew while we read: honor the cap anyway.
                    if (total > maxBytes) return null
                }
            }
            formatCrc(crc.value)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The lone ROM inside a zip, from the central directory only. Null
     * when the file isn't a readable zip, holds zero or several files, or
     * the entry lacks a CRC.
     */
    fun zipSingleEntry(file: File): RomFingerprint? = try {
        ZipFile(file).use { zf ->
            val files = zf.entries().asSequence()
                .filter { !it.isDirectory }
                .filterNot { isJunkEntry(it.name) }
                .take(2).toList()
            val e = files.singleOrNull() ?: return null
            if (e.crc < 0 || e.size < 0) return null
            RomFingerprint(formatCrc(e.crc), e.size, e.name.substringAfterLast('/'))
        }
    } catch (_: Exception) {
        null
    }

    /** macOS resource forks and readme-style extras don't count as the ROM. */
    private fun isJunkEntry(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return name.startsWith("__MACOSX/") || base.startsWith("._") ||
            base.equals(".DS_Store", ignoreCase = true)
    }

    /**
     * Fingerprint for [file]: single-entry zip -> inner ROM; else the file
     * itself (multi-file zips such as arcade sets hash as the archive,
     * which is how ScreenScraper indexes them). Null when capped/unreadable.
     */
    fun fingerprint(
        file: File,
        maxBytes: Long = MAX_HASH_BYTES,
        checkCancel: () -> Unit = {},
    ): RomFingerprint? {
        if (!file.isFile) return null
        if (file.extension.equals("zip", ignoreCase = true)) {
            zipSingleEntry(file)?.let { return it }
        }
        val crc = crc32(file, maxBytes, checkCancel) ?: return null
        return RomFingerprint(crc, file.length(), file.name)
    }

    // Cache (app-private, keyed by game id).

    /**
     * Cache line: `<srcSize>:<srcMtime>:<crc>:<romSize>:<romName>`. The
     * source size+mtime guard means a replaced file rehashes. App-private
     * prefs, never a sidecar: user game folders are read-only.
     */
    fun encode(srcSize: Long, srcMtime: Long, fp: RomFingerprint): String =
        "$srcSize:$srcMtime:${fp.crc}:${fp.size}:${fp.name}"

    /** Cached fingerprint when the stamp still matches the source file. */
    fun decode(line: String?, srcSize: Long, srcMtime: Long): RomFingerprint? {
        if (line == null) return null
        val parts = line.split(':', limit = 5)
        if (parts.size != 5) return null
        if (parts[0].toLongOrNull() != srcSize || parts[1].toLongOrNull() != srcMtime) return null
        val crc = parts[2].takeIf { it.length == 8 && it.all { c -> c.isDigit() || c in 'A'..'F' } }
            ?: return null
        val size = parts[3].toLongOrNull() ?: return null
        if (parts[4].isEmpty()) return null
        return RomFingerprint(crc, size, parts[4])
    }

    private const val PREFS = "rom_hash_cache"

    /**
     * Cached-or-computed fingerprint, hashed on Dispatchers.IO and
     * cancellable per chunk. Capped files cache nothing and return null
     * (name search only).
     */
    suspend fun cached(ctx: Context, gameId: Long, file: File): RomFingerprint? = withContext(Dispatchers.IO) {
        val job = coroutineContext.job
        cachedBlocking(ctx, gameId, file) { job.ensureActive() }
    }

    private fun cachedBlocking(ctx: Context, gameId: Long, file: File, checkCancel: () -> Unit): RomFingerprint? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "g$gameId"
        val size = file.length()
        val mtime = file.lastModified()
        decode(prefs.getString(key, null), size, mtime)?.let { return it }
        val fp = fingerprint(file, checkCancel = checkCancel) ?: return null
        prefs.edit().putString(key, encode(size, mtime, fp)).apply()
        return fp
    }
}
