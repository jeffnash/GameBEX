package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.MediaFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecoderTest {
    private fun frame(seq: Int, key: Boolean = true) =
        MediaFrame.Video(key, seq, 1L, 2L, 3L, byteArrayOf(1, 2))

    private class Losses {
        val reasons = mutableMapOf<String, Int>()
        val stats = LatencyStats()
        fun drop(reason: String, count: Int) {
            reasons[reason] = reasons.getOrDefault(reason, 0) + count
            stats.count(reason, count.toLong())
            stats.frameDropped(count)
        }
    }

    @Test
    fun overflowCountsEveryPendingAuAndTheRejectedDelta() {
        val losses = Losses()
        val frames = DecoderFrames(drop = losses::drop)
        frames.configure(byteArrayOf(1), 1280, 576, 1)
        repeat(8) { frames.offer(frame(it, key = it == 0), it.toLong(), 1) }
        assertEquals(8, frames.pendingCount)
        assertEquals(DecoderFrames.Offer.NEED_KEY, frames.offer(frame(8, key = false), 8, 1))
        assertEquals(8, losses.reasons["dec_overflow"])
        assertEquals(1, losses.reasons["dec_await_key"])
        assertEquals(9L, losses.stats.framesDropped)
        assertEquals(0, frames.pendingCount)
        assertEquals(DecoderFrames.Offer.DROPPED, frames.offer(frame(9, key = false), 9, 1))
        assertEquals(DecoderFrames.Offer.ACCEPTED, frames.offer(frame(10), 10, 1))
    }

    @Test
    fun overflowKeyIsKeptAfterAllEightOlderAusAreCounted() {
        val losses = Losses()
        val frames = DecoderFrames(drop = losses::drop)
        frames.configure(byteArrayOf(1), 1280, 576, 1)
        repeat(8) { frames.offer(frame(it), it.toLong(), 1) }
        assertEquals(DecoderFrames.Offer.ACCEPTED, frames.offer(frame(8), 8, 1))
        assertEquals(8L, losses.stats.framesDropped)
        assertEquals(1, frames.pendingCount)
        assertEquals(8, frames.submit()!!.frame.seq)
    }

    @Test
    fun resetAccountsBothPendingAndSubmittedOnce() {
        val losses = Losses()
        val frames = DecoderFrames(drop = losses::drop)
        frames.configure(byteArrayOf(1), 1280, 576, 1)
        repeat(3) { frames.offer(frame(it), it.toLong(), 1) }
        val queued = frames.submit()!!
        frames.configure(byteArrayOf(2), 960, 432, 2)
        assertEquals(3, losses.reasons["dec_config_reset"])
        assertNull(frames.terminal(queued.pts, "dec_release_fail"))
        frames.reset("dec_close")
        assertEquals(3L, losses.stats.framesDropped)
        assertEquals(0, frames.pendingCount)
    }

    @Test
    fun eachAuHasOneTerminalIncludingCodecFailures() {
        listOf("dec_input_oversize", "dec_input_null", "dec_input_fail", "dec_release_fail", "dec_decoded_superseded").forEach { reason ->
            val losses = Losses()
            val frames = DecoderFrames(drop = losses::drop)
            frames.configure(byteArrayOf(1), 1280, 576, 1)
            frames.offer(frame(1), 10, 1)
            val input = frames.submit()!!
            assertEquals(input, frames.terminal(input.pts, reason))
            assertNull(frames.terminal(input.pts, "dec_recreate_abandon"))
            frames.reset("dec_close")
            assertEquals(mapOf(reason to 1), losses.reasons)
            assertEquals(1L, losses.stats.framesDropped)
        }
    }

    @Test
    fun releasedAuIsNotLostAgainOnCloseOrLateCallback() {
        val losses = Losses()
        val frames = DecoderFrames(drop = losses::drop)
        frames.configure(byteArrayOf(1), 1280, 576, 1)
        frames.offer(frame(1), 10, 1)
        val queued = frames.submit()!!
        assertEquals(queued, frames.terminal(queued.pts, null))
        assertNull(frames.terminal(queued.pts, "dec_decoded_superseded"))
        frames.reset("dec_close")
        assertTrue(losses.reasons.isEmpty())
        assertEquals(0L, losses.stats.framesDropped)
    }

    @Test
    fun timeEvictionIsTerminalAndNeverRendersLater() {
        val losses = Losses()
        val frames = DecoderFrames(maxTimes = 2, drop = losses::drop)
        frames.configure(byteArrayOf(1), 1280, 576, 1)
        val pts = (1..3).map {
            frames.offer(frame(it), it.toLong(), 1)
            frames.submit()!!.pts
        }
        assertEquals(1, losses.reasons["dec_time_evict"])
        assertFalse(frames.contains(pts[0]))
        assertNull(frames.terminal(pts[0], "dec_release_fail"))
        frames.reset("dec_recreate_abandon")
        assertEquals(2, losses.reasons["dec_recreate_abandon"])
        assertEquals(3L, losses.stats.framesDropped)
    }

    @Test
    fun staleEpochIsRejectedAndKeyIsRequiredForEachConfig() {
        val losses = Losses()
        val frames = DecoderFrames(drop = losses::drop)
        frames.configure(byteArrayOf(1), 1280, 576, 2)
        assertEquals(DecoderFrames.Offer.DROPPED, frames.offer(frame(1), 1, 1))
        assertEquals(DecoderFrames.Offer.DROPPED, frames.offer(frame(2, key = false), 2, 2))
        assertEquals(DecoderFrames.Offer.ACCEPTED, frames.offer(frame(3), 3, 2))
        assertEquals(2L, losses.stats.framesDropped)
        assertEquals(2L, frames.submit()!!.codecEpoch)
    }

    @Test
    fun inputFailureDiscardsUndecodablePendingDeltasAndRequestsKey() {
        val losses = Losses()
        val frames = DecoderFrames(drop = losses::drop)
        frames.configure(byteArrayOf(1), 1280, 576, 1)
        frames.offer(frame(1), 1, 1)
        frames.offer(frame(2, key = false), 2, 1)
        val input = frames.submit()!!
        frames.terminal(input.pts, "dec_input_fail")
        frames.requireKey()
        assertEquals(mapOf("dec_input_fail" to 1, "dec_await_key" to 1), losses.reasons)
        assertEquals(DecoderFrames.Offer.DROPPED, frames.offer(frame(3, key = false), 3, 1))
        assertEquals(DecoderFrames.Offer.ACCEPTED, frames.offer(frame(4), 4, 1))
    }

    @Test
    fun ptsRemainsPositiveAndUniqueThroughSeqWrapAndReconnectEpochReset() {
        val frames = DecoderFrames { _, _ -> }
        frames.configure(byteArrayOf(1), 1280, 576, 1)
        val pts = listOf(Int.MAX_VALUE, Int.MIN_VALUE, -1, 0).map {
            frames.offer(frame(it), 1, 1)
            frames.submit()!!.pts
        }.toMutableList()
        frames.configure(byteArrayOf(2), 1280, 576, 1)
        frames.offer(frame(0), 2, 1)
        pts += frames.submit()!!.pts
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), pts)
    }

    @Test
    fun hundredRapidConfigSchedulesKeepOnlyTheExactLatestRequest() {
        repeat(100) { schedule ->
            val losses = Losses()
            val frames = DecoderFrames(drop = losses::drop)
            val old = frames.configure(byteArrayOf(1), 1280, 576, 1)
            val oldCodec = Any()
            frames.offer(frame(schedule), 1, 1)
            if (schedule % 2 == 0) frames.submit()
            val current = frames.configure(byteArrayOf(2), 960, 432, 2)
            val liveCodec = Any()
            assertFalse(ownsDecoder(oldCodec, liveCodec, old, current))
            assertFalse(ownsDecoder(liveCodec, liveCodec, old, current))
            assertTrue(ownsDecoder(liveCodec, liveCodec, current, current))
            assertNotSame(old, current)
            assertEquals(1, losses.reasons["dec_config_reset"])
        }
    }

    @Test
    fun configCopiesCsdAndDoesNotPairItWithLaterInfoDimensions() {
        val bytes = byteArrayOf(1, 2)
        val frames = DecoderFrames { _, _ -> }
        val request = frames.configure(bytes, 1280, 576, 1, fps = 30)
        bytes[0] = 9
        assertEquals(1.toByte(), request.csd[0])
        frames.configure(byteArrayOf(3), 960, 432, 2)
        assertEquals(1280, request.width)
        assertEquals(576, request.height)
        assertEquals(1L, request.codecEpoch)
        assertEquals(30, request.fps)
    }

    @Test
    fun jointSizeAndAlignmentAreRequiredWithoutIndependentMaxima() {
        assertFalse(supportsDecoderSize(1280, 576, 60, 16, 16) { false })
        assertFalse(supportsDecoderSize(1279, 576, 60, 16, 16) { error("joint should not be queried") })
        assertFalse(supportsDecoderSize(1280, 576, 60, 0, 16) { true })
        assertTrue(supportsDecoderSize(960, 432, 60, 16, 16) { true })
    }

    @Test
    fun eligibleHardwareWinsAndFallbacksRemainExplicitAndFinite() {
        val candidates = listOf(
            DecoderCandidate("hw.unsupported", true, false),
            DecoderCandidate("sw.supported", false, true),
            DecoderCandidate("hw.supported", true, true),
            DecoderCandidate("hw.supported", true, true),
        )
        assertEquals(listOf("hw.supported", "sw.supported", "hw.unsupported"), rankDecoderCandidates(candidates, null).map { it.name })
        assertEquals(listOf(candidates[0]), rankDecoderCandidates(candidates, "hw.unsupported"))
        assertEquals(listOf(DecoderCandidate("debug.vendor", false, false)), rankDecoderCandidates(candidates, "debug.vendor"))
        assertTrue(rankDecoderCandidates(emptyList(), null).isEmpty())
    }

    @Test
    fun successfulReleaseRecordsDecodeWithoutInventingPresentOrCaptureZero() {
        val ms = 1_000_000L
        val stats = LatencyStats()
        val clock = ClockSync(clock = { 160 * ms })
        repeat(4) { index ->
            val t0 = (100 + index * 10) * ms
            clock.add(t0, t0 + 5_001 * ms, t0 + 5_002 * ms, t0 + 3 * ms)
        }
        VideoDecoder.recordOutputStats(stats, clock, 0, 5_140 * ms, 145 * ms, 160 * ms)
        assertEquals(1L, stats.framesRendered)
        assertEquals(15.0, stats.percentileMs(LatencyStats.Metric.DECODE, 0.5)!!, 0.0)
        assertNull(stats.percentileMs(LatencyStats.Metric.VIDEO, 0.5))
        assertNull(stats.percentileMs(LatencyStats.Metric.PRESENT, 0.5))
    }
}
