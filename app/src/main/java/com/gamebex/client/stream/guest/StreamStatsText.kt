package com.gamebex.client.stream.guest

import com.gamebex.client.together.TogetherCopy
import java.util.Locale

/** Stats overlay lines: plain numbers, no judgement words. */
object StreamStatsText {
    fun lines(s: LatencyStats.Snapshot): List<String> {
        val m = LatencyStats.Metric.VIDEO
        return listOf(
            "Video ${ms(s.p50Ms[m])} ms (p95 ${ms(s.p95Ms[m])}) · ${s.fps} fps · ${TogetherCopy.mbps(s.bitrate)} Mbps",
            "Input ${ms(s.p50Ms[LatencyStats.Metric.INPUT])} ms (p95 ${ms(s.p95Ms[LatencyStats.Metric.INPUT])})",
            "Audio ${ms(s.p50Ms[LatencyStats.Metric.AUDIO])} ms · buffer ${s.audioBufferMs} ms · ${s.audioUnderruns} gaps · ${s.trackUnderruns} track underruns",
            "Wi-Fi round trip ${ms(s.rttMs)} ms · ${s.framesDropped} frames dropped",
        )
    }

    private fun ms(v: Double?): String = if (v == null) "–" else String.format(Locale.US, "%.0f", v)
}
