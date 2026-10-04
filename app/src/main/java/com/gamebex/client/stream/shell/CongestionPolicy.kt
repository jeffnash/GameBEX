package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.VideoLevel

/**
 * Chooses the encoder bitrate and quality level from queue drops.
 * Pure and single-threaded: MediaServer calls it under its lock and applies
 * the returned [Action] from its own ticker thread.
 *
 * - A drop cuts the bitrate by [DOWN_FACTOR], at most once per [DOWN_SPACING_MS],
 *   never below the floor.
 * - Drops that keep coming for [StreamCaps.LEVEL_DOWN_AFTER_MS] at the floor move
 *   to the next lower level at the floor bitrate.
 * - [StreamCaps.STEP_UP_AFTER_MS] without a drop raises the bitrate by
 *   [UP_FACTOR], up to the level's maximum. The level never goes back up
 *   during one stream, so quality can't oscillate between sizes.
 */
class CongestionPolicy(
    levels: List<VideoLevel> = StreamCaps.LEVELS,
    startLevel: Int = 0,
    private val minBitrate: Int = StreamCaps.MIN_BITRATE,
    startNs: Long = 0L,
    allowExperimentalRung: Boolean = false,
) {
    private val ladder = levels.sortedBy { it.index }.filter { allowExperimentalRung || it.index != 2 || it.index == startLevel }
    sealed interface Action {
        data class SetBitrate(val bps: Int) : Action
        data class ChangeLevel(val level: VideoLevel, val bps: Int) : Action
    }

    var level: VideoLevel = ladder.firstOrNull { it.index == startLevel } ?: ladder.first()
        private set
    var bitrate: Int = level.maxBitrate
        private set

    private var lastDropNs = startNs
    private var lastDownNs = Long.MIN_VALUE
    private var lastChangeNs = startNs
    private var floorSinceNs = NOT_AT_FLOOR

    /** A queue drop happened at [now]. */
    fun onDrop(now: Long): Action? {
        lastDropNs = now
        if (bitrate > minBitrate) {
            if (lastDownNs != Long.MIN_VALUE && now - lastDownNs < DOWN_SPACING_MS * MS) return null
            bitrate = maxOf(minBitrate, (bitrate * DOWN_FACTOR).toInt())
            lastDownNs = now
            lastChangeNs = now
            if (bitrate == minBitrate) floorSinceNs = now
            return Action.SetBitrate(bitrate)
        }
        if (floorSinceNs == NOT_AT_FLOOR) floorSinceNs = now
        val next = ladder.firstOrNull { it.index > level.index } ?: return null
        if (now - floorSinceNs < StreamCaps.LEVEL_DOWN_AFTER_MS * MS) return null
        level = next
        bitrate = minOf(next.maxBitrate, minBitrate)
        floorSinceNs = now
        lastDownNs = now
        lastChangeNs = now
        return Action.ChangeLevel(next, bitrate)
    }

    /** Called about every 100 ms; raises the bitrate after a clean stretch. */
    fun tick(now: Long): Action? {
        if (bitrate >= level.maxBitrate) return null
        if (now - lastDropNs < StreamCaps.STEP_UP_AFTER_MS * MS) return null
        if (now - lastChangeNs < StreamCaps.STEP_UP_AFTER_MS * MS) return null
        bitrate = minOf(level.maxBitrate, (bitrate * UP_FACTOR).toInt())
        lastChangeNs = now
        floorSinceNs = NOT_AT_FLOOR
        return Action.SetBitrate(bitrate)
    }

    companion object {
        const val DOWN_FACTOR: Double = 0.75
        const val UP_FACTOR: Double = 1.10
        const val DOWN_SPACING_MS: Long = 500L
        private const val MS = 1_000_000L
        private const val NOT_AT_FLOOR = Long.MIN_VALUE
    }
}
