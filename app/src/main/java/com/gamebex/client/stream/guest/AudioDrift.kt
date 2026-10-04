package com.gamebex.client.stream.guest

class AudioDrift {
    private var seq: Int? = null
    private var packetFrames = 0
    private var sourceFrame = 0L
    private var arrivalFirst: Pair<Long, Long>? = null
    private var arrivalLast: Pair<Long, Long>? = null
    private var outputFirst: Pair<Long, Long>? = null
    private var outputLast: Pair<Long, Long>? = null
    @Volatile var ppm = 0.0
        private set
    @Volatile var valid = false
        private set

    @Synchronized fun reset() {
        seq = null; packetFrames = 0; sourceFrame = 0L
        arrivalFirst = null; arrivalLast = null; outputFirst = null; outputLast = null
        ppm = 0.0; valid = false
    }

    @Synchronized fun observePacket(sequence: Int, frames: Int, nowNs: Long) {
        require(frames > 0)
        if (packetFrames != 0 && packetFrames != frames) reset()
        packetFrames = frames
        val previous = seq
        if (previous != null) {
            val delta = sequence - previous
            if (delta <= 0) return
            sourceFrame += delta.toLong() * frames
        }
        seq = sequence
        observeArrival(sourceFrame, nowNs)
    }

    @Synchronized fun observeArrival(firstSourceFrame: Long, nowNs: Long) {
        val previous = arrivalLast
        if (previous != null && (firstSourceFrame <= previous.first || nowNs <= previous.second)) return
        val observation = firstSourceFrame to nowNs
        if (arrivalFirst == null) arrivalFirst = observation
        arrivalLast = observation
        update()
    }

    @Synchronized fun observePlayout(outputFrame: Long, nowNs: Long) {
        val previous = outputLast
        if (previous != null && (outputFrame <= previous.first || nowNs <= previous.second)) return
        val observation = outputFrame to nowNs
        if (outputFirst == null) outputFirst = observation
        outputLast = observation
        update()
    }

    private fun update() {
        val a0 = arrivalFirst ?: return
        val a1 = arrivalLast ?: return
        val p0 = outputFirst ?: return
        val p1 = outputLast ?: return
        val arrivalTime = a1.second - a0.second
        val outputTime = p1.second - p0.second
        if (arrivalTime < 5_000_000_000L || outputTime < 5_000_000_000L) return
        val sourceRate = (a1.first - a0.first).toDouble() / arrivalTime
        val outputRate = (p1.first - p0.first).toDouble() / outputTime
        if (sourceRate <= 0.0 || outputRate <= 0.0) return
        ppm = ((sourceRate / outputRate - 1.0) * 1_000_000.0).coerceIn(-10_000.0, 10_000.0)
        valid = true
        if (minOf(arrivalTime, outputTime) >= 30_000_000_000L) {
            arrivalFirst = a1; outputFirst = p1
        }
    }
}
