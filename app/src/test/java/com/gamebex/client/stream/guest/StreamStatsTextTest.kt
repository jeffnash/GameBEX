package com.gamebex.client.stream.guest

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStatsTextTest {
    @Test
    fun formatsEveryLine() {
        val s = LatencyStats()
        val ms = 1_000_000L
        s.add(LatencyStats.Metric.VIDEO, 38 * ms)
        s.add(LatencyStats.Metric.INPUT, 6 * ms)
        s.bitrate = 7_800_000
        s.audioBufferMs = 40
        s.audioUnderruns = 2
        s.trackUnderruns = 5
        s.rttNs = 4 * ms
        assertEquals(
            listOf(
                "Video 38 ms (p95 38) · 0 fps · 7.8 Mbps",
                "Input 6 ms (p95 6)",
                "Audio – ms · buffer 40 ms · 2 gaps · 5 track underruns",
                "Wi-Fi round trip 4 ms · 0 frames dropped",
            ),
            StreamStatsText.lines(s.snapshot(0)),
        )
    }
}
