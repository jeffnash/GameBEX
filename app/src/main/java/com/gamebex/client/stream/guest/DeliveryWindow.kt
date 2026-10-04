package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.DeliveryWire
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.floor

class DeliveryWindow(private val invalid: (String) -> Unit = {}) {
    private data class Sample(val at: Long, val transit: Long)
    private var epoch = 0L
    private var start = 0L
    private var seq = 0
    private var bytes = 0L
    private var frames = 0
    private var lastSeq = 0
    private val history = ArrayDeque<Sample>()
    private val window = ArrayList<Long>()
    private var overflow = false

    @Synchronized
    fun begin(codecEpoch: Long, now: Long) {
        require(codecEpoch > 0L)
        epoch = codecEpoch
        start = now
        bytes = 0L
        frames = 0
        lastSeq = 0
        history.clear()
        window.clear()
        overflow = false
    }

    @Synchronized
    fun received(codecEpoch: Long, frameSeq: Int, receivedNs: Long, hostSendNs: Long, auBytes: Int) {
        if (epoch == 0L || codecEpoch != epoch) return
        require(auBytes >= 0)
        bytes += auBytes
        frames += 1
        lastSeq = frameSeq
        while (history.isNotEmpty() && receivedNs - history.first.at > 5_000_000_000L) history.removeFirst()
        if (hostSendNs <= 0L) return
        if (history.size >= 2048 || window.size >= 2048) {
            overflow = true
            return
        }
        val transit = receivedNs - hostSendNs
        history.addLast(Sample(receivedNs, transit))
        window += transit
    }

    @Synchronized
    fun report(now: Long, decoderPending: Int): DeliveryWire.Report? {
        if (epoch == 0L || now - start < 500_000_000L) return null
        val span = now - start
        while (history.isNotEmpty() && now - history.first.at > 5_000_000_000L) history.removeFirst()
        val result = if (overflow || span > 2_000_000_000L || bytes > 64_000_000L || frames > 500) {
            invalid("delivery_window_invalid")
            null
        } else {
            val relative = if (window.isEmpty() || history.isEmpty()) -1L else {
                val sorted = window.sorted()
                val x = (sorted.size - 1) * .95
                val baseline = history.minOf { it.transit }
                val lo = sorted[floor(x).toInt()] - baseline
                val hi = sorted[ceil(x).toInt()] - baseline
                (lo + (hi - lo) * (x - floor(x))).toLong().coerceAtLeast(0L)
            }
            DeliveryWire.Report(epoch, ++seq, now, span, bytes, frames,
                relative.coerceAtMost(2_000_000_000L), decoderPending.coerceIn(0, 32), lastSeq)
        }
        start = now
        bytes = 0L
        frames = 0
        window.clear()
        overflow = false
        return result
    }
}
