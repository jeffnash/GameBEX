package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.DeliveryWire

class WeakWifiPolicy {
    private var startLevel = -1
    private var startMaxBitrate = 0
    private var reduced = false
    private var epoch = 0L
    private var rttAt = -1L
    private var rttNs = -1L
    private var deliveryAt = -1L
    private var deliveryDelayNs = -1L
    private var deliverySeq = 0
    private var distressedSince = -1L
    private var evaluatedAt = -1L

    @Synchronized
    fun connectionReset() {
        reduced = false
        epoch = 0L
        rttAt = -1L
        rttNs = -1L
        deliveryAt = -1L
        deliveryDelayNs = -1L
        deliverySeq = 0
        distressedSince = -1L
        evaluatedAt = -1L
    }

    @Synchronized
    fun quality(level: Int, bitrate: Int, maxBitrate: Int, nowNs: Long): Boolean {
        if (level < 0 || bitrate <= 0 || maxBitrate <= 0) return tick(nowNs)
        if (startLevel < 0) {
            startLevel = level
            startMaxBitrate = maxBitrate
        }
        if (level <= startLevel && bitrate.toLong() * 100 >= startMaxBitrate.toLong() * 80) {
            reduced = false
        } else if (level > startLevel || bitrate.toLong() * 100 < startMaxBitrate.toLong() * 60) {
            reduced = true
        }
        return tick(nowNs)
    }

    @Synchronized
    fun codecEpoch(codecEpoch: Long) {
        epoch = codecEpoch
        deliveryAt = -1L
        deliveryDelayNs = -1L
        deliverySeq = 0
        distressedSince = -1L
    }

    @Synchronized
    fun roundTrip(valueNs: Long, valid: Boolean, nowNs: Long): Boolean {
        if (nowNs < rttAt) return tick(nowNs)
        rttAt = if (valid && valueNs >= 0L) nowNs else -1L
        rttNs = if (rttAt >= 0L) valueNs else -1L
        return tick(nowNs)
    }

    @Synchronized
    fun delivery(report: DeliveryWire.Report): Boolean {
        if (epoch <= 0L || report.codecEpoch != epoch || report.seq <= deliverySeq ||
            report.endNs < deliveryAt) return false
        deliverySeq = report.seq
        deliveryAt = if (report.frames > 0 && report.spanNs in 1L..DELIVERY_FRESH_NS &&
            report.relativeP95Ns >= 0L) report.endNs else -1L
        deliveryDelayNs = if (deliveryAt >= 0L) report.relativeP95Ns else -1L
        return tick(report.endNs)
    }

    @Synchronized
    fun tick(nowNs: Long): Boolean {
        val now = maxOf(nowNs, evaluatedAt)
        evaluatedAt = now
        val fresh = rttAt >= 0L && now >= rttAt && now - rttAt <= RTT_FRESH_NS &&
            deliveryAt >= 0L && now >= deliveryAt && now - deliveryAt <= DELIVERY_FRESH_NS
        val distressed = fresh && (rttNs > RTT_DISTRESS_NS || deliveryDelayNs > DELIVERY_DISTRESS_NS)
        if (!reduced || !distressed) {
            distressedSince = -1L
            return false
        }
        if (distressedSince < 0L) distressedSince = now
        return now - distressedSince >= SUSTAINED_NS
    }

    companion object {
        const val SUSTAINED_NS = 10_000_000_000L
        const val RTT_DISTRESS_NS = 50_000_000L
        const val DELIVERY_DISTRESS_NS = 40_000_000L
        const val RTT_FRESH_NS = 3_000_000_000L
        const val DELIVERY_FRESH_NS = 1_500_000_000L
    }
}
