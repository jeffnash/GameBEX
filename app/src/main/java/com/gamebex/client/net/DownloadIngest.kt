package com.gamebex.client.net

import android.content.Context
import com.gamebex.client.data.local.AutoPrep
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.EnrichItem
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.LibraryScanner
import com.gamebex.client.data.local.PrepPipeline
import com.gamebex.source.DownloadFile
import com.gamebex.source.DownloadProvenance
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Finished-download publication + library attach, extracted from
 * DownloadEngine. Owns the staged-publish protocol (previous valid file
 * survives until the new bytes validate) and the durable ingest that
 * attaches the published file to the library. Explicit deps only.
 */
class DownloadIngest(
    private val db: LibraryDb,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val destDirOf: (DownloadRecord) -> File?,
) {
    /**
     * Seed BIOS, then prepare in the background: a landed disc archive is
     * playable without a trip to Detail, and a READY result launches the
     * game when Play when ready is armed for it, else posts
     * the "ready to play" notification. Idempotent (prep marker + per-game
     * lock); never blocks ingest.
     */
    private val autoPrep by lazy {
        AutoPrep(
            scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
            findGame = { db.games().findById(it) },
            prepare = { PrepPipeline.prepare(appContext, db, it).state },
            onReady = { game ->
                scope.launch {
                    // runCatching: a failed launch falls back to the ready
                    // notification; the READY is never lost.
                    val launched = runCatching {
                        PlayWhenReady.onReady(appContext, db, game.id)
                    }.getOrDefault(false)
                    if (!launched) {
                        ReadyNotifier.post(appContext, game.id, game.title)
                    }
                }
            },
        )
    }

    init {
        // An arm dies with its download — a cancelled (queue
        // row gone) or failed download must never auto-launch later. The
        // queue's change stream is the owned view of cancel/failure.
        scope.launch {
            db.downloads().observeRecent(ARM_WATCH_LIMIT).collect { reconcileArms(it) }
        }
    }

    /** Disarms armed ids whose download is gone (cancelled) or failed. */
    private suspend fun reconcileArms(recent: List<DownloadRecord>) {
        val byId = recent.associateBy { it.id }
        for (id in PlayWhenReady.armed.value) {
            val rec = byId[id] ?: runCatching { db.downloads().getById(id) }.getOrNull()
            if (rec == null || rec.status == DownloadEngine.ST_FAILED) PlayWhenReady.disarm(id)
        }
    }

    /** Durable library attach for a published file. Null = retry later. */
    suspend fun ingest(rec: DownloadRecord): Long? {
        val dir = destDirOf(rec) ?: return null
        val final = File(dir, rec.fileName)
        if (!final.isFile) return null
        if (final.length() == 0L) {
            // A published empty file can never attach to the
            // library — fail it for good with the empty-file message
            // instead of parking it in the library-retry state forever.
            // Manual Resume re-downloads; if the file lands empty again,
            // this same failure is what the user sees.
            runCatching {
                db.downloads().update(
                    rec.copy(
                        status = DownloadEngine.ST_FAILED,
                        error = EMPTY_FILE_ERROR,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
            return null
        }
        val file = rec.sourceFileJson?.let {
            runCatching { json.decodeFromString(DownloadFile.serializer(), it) }.getOrNull()
        }?.takeIf { rec.sourceItemKey != null && it.ref.itemKey == rec.sourceItemKey }
        val gameId = runCatching {
            LibraryScanner.ingestDownload(
                appContext, db, final, rec.title, rec.consoleSlug,
                rec.description, rec.coverUrl,
                DownloadProvenance(ref = file?.ref, variant = file),
            )
        }.getOrNull() ?: return null
        if (gameId > 0) {
            // Known cover: the row already carries coverUrl, and the
            // enrich queue below lets ArtWorker fetch it through the
            // owning source's pinned revision + artwork policy —
            // scheduled immediately, never behind an unrelated search.
            runCatching {
                db.enrich().enqueue(
                    EnrichItem(gameId = gameId, updatedAtMs = System.currentTimeMillis()),
                )
            }
            ArtWorker.kick(appContext)
            // First download per console seeds that console's BIOS in
            // the background, so Play never waits on firmware; prep runs
            // right after so it sees the seeded files.
            autoPrep.kick(gameId) { BiosFetcher.seedForGame(appContext, db, gameId) }
        }
        return gameId.takeIf { it > 0 }
    }

    companion object {
        private const val ARM_WATCH_LIMIT = 100
        private const val EMPTY_FILE_ERROR = "This file is empty. Download it again."
        private val json = Json { ignoreUnknownKeys = true }
        /**
         * Staged publish: moves [part] ([read] bytes) over
         * `dir/fileName`, keeping the previous file until the new bytes
         * validate. Returns the published file, or null (best-effort
         * restore already attempted). Pure file protocol — unit-tested.
         */
        fun publishStaged(part: File, dir: File, fileName: String, read: Long): File? {
            val final = File(dir, fileName)
            val tmp = File(dir, "$fileName.tmp")
            runCatching { tmp.delete() }
            val stagedBytes = when {
                part.renameTo(tmp) -> tmp.length()
                else -> runCatching {
                    part.copyTo(tmp, overwrite = true)
                    tmp.length()
                }.getOrDefault(-1L)
            }
            if (stagedBytes != read) {
                runCatching { tmp.delete() }
                return null
            }
            runCatching { part.delete() } // copyTo path leaves the source behind
            val bak = File(dir, "$fileName.bak")
            runCatching { bak.delete() }
            val hadFinal = final.exists()
            if (hadFinal && !final.renameTo(bak)) {
                runCatching { tmp.renameTo(part) }
                return null
            }
            if (!tmp.renameTo(final)) {
                if (hadFinal) runCatching { bak.renameTo(final) } // restore previous
                runCatching { tmp.renameTo(part) }
                return null
            }
            runCatching { bak.delete() }
            if (!final.exists() || final.length() != read) return null
            return final
        }
    }
}
