package com.gamebex.client.launch

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Core system files: the data packs RetroArch's "Core System Files
 * Downloader" installs into its system dir (Dolphin's Sys tree, PPSSPP's
 * fonts/flash0, blueMSX's machine and ROM databases). They are the
 * emulators' own open-source data, not console firmware, and come from
 * the official Libretro buildbot `assets/system/` — the same zips RA's
 * updater fetches. Without them Dolphin warns and loses fonts/game
 * fixes, and blueMSX can't boot at all.
 *
 * The buildbot rebuilds these nightly and publishes no hashes, so there is
 * no pin; trust is HTTPS + same-host redirects + a strict zip check (every
 * entry must sit under the pack's own top-level folders, no traversal).
 * Existing files are never overwritten, and the marker file is extracted
 * last so an interrupted install reads as missing and retries.
 */
object CoreSystemFiles {
    private const val TAG = "GameBEXSysFiles"
    private const val BASE = "https://buildbot.libretro.com/assets/system/"
    /** Largest pack accepted, zipped or extracted (PPSSPP is ~20 MB unpacked). */
    const val MAX_PACK_BYTES = 64L * 1024 * 1024

    data class Pack(
        val zip: String,
        /** Top-level folders every entry must live under. */
        val roots: Set<String>,
        /** File whose presence means the pack is installed. */
        val marker: String,
        val approxMb: Int,
    ) {
        val url: String get() = BASE + zip
    }

    val PACKS: Map<String, Pack> = mapOf(
        "dolphin" to Pack("Dolphin.zip", setOf("dolphin-emu"), "dolphin-emu/Sys/GC/dsp_coef.bin", 3),
        "ppsspp" to Pack("PPSSPP.zip", setOf("PPSSPP"), "PPSSPP/ppge_atlas.zim", 11),
        "bluemsx" to Pack("blueMSX.zip", setOf("Databases", "Machines"), "Databases/msxromdb.xml", 5),
    )

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /** RetroArch's system dir as GameBEX configures it (RetroArchTuner). */
    @Suppress("DEPRECATION")
    fun systemDir(): File = File(Environment.getExternalStorageDirectory(), "RetroArch/system")

    /** True when [core] needs no pack or its pack is in place. */
    fun isInstalled(core: String, systemDir: File = systemDir()): Boolean {
        val pack = PACKS[core] ?: return true
        return File(systemDir, pack.marker).isFile
    }

    /** Cores among [cores] whose pack is missing. */
    fun missing(cores: Collection<String>, systemDir: File = systemDir()): Set<String> =
        cores.filterTo(mutableSetOf()) { !isInstalled(it, systemDir) }

    /**
     * Relative output path for a zip entry, or null when it must be
     * rejected: absolute, backslashes, `..`/`.` segments, or outside the
     * pack's [roots]. Directory entries return null too (dirs are made
     * on demand). Pure for tests.
     */
    fun safeEntryPath(name: String, roots: Set<String>): String? {
        if (name.isEmpty() || name.endsWith("/")) return null
        if (name.startsWith("/") || '\\' in name || '\u0000' in name) return null
        val parts = name.split("/")
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (parts.size < 2 || parts.first() !in roots) return null
        return name
    }

    sealed interface Result {
        data object AlreadyInstalled : Result
        data class Installed(val files: Int) : Result
        data class Failed(val reason: String) : Result
    }

    /** Fetches and unpacks [core]'s pack when missing. Idempotent. */
    suspend fun ensure(
        ctx: Context,
        core: String,
        onProgress: ((read: Long, total: Long) -> Unit)? = null,
        systemDir: File = systemDir(),
        /** Unpack progress (files done, files total); storage writes dominate. */
        onUnpack: ((done: Int, total: Int) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val pack = PACKS[core] ?: return@withContext Result.AlreadyInstalled
        locks.getOrPut(core) { Mutex() }.withLock {
            if (isInstalled(core, systemDir)) return@withLock Result.AlreadyInstalled
            val tmp = File.createTempFile("sysfiles", ".zip", ctx.cacheDir)
            try {
                if (!RetroCoreFetcher.downloadTo(pack.url, tmp, onProgress, MAX_PACK_BYTES)) {
                    return@withLock Result.Failed("Couldn't download ${pack.zip}. Check the connection and try again.")
                }
                unpack(tmp, pack, systemDir, onUnpack)
            } catch (e: Exception) {
                Log.w(TAG, "${pack.zip} install failed", e)
                Result.Failed("Couldn't install ${pack.zip}.")
            } finally {
                runCatching { tmp.delete() }
            }
        }
    }

    /** Validates every entry first, then extracts; marker last. */
    internal fun unpack(
        zipFile: File,
        pack: Pack,
        systemDir: File,
        onUnpack: ((done: Int, total: Int) -> Unit)? = null,
    ): Result {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            val bad = entries.firstOrNull { safeEntryPath(it.name, pack.roots) == null }
            if (bad != null) {
                logw("${pack.zip} rejected: unsafe entry ${bad.name}")
                return Result.Failed("${pack.zip} looked wrong — not installed.")
            }
            if (entries.none { it.name == pack.marker }) {
                return Result.Failed("${pack.zip} is missing expected files — not installed.")
            }
            var budget = MAX_PACK_BYTES
            var written = 0
            val ordered = entries.filter { it.name != pack.marker } + entries.filter { it.name == pack.marker }
            for ((i, e) in ordered.withIndex()) {
                if (i % 25 == 0) onUnpack?.invoke(i, ordered.size)
                val out = File(systemDir, e.name)
                if (out.exists()) continue // never clobber the user's files
                out.parentFile?.mkdirs()
                val part = File(out.path + ".part")
                val n = zip.getInputStream(e).use { inp ->
                    part.outputStream().use { RetroCoreFetcher.copyCapped(inp, it, budget) }
                }
                if (n == null) {
                    runCatching { part.delete() }
                    return Result.Failed("${pack.zip} is larger than expected — not installed.")
                }
                budget -= n
                if (!part.renameTo(out)) {
                    part.copyTo(out, overwrite = false)
                    part.delete()
                }
                written++
            }
            runCatching { Log.i(TAG, "${pack.zip}: $written files → $systemDir") }
            return Result.Installed(written)
        }
    }

    /** android.jar stubs throw in unit tests; never let logging break an install. */
    private fun logw(msg: String) = runCatching { Log.w(TAG, msg) }
}
