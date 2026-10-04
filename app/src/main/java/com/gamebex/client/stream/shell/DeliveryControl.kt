package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.VideoLevel

class DeliveryControl(
    levels: List<VideoLevel>,
    startLevel: Int,
    private val floor: Int = StreamCaps.MIN_BITRATE,
    startNs: Long = 0L,
    allowExperimentalRung: Boolean = false,
) {
    sealed interface Action {
        data class Bitrate(val bps: Int) : Action
        data class Level(val level: VideoLevel, val bps: Int) : Action
    }

    private val ladder = levels.sortedBy { it.index }.filter { allowExperimentalRung || it.index != 2 || it.index == startLevel }
    var level = ladder.firstOrNull { it.index == startLevel } ?: ladder.first()
        private set
    var bitrate = level.maxBitrate
        private set
    private var lastChange = startNs
    private var lastDown = Long.MIN_VALUE
    private var lastBad = startNs
    private var floorSince = Long.MIN_VALUE
    private var badWindows = 0
    private var lastReport = Long.MIN_VALUE
    private var cleanSince = Long.MIN_VALUE

    fun window(now: Long, relativeP95Ns: Long?, queueAgeNs: Long, writeP95Ns: Long,
        decoderPending: Int, dropped: Boolean): Action? {
        if (!feedbackFresh(now)) {
            badWindows = 0
            cleanSince = Long.MIN_VALUE
        }
        lastReport = now
        val bad = dropped || (relativeP95Ns != null && relativeP95Ns > 30 * MS) ||
            queueAgeNs > 25 * MS || writeP95Ns > 15 * MS || decoderPending >= 4
        if (bad) {
            lastBad = now
            badWindows++
            cleanSince = Long.MIN_VALUE
        } else badWindows = 0
        if (dropped || queueAgeNs > 50 * MS || badWindows >= 2) return down(now)
        if (relativeP95Ns == null || relativeP95Ns > 20 * MS || queueAgeNs > 12 * MS ||
            writeP95Ns > 15 * MS || decoderPending > 1) {
            cleanSince = Long.MIN_VALUE
            return null
        }
        if (cleanSince == Long.MIN_VALUE) cleanSince = now
        if (now - cleanSince < 10 * SECOND || now - lastBad < 10 * SECOND ||
            now - lastChange < 10 * SECOND || bitrate >= level.maxBitrate) return null
        bitrate = minOf(level.maxBitrate, maxOf(bitrate + 1, (bitrate * 1.05).toInt()))
        lastChange = now
        floorSince = Long.MIN_VALUE
        return Action.Bitrate(bitrate)
    }

    fun localPressure(now: Long, queueAgeNs: Long, dropped: Boolean): Action? {
        if (!dropped && queueAgeNs <= 50 * MS) return null
        lastBad = now
        cleanSince = Long.MIN_VALUE
        return down(now)
    }

    fun feedbackFresh(now: Long): Boolean = lastReport != Long.MIN_VALUE && now >= lastReport && now - lastReport <= 1500 * MS

    private fun down(now: Long): Action? {
        if (lastDown != Long.MIN_VALUE && now - lastDown < SECOND) return null
        val minimum = minOf(floor, level.maxBitrate)
        if (bitrate > minimum) {
            bitrate = maxOf(minimum, (bitrate * 0.85).toInt())
            lastDown = now
            lastChange = now
            if (bitrate == minimum) floorSince = now
            return Action.Bitrate(bitrate)
        }
        if (floorSince == Long.MIN_VALUE) floorSince = now
        if (now - floorSince < 3 * SECOND) return null
        val next = ladder.firstOrNull { it.index > level.index } ?: return null
        level = next
        bitrate = minOf(next.maxBitrate, maxOf(minimum, bitrate))
        lastDown = now
        lastChange = now
        floorSince = now
        badWindows = 0
        return Action.Level(level, bitrate)
    }

    companion object {
        const val MS = 1_000_000L
        const val SECOND = 1_000_000_000L
    }
}
