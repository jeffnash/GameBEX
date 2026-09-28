package com.gamebex.client.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Background prep after a download lands, so a finished disc archive is playable
 * without opening Detail. One run per game; a duplicate kick while one runs is dropped.
 * [prepare] returns the resulting prepState. PrepPipeline serializes per game, so a
 * concurrent Finish Setup waits and then hits the idempotent marker path.
 * [onReady] fires once per kick when the row ends READY. Cancellation propagates;
 * other failures are logged and leave the row for Detail's Finish Setup.
 */
class AutoPrep(
    private val scope: CoroutineScope,
    private val findGame: suspend (Long) -> GameEntity?,
    private val prepare: suspend (Long) -> String,
    private val onReady: (GameEntity) -> Unit,
) {
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * Schedules prep for [gameId]; [before] runs first in the same job
     * (BIOS seeding, so prep sees the firmware it just fetched). Returns
     * null when a run for this game is already in flight.
     */
    fun kick(gameId: Long, before: suspend () -> Unit = {}): Job? {
        if (gameId <= 0 || !inFlight.add(gameId)) return null
        _active.update { it + gameId }
        return scope.launch {
            try {
                run(gameId, before)
            } finally {
                inFlight.remove(gameId)
                _active.update { it - gameId }
            }
        }
    }

    /** One pass: seed, prep unless already READY, notify when READY. */
    internal suspend fun run(gameId: Long, before: suspend () -> Unit = {}) {
        try {
            before()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("pre-prep step failed for $gameId", e)
        }
        val game = findGame(gameId) ?: return
        if (!game.available) return
        if (game.prepState != PrepPipeline.PREP_READY) {
            val state = try {
                prepare(gameId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("auto-prep failed for $gameId", e)
                return
            }
            if (state != PrepPipeline.PREP_READY) return
        }
        val fresh = findGame(gameId)?.takeIf { it.prepState == PrepPipeline.PREP_READY } ?: return
        try {
            onReady(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("ready callback failed for $gameId", e)
        }
    }

    companion object {
        private val _active = MutableStateFlow<Set<Long>>(emptySet())
        /** Games with a background prep in flight (Detail shows "Preparing…"). */
        val active: StateFlow<Set<Long>> = _active
    }

    private fun log(msg: String, e: Exception) {
        runCatching { Log.w("GameBEXAutoPrep", msg, e) }
    }
}
