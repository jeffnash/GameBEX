package com.gamebex.client.stream.guest

import com.gamebex.client.together.TogetherCopy
import java.util.Locale

/** Stats overlay lines: plain numbers, no judgement words. */
object StreamStatsText {
    fun lines(s: LatencyStats.Snapshot, audioOrigin: String = "capture"): List<String> {
        val m = LatencyStats.Metric.VIDEO
        val audioLabel = if (audioOrigin == "bridge-arrival") "Bridge arrival → output" else "Audio"
        val invalid = s.invalid.values.sum()
        val validity = if (invalid > 0L || s.stallCount > 0L) listOf(
            "${s.stallCount} release stalls · ${ms(s.stallTotalNs / 1_000_000.0)} ms total · $invalid invalid samples",
        ) else emptyList()
        return listOf(
            "Video ${ms(s.p50Ms[m])} ms (p95 ${ms(s.p95Ms[m])}) · ${s.fps} fps · ${TogetherCopy.mbps(s.bitrate)} Mbps",
            "Input ${ms(s.p50Ms[LatencyStats.Metric.INPUT])} ms (p95 ${ms(s.p95Ms[LatencyStats.Metric.INPUT])})",
            "$audioLabel ${ms(s.p50Ms[LatencyStats.Metric.AUDIO])} ms · buffer ${s.audioBufferMs} ms · ${s.audioUnderruns} gaps · ${s.trackUnderruns} track underruns",
            "Wi-Fi round trip ${ms(s.rttMs)} ms · ${s.framesDropped} frames dropped",
        ) + validity
    }

    fun runLogLines(lines: List<String>): List<String> = lines.flatMap { line ->
        if (line.length <= 3000) listOf(line) else {
            val fields = line.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
            val chunks = line.chunked(3000)
            chunks.mapIndexed { index, chunk ->
                "RUNPART label=${fields.getValue("label")} metric=${fields.getValue("metric")} " +
                    "phase=${fields.getValue("phase")} part=$index parts=${chunks.size} data=$chunk"
            }
        }
    }

    private fun ms(v: Double?): String = if (v == null) "–" else String.format(Locale.US, "%.0f", v)
}
