package com.gamebex.client.data.local

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Delete a game to free space: the ROM, its sidecar and publish leftovers,
 * track files a loose cue/gdi/ccd names next to it, prep-owned output
 * (manifest entries of the prep marker + the staging dir), the app-private
 * art cache, then the DB row (a source game's row stays as a "Download
 * again" tombstone — see [finishRow]).
 *
 * Safety contract: every path is canonical-contained in the game's own
 * platform folder inside its library volume root. Prep output is deleted
 * by manifest only, so foreign files in that folder survive (same rule as
 * a prep rebuild); a marker without a manifest deletes nothing.
 * Emulator saves live in the emulators' own dirs and are never touched.
 */
object GameDeleter {
    private const val TAG = "GameBEXDelete"

    /** What a delete removes; paths already verified contained. */
    data class Plan(
        /** Plain files to delete (existing only). */
        val files: List<File>,
        /** Prep scratch dirs removed wholesale (prep-owned by name). */
        val trees: List<File>,
        /** Dirs pruned afterwards when left empty, deepest first. */
        val prune: List<File>,
        /** Pruning never climbs to (or past) this dir: the platform folder. */
        val stopAt: File? = null,
    ) {
        val bytes: Long get() = files.sumOf { it.length() } + trees.sumOf { dirBytes(it) }
    }

    sealed interface Outcome {
        data class Deleted(val bytesFreed: Long) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Pure file selection (unit-tested). [root] is the volume root, [relPath]
     * the row's `folder/file`. Null when the ROM path escapes the root or
     * doesn't sit inside a platform folder — nothing is deleted then.
     */
    fun plan(root: File, relPath: String): Plan? {
        val rootCanon = canon(root) ?: return null
        val rom = File(root, relPath)
        val romCanon = canon(rom) ?: return null
        if (!isInside(romCanon, rootCanon)) return null
        val platformDir = rom.parentFile ?: return null
        val platformCanon = canon(platformDir) ?: return null
        // The ROM must live in a platform folder, never at the root itself.
        if (platformCanon == rootCanon || !isInside(platformCanon, rootCanon)) return null
        if (rom.isDirectory) return null

        val files = linkedSetOf<File>()
        val trees = mutableListOf<File>()
        val prune = mutableListOf<File>()
        fun addFile(f: File) {
            val c = canon(f) ?: return
            if (isInside(c, platformCanon) && f.isFile) files += f
        }

        addFile(rom)
        addFile(Sidecars.fileFor(rom))
        addFile(File(platformDir, "${Sidecars.fileFor(rom).name}.tmp"))
        addFile(File(platformDir, "${rom.name}.tmp"))
        addFile(File(platformDir, "${rom.name}.bak"))

        // Loose sheets: the tracks they name are this game's bytes. M3U
        // refs are other rows' sheets, so they stay.
        if (rom.isFile && rom.extension.lowercase() in setOf("cue", "gdi", "ccd")) {
            PrepPipeline.parseSheetRefs(rom).orEmpty()
                .filter { PrepPipeline.isSafeRef(it) }
                .forEach { addFile(File(platformDir, it)) }
        }

        // Prep output: manifest-owned files + marker, then prune empties.
        val outDir = File(platformDir, rom.nameWithoutExtension)
        val outCanon = canon(outDir)
        if (outCanon != null && outCanon != platformCanon && isInside(outCanon, platformCanon) &&
            outDir.isDirectory
        ) {
            val marker = File(outDir, PrepPipeline.STAGE_MARKER)
            val manifest = PrepPipeline.parseMarkerManifest(runCatching { marker.readText() }.getOrNull())
            if (manifest.isNotEmpty()) {
                manifest.forEach { rel ->
                    val f = File(outDir, rel)
                    val c = canon(f) ?: return@forEach
                    if (isInside(c, outCanon) && f.isFile) files += f
                }
                if (marker.isFile) files += marker
                manifest.mapNotNull { File(outDir, it).parentFile }
                    .filter { d -> canon(d)?.let { it == outCanon || isInside(it, outCanon) } == true }
                    .toSet()
                    .sortedByDescending { it.absolutePath.length }
                    .forEach { prune += it }
                if (outDir !in prune) prune += outDir
            }
        }
        val stage = File(platformDir, rom.nameWithoutExtension + ".staging")
        val stageCanon = canon(stage)
        if (stageCanon != null && isInside(stageCanon, platformCanon) && stage.isDirectory) {
            trees += stage
        }
        return Plan(files.toList(), trees, prune, stopAt = platformDir)
    }

    /**
     * Executes [plan]. Returns true when the ROM (first file) is gone;
     * leftovers are best-effort. Pruning removes only empty dirs.
     */
    fun execute(plan: Plan): Boolean {
        plan.files.forEach { runCatching { it.delete() } }
        plan.trees.forEach { runCatching { it.deleteRecursively() } }
        plan.prune.forEach { start ->
            var d: File? = start
            while (d != null && d != plan.stopAt && d.isDirectory && d.list()?.isEmpty() == true) {
                runCatching { d.delete() }
                d = d.parentFile
            }
        }
        return plan.files.firstOrNull()?.exists() != true
    }

    /**
     * Full delete for a library row. Serialized with prep for this game so
     * a running extraction can't republish mid-delete. The DB row goes only
     * after the ROM is confirmed gone (a missing ROM still deletes the row).
     */
    suspend fun delete(ctx: Context, db: LibraryDb, gameId: Long): Outcome =
        PrepPipeline.withGameLock(gameId) {
            try {
                val game = db.games().findById(gameId) ?: return@withGameLock Outcome.Failed("Game not in library.")
                val root = RomStore.volumeRoots(ctx).firstOrNull { it.first == game.volumeId }?.second
                    ?: return@withGameLock Outcome.Failed("Its storage isn't mounted — insert the SD card first.")
                val plan = plan(root, game.relPath)
                    ?: return@withGameLock Outcome.Failed("Refusing to delete: file path is outside the library.")
                val bytes = plan.bytes
                if (!execute(plan)) {
                    return@withGameLock Outcome.Failed("Couldn't delete ${game.fileName} — check file access.")
                }
                runCatching { File(File(ctx.filesDir, "art"), gameId.toString()).deleteRecursively() }
                runCatching { db.enrich().remove(gameId) }
                finishRow(db, game, System.currentTimeMillis())
                Outcome.Deleted(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { Log.w(TAG, "delete $gameId failed", e) }
                Outcome.Failed("Delete failed: ${e.message ?: "unknown error"}")
            }
        }

    /**
     * The row after the files are gone (unit-tested): a source
     * game keeps its row as a tombstone for "Download again" (saves, play
     * counts and provenance survive); an imported game loses its row.
     */
    internal suspend fun finishRow(db: LibraryDb, game: GameEntity, now: Long) {
        if (game.sourceRefJson != null) db.games().markDeleted(game.id, now)
        else db.games().deleteById(game.id)
    }

    private fun canon(f: File): String? = runCatching { f.canonicalPath }.getOrNull()

    private fun isInside(path: String, dir: String): Boolean =
        path.startsWith(dir + File.separator)

    private fun dirBytes(d: File): Long =
        runCatching { d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
}
