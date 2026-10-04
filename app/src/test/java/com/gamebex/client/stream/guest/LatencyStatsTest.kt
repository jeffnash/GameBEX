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
    fun negativeSamplesAreInvalidNotZero() {
        val s = LatencyStats()
        s.add(LatencyStats.Metric.NETWORK, -3 * ms)
        assertNull(s.percentileMs(LatencyStats.Metric.NETWORK, 0.5))
        assertEquals(0, s.snapshot(0).samples[LatencyStats.Metric.NETWORK])
        assertEquals(1L, s.snapshot(0).invalid[LatencyStats.Metric.NETWORK])
        s.add(LatencyStats.Metric.NETWORK, 0L)
        assertEquals(0.0, s.percentileMs(LatencyStats.Metric.NETWORK, 0.5)!!, 0.0)
        assertEquals(1, s.snapshot(0).samples[LatencyStats.Metric.NETWORK])
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
            listOf("${m.key}_p50", "${m.key}_p95", "${m.key}_n", "${m.key}_invalid")
        } + listOf("fps", "received", "rendered", "dropped", "underruns", "track_underruns", "audio_buffer", "rtt", "bitrate", "level", "reconnects", "stall_n", "stall_total_ns", "stall_max_ns")
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
        val clock = ClockSync(clock = { 200 * ms }).apply {
            repeat(4) { i -> val t = (97 + i) * ms; add(t, t + 11 * ms, t + 12 * ms, t + 3 * ms) }
        }
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
        val clock = ClockSync(clock = { 200 * ms }).apply {
            repeat(4) { i -> val t = (97 + i) * ms; add(t, t + 11 * ms, t + 12 * ms, t + 3 * ms) }
        }
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

    @Test
    fun dropsAndNamedCountersAreCountedByMultiplicityAndSnapshotIsDetached() {
        val s = LatencyStats()
        s.frameDropped(8)
        s.count("dec_overflow", 8L)
        val snapshot = s.snapshot(0)
        s.count("dec_overflow")
        assertEquals(8L, snapshot.framesDropped)
        assertEquals(8L, snapshot.counters["dec_overflow"])
        assertEquals(9L, s.snapshot(0).counters["dec_overflow"])
    }

    @Test
    fun stalledProgressAccumulatesWithoutDoubleCountingAndResetsOnRelease() {
        val s = LatencyStats()
        s.frameRendered(0L)
        s.tick(100 * ms)
        assertEquals(0L, s.snapshot(100 * ms).stallCount)
        s.tick(200 * ms)
        s.tick(300 * ms)
        s.frameRendered(350 * ms)
        s.tick(550 * ms)
        val result = s.snapshot(550 * ms)
        assertEquals(2L, result.stallCount)
        assertEquals(550 * ms, result.stallTotalNs)
        assertEquals(350 * ms, result.stallMaxNs)
    }

    @Test
    fun startupFreezeAfterReceiveIsVisibleBeforeAnyRelease() {
        var now = 0L
        val s = LatencyStats(clock = { now })
        s.frameReceived()
        now = 200 * ms
        s.tick(now)
        assertEquals(1L, s.snapshot(now).stallCount)
        assertEquals(now, s.snapshot(now).stallTotalNs)
        s.frameRendered(250 * ms)
        assertEquals(250 * ms, s.snapshot(250 * ms).stallTotalNs)
    }

    @Test
    fun runExcludesWarmupClosesAtExactEndAndOnlyEmitsOnce() {
        var now = 0L
        val s = LatencyStats(capacity = 2, clock = { now }, measureWarmupSeconds = 10,
            measureSeconds = 120, runLabel = "B label", runEnabled = true)
        s.add(LatencyStats.Metric.DECODE, 99 * ms)
        now = 10_000 * ms
        s.add(LatencyStats.Metric.DECODE, 1_050_000L)
        s.add(LatencyStats.Metric.DECODE, 2_050_000L)
        s.count("dec_overflow", 2L)
        assertTrue(s.runLines().isEmpty())
        now = 130_000 * ms
        s.add(LatencyStats.Metric.DECODE, 99 * ms)
        val lines = s.runLines(audioOrigin = "bridge-arrival")
        val decode = lines.single { it.contains(" metric=decode ") }
        assertTrue(decode.contains("phase=measure_end start_ns=10000000000 end_ns=130000000000"))
        assertTrue(decode.contains("n=2 invalid=0 overflow=0 bin_us=100 p50_ns=1550000 p95_ns=2000000 hist=10:1,20:1"))
        assertTrue(decode.contains("label=B_label"))
        assertTrue(decode.endsWith("audio_origin=bridge-arrival"))
        assertTrue(lines.last().contains("dec_overflow=2"))
        assertTrue(s.runLines().isEmpty())
    }

    @Test
    fun deferredRunExcludesConstructorDelayAndStartsWarmupAtFirstRender() {
        var now = 0L
        val s = LatencyStats(clock = { now }, measureWarmupSeconds = 10, measureSeconds = 120,
            runLabel = "delayed", runEnabled = true, deferredRunStart = true)
        s.frameReceived()
        s.addClockSample(LatencyStats.Metric.AUDIO, 99 * ms, 9 * ms)
        s.addClockSample(LatencyStats.Metric.AUDIO, -1L, 9 * ms)
        s.frameDropped(8)
        s.count("dec_overflow", 8L)
        now = 200_000 * ms
        s.tick(now)
        s.frameRendered(now)
        assertTrue(s.runLines().isEmpty())
        s.beginMeasurement(now)
        now = 209_999 * ms
        s.addClockSample(LatencyStats.Metric.AUDIO, 88 * ms, 8 * ms)
        s.count("dec_overflow", 7L)
        now = 210_000 * ms
        s.addClockSample(LatencyStats.Metric.AUDIO, 3 * ms, 4 * ms)
        s.frameReceived()
        s.frameRendered(now)
        s.frameDropped(2)
        s.count("dec_overflow", 3L)
        now = 329_999 * ms
        assertTrue(s.runLines().isEmpty())
        now = 330_000 * ms
        val lines = s.runLines()
        val audio = lines.single { it.contains(" metric=audio ") }
        assertTrue(audio.contains("phase=measure_end start_ns=210000000000 end_ns=330000000000"))
        assertTrue(audio.contains("n=1 invalid=0 overflow=0"))
        assertTrue(audio.contains("p50_ns=3000000 p95_ns=3000000 hist=30:1"))
        assertTrue(audio.contains("uncertainty=interval error_n=1 error_p50_ns=4000000 error_p95_ns=4000000 error_hist=40:1"))
        assertTrue(lines.last().contains("received=1 rendered=1"))
        assertTrue(lines.last().contains("dropped=2"))
        assertTrue(lines.last().contains("dec_overflow=3"))
    }

    @Test
    fun repeatedMeasurementBeginAndReconnectRendersCannotResetRun() {
        var now = 0L
        val s = LatencyStats(clock = { now }, measureWarmupSeconds = 1, measureSeconds = 2,
            runEnabled = true, deferredRunStart = true)
        now = 5_000 * ms
        s.frameRendered(now)
        s.beginMeasurement(now)
        now = 6_000 * ms
        s.add(LatencyStats.Metric.DECODE, 1 * ms)
        s.beginMeasurement(now)
        s.frameRendered(now)
        now = 7_000 * ms
        s.reconnects += 1
        s.frameRendered(now)
        s.beginMeasurement(now)
        s.add(LatencyStats.Metric.DECODE, 2 * ms)
        now = 8_000 * ms
        s.beginMeasurement(now)
        val decode = s.runLines().single { it.contains(" metric=decode ") }
        assertTrue(decode.contains("phase=measure_end start_ns=6000000000 end_ns=8000000000"))
        assertTrue(decode.contains("n=2 invalid=0"))
        assertTrue(decode.contains("hist=10:1,20:1"))
        now = 20_000 * ms
        s.beginMeasurement(now)
        assertTrue(s.runLines().isEmpty())
    }

    @Test
    fun finishBeforeMeasurementBeginIsIncompleteWithNoInventedWindowOrSamples() {
        var now = 0L
        val s = LatencyStats(clock = { now }, runEnabled = true, deferredRunStart = true)
        s.frameReceived()
        s.frameDropped(8)
        s.count("dec_overflow", 8L)
        s.addClockSample(LatencyStats.Metric.AUDIO, 3 * ms, 4 * ms)
        s.addClockSample(LatencyStats.Metric.AUDIO, -1L, 4 * ms)
        now = 200_000 * ms
        s.tick(now)
        s.frameRendered(now)
        assertTrue(s.runLines().isEmpty())
        now = 400_000 * ms
        assertTrue(s.runLines().isEmpty())
        assertTrue(s.snapshot(now).stallTotalNs > 0L)
        s.finish()
        s.beginMeasurement(now)
        val lines = s.runLines()
        assertTrue(lines.all { it.contains("phase=incomplete start_ns=400000000000 end_ns=400000000000") })
        val audio = lines.single { it.contains(" metric=audio ") }
        assertTrue(audio.contains("n=0 invalid=0 overflow=0"))
        assertTrue(audio.contains("p50_ns=- p95_ns=- hist=-"))
        assertTrue(audio.contains("uncertainty=interval error_n=0 error_p50_ns=- error_p95_ns=- error_hist=-"))
        assertTrue(lines.last().contains("stall_n=0 stall_total_ns=0 stall_max_ns=0"))
        assertTrue(lines.last().contains("received=0 rendered=0"))
        assertTrue(lines.last().contains("dropped=0"))
        assertTrue(lines.last().contains("dec_overflow=0"))
        assertTrue(s.runLines().isEmpty())
    }

    @Test
    fun deferredMeasurementUsesExactHalfOpenDurationAndClipsStallsAtEnd() {
        var now = 0L
        val s = LatencyStats(capacity = 1, clock = { now }, measureWarmupSeconds = 0, measureSeconds = 2,
            runEnabled = true, deferredRunStart = true)
        now = 5_000 * ms
        s.beginMeasurement(now)
        s.frameRendered(now)
        s.add(LatencyStats.Metric.DECODE, 1 * ms)
        now = 7_000 * ms - 1L
        s.add(LatencyStats.Metric.DECODE, 2 * ms)
        s.count("dec_overflow", 2L)
        assertTrue(s.runLines().isEmpty())
        now = 7_000 * ms
        s.add(LatencyStats.Metric.DECODE, 99 * ms)
        s.count("dec_overflow", 9L)
        now = 20_000 * ms
        val lines = s.runLines()
        val decode = lines.single { it.contains(" metric=decode ") }
        assertTrue(decode.contains("phase=measure_end start_ns=5000000000 end_ns=7000000000"))
        assertTrue(decode.contains("n=2 invalid=0"))
        assertTrue(decode.contains("hist=10:1,20:1"))
        assertTrue(lines.last().contains("stall_n=1 stall_total_ns=2000000000 stall_max_ns=2000000000"))
        assertTrue(lines.last().contains("dec_overflow=2"))
        assertTrue(s.runLines().isEmpty())
    }

    @Test
    fun incompleteRunAndNegativeSamplesKeepHonestValidity() {
        var now = 0L
        val s = LatencyStats(clock = { now }, measureWarmupSeconds = 0, measureSeconds = 120, runEnabled = true)
        s.addClockSample(LatencyStats.Metric.AUDIO, -1L, 4 * ms)
        s.addClockSample(LatencyStats.Metric.AUDIO, 3 * ms, 4 * ms)
        now = 2_000 * ms
        s.finish()
        val audio = s.runLines().single { it.contains(" metric=audio ") }
        assertTrue(audio.contains("phase=incomplete"))
        assertTrue(audio.contains("n=1 invalid=1"))
        assertTrue(audio.contains("uncertainty=interval error_n=1 error_p50_ns=4000000 error_p95_ns=4000000 error_hist=40:1"))
    }
}
