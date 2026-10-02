package com.gamebex.client.stream.guest

import java.util.Locale

/**
 * Rolling latency samples and counters for the stats overlay and the
 * `GameBEXStream` STATS log line. Values are nanoseconds in, milliseconds out.
 * Thread-safe.
 */
class LatencyStats(private val capacity: Int = 600) {
    enum class Metric(val key: String) {
        /** Host capture to decoded frame handed to the display. */
        VIDEO("video"),
        /** Host send to guest receive of a video frame. */
        NETWORK("net"),
        /** Guest receive to decoded frame handed to the display. */
        DECODE("decode"),
        /** Host capture to host encoder output (host clock only). */
        ENCODE("encode"),
        /** Decoded frame handed over to SurfaceFlinger's reported render time. */
        PRESENT("present"),
        /** Guest controller event to host pad write. */
        INPUT("input"),
        /** Guest controller event to the host's acknowledgement arriving back. */
        INPUT_RTT("input_rtt"),
        /** Host audio capture to expected guest speaker output. */
        AUDIO("audio"),
    }

    class Snapshot(
        val p50Ms: Map<Metric, Double?>,
        val p95Ms: Map<Metric, Double?>,
        val samples: Map<Metric, Int>,
        val framesReceived: Long,
        val framesRendered: Long,
        val framesDropped: Long,
        val fps: Int,
        val audioUnderruns: Int,
        val audioBufferMs: Int,
        val rttMs: Double?,
        val bitrate: Int,
        val level: Int,
        val reconnects: Int,
        val trackUnderruns: Int = 0,
    )

    private val rings = Metric.values().associateWith { LongArray(capacity) }
    private val counts = IntArray(Metric.values().size)
    private val renderTimes = java.util.ArrayDeque<Long>()

    var framesReceived: Long = 0
        @Synchronized get
        private set
    var framesRendered: Long = 0
        @Synchronized get
        private set
    var framesDropped: Long = 0
        @Synchronized get
        private set
    @Volatile var audioUnderruns: Int = 0
    @Volatile var trackUnderruns: Int = 0
    @Volatile var audioBufferMs: Int = 0
    @Volatile var rttNs: Long = -1L
    @Volatile var bitrate: Int = 0
    @Volatile var level: Int = 0
    @Volatile var reconnects: Int = 0

    @Synchronized
    fun add(metric: Metric, ns: Long) {
        val i = metric.ordinal
        rings.getValue(metric)[counts[i] % capacity] = ns.coerceAtLeast(0L)
        counts[i] += 1
    }

    @Synchronized
    fun frameReceived() {
        framesReceived += 1
    }

    @Synchronized
    fun frameDropped() {
        framesDropped += 1
    }

    @Synchronized
    fun frameRendered(nowNs: Long) {
        framesRendered += 1
        renderTimes.addLast(nowNs)
        while (renderTimes.size > 240) renderTimes.pollFirst()
    }

    /** [p] in 0..1 of the last [capacity] samples, in ms; null with no samples. */
    @Synchronized
    fun percentileMs(metric: Metric, p: Double): Double? {
        val n = minOf(counts[metric.ordinal], capacity)
        if (n == 0) return null
        val sorted = rings.getValue(metric).copyOf(n).also { it.sort() }
        val index = Math.round((n - 1) * p).toInt().coerceIn(0, n - 1)
        return sorted[index] / 1_000_000.0
    }

    @Synchronized
    fun snapshot(nowNs: Long): Snapshot {
        while (renderTimes.isNotEmpty() && nowNs - renderTimes.peekFirst() > 1_000_000_000L) renderTimes.pollFirst()
        return Snapshot(
            p50Ms = Metric.values().associateWith { percentileMs(it, 0.5) },
            p95Ms = Metric.values().associateWith { percentileMs(it, 0.95) },
            samples = Metric.values().associateWith { minOf(counts[it.ordinal], capacity) },
            framesReceived = framesReceived,
            framesRendered = framesRendered,
            framesDropped = framesDropped,
            fps = renderTimes.size,
            audioUnderruns = audioUnderruns,
            audioBufferMs = audioBufferMs,
            rttMs = if (rttNs < 0) null else rttNs / 1_000_000.0,
            bitrate = bitrate,
            level = level,
            reconnects = reconnects,
            trackUnderruns = trackUnderruns,
        )
    }

    /**
     * One line for logcat: `STATS ` and then space-separated
     * `key=value` pairs: `<metric>_p50`, `<metric>_p95` (ms) and `<metric>_n` for every
     * [Metric], then fps, received, rendered, dropped, underruns, audio_buffer, rtt,
     * bitrate, level and reconnects. Missing values print as `-`.
     */
    fun statsLine(nowNs: Long): String {
        val s = snapshot(nowNs)
        val parts = ArrayList<String>()
        for (m in Metric.values()) {
            parts += "${m.key}_p50=${fmt(s.p50Ms[m])}"
            parts += "${m.key}_p95=${fmt(s.p95Ms[m])}"
            parts += "${m.key}_n=${s.samples[m]}"
        }
        parts += "fps=${s.fps}"
        parts += "received=${s.framesReceived}"
        parts += "rendered=${s.framesRendered}"
        parts += "dropped=${s.framesDropped}"
        parts += "underruns=${s.audioUnderruns}"
        parts += "track_underruns=${s.trackUnderruns}"
        parts += "audio_buffer=${s.audioBufferMs}"
        parts += "rtt=${fmt(s.rttMs)}"
        parts += "bitrate=${s.bitrate}"
        parts += "level=${s.level}"
        parts += "reconnects=${s.reconnects}"
        return "STATS " + parts.joinToString(" ")
    }

    companion object {
        fun fmt(ms: Double?): String = if (ms == null) "-" else String.format(Locale.US, "%.1f", ms)
    }
}
