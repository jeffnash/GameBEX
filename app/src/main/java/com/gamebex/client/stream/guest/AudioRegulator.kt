package com.gamebex.client.stream.guest

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil

class AudioRegulator {
    private data class Arrival(val now: Long, val residual: Long)
    private val arrivals = ArrayDeque<Arrival>()
    private var lastIndex = -1L
    private var lastStep: Long? = null
    private var lastTarget = 0L
    private var lastShrink = 0L
    private var lastLoss: Long? = null
    @Volatile var targetFrames = 1_920
        private set
    @Volatile var driftPpm = 0.0
        private set
    @Volatile var ratio = 1.0
        private set

    @Synchronized fun observe(firstFrame: Long, nowNs: Long) {
        if (firstFrame <= lastIndex) return
        lastIndex = firstFrame
        arrivals.addLast(Arrival(nowNs, nowNs - audioFrameNs(firstFrame)))
        while (arrivals.size > 2_048 || nowNs - arrivals.first.now > 5_000_000_000L) arrivals.removeFirst()
    }

    @Synchronized fun reset() {
        arrivals.clear(); lastIndex = -1L; lastStep = null; lastTarget = 0L; lastShrink = 0L; lastLoss = null
        targetFrames = 1_920; driftPpm = 0.0; ratio = 1.0
    }

    @Synchronized fun step(nowNs: Long, totalReserveFrames: Double, missingFrames: Long,
        measuredDriftPpm: Double? = null): Double {
        val dt = lastStep?.let { ((nowNs - it) / 1e9).coerceIn(0.0, 0.25) } ?: 0.0
        lastStep = nowNs
        if (missingFrames > 0) {
            if (lastLoss == null || nowNs - lastLoss!! > 100_000_000L) targetFrames = minOf(2_880, targetFrames + 240)
            lastLoss = nowNs
        }
        if (nowNs - lastTarget >= 1_000_000_000L && arrivals.size >= 20) {
            lastTarget = nowNs
            val residuals = arrivals.map { it.residual }.sorted()
            val variation = (residuals[ceil((residuals.size - 1) * .99).toInt()] - residuals.first()).coerceAtLeast(0L)
            val desired = (960 + ceil(variation * 48_000.0 / 1e9).toInt() + 96).coerceIn(960, 2_880)
            if (desired > targetFrames) targetFrames = desired
            else if (desired < targetFrames && nowNs - lastShrink >= 5_000_000_000L &&
                (lastLoss == null || nowNs - lastLoss!! >= 10_000_000_000L)) {
                targetFrames = maxOf(desired, targetFrames - 48); lastShrink = nowNs
            }
        }
        val error = totalReserveFrames - targetFrames
        if (measuredDriftPpm != null && measuredDriftPpm.isFinite()) {
            driftPpm += (measuredDriftPpm.coerceIn(-1_500.0, 1_500.0) - driftPpm) * minOf(1.0, dt / 5.0)
        } else if (abs(error) < 240 && (lastLoss == null || nowNs - lastLoss!! > 5_000_000_000L)) {
            driftPpm = (driftPpm + error * .2 * dt).coerceIn(-1_500.0, 1_500.0)
        }
        ratio = (1.0 + (driftPpm + 4.0 * error) / 1e6).coerceIn(.997, 1.003)
        return ratio
    }
}
