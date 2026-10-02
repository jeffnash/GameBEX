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
        val b = PcmJitterBuffer(chunkMs = 10)
        b.fill(1, 12)
        assertEquals(120, b.depthMs)
        b.fill(13, 1)
        assertEquals(30, b.depthMs)
        assertEquals(10, b.dropped)
        assertEquals(11, b.pull()!!.seq)
    }

    @Test
    fun aLingeringBacklogDrainsBackTowardTheTarget() {
        val b = PcmJitterBuffer(chunkMs = 10)
        b.fill(1, 9)
        var seq = 10
        repeat(400) {
            b.push(seq++, 0L, pcm)
            assertNotNull(b.pull())
        }
        assertTrue("depth ${b.depthMs}", b.depthMs <= b.targetMs + PcmJitterBuffer.SLACK_MS)
        assertTrue(b.dropped >= 3)
        assertEquals(0, b.underruns)
    }

    @Test
    fun cleanPlayLowersTheTargetToTheFloor() {
        val b = PcmJitterBuffer(chunkMs = 10)
        var seq = 1
        b.fill(seq, 4)
        seq += 4
        repeat(b.shrinkAfterPulls * 3) {
            b.push(seq++, 0L, pcm)
            assertNotNull(b.pull())
        }
        assertEquals(20, b.targetMs)
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
