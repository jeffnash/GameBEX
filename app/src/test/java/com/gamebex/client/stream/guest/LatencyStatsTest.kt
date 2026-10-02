package com.gamebex.client.stream.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyStatsTest {
    private val ms = 1_000_000L

    @Test
    fun percentilesOverTheWindow() {
        val s = LatencyStats()
        for (v in 1..100) s.add(LatencyStats.Metric.VIDEO, v * ms)
        assertEquals(51.0, s.percentileMs(LatencyStats.Metric.VIDEO, 0.5)!!, 0.0)
        assertEquals(95.0, s.percentileMs(LatencyStats.Metric.VIDEO, 0.95)!!, 0.0)
        assertNull(s.percentileMs(LatencyStats.Metric.INPUT, 0.5))
    }

    @Test
    fun onlyTheLastSamplesCount() {
        val s = LatencyStats(capacity = 10)
        repeat(10) { s.add(LatencyStats.Metric.INPUT, 100 * ms) }
        repeat(10) { s.add(LatencyStats.Metric.INPUT, 5 * ms) }
        assertEquals(5.0, s.percentileMs(LatencyStats.Metric.INPUT, 0.95)!!, 0.0)
    }

    @Test
    fun negativeSamplesCountAsZero() {
        val s = LatencyStats()
        s.add(LatencyStats.Metric.NETWORK, -3 * ms)
        assertEquals(0.0, s.percentileMs(LatencyStats.Metric.NETWORK, 0.5)!!, 0.0)
    }

    @Test
    fun fpsCountsRendersInTheLastSecond() {
        val s = LatencyStats()
        for (i in 0 until 90) s.frameRendered(i * 16 * ms)
        assertEquals(63, s.snapshot(89 * 16 * ms).fps)
    }

    @Test
    fun theStatsLineHasEveryKey() {
        val s = LatencyStats()
        s.add(LatencyStats.Metric.VIDEO, 38 * ms)
        s.add(LatencyStats.Metric.INPUT, 6 * ms)
        s.bitrate = 8_000_000
        s.audioUnderruns = 3
        s.trackUnderruns = 7
        val line = s.statsLine(0)
        assertTrue(line.startsWith("STATS "))
        for (k in listOf("video_p50=38.0", "video_p95=38.0", "video_n=1", "input_p50=6.0", "audio_p50=-", "fps=0", "underruns=3", "track_underruns=7", "bitrate=8000000", "reconnects=0")) {
            assertTrue(k, line.contains(" $k"))
        }
        val expectedKeys = LatencyStats.Metric.values().flatMap { m ->
            listOf("${m.key}_p50", "${m.key}_p95", "${m.key}_n")
        } + listOf("fps", "received", "rendered", "dropped", "underruns", "track_underruns", "audio_buffer", "rtt", "bitrate", "level", "reconnects")
        val actualKeys = line.removePrefix("STATS ").split(' ').map { it.substringBefore('=') }
        assertEquals(expectedKeys, actualKeys)
    }

    @Test
    fun jitterAndNativeUnderrunsHaveIndependentSnapshotValues() {
        val s = LatencyStats()
        s.audioUnderruns = 2
        s.trackUnderruns = 9
        val snapshot = s.snapshot(0)
        s.audioUnderruns = 4
        s.trackUnderruns = 12
        assertEquals(2, snapshot.audioUnderruns)
        assertEquals(9, snapshot.trackUnderruns)
        assertEquals(4, s.snapshot(0).audioUnderruns)
        assertEquals(12, s.snapshot(0).trackUnderruns)
    }

    @Test
    fun aSnapshotWithoutTheNewArgumentDefaultsNativeUnderrunsToZero() {
        val s = LatencyStats.Snapshot(
            p50Ms = emptyMap(),
            p95Ms = emptyMap(),
            samples = emptyMap(),
            framesReceived = 0,
            framesRendered = 0,
            framesDropped = 0,
            fps = 0,
            audioUnderruns = 5,
            audioBufferMs = 30,
            rttMs = null,
            bitrate = 0,
            level = 0,
            reconnects = 0,
        )
        assertEquals(0, s.trackUnderruns)
        assertEquals(5, s.audioUnderruns)
    }

    @Test
    fun decoderUnknownCaptureSkipsVideoButKeepsOtherOutputMetrics() {
        val s = LatencyStats()
        val clock = ClockSync().apply { add(100 * ms, 111 * ms, 112 * ms, 103 * ms) }
        VideoDecoder.recordOutputStats(s, clock, 0L, 140 * ms, 145 * ms, 160 * ms)
        val snapshot = s.snapshot(160 * ms)
        assertNull(snapshot.p50Ms[LatencyStats.Metric.VIDEO])
        assertEquals(0, snapshot.samples.getValue(LatencyStats.Metric.VIDEO))
        assertEquals(15.0, snapshot.p50Ms[LatencyStats.Metric.DECODE]!!, 0.0)
        assertEquals(15.0, snapshot.p50Ms[LatencyStats.Metric.NETWORK]!!, 0.0)
        assertEquals(1L, snapshot.framesRendered)
        assertEquals(1, snapshot.fps)
    }

    @Test
    fun decoderValidCaptureUsesClockOffsetAndUnknownCaptureDoesNotPolluteIt() {
        val s = LatencyStats()
        val clock = ClockSync().apply { add(100 * ms, 111 * ms, 112 * ms, 103 * ms) }
        VideoDecoder.recordOutputStats(s, clock, 120 * ms, 140 * ms, 145 * ms, 160 * ms)
        VideoDecoder.recordOutputStats(s, clock, 0L, 180 * ms, 185 * ms, 200 * ms)
        val snapshot = s.snapshot(200 * ms)
        assertEquals(50.0, snapshot.p50Ms[LatencyStats.Metric.VIDEO]!!, 0.0)
        assertEquals(1, snapshot.samples.getValue(LatencyStats.Metric.VIDEO))
        assertEquals(2, snapshot.samples.getValue(LatencyStats.Metric.DECODE))
        assertEquals(2, snapshot.samples.getValue(LatencyStats.Metric.NETWORK))
        assertEquals(2L, snapshot.framesRendered)
    }

    @Test
    fun decoderWithoutClockSyncStillRecordsDecodeAndFramesRendered() {
        val s = LatencyStats()
        VideoDecoder.recordOutputStats(s, ClockSync(), 120 * ms, 140 * ms, 145 * ms, 160 * ms)
        val snapshot = s.snapshot(160 * ms)
        assertNull(snapshot.p50Ms[LatencyStats.Metric.VIDEO])
        assertNull(snapshot.p50Ms[LatencyStats.Metric.NETWORK])
        assertEquals(15.0, snapshot.p50Ms[LatencyStats.Metric.DECODE]!!, 0.0)
        assertEquals(1L, snapshot.framesRendered)
    }
}
