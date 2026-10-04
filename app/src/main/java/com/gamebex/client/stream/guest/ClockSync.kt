package com.gamebex.client.stream.guest

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class ClockSync(
    private val window: Int = 16,
    private val clock: () -> Long = System::nanoTime,
    private val maxPpm: Double = 500.0,
) {
    data class Interval(val lowerNs: Long, val upperNs: Long)
    data class Estimate(val guestNs: Long, val errorBoundNs: Long, val driftPpm: Double,
        val driftReady: Boolean, val rttNs: Long)
    private data class Sample(val atNs: Long, val offsetNs: Long, val rttNs: Long,
        val lo: Double, val hi: Double)

    private val samples = ArrayDeque<Sample>()
    private val rate = maxPpm / 1_000_000.0
    var resets: Long = 0L
        @Synchronized get
        private set

    init { require(window > 0 && maxPpm in 0.0..1000.0) }

    @Synchronized
    fun add(t0: Long, t1: Long, t2: Long, t3: Long): Boolean {
        if (t0 < 0L || t1 < 0L || t2 < t1 || t3 < t0 || t3 - t0 > 1_000_000_000L) return false
        val elapsed = t3 - t0
        val rtt = elapsed - (t2 - t1)
        if (rtt < 0L || samples.isNotEmpty() && t3 <= samples.last.atNs) return false
        val slack = 100_000.0 + elapsed * rate
        val next = Sample(t3, ((t1 - t0) / 2L + (t2 - t3) / 2L), rtt,
            (t2 - t3).toDouble() - slack, (t1 - t0).toDouble() + slack)
        samples.addLast(next)
        while (samples.size > window) samples.pollFirst()
        val bound = bounds(t3)
        if (bound.first > bound.second) {
            samples.clear()
            samples.addLast(next)
            resets += 1L
            return false
        }
        return true
    }

    val ready: Boolean
        @Synchronized get() = usable(clock())

    val offsetNs: Long
        @Synchronized get() = best()?.offsetNs ?: 0L

    val rttNs: Long
        @Synchronized get() = best()?.rttNs ?: -1L

    val errorBoundNs: Long
        @Synchronized get() {
            val now = clock()
            val b = interval(now) ?: return -1L
            val point = offsetAt(now)
            return ceil(max(abs(point - b.lowerNs), abs(b.upperNs - point))).toLong()
        }

    val driftPpm: Double
        @Synchronized get() = fit().first * 1_000_000.0

    @Synchronized
    fun interval(nowGuestNs: Long = clock()): Interval? {
        if (!usable(nowGuestNs)) return null
        val b = bounds(nowGuestNs)
        if (b.first > b.second) return null
        return Interval(kotlin.math.floor(b.first).toLong(), ceil(b.second).toLong())
    }

    @Synchronized
    fun estimateHost(hostNs: Long, nowGuestNs: Long = clock()): Estimate? {
        if (!usable(nowGuestNs)) return null
        val ref = samples.last.atNs
        val fit = fit()
        val offset = offsetAt(ref)
        val guest = ref + ((hostNs - ref - offset) / (1.0 + fit.first)).roundToLong()
        val b = bounds(guest)
        if (b.first > b.second) return null
        val implied = (hostNs - guest).toDouble()
        val error = ceil(max(abs(implied - b.first), abs(b.second - implied)) / (1.0 - rate)).toLong()
        return Estimate(guest, error, fit.first * 1_000_000.0, fit.second, rttNs)
    }

    fun hostToGuest(hostNs: Long): Long = estimateHost(hostNs)?.guestNs ?: hostNs - offsetNs

    @Synchronized
    fun guestToHost(guestNs: Long): Long = guestNs + offsetAt(guestNs).roundToLong()

    @Synchronized
    fun reset() {
        samples.clear()
        resets += 1L
    }

    private fun usable(now: Long): Boolean = samples.size >= 4 &&
        now >= samples.last.atNs && now - samples.last.atNs <= 3_000_000_000L

    private fun bounds(at: Long): Pair<Double, Double> {
        var lo = Double.NEGATIVE_INFINITY
        var hi = Double.POSITIVE_INFINITY
        for (sample in samples) {
            val allowance = abs(at.toDouble() - sample.atNs) * rate
            lo = max(lo, sample.lo - allowance)
            hi = min(hi, sample.hi + allowance)
        }
        return lo to hi
    }

    private fun fit(): Pair<Double, Boolean> {
        if (samples.isEmpty()) return 0.0 to false
        val ref = samples.last.atNs
        val fast = samples.sortedBy { it.rttNs }.take(maxOf(8, samples.size * 3 / 4))
        if (fast.size < 8 || fast.maxOf { it.atNs } - fast.minOf { it.atNs } < 10_000_000_000L) return 0.0 to false
        val meanX = fast.map { (it.atNs - ref).toDouble() }.average()
        val meanY = fast.map { it.offsetNs.toDouble() }.average()
        val denominator = fast.sumOf { val dx = (it.atNs - ref).toDouble() - meanX; dx * dx }
        if (denominator == 0.0) return 0.0 to false
        val slope = fast.sumOf { ((it.atNs - ref).toDouble() - meanX) * (it.offsetNs - meanY) } / denominator
        return slope.coerceIn(-rate, rate) to true
    }

    private fun offsetAt(at: Long): Double {
        val best = best() ?: return 0.0
        val point = best.offsetNs + (at.toDouble() - best.atNs) * fit().first
        val b = bounds(at)
        return if (b.first <= b.second) point.coerceIn(b.first, b.second) else point
    }

    private fun best(): Sample? = samples.minByOrNull { it.rttNs }
}
