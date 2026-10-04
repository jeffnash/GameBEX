package com.gamebex.client.stream.guest

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStatsTextTest {
    @Test
    fun rsoundTimingIsExplicitlyBridgeArrivalNotSourceAudio() {
        val stats = LatencyStats()
        stats.add(LatencyStats.Metric.AUDIO, 121_000_000L)
        assertEquals(
            "Bridge arrival → output 121 ms · buffer 0 ms · 0 gaps · 0 track underruns",
            StreamStatsText.lines(stats.snapshot(0), "bridge-arrival")[2],
        )
    }

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

    @Test
    fun oversizedRunHistogramsRoundTripWithMetricAndPhaseIdentified() {
        val row = "RUN label=B metric=video phase=measure_end hist=" + "1:5,".repeat(2500)
        val chunks = StreamStatsText.runLogLines(listOf(row))
        assertEquals(row, chunks.joinToString("") { it.substringAfter(" data=") })
        chunks.forEachIndexed { index, part ->
            assertEquals("RUNPART label=B metric=video phase=measure_end part=$index parts=${chunks.size}", part.substringBefore(" data="))
            org.junit.Assert.assertTrue(part.substringAfter(" data=").length <= 3000)
        }
        assertEquals(listOf("RUN label=B metric=decode phase=measure_end n=0"),
            StreamStatsText.runLogLines(listOf("RUN label=B metric=decode phase=measure_end n=0")))
    }

    @Test
    fun invalidSamplesAndReleaseStallsAreVisibleWithoutChangingAudioOrigin() {
        val s = LatencyStats()
        s.add(LatencyStats.Metric.PRESENT, -1L)
        s.frameRendered(0L)
        s.tick(200_000_000L)
        val lines = StreamStatsText.lines(s.snapshot(200_000_000L), "bridge-arrival")
        assertEquals("1 release stalls · 200 ms total · 1 invalid samples", lines.last())
        assertEquals("Bridge arrival → output – ms · buffer 0 ms · 0 gaps · 0 track underruns", lines[2])
    }
}
