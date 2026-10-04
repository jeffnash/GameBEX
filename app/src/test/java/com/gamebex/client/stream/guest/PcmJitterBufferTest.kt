package com.gamebex.client.stream.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guest audio buffering, with 10 ms chunks so the numbers read easily. */
class PcmJitterBufferTest {
    private val pcm = ByteArray(1_920)

    private fun PcmJitterBuffer.fill(from: Int, count: Int) {
        for (s in from until from + count) push(s, s * 10_000_000L, pcm)
    }

    @Test
    fun waitsForTheTargetBeforePlaying() {
        val b = PcmJitterBuffer(chunkMs = 10)
        b.fill(1, 2)
        assertNull(b.pull())
        assertEquals(0, b.underruns)
        b.fill(3, 1)
        assertEquals(1, b.pull()!!.seq)
        assertEquals(20, b.depthMs)
    }

    @Test
    fun ignoresDuplicatesAndOldChunks() {
        val b = PcmJitterBuffer(chunkMs = 10)
        b.fill(5, 4)
        b.push(5, 0L, pcm)
        b.push(2, 0L, pcm)
        assertEquals(40, b.depthMs)
    }

    @Test
    fun aShortUnderrunRaisesTheTargetButResumesWithOneChunk() {
        var now = 0L
        val b = PcmJitterBuffer(chunkMs = 10, startupMs = 0, nowNs = { now })
        b.fill(1, 3)
        repeat(3) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        assertEquals(1, b.underruns)
        assertEquals(35, b.targetMs)
        now = 20_000_000L
        assertNull(b.pull())
        assertEquals(1, b.underruns)
        assertEquals(35, b.targetMs)
        b.fill(4, 1)
        assertEquals(4, b.pull()!!.seq)
        assertEquals(0, b.depthMs)
    }

    @Test
    fun aGapAtExactlyOneHundredMillisecondsDoesNotReprime() {
        var now = 0L
        val b = PcmJitterBuffer(chunkMs = 10, startupMs = 0, nowNs = { now })
        b.fill(1, 3)
        repeat(3) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        now = 100_000_000L
        b.fill(4, 1)
        assertEquals(4, b.pull()!!.seq)
        assertEquals(1, b.underruns)
    }

    @Test
    fun aShortArrivalDoesNotTurnIntoALongGapWhenThePlayerIsDelayed() {
        var now = 0L
        val b = PcmJitterBuffer(chunkMs = 10, startupMs = 0, nowNs = { now })
        b.fill(1, 3)
        repeat(3) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        now = 99_999_999L
        b.fill(4, 1)
        now = 300_000_000L
        assertEquals(4, b.pull()!!.seq)
        assertEquals(1, b.underruns)
    }

    @Test
    fun aGapBeyondOneHundredMillisecondsReprimesEvenWithoutAnEmptyPoll() {
        var now = 0L
        val b = PcmJitterBuffer(chunkMs = 10, startupMs = 0, nowNs = { now })
        b.fill(1, 3)
        repeat(3) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        now = 100_000_001L
        b.fill(4, 1)
        assertNull(b.pull())
        now = 200_000_000L
        b.fill(5, 2)
        assertNull(b.pull())
        assertEquals(30, b.depthMs)
        assertEquals(35, b.targetMs)
        assertEquals(1, b.underruns)
        b.fill(7, 1)
        assertEquals(4, b.pull()!!.seq)
    }

    @Test
    fun anEmptyLongGapStaysOneUnderrunUntilAudioResumes() {
        var now = 0L
        val b = PcmJitterBuffer(chunkMs = 10, startupMs = 0, nowNs = { now })
        b.fill(1, 3)
        repeat(3) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        repeat(50) {
            now += 10_000_000L
            assertNull(b.pull())
        }
        assertEquals(1, b.underruns)
        assertEquals(35, b.targetMs)
        b.fill(4, 3)
        assertNull(b.pull())
        b.fill(7, 1)
        assertEquals(4, b.pull()!!.seq)
        repeat(3) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        assertEquals(2, b.underruns)
        assertEquals(40, b.targetMs)
    }

    @Test
    fun eachSeparatePostStartupUnderrunAddsFiveMillisecondsWithFiveMillisecondChunks() {
        val b = PcmJitterBuffer(chunkMs = 5, startupMs = 0, nowNs = { 0L })
        b.fill(1, 6)
        repeat(6) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        assertEquals(35, b.targetMs)
        b.fill(7, 1)
        assertEquals(7, b.pull()!!.seq)
        assertNull(b.pull())
        assertEquals(40, b.targetMs)
        assertEquals(2, b.underruns)
    }

    @Test
    fun theStartupGraceEndsAfterTheConfiguredPlaybackDuration() {
        val b = PcmJitterBuffer(chunkMs = 10, startupMs = 40, nowNs = { 0L })
        b.fill(1, 3)
        repeat(3) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        assertEquals(30, b.targetMs)
        b.fill(4, 1)
        assertEquals(4, b.pull()!!.seq)
        assertNull(b.pull())
        assertEquals(35, b.targetMs)
    }

    @Test
    fun underrunsWhileTheStreamStartsDoNotRaiseTheTarget() {
        val b = PcmJitterBuffer(chunkMs = 10)
        b.fill(1, 3)
        repeat(3) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        assertEquals(1, b.underruns)
        assertEquals(30, b.targetMs)
    }

    @Test
    fun theTargetNeverPassesTheCeiling() {
        val b = PcmJitterBuffer(chunkMs = 10, startupMs = 0, nowNs = { 0L })
        b.fill(1, 3)
        repeat(3) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        repeat(20) {
            b.fill(4 + it, 1)
            assertNotNull(b.pull())
            assertNull(b.pull())
        }
        assertEquals(100, b.targetMs)
        assertEquals(21, b.underruns)
    }

    @Test
    fun tooMuchQueuedAudioIsCutBackToTheTarget() {
        var evicted = 0L
        val b = PcmJitterBuffer(chunkMs = 10, onCounter = { name, delta ->
            if (name == "a_evicted_frames") evicted += delta
        })
        b.fill(1, 12)
        assertEquals(120, b.depthMs)
        b.fill(13, 1)
        assertEquals(30, b.depthMs)
        assertEquals(10, b.dropped)
        assertEquals(4_800L, evicted)
        assertEquals(b.evictedFrames, evicted)
        assertEquals(11, b.pull()!!.seq)
    }

    @Test
    fun aLingeringBacklogDrainsBackTowardTheTarget() {
        var evicted = 0L
        val b = PcmJitterBuffer(chunkMs = 10, onCounter = { name, delta ->
            if (name == "a_evicted_frames") evicted += delta
        })
        b.fill(1, 9)
        var seq = 10
        repeat(400) {
            b.push(seq++, 0L, pcm)
            assertNotNull(b.pull())
        }
        assertTrue("depth ${b.depthMs}", b.depthMs <= b.targetMs + PcmJitterBuffer.SLACK_MS)
        assertTrue(b.dropped >= 3)
        assertEquals(b.dropped * 480L, evicted)
        assertEquals(b.evictedFrames, evicted)
        assertEquals(0, b.underruns)
    }

    @Test
    fun cleanPlayLowersTheTargetToTheFloor() {
        var evicted = 0L
        val b = PcmJitterBuffer(chunkMs = 10, onCounter = { name, delta ->
            if (name == "a_evicted_frames") evicted += delta
        })
        var seq = 1
        b.fill(seq, 4)
        seq += 4
        repeat(b.shrinkAfterPulls * 3) {
            b.push(seq++, 0L, pcm)
            assertNotNull(b.pull())
        }
        assertEquals(20, b.targetMs)
        assertEquals(b.evictedFrames, evicted)
        assertTrue(evicted > 0L)
        assertEquals(0, b.underruns)
        assertTrue("depth ${b.depthMs}", b.depthMs <= b.targetMs + PcmJitterBuffer.SLACK_MS)
    }

    @Test
    fun anUnderrunRestartsTheCleanWindowBeforeTheTargetShrinks() {
        val b = PcmJitterBuffer(chunkMs = 10, startupMs = 0, nowNs = { 0L })
        b.fill(1, 3)
        var seq = 4
        repeat(b.shrinkAfterPulls - 4) {
            assertNotNull(b.pull())
            b.fill(seq++, 1)
        }
        repeat(3) { assertNotNull(b.pull()) }
        assertEquals(30, b.targetMs)
        assertNull(b.pull())
        assertEquals(35, b.targetMs)
        repeat(b.shrinkAfterPulls - 1) {
            b.fill(seq++, 1)
            assertNotNull(b.pull())
        }
        assertEquals(35, b.targetMs)
        b.fill(seq, 1)
        assertNotNull(b.pull())
        assertEquals(25, b.targetMs)
    }

    @Test
    fun resetForgetsSequenceQueueTargetAndGapButKeepsSessionCounters() {
        var now = 0L
        val b = PcmJitterBuffer(chunkMs = 10, startupMs = 40, nowNs = { now })
        b.fill(100, 13)
        assertEquals(10, b.dropped)
        repeat(3) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        b.fill(113, 1)
        assertNotNull(b.pull())
        assertNull(b.pull())
        assertEquals(35, b.targetMs)
        now = 200_000_000L
        b.fill(114, 1)
        assertNull(b.pull())
        val underruns = b.underruns
        val dropped = b.dropped
        b.reset()
        assertEquals(0, b.depthMs)
        assertEquals(underruns, b.underruns)
        assertEquals(dropped, b.dropped)
        assertEquals(30, b.targetMs)
        b.fill(1, 2)
        assertNull(b.pull())
        b.fill(3, 1)
        assertEquals(1, b.pull()!!.seq)
        repeat(2) { assertNotNull(b.pull()) }
        assertNull(b.pull())
        assertEquals(30, b.targetMs)
        b.fill(4, 1)
        assertEquals(4, b.pull()!!.seq)
    }

    @Test
    fun countsPacketGapsDuplicatesLateEvictionsAndSilenceAcrossReconnect() {
        val b = PcmJitterBuffer()
        b.push(100, 1, pcm)
        b.push(102, 3, pcm)
        b.push(102, 3, pcm)
        b.push(101, 2, pcm)
        assertEquals(1L, b.gapPackets)
        assertEquals((pcm.size / 4).toLong(), b.duplicateFrames)
        assertEquals((pcm.size / 4).toLong(), b.lateFrames)
        assertNull(b.pull())
        assertEquals(240L, b.silenceFrames)
        val generation = b.generation
        b.reset()
        assertEquals((pcm.size / 4 * 2).toLong(), b.evictedFrames)
        assertEquals(generation + 1, b.generation)
        assertEquals(1L, b.gapPackets)
        assertEquals(240L, b.silenceFrames)
    }

    @Test
    fun callbackDeltasStayPositiveAndSynchronizedAcrossSequenceWrapAndReset() {
        val events = mutableListOf<Pair<String, Long>>()
        lateinit var b: PcmJitterBuffer
        b = PcmJitterBuffer(onCounter = { name, delta ->
            assertTrue(Thread.holdsLock(b))
            assertTrue(delta > 0L)
            events += name to delta
        })
        b.push(Int.MAX_VALUE, 1L, pcm)
        b.push(Int.MIN_VALUE, 2L, pcm)
        b.push(Int.MIN_VALUE, 2L, pcm)
        b.push(Int.MAX_VALUE, 1L, pcm)
        assertNull(b.pull())
        b.reset()
        b.reset()
        assertEquals(listOf(
            "a_source_unique_frames" to 480L,
            "a_source_unique_frames" to 480L,
            "a_duplicate_frames" to 480L,
            "a_late_frames" to 480L,
            "a_silence_frames" to 240L,
            "a_evicted_frames" to 960L,
        ), events)
        assertEquals(0L, b.gapPackets)
        assertEquals(960L, b.sourceUniqueFrames)
        b.push(999, 3L, pcm)
        assertEquals(0L, b.gapPackets)
        assertEquals(1_440L, b.sourceUniqueFrames)
    }

    @Test
    fun eventTimeCountersExcludePreRenderAndWarmupButIncludeFinalHalfSecond() {
        var now = 0L
        val stats = LatencyStats(clock = { now }, measureWarmupSeconds = 1,
            measureSeconds = 2, runEnabled = true, deferredRunStart = true)
        val b = PcmJitterBuffer(nowNs = { now }, onCounter = { name, delta -> stats.count(name, delta) })
        val packet = ByteArray(960)
        b.push(100, 1L, packet)
        b.push(102, 3L, packet)
        assertNull(b.pull())
        now = 10_000_000_000L
        stats.beginMeasurement(now)
        stats.frameRendered(now)
        now += 500_000_000L
        b.push(104, 5L, packet)
        assertNull(b.pull())
        now = 12_000_000_000L
        repeat(3) {
            stats.audioBufferMs = b.depthMs
            stats.audioUnderruns = b.underruns
            stats.snapshot(now)
            stats.statsLine(now)
        }
        now += 500_000_000L
        b.push(107, 8L, packet)
        b.push(107, 8L, packet)
        b.push(106, 7L, packet)
        assertNull(b.pull())
        b.reset()
        val cumulative = stats.snapshot(now).counters
        assertEquals(4L, cumulative["a_gap_pkts"])
        assertEquals(960L, cumulative["a_source_unique_frames"])
        assertEquals(240L, cumulative["a_duplicate_frames"])
        assertEquals(240L, cumulative["a_late_frames"])
        assertEquals(960L, cumulative["a_evicted_frames"])
        assertEquals(720L, cumulative["a_silence_frames"])
        now = 13_000_000_000L
        stats.tick(now)
        repeat(3) {
            stats.audioBufferMs = b.depthMs
            stats.audioUnderruns = b.underruns
            assertEquals(cumulative, stats.snapshot(now).counters)
            stats.statsLine(now)
        }
        val line = stats.runLines(now).single { it.contains(" metric=counters ") }
        val counters = line.split(' ').filter { '=' in it }.associate {
            it.substringBefore('=') to it.substringAfter('=')
        }
        assertEquals("measure_end", counters["phase"])
        assertEquals("11000000000", counters["start_ns"])
        assertEquals("13000000000", counters["end_ns"])
        assertEquals("2", counters["a_gap_pkts"])
        assertEquals("240", counters["a_source_unique_frames"])
        assertEquals("240", counters["a_duplicate_frames"])
        assertEquals("240", counters["a_late_frames"])
        assertEquals("960", counters["a_evicted_frames"])
        assertEquals("240", counters["a_silence_frames"])
        now += 100_000_000L
        b.push(109, 10L, packet)
        b.push(111, 12L, packet)
        assertEquals(5L, stats.snapshot(now).counters["a_gap_pkts"])
        assertTrue(stats.runLines(now).isEmpty())
    }

    @Test
    fun shortTrackWritesRetryOnlyTheUnwrittenSuffixAndAccountForEveryByte() {
        val requests = mutableListOf<Pair<Int, Int>>()
        val accounted = mutableListOf<Int>()
        val results = listOf(3, 1, 4)
        val n = writePcmFully(
            size = 8,
            keepWriting = { true },
            write = { offset, remaining ->
                requests += offset to remaining
                results[requests.lastIndex]
            },
            onWritten = { accounted += it },
        )
        assertEquals(8, n)
        assertEquals(listOf(0 to 8, 3 to 5, 4 to 4), requests)
        assertEquals(results, accounted)
        assertEquals(8, accounted.sum())
    }

    @Test
    fun trackWriteErrorsAndZeroProgressStopWithoutAccountingForMissingBytes() {
        for (result in listOf(0, -6)) {
            var calls = 0
            var accounted = 0
            val n = writePcmFully(
                size = 8,
                keepWriting = { true },
                write = { _, _ -> if (calls++ == 0) 4 else result },
                onWritten = { accounted += it },
            )
            assertEquals(result, n)
            assertEquals(2, calls)
            assertEquals(4, accounted)
        }
    }

    @Test
    fun stoppingDuringAShortTrackWriteDoesNotWriteTheRest() {
        var running = true
        var calls = 0
        var accounted = 0
        val n = writePcmFully(
            size = 8,
            keepWriting = { running },
            write = { _, _ ->
                calls += 1
                running = false
                4
            },
            onWritten = { accounted += it },
        )
        assertEquals(4, n)
        assertEquals(1, calls)
        assertEquals(4, accounted)
    }
}
