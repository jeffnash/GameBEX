package com.gamebex.client.stream.guest

import java.util.Locale

/**
 * Rolling latency samples and counters for the stats overlay and the
 * `GameBEXStream` STATS log line. Values are nanoseconds in, milliseconds out.
 * Thread-safe.
 */
class LatencyStats(
    private val capacity: Int = 600,
    private val clock: () -> Long = System::nanoTime,
    measureWarmupSeconds: Int = 10,
    measureSeconds: Int = 120,
    private val runLabel: String = "",
    private val runEnabled: Boolean = false,
    deferredRunStart: Boolean = false,
) {
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
        CALLBACK_RELEASE("cb_release"),
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
        val invalid: Map<Metric, Long> = emptyMap(),
        val counters: Map<String, Long> = emptyMap(),
        val stallCount: Long = 0L,
        val stallTotalNs: Long = 0L,
        val stallMaxNs: Long = 0L,
    )

    private val rings = Metric.values().associateWith { LongArray(capacity) }
    private val counts = LongArray(Metric.values().size)
    private val invalid = LongArray(Metric.values().size)
    private val counters = linkedMapOf<String, Long>()
    private val renderTimes = java.util.ArrayDeque<Long>()
    private val runHistograms = Metric.values().associateWith { RunHistogram() }
    private val runErrors = Metric.values().associateWith { RunHistogram() }
    private val runCounters = linkedMapOf<String, Long>()
    private val runWarmupNs = measureWarmupSeconds.coerceIn(0, 600) * 1_000_000_000L
    private val runDurationNs = measureSeconds.coerceIn(1, 3600) * 1_000_000_000L
    private var runStartNs: Long? = if (deferredRunStart) null else clock() + runWarmupNs
    private var runEndNs: Long? = runStartNs?.let { it + runDurationNs }
    private var finishedNs: Long? = null
    private var emitted = false
    private var lastRenderNs: Long? = null
    private var accountedStallNs = 0L
    private var runAccountedStallNs = 0L
    private var stallCount = 0L
    private var stallTotalNs = 0L
    private var stallMaxNs = 0L
    private var runStallCount = 0L
    private var runStallTotalNs = 0L
    private var runStallMaxNs = 0L

    init { require(capacity > 0) }

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
    fun add(metric: Metric, ns: Long) = addAt(metric, ns, clock())

    private fun addAt(metric: Metric, ns: Long, nowNs: Long) {
        val i = metric.ordinal
        if (measuring(nowNs)) runHistograms.getValue(metric).add(ns)
        if (ns < 0L) {
            invalid[i] += 1L
            return
        }
        rings.getValue(metric)[(counts[i] % capacity).toInt()] = ns
        counts[i] += 1L
    }

    @Synchronized
    fun addClockSample(metric: Metric, ns: Long, errorBoundNs: Long) {
        require(errorBoundNs >= 0L)
        val now = clock()
        if (ns >= 0L && measuring(now)) runErrors.getValue(metric).add(errorBoundNs)
        addAt(metric, ns, now)
    }

    @Synchronized
    fun frameReceived() {
        val now = clock()
        if (lastRenderNs == null) lastRenderNs = now
        framesReceived += 1
        runCount("received", 1L, now)
    }

    @Synchronized
    fun frameDropped(count: Int = 1) {
        require(count >= 0)
        framesDropped += count
        runCount("dropped", count.toLong(), clock())
    }

    @Synchronized
    fun count(name: String, delta: Long = 1L) {
        require(name.matches(COUNTER_NAME) && name !in RESERVED)
        require(delta >= 0L)
        counters[name] = (counters[name] ?: 0L) + delta
        runCount(name, delta, clock())
    }

    @Synchronized
    fun frameRendered(nowNs: Long) {
        updateStall(nowNs)
        lastRenderNs = nowNs
        accountedStallNs = 0L
        runAccountedStallNs = 0L
        framesRendered += 1
        runCount("rendered", 1L, nowNs)
        renderTimes.addLast(nowNs)
        while (renderTimes.size > 240) renderTimes.pollFirst()
    }

    /** [p] in 0..1 of the last [capacity] samples, in ms; null with no samples. */
    fun percentileMs(metric: Metric, p: Double): Double? {
        val sorted = synchronized(this) { rings.getValue(metric).copyOf(sampleCount(metric)) }
        sorted.sort()
        return percentile(sorted, p)
    }

    fun snapshot(nowNs: Long): Snapshot {
        val p50 = linkedMapOf<Metric, Double?>()
        val p95 = linkedMapOf<Metric, Double?>()
        val (copies, result) = synchronized(this) {
            while (renderTimes.isNotEmpty() && nowNs - renderTimes.peekFirst() > 1_000_000_000L) renderTimes.pollFirst()
            val copies = Metric.values().associateWith { rings.getValue(it).copyOf(sampleCount(it)) }
            copies to Snapshot(
                p50Ms = p50,
                p95Ms = p95,
                samples = copies.mapValues { it.value.size },
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
                invalid = Metric.values().associateWith { invalid[it.ordinal] },
                counters = counters.toMap(),
                stallCount = stallCount,
                stallTotalNs = stallTotalNs,
                stallMaxNs = stallMaxNs,
            )
        }
        for ((metric, values) in copies) {
            values.sort()
            p50[metric] = percentile(values, 0.5)
            p95[metric] = percentile(values, 0.95)
        }
        return result
    }

    private fun sampleCount(metric: Metric): Int = minOf(counts[metric.ordinal], capacity.toLong()).toInt()

    private fun percentile(sorted: LongArray, p: Double): Double? {
        if (sorted.isEmpty()) return null
        val index = Math.round((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index] / 1_000_000.0
    }

    @Synchronized
    fun beginMeasurement(nowNs: Long) {
        if (runStartNs != null || finishedNs != null) return
        runStartNs = nowNs + runWarmupNs
        runEndNs = nowNs + runWarmupNs + runDurationNs
    }

    @Synchronized
    fun tick(nowNs: Long) {
        updateStall(nowNs)
        val end = runEndNs
        if (runEnabled && finishedNs == null && end != null && nowNs >= end) finishedNs = end
    }

    @Synchronized
    fun finish(nowNs: Long = clock()) {
        tick(nowNs)
        if (finishedNs == null) finishedNs = nowNs
    }

    fun runLines(nowNs: Long = clock(), audioOrigin: String = "capture"): List<String> {
        val summary = synchronized(this) {
            tick(nowNs)
            val end = finishedNs ?: return emptyList()
            if (!runEnabled || emitted) return emptyList()
            emitted = true
            val runEnd = runEndNs
            val phase = if (runEnd != null && end >= runEnd) "measure_end" else "incomplete"
            val start = runStartNs ?: end
            val prefix = "RUN label=${token(runLabel.ifEmpty { "unlabelled" })} phase=$phase start_ns=$start end_ns=$end"
            val totals = "stall_n=$runStallCount stall_total_ns=$runStallTotalNs stall_max_ns=$runStallMaxNs " +
                ((listOf("received", "rendered", "dropped") + LOSS_KEYS).associateWith { runCounters[it] ?: 0L } + runCounters)
                    .toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }
            prefix to totals
        }
        val origin = "audio_origin=${token(audioOrigin)}"
        val lines = Metric.values().map { metric ->
            val s = runHistograms.getValue(metric).snapshot()
            val error = runErrors.getValue(metric).snapshot()
            val bounds = "uncertainty=${if (metric in CROSS_CLOCK) "interval" else "none"} error_n=${error.n} " +
                "error_p50_ns=${error.p50Ns ?: "-"} error_p95_ns=${error.p95Ns ?: "-"} " +
                "error_hist=${error.bins.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "-" }}"
            val bins = s.bins.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "-" }
            "${summary.first} metric=${metric.key} n=${s.n} invalid=${s.invalid} overflow=${s.overflow} bin_us=100 " +
                "p50_ns=${s.p50Ns ?: "-"} p95_ns=${s.p95Ns ?: "-"} hist=$bins $bounds $origin"
        }
        return lines + "${summary.first} metric=counters ${summary.second} $origin"
    }

    private fun measuring(nowNs: Long): Boolean {
        val start = runStartNs ?: return false
        val end = runEndNs ?: return false
        return runEnabled && finishedNs == null && nowNs >= start && nowNs < end
    }

    private fun runCount(name: String, delta: Long, nowNs: Long) {
        if (measuring(nowNs)) runCounters[name] = (runCounters[name] ?: 0L) + delta
    }

    private fun updateStall(nowNs: Long) {
        val last = lastRenderNs ?: return
        val duration = (nowNs - last).coerceAtLeast(0L)
        if (duration > STALL_NS) {
            if (accountedStallNs == 0L) stallCount += 1L
            stallTotalNs += (duration - accountedStallNs).coerceAtLeast(0L)
            stallMaxNs = maxOf(stallMaxNs, duration)
            accountedStallNs = maxOf(accountedStallNs, duration)
        }
        val start = runStartNs ?: return
        val runEnd = runEndNs ?: return
        val end = minOf(nowNs, finishedNs ?: runEnd, runEnd)
        val runDuration = (end - maxOf(last, start)).coerceAtLeast(0L)
        if (runEnabled && duration > STALL_NS && runDuration > 0L) {
            if (runAccountedStallNs == 0L) runStallCount += 1L
            runStallTotalNs += (runDuration - runAccountedStallNs).coerceAtLeast(0L)
            runStallMaxNs = maxOf(runStallMaxNs, runDuration)
            runAccountedStallNs = maxOf(runAccountedStallNs, runDuration)
        }
    }

    private fun token(value: String): String = value.replace(Regex("[^A-Za-z0-9_.:-]"), "_")

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
            parts += "${m.key}_invalid=${s.invalid[m] ?: 0L}"
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
        parts += "stall_n=${s.stallCount}"
        parts += "stall_total_ns=${s.stallTotalNs}"
        parts += "stall_max_ns=${s.stallMaxNs}"
        parts += s.counters.toSortedMap().entries.map { "${it.key}=${it.value}" }
        return "STATS " + parts.joinToString(" ")
    }

    companion object {
        private const val STALL_NS = 100_000_000L
        private val COUNTER_NAME = Regex("[a-z][a-z0-9_]*")
        private val RESERVED = setOf("received", "rendered", "dropped", "stall_n", "stall_total_ns", "stall_max_ns")
        private val CROSS_CLOCK = setOf(Metric.VIDEO, Metric.NETWORK, Metric.INPUT, Metric.AUDIO)
        private val LOSS_KEYS = listOf("dec_overflow", "dec_await_key", "dec_config_reset", "dec_input_oversize",
            "dec_input_fail", "dec_release_fail", "dec_time_evict", "dec_recreate_abandon", "dec_stall",
            "dec_epoch_stale", "dec_out_drop", "v_no_decoder", "v_before_config", "delivery_window_invalid",
            "a_gap_pkts", "a_duplicate_frames", "a_late_frames", "a_evicted_frames", "a_missing_frames",
            "a_silence_frames", "audio_gap_started", "audio_expanded_equivalent_frames",
            "audio_compressed_equivalent_frames", "audio_explicit_rebase_frames", "audio_startup_output_frames",
            "audio_reprime_output_frames", "audio_abandoned_output_frames", "audio_flushed_queued_frames",
            "audio_invalid_head", "audio_output_failure", "audio_plc_frames", "audio_native_underruns")
        fun fmt(ms: Double?): String = if (ms == null) "-" else String.format(Locale.US, "%.1f", ms)
    }
}
