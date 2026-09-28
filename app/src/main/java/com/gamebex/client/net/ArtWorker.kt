package com.gamebex.client.net

import android.content.Context
import android.util.Log
import com.gamebex.client.data.local.ArtStore
import com.gamebex.client.data.local.EnrichItem
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.RomStore
import com.gamebex.client.data.local.Sidecars
import com.gamebex.client.data.local.matchSourceEntry
import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Art worker: drains the enrich queue serially (ScreenScraper allows
 * ~1 request/sec). Per game: lookup (skipped when the sidecar already
 * pins the SS id) -> download the 4 slots as raw jobs -> validate
 * image magic -> record paths + sidecar id.
 *
 * Headless: status flow + refreshNow() are the harness seam (LocalDetail
 * art section).
 *
 * Slot safety: user-owned slots (ArtStore markers) and already-populated
 * slots are never overwritten — ownership is checked before any download
 * is queued, re-checked before a staged fetch replaces the slot file, and
 * fetches land in staging files, never directly in the slot.
 */
class ArtWorker(
    private val appContext: Context,
    private val engine: DownloadEngine,
    private val db: LibraryDb,
    private val ss: ScreenScraper = ScreenScraper.withOverrides(appContext),
    val sources: com.gamebex.client.sources.SourceRegistry,
) {
    companion object {
        private const val TAG = "GameBEXArt"

        /** Wake the worker (idempotent). Safe to call from anywhere. */
        fun kick(context: Context) {
            val app = context.applicationContext as? com.gamebex.client.GameBexApp ?: return
            app.art.kick()
        }

        /**
         * Tombstoned games never enqueue or fetch art: the game is
         * off the phone and its row is a "Download again" placeholder.
         */
        fun wantsArt(game: GameEntity): Boolean = game.deletedAtMs <= 0

        /**
         * Job states where the worker must stop waiting: terminal,
         * suspended, intervention, or gone. Waiting only for DONE/FAILED
         * stalls the serial queue forever on paused/parked/needs-assist.
         */
        fun isSettledStatus(status: String?): Boolean = status == null || status in setOf(
            DownloadEngine.ST_DONE, DownloadEngine.ST_FAILED,
            DownloadEngine.ST_PAUSED, DownloadEngine.ST_PARKED_SD,
            DownloadEngine.ST_NEEDS_ASSIST, DownloadEngine.ST_INGEST,
        )

        /** Suspended/intervention states: retryable later, never terminal. */
        fun isSuspendedStatus(status: String): Boolean = status in setOf(
            DownloadEngine.ST_PAUSED, DownloadEngine.ST_PARKED_SD,
            DownloadEngine.ST_NEEDS_ASSIST, DownloadEngine.ST_INGEST,
        )

        /**
         * True when auto-fetch may fill the slot: never when user-owned,
         * never when the row already points at a live file. Checked before
         * queueing a download and again before replacing the slot file.
         */
        fun shouldFetchSlot(existingPath: String?, fileExists: Boolean, userOwned: Boolean): Boolean {
            if (userOwned) return false
            if (existingPath != null && fileExists) return false
            return true
        }

        fun rowPaths(game: GameEntity): Map<String, String?> = mapOf(
            ArtStore.SLOT_BOX to game.artBox,
            ArtStore.SLOT_HERO to game.artHero,
            ArtStore.SLOT_LOGO to game.artLogo,
            ArtStore.SLOT_SHOT to game.artShot,
        )

        /** Slots whose row path is absent or dangling (per-slot completion state). */
        fun missingSlots(rowPaths: Map<String, String?>, fileExists: (String) -> Boolean): Set<String> =
            rowPaths.filter { (_, path) -> path == null || !fileExists(path) }.keys

        /** Auto-fetched provenance: ScreenScraper plus per-source labels. Pure; tested. */
        fun isAutoSource(artSource: String): Boolean =
            artSource == "SS" || artSource.startsWith("SOURCE:")

        /** ACTIVE with no byte progress this long aborts the cover wait. */
        const val COVER_STALL_MS = 120_000L

        /** Absolute bound on one cover wait, whatever the engine does. */
        const val COVER_WAIT_MAX_MS = 30 * 60_000L

        /**
         * Pure stall policy: settled (or vanished) returns; QUEUED time is
         * free and byte progress resets the clock, so a cover parked
         * behind long ROM transfers survives until a slot opens; only an
         * ACTIVE transfer frozen past the budget aborts.
         */
        fun coverWaitStep(
            status: String?,
            read: Long,
            lastRead: Long,
            stalledMs: Long,
        ): CoverWait {
            if (isSettledStatus(status)) return CoverWait.RETURN_SETTLED
            if (status == DownloadEngine.ST_QUEUED) return CoverWait.RESET_CLOCK
            if (read != lastRead) return CoverWait.RESET_CLOCK
            return if (stalledMs >= COVER_STALL_MS) CoverWait.ABORT_STALLED
            else CoverWait.KEEP_WAITING
        }
    }

    /** Per-slot fetch result: partial success keeps the queue item for retry. */
    private data class SlotOutcome(val got: Map<String, String>, val wanted: Set<String>) {
        val complete: Boolean get() = wanted.isNotEmpty() && got.keys.containsAll(wanted)
    }

    /** Cover-wait verdict for one engine snapshot while awaiting an art job. */
    enum class CoverWait { RETURN_SETTLED, RESET_CLOCK, KEEP_WAITING, ABORT_STALLED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var running = false

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    /**
     * Games parked on UNAVAILABLE: their enrich rows stay pending but the
     * drain skips them without burning attempts until the source set or
     * selection changes.
     */
    private val unavailableHold = mutableSetOf<Long>()

    init {
        scope.launch(Dispatchers.IO) {
            // Wake held items on install/enable/selection change; the
            // first emission is the startup state, not a change.
            combine(sources.list(), sources.selected) { list, sel ->
                list.map { it.id to it.revision to it.enabled } to sel?.id
            }.distinctUntilChanged().drop(1).collect {
                val held = synchronized(unavailableHold) {
                    unavailableHold.isNotEmpty().also { unavailableHold.clear() }
                }
                if (held) kick()
            }
        }
    }

    fun kick() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (running) return@withLock
                running = true
            }
            try {
                drain()
            } finally {
                mutex.withLock { running = false }
            }
        }
    }

    /**
     * Manual "Refresh art": clear auto-fetched slots (source/SS only —
     * USER and MIXED rows keep the user's own images), reset attempts,
     * run now. USER/MIXED rows still requeue, but the slot guards below
     * only fill empty slots, never touching populated ones.
     */
    fun refreshNow(gameId: Long) {
        scope.launch(Dispatchers.IO) {
            val g = db.games().findById(gameId)
            if (g != null && !wantsArt(g)) return@launch
            if (g != null && (isAutoSource(g.artSource) || g.artSource == "NONE")) {
                runCatching {
                    ArtStore.dirFor(appContext, g.id).listFiles()?.forEach { it.delete() }
                }
                db.games().setArtSlots(g.id, null, null, null, null, "NONE", 0)
            }
            db.enrich().enqueue(EnrichItem(gameId = gameId, updatedAtMs = System.currentTimeMillis()))
            db.enrich().reset(gameId, System.currentTimeMillis())
            kick()
        }
    }

    /**
     * Startup repair: requeue art-less rows (past misses get a bounded
     * retry via the attempts budget; IGNORE keeps existing attempts) plus
     * rows with per-slot gaps from partial fetches, so missing slots retry
     * instead of sticking forever on a MIXED/SS row.
     */
    fun repairQueue() {
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            db.games().idsMissingArt().forEach {
                db.enrich().enqueue(EnrichItem(gameId = it, updatedAtMs = now))
            }
            runCatching {
                db.games().observeAll().first()
                    .filter { it.artSource != "NONE" }
                    .filter { wantsArt(it) }
                    .filter { missingSlots(rowPaths(it)) { p -> File(p).isFile }.isNotEmpty() }
                    .forEach { db.enrich().enqueue(EnrichItem(gameId = it.id, updatedAtMs = now)) }
            }
            kick()
        }
    }

    /** Source cover outcome: hit, terminal miss, transient retry, or source gone. */
    enum class SourceArtResult { HIT, MISS, RETRY, UNAVAILABLE }

    /** Staged-fetch verdict before slot ownership is re-checked. */
    private enum class Fetch { HIT, MISS, RETRY, UNAVAILABLE, NEXT }

    /**
     * Source cover fallback: the owning source's cover (stored URL first,
     * else a platform-scoped search) into box+hero — empty slots only,
     * never clobbers SS/USER art.
     *
     * Selection: a game with sourceId uses that installed source only
     * (absent → UNAVAILABLE, no provider hopping); an imported game
     * without sourceId uses the selected source, if one exists.
     */
    private suspend fun sourceCover(game: GameEntity): SourceArtResult {
        val dir = ArtStore.dirFor(appContext, game.id)
        // Ownership first: never queue a download at a populated slot.
        val cur0 = db.games().findById(game.id)
        val boxFile = File(dir, "${ArtStore.SLOT_BOX}.jpg")
        if (cur0 != null && !shouldFetchSlot(
                cur0.artBox, boxFile.isFile && boxFile.length() > 0,
                ArtStore.isUserOwned(dir, ArtStore.SLOT_BOX),
            )
        ) {
            // Slot already satisfied: backfill hero from the same art.
            val boxPath = listOfNotNull(cur0.artBox?.takeIf { File(it).isFile }, boxFile.takeIf { it.isFile }?.absolutePath).firstOrNull()
            if (boxPath != null && cur0.artHero == null) {
                db.games().setArtHero(cur0.id, boxPath)
            }
            return SourceArtResult.HIT
        }
        val sourceId = game.sourceId ?: sources.selected.value?.id
            ?: return SourceArtResult.UNAVAILABLE
        val source = try {
            sources.open(sourceId)
        } catch (e: SourceException) {
            return if (e.failure == SourceFailure.UNAVAILABLE) SourceArtResult.UNAVAILABLE
            else SourceArtResult.RETRY
        } catch (_: Exception) {
            return SourceArtResult.RETRY
        }
        // Immediate post-ingest fill: the stored cover wins over a search.
        game.coverUrl?.takeIf { it.isNotBlank() }?.let { stored ->
            val policyOk = runCatching {
                sources.httpFor(sourceId, source.revision).checkArtworkUrl(stored)
            }.isSuccess
            if (policyOk) {
                when (fetchSourceCover(game, sourceId, source.revision, stored, dir, boxFile)) {
                    Fetch.HIT -> return SourceArtResult.HIT
                    Fetch.UNAVAILABLE -> return SourceArtResult.UNAVAILABLE
                    Fetch.NEXT, Fetch.MISS, Fetch.RETRY -> {}
                }
            }
        }
        kotlinx.coroutines.delay(1000) // politeness between catalog searches
        val q = ss.cleanTitle(game.title)
        if (q.isBlank()) return SourceArtResult.MISS
        val hits = try {
            // Platform-scoped search: the catalog ranks cross-platform
            // hits above the right one (GB Tetris hides behind MAME Tetris).
            source.search(q, game.folder).items
        } catch (e: SourceException) {
            return if (e.failure == SourceFailure.UNAVAILABLE) SourceArtResult.UNAVAILABLE
            else SourceArtResult.RETRY
        } catch (_: Exception) {
            return SourceArtResult.RETRY
        }
        Log.d(TAG, "source '$q' -> " + hits.take(8).joinToString("; ") { "${it.title}|${it.platformId}" })
        val url = matchSourceEntry(game.title, game.folder, hits)?.coverUrl
            ?: return SourceArtResult.MISS
        return when (fetchSourceCover(game, sourceId, source.revision, url, dir, boxFile)) {
            Fetch.HIT -> SourceArtResult.HIT
            // Done-but-unusable is terminal; anything else may retry later.
            Fetch.MISS, Fetch.NEXT -> SourceArtResult.MISS
            Fetch.UNAVAILABLE -> SourceArtResult.UNAVAILABLE
            Fetch.RETRY -> SourceArtResult.RETRY
        }
    }

    /**
     * One pinned-revision ART transfer into the box slot (+hero backfill).
     * NEXT means "try the next candidate" (stored URL fell through).
     */
    private suspend fun fetchSourceCover(
        game: GameEntity,
        sourceId: String,
        revision: String,
        url: String,
        dir: File,
        boxFile: File,
    ): Fetch {
        val staged = ArtStore.stageFileFor(dir, ArtStore.SLOT_BOX)
        val jobId = try {
            engine.enqueueSourceArt(
                sourceId = sourceId, revision = revision, url = url,
                gameId = game.id, slot = ArtStore.SLOT_BOX,
                destDir = dir, fileName = staged.name,
            )
        } catch (e: SourceException) {
            return if (e.failure == SourceFailure.UNAVAILABLE) Fetch.UNAVAILABLE else Fetch.RETRY
        } catch (_: Exception) {
            return Fetch.RETRY
        }
        val terminal = awaitJob(jobId)
        if (terminal == DownloadEngine.ST_SOURCE_UNAVAILABLE) {
            runCatching { staged.delete() }
            return Fetch.UNAVAILABLE
        }
        if (isSuspendedStatus(terminal)) runCatching { engine.cancel(jobId) }
        if (terminal != DownloadEngine.ST_DONE || !ArtStore.isValidImageFile(staged)) {
            runCatching { staged.delete() }
            return if (terminal == DownloadEngine.ST_DONE) Fetch.NEXT else Fetch.RETRY
        }
        if (ArtStore.isUserOwned(dir, ArtStore.SLOT_BOX) ||
            !ArtStore.moveFile(staged, boxFile)
        ) {
            runCatching { staged.delete() }
            return Fetch.HIT // user claimed the slot mid-flight; consume
        }
        val cur = db.games().findById(game.id) ?: return Fetch.HIT // gone; consume
        val hadArt = listOf(cur.artBox, cur.artHero, cur.artLogo, cur.artShot).any { it != null }
        db.games().setArtSlots(
            cur.id, cur.artBox ?: boxFile.absolutePath, cur.artHero ?: boxFile.absolutePath,
            cur.artLogo, cur.artShot, if (hadArt) "MIXED" else "SOURCE:$sourceId", cur.ssGameId,
        )
        Log.d(TAG, "game ${game.id} cover from $sourceId")
        return Fetch.HIT
    }

    private suspend fun drain() {
        Log.i(TAG, "drain start, queued=${runCatching { db.enrich().count() }.getOrNull()}")
        // Single pass per kick: each item burns at most one attempt, so the
        // budget of 3 spans launches instead of one long first drain.
        val seen = mutableSetOf<Long>()
        while (true) {
            val item = try {
                db.enrich().next()
            } catch (e: Exception) {
                Log.w(TAG, "queue read failed; parking drain", e)
                break
            } ?: break
            Log.i(TAG, "drain item game=${item.gameId} attempts=${item.attempts}")
            if (!seen.add(item.gameId)) break
            // Held on UNAVAILABLE: row stays pending, no attempt burned.
            if (synchronized(unavailableHold) { item.gameId in unavailableHold }) continue
            val game = db.games().findById(item.gameId)
            if (game == null) {
                db.enrich().remove(item.gameId)
                continue
            }
            if (!wantsArt(game)) {
                // Tombstoned game: its art work is pointless now.
                db.enrich().remove(item.gameId)
                continue
            }
            _status.value = "Fetching art for ${game.title}…"
            // No early NoCreds break: lookup() reports it per item and the
            // GameBEX fallback needs no credentials at all.
            val creds = ScreenScraper.loadCreds(appContext)
            // Fast path: sidecar/row already pins the SS id (rebuilds).
            val info = if (game.ssGameId > 0) {
                ss.infos(game.ssGameId, creds)?.let { ScreenScraper.Lookup.Found(it) }
                    ?: ScreenScraper.Lookup.Unavailable("infos failed")
            } else {
                // Checksum identity first (cached per game; hashed on IO,
                // capped for huge disc images); name search is the fallback.
                val fp = if (creds.complete()) romFingerprint(game) else null
                ss.lookup(game.title, game.folder, creds, fp)
            }
            when (info) {
                is ScreenScraper.Lookup.Found -> {
                    val out = fetchSlots(game.id, info.info)
                    if (out.wanted.isEmpty() || out.complete) {
                        db.enrich().remove(item.gameId)
                    } else {
                        // Partial: keep the item so missing slots retry.
                        db.enrich().noteFailure(
                            item.gameId, "partial art (${out.got.size}/${out.wanted.size})",
                            System.currentTimeMillis(),
                        )
                    }
                }
                is ScreenScraper.Lookup.NotFound -> {
                    // Misses fall through to the source cover (hacks and
                    // homebrew live there even when SS has never heard of
                    // them). Attempts-bounded: parks after 3 rounds.
                    Log.d(TAG, "SS has no '${game.title}' (searched '${info.searched}')")
                    handleSourceCover(item.gameId, sourceCover(game))
                }
                is ScreenScraper.Lookup.NoCreds -> {
                    // No SS login: source covers still flow (no creds needed).
                    _status.value = "Add your ScreenScraper login for full art."
                    handleSourceCover(item.gameId, sourceCover(game))
                }
                is ScreenScraper.Lookup.Unavailable -> {
                    // SS down: the source host may still answer.
                    when (sourceCover(game)) {
                        SourceArtResult.HIT -> db.enrich().remove(item.gameId)
                        SourceArtResult.UNAVAILABLE -> {
                            holdUnavailable(item.gameId)
                            break
                        }
                        else -> {
                            db.enrich().noteFailure(item.gameId, info.reason, System.currentTimeMillis())
                            _status.value = "Art paused: ${info.reason}."
                            Log.d(TAG, "SS unavailable (${info.reason}); queue parked")
                            break
                        }
                    }
                }
            }
        }
        if (db.enrich().count() == 0) _status.value = null
    }

    /** Cached-or-computed CRC fingerprint of the game's ROM; null when unavailable. */
    private suspend fun romFingerprint(game: GameEntity): RomFingerprint? {
        val rom = RomStore.resolveFile(appContext, game.volumeId, game.relPath)
            ?.takeIf { it.isFile } ?: return null
        return try {
            RomHash.cached(appContext, game.id, rom)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "rom hash failed for game ${game.id}: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Source-cover verdict accounting. One source's transient failure never
     * stalls other queues (no drain break); UNAVAILABLE holds the item
     * without burning the attempt budget.
     */
    private suspend fun handleSourceCover(gameId: Long, result: SourceArtResult) {
        when (result) {
            SourceArtResult.HIT -> db.enrich().remove(gameId)
            SourceArtResult.MISS -> db.enrich().noteFailure(
                gameId, "no art match", System.currentTimeMillis(),
            )
            SourceArtResult.RETRY -> db.enrich().noteFailure(
                gameId, "art offline", System.currentTimeMillis(),
            )
            SourceArtResult.UNAVAILABLE -> holdUnavailable(gameId)
        }
    }

    private fun holdUnavailable(gameId: Long) {
        synchronized(unavailableHold) { unavailableHold.add(gameId) }
        Log.d(TAG, "game $gameId art held: source unavailable")
    }

    private suspend fun fetchSlots(gameId: Long, info: ScreenScraper.GameInfo): SlotOutcome {
        val urls = ss.pickSlots(info.medias)
        if (urls.isEmpty()) {
            Log.d(TAG, "SS game ${info.ssId} has no usable media")
            return SlotOutcome(emptyMap(), emptySet())
        }
        val dir = ArtStore.dirFor(appContext, gameId)
        val got = mutableMapOf<String, String>()
        for ((slot, url) in urls) {
            val final = File(dir, "$slot.jpg")
            // Ownership BEFORE writing: skip user-owned/populated slots.
            val row = db.games().findById(gameId)
            val existing = row?.let { rowPaths(it)[slot] }
            if (row != null && !shouldFetchSlot(
                    existing, final.isFile && final.length() > 0,
                    ArtStore.isUserOwned(dir, slot),
                )
            ) {
                // Already satisfied: counts toward completion, no download.
                val live = listOfNotNull(
                    existing?.takeIf { File(it).isFile },
                    final.takeIf { it.isFile && it.length() > 0 }?.absolutePath,
                ).firstOrNull()
                if (live != null) got[slot] = live
                continue
            }
            val staged = ArtStore.stageFileFor(dir, slot)
            val jobId = engine.enqueue(
                pageUrl = "ss://${info.ssId}/$slot", title = "${info.title} [$slot]",
                fileName = staged.name, platformFolder = null,
                directUrl = url, destDirAbs = dir,
            )
            val terminal = awaitJob(jobId)
            if (isSuspendedStatus(terminal)) runCatching { engine.cancel(jobId) }
            if (terminal == DownloadEngine.ST_DONE && ArtStore.isValidImageFile(staged) &&
                !ArtStore.isUserOwned(dir, slot) && ArtStore.moveFile(staged, final)
            ) {
                got[slot] = final.absolutePath
            } else {
                runCatching { staged.delete() }
                Log.d(TAG, "slot $slot for game $gameId unusable ($terminal), kept for retry")
            }
        }
        if (got.isEmpty()) return SlotOutcome(got, urls.keys)
        val game = db.games().findById(gameId) ?: return SlotOutcome(got, urls.keys)
        val mixed = game.artSource == "USER" || game.artSource == "MIXED" ||
            game.artSource.startsWith("SOURCE:")
        db.games().setArtSlots(
            game.id,
            got[ArtStore.SLOT_BOX] ?: game.artBox,
            got[ArtStore.SLOT_HERO] ?: game.artHero,
            got[ArtStore.SLOT_LOGO] ?: game.artLogo,
            got[ArtStore.SLOT_SHOT] ?: game.artShot,
            if (mixed) "MIXED" else "SS",
            info.ssId,
        )
        // Pin the id in the sidecar so rebuilds skip the search.
        runCatching {
            // User game folders are read-only (no sidecar writes there).
            val rom = RomStore.resolveFile(appContext, game.volumeId, game.relPath)
                ?.takeUnless { RomStore.isUserVolume(game.volumeId) }
            if (rom != null) {
                val sc = Sidecars.read(rom)
                if (sc != null) Sidecars.write(rom, sc.copy(ssGameId = info.ssId))
            }
        }
        Log.d(TAG, "game $gameId art: ${got.keys} (ss ${info.ssId})")
        return SlotOutcome(got, urls.keys)
    }

    private suspend fun awaitJob(jobId: Long): String {
        // Stall-based wait: queue time is free (covers legitimately wait
        // behind ROM transfers), ACTIVE without byte progress aborts after
        // COVER_STALL_MS, and an absolute cap bounds a silent engine.
        val startMs = System.currentTimeMillis()
        var last: DownloadEngine.DlSnapshot? = null
        var lastRead = -1L
        var stallStartMs = startMs
        while (true) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - startMs >= COVER_WAIT_MAX_MS) {
                Log.w(TAG, "cover job $jobId exceeded max wait; cancelling")
                runCatching { engine.cancel(jobId) }
                return DownloadEngine.ST_FAILED
            }
            // Bounded leg: re-check the stall budget even when the engine
            // goes quiet (no emission must not wedge the drain).
            val remaining = COVER_WAIT_MAX_MS - (nowMs - startMs)
            val emission = kotlinx.coroutines.withTimeoutOrNull(minOf(remaining, COVER_STALL_MS)) {
                engine.jobs.first { it[jobId] != last }
            }
            // Leg timeout re-evaluates the last snapshot; a real emission
            // with the entry gone means cancelled away (settled).
            val cur = if (emission == null) last else emission[jobId]
            val atMs = System.currentTimeMillis()
            val read = cur?.read ?: -1L
            when (coverWaitStep(cur?.status, read, lastRead, atMs - stallStartMs)) {
                CoverWait.RETURN_SETTLED -> return cur?.status ?: DownloadEngine.ST_FAILED
                CoverWait.ABORT_STALLED -> {
                    Log.w(TAG, "cover job $jobId stalled while ${cur?.status}; cancelling")
                    runCatching { engine.cancel(jobId) }
                    return DownloadEngine.ST_FAILED
                }
                CoverWait.RESET_CLOCK -> {
                    stallStartMs = atMs
                    lastRead = read
                    last = cur
                }
                CoverWait.KEEP_WAITING -> {
                    last = cur
                }
            }
        }
    }
}
