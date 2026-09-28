package com.gamebex.client.net

import android.content.Context
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.ui.presentation.LocalLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Play when ready" arming: a download the player wants to
 * play the moment AutoPrep reports the game READY. App-scoped and
 * in-memory; armed ids are download ids, mapped to games through the
 * download queue row (same lookup as [DownloadEngine.gameIdForDownload]).
 *
 * Auto-launch only happens while GameBEX is in the foreground ([foreground]
 * is set from MainActivity's lifecycle); otherwise the ready notification
 * posts. An arm dies with its download's first READY — launched
 * or not — and on the download's cancel or failure, so a leftover arm can
 * never auto-launch on a later READY.
 */
object PlayWhenReady {
    private val _armed = MutableStateFlow<Set<Long>>(emptySet())

    /** Serializes claim's resolve-then-remove; concurrent READYs launch once. */
    private val claimMutex = Mutex()

    /** Download ids armed for Play when ready. */
    val armed: StateFlow<Set<Long>> = _armed

    /** GameBEX is in the foreground (set from MainActivity). */
    @Volatile
    var foreground: Boolean = false

    /** Arms [downloadId] when it isn't armed, disarms it otherwise. */
    fun toggle(downloadId: Long) {
        _armed.update { if (downloadId in it) it - downloadId else it + downloadId }
    }

    /** Disarms [downloadId] (its download was cancelled or failed). */
    fun disarm(downloadId: Long) {
        _armed.update { it - downloadId }
    }

    /**
     * The armed download that should launch [gameId] right now, or null.
     * Resolve-then-remove runs under [claimMutex], so concurrent claims
     * consume the arm exactly once. The winner is disarmed on every READY
     * of its download — launched or not — and only the foreground returns
     * it (the caller posts the ready notification otherwise). Pure
     * decision + armed-set mutation — unit-tested.
     */
    suspend fun claim(gameId: Long, resolveGameId: suspend (Long) -> Long?): Long? =
        claimMutex.withLock {
            val winner = _armed.value.firstOrNull { resolveGameId(it) == gameId }
            if (winner == null) return@withLock null
            _armed.update { it - winner }
            winner.takeIf { foreground }
        }

    /**
     * Drops arms whose downloads are settled for good: cancelled (queue
     * row gone) or failed. A leftover arm must never auto-launch on a
     * later READY. Unit-tested.
     */
    suspend fun dropSettled(db: LibraryDb) {
        val settled = _armed.value.filter { id ->
            val rec = db.downloads().getById(id)
            rec == null || rec.status == DownloadEngine.ST_FAILED
        }
        if (settled.isNotEmpty()) _armed.update { it - settled.toSet() }
    }

    /** The library game a finished download landed on (null until ingest). */
    internal suspend fun gameForDownload(db: LibraryDb, downloadId: Long): Long? {
        val rec = db.downloads().getById(downloadId) ?: return null
        return db.games().findByPath(rec.volumeId, rec.destRelPath)?.id
    }

    /**
     * AutoPrep's ready hook: launch [gameId] when an armed download claims
     * it and return true; false lets the caller post the ready
     * notification instead.
     */
    suspend fun onReady(ctx: Context, db: LibraryDb, gameId: Long): Boolean {
        runCatching { dropSettled(db) }
        claim(gameId) { gameForDownload(db, it) } ?: return false
        LocalLauncher.play(ctx, gameId) {}
        return true
    }
}
