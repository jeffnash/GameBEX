package com.gamebex.client.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Live game-prep progress. Extraction and the publish copy after it can run
 * for minutes on a 1 GB disc image, so the UI shows bytes and percent to tell
 * slow from hung.
 *
 * [PrepProgressBus] is the in-memory rendezvous: [PrepPipeline] publishes
 * (always off the UI thread) and Home / game page / Downloads collect.
 * Progress never touches the DB, so a finished or dead prep has no entry
 * and stale bars can't survive a restart.
 */
enum class PrepPhase { EXTRACTING, COPYING, FIXING, FINISHING }

val PrepPhase.label: String get() = when (this) {
    PrepPhase.EXTRACTING -> "Extracting archive"
    PrepPhase.COPYING -> "Copying files"
    PrepPhase.FIXING -> "Fixing disc image"
    PrepPhase.FINISHING -> "Finishing"
}

/** One in-flight prep. [totalBytes] null = the archive declares no sizes. */
data class PrepProgress(
    val gameId: Long,
    val phase: PrepPhase,
    val bytesWritten: Long,
    val totalBytes: Long?,
    val startedMs: Long,
    /** Wall clock of the last byte movement (stall detection). */
    val updatedMs: Long,
) {
    /**
     * One live line for the game page and Downloads rows:
     * "Extracting archive… 312 of 996 MB · 31%", or "Still
     * working… 312 of 996 MB" after a 20s stall.
     */
    fun displayLine(nowMs: Long): String {
        val pair = when {
            totalBytes != null && totalBytes > 0 -> {
                val p = PrepProgressBus.percentOf(this)
                "${bytePair(bytesWritten, totalBytes)} · ${p ?: 0}%"
            }
            else -> formatBytes(bytesWritten)
        }
        if (PrepProgressBus.isStalled(this, nowMs)) return "Still working… $pair"
        return "${phase.label}… $pair"
    }

    companion object {
        /** "312 MB", "1.4 GB" — unit-aware, one decimal over GB. */
        fun formatBytes(b: Long): String = when {
            b < 0 -> "?"
            b >= 1024L * 1024 * 1024 ->
                String.format(java.util.Locale.ENGLISH, "%.1f GB", b.toDouble() / (1024 * 1024 * 1024))
            b >= 1024L * 1024 -> "${b / (1024 * 1024)} MB"
            b >= 1024L -> "${b / 1024} KB"
            else -> "$b B"
        }

        /** "312 of 996 MB" — unit written once, after the total. */
        fun bytePair(read: Long, total: Long): String {
            val r = read.coerceAtLeast(0)
            return when {
                total >= 1024L * 1024 * 1024 ->
                    "${formatBytes(r).removeSuffix(" GB")} of ${formatBytes(total).removeSuffix(" GB")} GB"
                total >= 1024L * 1024 -> "${r / (1024 * 1024)} of ${total / (1024 * 1024)} MB"
                total >= 1024L -> "${r / 1024} of ${total / 1024} KB"
                else -> "$r of $total B"
            }
        }
    }
}

object PrepProgressBus {
    /** No byte movement for this long = "Still working…", not dead. */
    const val STILL_WORKING_MS: Long = 20_000L

    /** Fastest UI refresh: extract reports per 64 KB chunk, the UI needs ~5 Hz. */
    const val EMIT_THROTTLE_MS: Long = 200L

    private val _byGame = MutableStateFlow<Map<Long, PrepProgress>>(emptyMap())
    val byGame: StateFlow<Map<Long, PrepProgress>> = _byGame

    /** Download job id → game id, so Downloads rows find their prep. */
    private val _downloadLink = MutableStateFlow<Map<Long, Long>>(emptyMap())

    private val lastEmitMs = HashMap<Long, Long>()

    /** Whole-number percent, or null when the total is unknown. */
    fun percentOf(p: PrepProgress): Int? {
        val total = p.totalBytes?.takeIf { it > 0 } ?: return null
        return ((p.bytesWritten.coerceAtLeast(0).toDouble() / total) * 100).toInt().coerceIn(0, 100)
    }

    fun isStalled(p: PrepProgress, nowMs: Long): Boolean =
        nowMs - p.updatedMs >= STILL_WORKING_MS

    fun begin(gameId: Long, phase: PrepPhase, totalBytes: Long?, nowMs: Long) {
        if (gameId <= 0) return
        lastEmitMs[gameId] = nowMs
        _byGame.update {
            it + (gameId to PrepProgress(gameId, phase, 0, totalBytes, nowMs, nowMs))
        }
    }

    /** Phase-only hop (cue/gdi fixes, validation): bytes stay, clock moves. */
    fun phase(gameId: Long, phase: PrepPhase, nowMs: Long) {
        _byGame.update { cur ->
            val p = cur[gameId] ?: return@update cur
            cur + (gameId to p.copy(phase = phase, updatedMs = nowMs))
        }
        lastEmitMs[gameId] = nowMs
    }

    fun progress(gameId: Long, phase: PrepPhase, written: Long, total: Long?, nowMs: Long) {
        val last = lastEmitMs[gameId] ?: 0L
        val done = total != null && total > 0 && written >= total
        if (!done && nowMs - last < EMIT_THROTTLE_MS) return
        lastEmitMs[gameId] = nowMs
        _byGame.update { cur ->
            val prev = cur[gameId]
            val started = prev?.startedMs ?: nowMs
            // Never step backwards (retries reuse the game id).
            val bytes = maxOf(written.coerceAtLeast(0), prev?.bytesWritten ?: 0)
            cur + (gameId to PrepProgress(gameId, phase, bytes, total, started, nowMs))
        }
    }

    fun clear(gameId: Long) {
        lastEmitMs.remove(gameId)
        _byGame.update { it - gameId }
        _downloadLink.update { cur ->
            if (cur.values.contains(gameId)) cur.filterValues { it != gameId } else cur
        }
    }

    fun linkDownload(downloadId: Long, gameId: Long) {
        if (downloadId <= 0 || gameId <= 0) return
        _downloadLink.update { it + (downloadId to gameId) }
    }

    /** Live prep for a download row, via [linkDownload]. */
    fun forDownload(downloadId: Long): PrepProgress? {
        val gameId = _downloadLink.value[downloadId] ?: return null
        return _byGame.value[gameId]
    }

    fun forGame(gameId: Long): PrepProgress? = _byGame.value[gameId]
}
