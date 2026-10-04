package com.gamebex.client.stream.guest

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

class RunHistogram(private val maxSamples: Int = 1_000_000) {
    data class Snapshot(
        val n: Int,
        val invalid: Long,
        val overflow: Long,
        val p50Ns: Long?,
        val p95Ns: Long?,
        val bins: Map<Long, Long>,
    )

    private val chunks = ArrayList<LongArray>()
    private var size = 0
    private var invalid = 0L
    private var overflow = 0L

    init { require(maxSamples > 0) }

    @Synchronized
    fun add(ns: Long) {
        if (ns < 0L) {
            invalid += 1
            return
        }
        if (size >= maxSamples) {
            overflow += 1
            return
        }
        if (size % CHUNK == 0) chunks += LongArray(CHUNK)
        chunks[size / CHUNK][size % CHUNK] = ns
        size += 1
    }

    fun snapshot(): Snapshot {
        val (values, rejected) = synchronized(this) {
            val copy = LongArray(size) { chunks[it / CHUNK][it % CHUNK] }
            copy to (invalid to overflow)
        }
        values.sort()
        val bins = linkedMapOf<Long, Long>()
        for (ns in values) {
            val bin = ns / BIN_NS
            bins[bin] = (bins[bin] ?: 0L) + 1L
        }
        return Snapshot(values.size, rejected.first, rejected.second,
            percentile(values, 0.5), percentile(values, 0.95), bins)
    }

    private fun percentile(sorted: LongArray, p: Double): Long? {
        if (sorted.isEmpty()) return null
        val x = (sorted.size - 1) * p
        val lo = sorted[floor(x).toInt()]
        val hi = sorted[ceil(x).toInt()]
        return lo + ((hi - lo) * (x - floor(x))).roundToLong()
    }

    companion object {
        const val BIN_NS = 100_000L
        private const val CHUNK = 1024
    }
}
