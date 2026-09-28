package com.gamebex.client.ui.home

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-download ETA: an exponential moving average of the byte rate, sampled at
 * least [minSampleGapMs] apart so bursty progress ticks don't swing the estimate.
 *
 * The model rebuild and the progress-patch paths call this from different
 * dispatchers for the same job, so every entry point is synchronized. [Patch.read]
 * never moves backwards until the job's status changes, because a status change
 * is the only sign of a genuine restart.
 */
class DownloadEta(
    private val alpha: Double = 0.3,
    private val minSampleGapMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The monotonic read to display, plus seconds left (null while unknown). */
    data class Patch(val read: Long, val etaSec: Long?)

    private class Sample(val atMs: Long, val read: Long, val rateBps: Double, val status: String)

    private val samples = ConcurrentHashMap<Long, Sample>()

    /**
     * Returns the read clamped to never drop while [status] is unchanged, and the
     * ETA for [total] bytes: null while the rate is unknown, 0 when done.
     */
    @Synchronized
    fun patch(id: Long, read: Long, total: Long, status: String = ""): Patch {
        val now = clock()
        val prev = samples[id]
        val reseed = prev == null || prev.status != status
        val effective = if (prev == null || reseed) read else maxOf(prev.read, read)
        if (total > 0 && effective >= total) {
            samples[id] = Sample(now, effective, rateBps = 0.0, status)
            return Patch(effective, 0L)
        }
        if (reseed) {
            samples[id] = Sample(now, effective, rateBps = 0.0, status)
            return Patch(effective, null)
        }
        val dtMs = now - prev.atMs
        if (dtMs < minSampleGapMs) {
            // Not a rate sample; keep the max so the display stays monotonic.
            if (effective > prev.read) {
                samples[id] = Sample(prev.atMs, effective, prev.rateBps, status)
            }
            return Patch(effective, etaOf(prev.rateBps, effective, total))
        }
        val rate = (effective - prev.read) * 1000.0 / dtMs
        val ema = if (prev.rateBps <= 0.0) rate else alpha * rate + (1 - alpha) * prev.rateBps
        samples[id] = Sample(now, effective, ema, status)
        return Patch(effective, etaOf(ema, effective, total))
    }

    @Synchronized
    fun remove(id: Long) {
        samples.remove(id)
    }

    @Synchronized
    fun clear() {
        samples.clear()
    }

    private fun etaOf(rateBps: Double, read: Long, total: Long): Long? {
        if (total <= 0 || rateBps <= 0.0) return null
        return ((total - read) / rateBps).toLong().coerceAtLeast(0L)
    }
}
