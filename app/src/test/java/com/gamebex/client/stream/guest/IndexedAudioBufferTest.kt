package com.gamebex.client.stream.guest

import org.junit.Assert.*
import org.junit.Test

class IndexedAudioBufferTest {
    private fun pcm(value: Int, frames: Int = 240): ByteArray = ByteArray(frames * 4).also { bytes ->
        repeat(frames * 2) { bytes[it * 2] = value.toByte(); bytes[it * 2 + 1] = (value shr 8).toByte() }
    }
    private fun take(buffer: IndexedAudioBuffer, frames: Int): Pair<ShortArray, BooleanArray> {
        val samples = ShortArray(frames * 2)
        val present = BooleanArray(frames)
        assertNotNull(buffer.take(buffer.snapshot().epoch, frames, samples, present, LongArray(frames), IntArray(frames)))
        return samples to present
    }
    @Test fun receiving100And102Preserves101Hole() {
        val buffer = IndexedAudioBuffer()
        buffer.push(100, 1, 240, pcm(100))
        buffer.push(102, 3, 240, pcm(102))
        assertEquals(720, buffer.snapshot().spanFrames)
        assertEquals(480L, buffer.snapshot().buffered)
        val (samples, present) = take(buffer, 720)
        assertEquals(100.toShort(), samples[0])
        assertEquals(0.toShort(), samples[480])
        assertEquals(102.toShort(), samples[960])
        assertEquals(480, present.count { it })
        assertEquals(240L, buffer.snapshot().missing)
    }
    @Test fun takeStopsAtArrivedHighWaterAndAcceptsNextArrivalAfterGap() {
        val events = mutableListOf<Pair<String, Long>>()
        val buffer = IndexedAudioBuffer(onCounter = { name, delta -> events += name to delta })
        buffer.push(100, 1L, 240, pcm(100))
        buffer.push(102, 3L, 240, pcm(102))
        val samples = ShortArray(1_024 * 2) { -1 }
        val present = BooleanArray(1_024) { true }
        val captures = LongArray(1_024) { -1L }
        val sequences = IntArray(1_024) { -1 }
        val epoch = buffer.snapshot().epoch
        val read = buffer.take(epoch, 1_024, samples, present, captures, sequences)
        assertEquals(IndexedAudioBuffer.Read(0L, 720), read)
        assertEquals(480, present.take(read!!.frames).count { it })
        assertEquals(100.toShort(), samples[0])
        assertEquals(0.toShort(), samples[480])
        assertEquals(102.toShort(), samples[960])
        assertEquals((-1).toShort(), samples[1_440])
        assertEquals(-1L, captures[720])
        assertEquals(-1, sequences[720])
        assertEquals(720L, buffer.snapshot().next)
        assertEquals(720L, buffer.snapshot().high)
        assertEquals(240L, buffer.snapshot().missing)
        val eventCount = events.size
        repeat(3) {
            assertEquals(IndexedAudioBuffer.Read(720L, 0),
                buffer.take(epoch, 512, samples, present, captures, sequences))
            assertEquals(720L, buffer.snapshot().next)
            assertEquals(240L, buffer.snapshot().missing)
        }
        assertEquals(eventCount, events.size)
        assertEquals(0, buffer.push(101, 2L, 240, pcm(101)))
        assertEquals(240L, buffer.snapshot().late)
        assertEquals(240, buffer.push(103, 4L, 240, pcm(103)))
        assertEquals(IndexedAudioBuffer.Read(720L, 240),
            buffer.take(epoch, 512, samples, present, captures, sequences))
        assertTrue(present.take(240).all { it })
        assertEquals(103.toShort(), samples[0])
        assertEquals(4L, captures[0])
        assertEquals(103, sequences[0])
        assertEquals(960L, buffer.snapshot().next)
        assertEquals(buffer.snapshot().high, buffer.snapshot().next)
        assertEquals(240L, buffer.snapshot().missing)
        assertEquals(240L, events.filter { it.first == "a_missing_frames" }.sumOf { it.second })
        assertEquals(0L, buffer.snapshot().buffered)
        assertEquals(0L, buffer.snapshot().rebasedFrames)
    }
    @Test fun noDataDoesNotAdvanceOrInventLossAndResetRejectsOldEpoch() {
        val buffer = IndexedAudioBuffer()
        val samples = ShortArray(1_024)
        val present = BooleanArray(512)
        val captures = LongArray(512)
        val sequences = IntArray(512)
        val initialEpoch = buffer.snapshot().epoch
        assertNull(buffer.take(initialEpoch, 512, samples, present, captures, sequences))
        assertEquals(0L, buffer.snapshot().next)
        assertEquals(0L, buffer.snapshot().high)
        assertEquals(0L, buffer.snapshot().missing)
        assertEquals(240, buffer.push(50, 1L, 240, pcm(50)))
        assertEquals(IndexedAudioBuffer.Read(0L, 240),
            buffer.take(initialEpoch, 512, samples, present, captures, sequences))
        val exhausted = buffer.snapshot()
        repeat(3) {
            assertEquals(IndexedAudioBuffer.Read(240L, 0),
                buffer.take(initialEpoch, 512, samples, present, captures, sequences))
            assertEquals(exhausted, buffer.snapshot())
        }
        assertEquals(240, buffer.push(51, 2L, 240, pcm(51)))
        buffer.reset()
        assertNull(buffer.take(initialEpoch, 512, samples, present, captures, sequences))
        assertEquals(240, buffer.push(999, 3L, 240, pcm(999)))
        val reconnected = buffer.snapshot()
        assertTrue(reconnected.epoch > initialEpoch)
        assertEquals(0L, reconnected.next)
        assertEquals(240L, reconnected.high)
        assertEquals(0L, reconnected.missing)
        assertEquals(0L, reconnected.late)
        assertEquals(240L, reconnected.evicted)
        assertEquals(IndexedAudioBuffer.Read(0L, 240),
            buffer.take(reconnected.epoch, 512, samples, present, captures, sequences))
    }
    @Test fun late101BeforeTakeFillsHole() {
        val stats = LatencyStats()
        val buffer = IndexedAudioBuffer(onCounter = { name, delta -> stats.count(name, delta) })
        buffer.push(100, 1, 240, pcm(100)); buffer.push(102, 3, 240, pcm(102))
        assertEquals(240, buffer.push(101, 2, 240, pcm(101)))
        assertTrue(take(buffer, 720).second.all { it })
        assertEquals(0L, buffer.snapshot().missing)
        assertEquals(1L, buffer.snapshot().gapPackets)
        assertEquals(720L, stats.snapshot(0L).counters["a_source_unique_frames"])
        assertFalse(stats.snapshot(0L).counters.containsKey("a_missing_frames"))
    }
    @Test fun late101AfterTakeDoesNotReplay() {
        val buffer = IndexedAudioBuffer()
        buffer.push(100, 1, 240, pcm(100)); buffer.push(102, 3, 240, pcm(102))
        take(buffer, 720)
        assertEquals(0, buffer.push(101, 2, 240, pcm(101)))
        assertEquals(240L, buffer.snapshot().late)
        assertEquals(0L, buffer.snapshot().buffered)
    }
    @Test fun overlapOnlyAddsUnseenSamples() {
        val buffer = IndexedAudioBuffer()
        buffer.push(1, 1, 240, pcm(1))
        take(buffer, 100)
        assertEquals(0, buffer.push(1, 1, 240, pcm(1)))
        assertEquals(100L, buffer.snapshot().late)
        assertEquals(140L, buffer.snapshot().duplicate)
        assertEquals(140L, buffer.snapshot().buffered)
    }
    @Test fun sequenceWrapIsContinuous() {
        val events = mutableListOf<Pair<String, Long>>()
        val buffer = IndexedAudioBuffer(onCounter = { name, delta -> events += name to delta })
        buffer.push(Int.MAX_VALUE, 1, 240, pcm(1))
        buffer.push(Int.MIN_VALUE, 2, 240, pcm(2))
        assertEquals(480, buffer.snapshot().spanFrames)
        assertEquals(0L, buffer.snapshot().gapPackets)
        assertEquals(listOf("a_source_unique_frames" to 240L, "a_source_unique_frames" to 240L), events)
    }
    @Test fun frameSizeChangeExplicitlyRebases() {
        val buffer = IndexedAudioBuffer()
        buffer.push(1, 1, 240, pcm(1))
        val epoch = buffer.snapshot().epoch
        buffer.push(2, 2, 256, pcm(2, 256))
        assertTrue(buffer.snapshot().epoch > epoch)
        assertEquals(240L, buffer.snapshot().evicted)
        assertEquals(256, buffer.snapshot().spanFrames)
        assertEquals(0L, buffer.snapshot().missing)
    }
    @Test fun capacityOverflowCountedNotSilentlyTrimmed() {
        val buffer = IndexedAudioBuffer(1_024)
        buffer.push(1, 1, 240, pcm(1))
        val epoch = buffer.snapshot().epoch
        buffer.push(10, 2, 240, pcm(10))
        assertEquals(240L, buffer.snapshot().evicted)
        assertEquals(2_160L, buffer.snapshot().rebasedFrames)
        assertTrue(buffer.snapshot().epoch > epoch)
        assertEquals(240, buffer.snapshot().spanFrames)
    }
    @Test fun firstJoinDoesNotInventPreAttachLossAndReconnectKeepsCounters() {
        val buffer = IndexedAudioBuffer()
        buffer.push(-1, 1, 240, pcm(1))
        buffer.push(-1, 1, 240, pcm(1))
        buffer.reset()
        buffer.push(999, 2, 240, pcm(2))
        assertEquals(0L, buffer.snapshot().gapPackets)
        assertEquals(240L, buffer.snapshot().duplicate)
        assertEquals(240L, buffer.snapshot().evicted)
        assertEquals(240L, buffer.snapshot().buffered)
    }
    @Test fun callbackDeltasMatchCumulativeCountersUnderMutationLock() {
        val stats = LatencyStats()
        val events = mutableListOf<Pair<String, Long>>()
        lateinit var buffer: IndexedAudioBuffer
        buffer = IndexedAudioBuffer(1_024, onCounter = { name, delta ->
            assertTrue(Thread.holdsLock(buffer))
            assertTrue(delta > 0L)
            events += name to delta
            stats.count(name, delta)
        })
        buffer.push(1, 1L, 240, pcm(1))
        buffer.push(10, 10L, 240, pcm(10))
        assertEquals(8L, stats.snapshot(0L).counters["a_gap_pkts"])
        assertEquals(2_160L, stats.snapshot(0L).counters["audio_explicit_rebase_frames"])
        buffer.push(10, 10L, 240, pcm(10))
        take(buffer, 100)
        buffer.push(10, 10L, 240, pcm(10))
        buffer.push(12, 12L, 240, pcm(12))
        take(buffer, 240)
        assertEquals(140, buffer.push(11, 11L, 240, pcm(11)))
        buffer.reset()
        val snapshot = buffer.snapshot()
        val counters = stats.snapshot(0L).counters
        assertEquals(snapshot.gapPackets, counters["a_gap_pkts"])
        assertEquals(snapshot.unique, counters["a_source_unique_frames"])
        assertEquals(snapshot.duplicate, counters["a_duplicate_frames"])
        assertEquals(snapshot.late, counters["a_late_frames"])
        assertEquals(snapshot.evicted, counters["a_evicted_frames"])
        assertEquals(snapshot.missing, counters["a_missing_frames"])
        assertEquals(snapshot.rebasedFrames, counters["audio_explicit_rebase_frames"])
        assertEquals(100L, snapshot.missing)
        assertEquals(9L, snapshot.gapPackets)
        assertEquals(620L, snapshot.evicted)
        assertEquals(200L, snapshot.late)
        assertEquals(380L, snapshot.duplicate)
        assertEquals(860L, snapshot.unique)
        val eventCount = events.size
        repeat(3) { buffer.snapshot(); stats.snapshot(0L); stats.statsLine(0L) }
        buffer.reset()
        assertEquals(eventCount, events.size)
        buffer.push(100, 100L, 256, pcm(100, 256))
        buffer.push(101, 101L, 240, pcm(101))
        assertEquals(snapshot.evicted + 256L, buffer.snapshot().evicted)
        assertEquals(snapshot.evicted + 256L, stats.snapshot(0L).counters["a_evicted_frames"])
    }

    @Test fun eventTimeCountersExcludePreRenderAndWarmupButIncludeFinalHalfSecond() {
        var now = 0L
        val stats = LatencyStats(clock = { now }, measureWarmupSeconds = 1,
            measureSeconds = 2, runEnabled = true, deferredRunStart = true)
        val buffer = IndexedAudioBuffer(onCounter = { name, delta -> stats.count(name, delta) })
        buffer.push(100, 1L, 240, pcm(100))
        buffer.push(102, 3L, 240, pcm(102))
        now = 10_000_000_000L
        stats.beginMeasurement(now)
        stats.frameRendered(now)
        now += 500_000_000L
        buffer.push(104, 5L, 240, pcm(104))
        now = 12_000_000_000L
        repeat(3) {
            stats.audioBufferMs = buffer.snapshot().spanFrames * 1_000 / 48_000
            stats.snapshot(now)
            stats.statsLine(now)
        }
        now += 500_000_000L
        buffer.push(107, 8L, 240, pcm(107))
        buffer.push(107, 8L, 240, pcm(107))
        take(buffer, 720)
        buffer.push(101, 2L, 240, pcm(101))
        buffer.reset()
        val cumulative = stats.snapshot(now).counters
        assertEquals(4L, cumulative["a_gap_pkts"])
        assertEquals(960L, cumulative["a_source_unique_frames"])
        assertEquals(240L, cumulative["a_duplicate_frames"])
        assertEquals(240L, cumulative["a_late_frames"])
        assertEquals(480L, cumulative["a_evicted_frames"])
        assertEquals(240L, cumulative["a_missing_frames"])
        now = 13_000_000_000L
        stats.tick(now)
        repeat(3) {
            stats.audioBufferMs = buffer.snapshot().spanFrames * 1_000 / 48_000
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
        assertEquals("480", counters["a_evicted_frames"])
        assertEquals("240", counters["a_missing_frames"])
        now += 100_000_000L
        buffer.push(999, 99L, 240, pcm(999))
        buffer.push(1001, 101L, 240, pcm(1001))
        assertEquals(5L, stats.snapshot(now).counters["a_gap_pkts"])
        assertTrue(stats.runLines(now).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun frameCountByteMismatchRejected() {
        IndexedAudioBuffer().push(1, 1, 240, ByteArray(4))
    }
}
