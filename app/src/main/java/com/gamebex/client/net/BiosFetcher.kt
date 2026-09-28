package com.gamebex.client.net

import android.content.Context
import android.os.Environment
import android.util.Log
import com.gamebex.client.data.BiosCheck
import com.gamebex.client.data.BiosTable
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.PrepPipeline
import com.gamebex.client.data.local.RomStore
import com.gamebex.client.data.RetroBios
import com.gamebex.client.data.RetroBios.resolve
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * BIOS downloads, now that the user opted into fetching: missing
 * firmware comes from the live RetroBIOS repo tree (resolved per system
 * by [RetroBios]), is MD5/size-verified before it lands, and is placed
 * where the game's route reads it. Blocking; call off the main thread.
 *
 * Placement follows the need's dirHint: RetroArch/system… for core
 * routes, the ROM folder for arcade sets, and shared staging
 * (GameBEX/bios/<folder>) for standalone apps whose configured folders
 * aren't visible — the note tells the user where to point the emu.
 */
object BiosFetcher {
    private const val TAG = "GameBEXBios"
    private const val INDEX_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** android.jar stubs throw in unit tests; never let logging break a fetch. */
    private fun logw(msg: String, e: Throwable? = null) = runCatching {
        if (e == null) Log.w(TAG, msg) else Log.w(TAG, msg, e)
    }
    private fun logi(msg: String) = runCatching { Log.i(TAG, msg) }

    sealed interface Outcome {
        val name: String
        data class Installed(override val name: String, val bytes: Long) : Outcome
        data class Skipped(override val name: String) : Outcome
        data class Failed(override val name: String, val reason: String) : Outcome
    }

    data class FetchReport(
        val outcomes: List<Outcome>,
        val indexError: String? = null,
        /** Set when files landed in shared staging: one-time emu pointer. */
        val stagedNote: String? = null,
    ) {
        val installed: List<Outcome.Installed> get() = outcomes.filterIsInstance<Outcome.Installed>()
        val failed: List<Outcome.Failed> get() = outcomes.filterIsInstance<Outcome.Failed>()
        fun summary(): String {
            val base = when {
                indexError != null -> "BIOS index unreachable: $indexError"
                outcomes.isEmpty() -> "Nothing to fetch."
                failed.isEmpty() -> "${installed.size} BIOS installed" +
                    if (installed.isNotEmpty()) ": ${installed.joinToString(", ") { it.name }}" else ""
                installed.isEmpty() -> "BIOS fetch failed: ${failed.first().reason}"
                else -> "${installed.size} installed, ${failed.size} failed (${failed.first().reason})"
            }
            return if (stagedNote != null && installed.isNotEmpty()) "$base. $stagedNote" else base
        }
    }

    /**
     * Where a need's files land. Pure for tests. Null when the ROM
     * folder target is unknown.
     */
    fun targetDir(sharedRoot: File, folder: String, dirHint: String, romDir: File?): File? {
        if (dirHint.startsWith("RetroArch/system")) {
            return File(sharedRoot, dirHint)
        }
        if (dirHint.startsWith("the ") && "ROM folder" in dirHint) {
            return romDir
        }
        return File(sharedRoot, BiosTable.stagingRelPath(folder))
    }

    /** Full pipeline: cached index + fetch. Blocking. */
    fun fetchMissing(
        ctx: Context,
        folder: String,
        romDir: File?,
        scanDirs: List<File>,
    ): FetchReport {
        val index = try {
            ensureIndex(File(ctx.cacheDir, "retrobios"))
        } catch (e: Exception) {
            logw("index fetch failed", e)
            return FetchReport(emptyList(), e.message ?: "network error")
        }
        @Suppress("DEPRECATION")
        val sharedRoot = Environment.getExternalStorageDirectory()
        return fetchWithIndex(index, folder, romDir, scanDirs, sharedRoot)
    }

    /**
     * Fetch core: no Context, injectable URL base for tests. Only needs
     * the folder report marks unsatisfied are fetched; files already
     * valid at the target are skipped.
     */
    fun fetchWithIndex(
        index: RetroBios.Index,
        folder: String,
        romDir: File?,
        scanDirs: List<File>,
        sharedRoot: File,
        baseUrl: String = RetroBios.RAW_BASE,
    ): FetchReport {
        val needs = BiosTable.TABLE[folder].orEmpty()
        if (needs.isEmpty()) return FetchReport(emptyList())
        val report = BiosCheck.checkFolder(folder, scanDirs)
        val out = mutableListOf<Outcome>()
        var staged: File? = null
        for (nr in report.needs) {
            if (nr.satisfied) {
                nr.files.forEach { out += Outcome.Skipped(it.want.name) }
                continue
            }
            val target = targetDir(sharedRoot, folder, nr.need.dirHint, romDir)
            if (target == null) {
                nr.files.forEach { out += Outcome.Failed(it.want.name, "ROM folder unknown") }
                continue
            }
            for (fr in nr.files) {
                val want = fr.want
                val atTarget = BiosCheck.checkFile(want, listOf(target))
                if (atTarget.state == BiosCheck.FileState.OK ||
                    atTarget.state == BiosCheck.FileState.PRESENT_UNVERIFIED
                ) {
                    out += Outcome.Skipped(want.name)
                    continue
                }
                val outcome = downloadOne(index, folder, want, target, baseUrl)
                if (outcome is Outcome.Installed &&
                    target.absolutePath.contains(BiosTable.stagingRelPath(folder))
                ) {
                    staged = target
                }
                out += outcome
            }
        }
        val note = staged?.let {
            "Point the emulator's BIOS folder at ${it.absolutePath} (one-time setup)"
        }
        return FetchReport(out, stagedNote = note)
    }

    private const val PREFS = "bios_fetch"

    fun isFolderSeeded(ctx: Context, folder: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("seeded_$folder", false)

    fun markFolderSeeded(ctx: Context, folder: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("seeded_$folder", true).apply()
    }

    /**
     * Background seed after a download lands: first game per console
     * fetches that console's BIOS so Play never waits on it. Blocking;
     * runs on the engine's IO scope. True when the folder is ready (or
     * needs nothing) — only then is the seeded flag set, so failures
     * retry on the next download or Play.
     */
    suspend fun seedForGame(
        ctx: Context,
        db: LibraryDb,
        gameId: Long,
    ): Boolean {
        val game = db.games().findById(gameId) ?: return false
        val needs = BiosTable.TABLE[game.folder].orEmpty()
        if (needs.isEmpty()) {
            markFolderSeeded(ctx, game.folder)
            return true
        }
        if (isFolderSeeded(ctx, game.folder)) return true
        @Suppress("DEPRECATION")
        val sharedRoot = Environment.getExternalStorageDirectory()
        val rom = RomStore.resolveFile(ctx, game.volumeId, game.relPath)
        val targets = needs.mapNotNull { targetDir(sharedRoot, game.folder, it.dirHint, rom?.parentFile) }
            .distinctBy { it.absolutePath }
        if (BiosCheck.checkFolder(game.folder, targets).ready) {
            markFolderSeeded(ctx, game.folder)
            return true
        }
        // User game folders are read-only: ROM-folder BIOS isn't fetched there.
        val fetch = fetchMissing(ctx, game.folder, RomStore.writableRomDir(game, rom), targets)
        logi("seed ${game.folder}: ${fetch.summary()}")
        if (fetch.indexError == null && BiosCheck.checkFolder(game.folder, targets).ready) {
            markFolderSeeded(ctx, game.folder)
            return true
        }
        return false
    }

    /**
     * Launch boundary: when the game isn't READY because of BIOS (or was
     * never prepared), fetch what's missing and re-prepare, returning the
     * fresh row. READY/FAILED/NEEDS_MANUAL rows pass through untouched.
     * Blocking; callers run it on Dispatchers.IO.
     */
    suspend fun ensureForLaunch(
        ctx: Context,
        db: LibraryDb,
        game: GameEntity,
    ): GameEntity {
        if (game.prepState != PrepPipeline.PREP_NONE &&
            game.prepState != PrepPipeline.PREP_NEEDS_BIOS
        ) {
            return game
        }
        if (BiosTable.TABLE[game.folder].isNullOrEmpty()) return game
        val rom = RomStore.resolveFile(ctx, game.volumeId, game.relPath)
        val scanDirs = PrepPipeline
            .biosEmuDirs(ctx, game.folder, game.emulatorOverride) + listOfNotNull(rom?.parentFile)
        if (BiosCheck.checkFolder(game.folder, scanDirs).ready) {
            // BIOS present but prep never ran/confirmed: run prep only.
            PrepPipeline.prepare(ctx, db, game.id)
            return db.games().findById(game.id) ?: game
        }
        val fetch = fetchMissing(ctx, game.folder, RomStore.writableRomDir(game, rom), scanDirs)
        logi("ensure ${game.folder}: ${fetch.summary()}")
        PrepPipeline.prepare(ctx, db, game.id)
        return db.games().findById(game.id) ?: game
    }

    private fun downloadOne(
        index: RetroBios.Index,
        folder: String,
        want: BiosTable.BiosFile,
        target: File,
        baseUrl: String,
    ): Outcome {
        val resolved = when (val r = index.resolve(folder, want)) {
            is RetroBios.ResolveResult.Ok -> r.resolved
            is RetroBios.ResolveResult.Missing -> return Outcome.Failed(want.name, r.reason)
            is RetroBios.ResolveResult.Conflict -> {
                logw("pin conflict, not downloading: ${r.reason}")
                return Outcome.Failed(want.name, r.reason)
            }
        }
        if (!target.mkdirs() && !target.isDirectory) {
            return Outcome.Failed(want.name, "can't write ${target.absolutePath} (grant All files access)")
        }
        val url = RetroBios.rawUrl(resolved.repoPath, baseUrl)
        val bytes = try {
            download(url)
        } catch (e: Exception) {
            logw("download failed $url", e)
            return Outcome.Failed(want.name, e.message ?: "network error")
        }
        if (bytes == null) return Outcome.Failed(want.name, "download failed (HTTP error)")
        if (resolved.size >= 0 && bytes.size.toLong() != resolved.size) {
            return Outcome.Failed(want.name, "size mismatch (got ${bytes.size}, want ${resolved.size})")
        }
        val tmp = File(target, "${resolved.saveName}.part")
        try {
            tmp.writeBytes(bytes)
            val actual = BiosCheck.md5(tmp)
            if (!actual.equals(resolved.md5, ignoreCase = true)) {
                tmp.delete()
                return Outcome.Failed(want.name, "md5 mismatch (got $actual)")
            }
            val dest = File(target, resolved.saveName)
            if (!tmp.renameTo(dest)) {
                // Cross-volume rename fallback (shouldn't happen: same dir).
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            return Outcome.Failed(want.name, e.message ?: "write failed")
        }
        logi("installed ${resolved.saveName} (${bytes.size}B) -> ${target.absolutePath}")
        return Outcome.Installed(resolved.saveName, bytes.size.toLong())
    }

    internal fun ensureIndex(cacheDir: File): RetroBios.Index {
        cacheDir.mkdirs()
        val platforms = cachedGet(cacheDir, "platforms.json", RetroBios.PLATFORMS_URL)
        val database = cachedGet(cacheDir, "database.json", RetroBios.DATABASE_URL)
        return RetroBios.buildIndex(platforms, database)
    }

    private fun cachedGet(cacheDir: File, name: String, url: String): String {
        val f = File(cacheDir, name)
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < INDEX_MAX_AGE_MS) {
            return f.readText()
        }
        val bytes = download(url) ?: throw java.io.IOException("HTTP error for $url")
        f.writeBytes(bytes)
        return f.readText()
    }

    private fun download(url: String): ByteArray? {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", HOST_UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { inp ->
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }
}
