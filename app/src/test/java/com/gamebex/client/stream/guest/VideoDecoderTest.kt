package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.MediaFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    private class RetrySchedule {
        data class Task(val action: Runnable, val dueMs: Long)
        var nowMs = 0L
        val delays = mutableListOf<Long>()
        val pending = mutableListOf<Task>()
        val cancelled = mutableListOf<Runnable>()

        fun post(action: Runnable, delayMs: Long) {
            delays += delayMs
            pending += Task(action, nowMs + delayMs)
        }

        fun cancel(action: Runnable) {
            cancelled += action
            pending.removeAll { it.action === action }
        }

        fun advanceBy(delayMs: Long) {
            val target = nowMs + delayMs
            while (true) {
                val next = pending.minByOrNull { it.dueMs }?.takeIf { it.dueMs <= target } ?: break
                pending.remove(next)
                nowMs = next.dueMs
                next.action.run()
            }
            nowMs = target
        }
    }

    @Test
    fun configureRetriesSameRequestAtBoundedBackoffAndThenStops() {
        val schedule = RetrySchedule()
        val request = DecoderRequest(byteArrayOf(1), 1280, 576, 4, 30)
        val attempts = mutableListOf<Pair<DecoderRequest, Long>>()
        var keys = 0
        val retry = DecoderConfigureRetry(schedule::post, schedule::cancel, {
            attempts += it to schedule.nowMs
            false
        }, { keys++ })
        retry.configure(request)
        assertTrue(attempts.isEmpty())
        schedule.advanceBy(0)
        schedule.advanceBy(99)
        assertEquals(1, attempts.size)
        schedule.advanceBy(1)
        schedule.advanceBy(299)
        assertEquals(2, attempts.size)
        schedule.advanceBy(1)
        schedule.advanceBy(999)
        assertEquals(3, attempts.size)
        schedule.advanceBy(1)
        assertEquals(listOf(0L, 100L, 400L, 1400L), attempts.map { it.second })
        attempts.forEach { assertSame(request, it.first) }
        assertEquals(listOf(0L, 100L, 300L, 1000L), schedule.delays)
        assertTrue(schedule.pending.isEmpty())
        repeat(100) {
            retry.requestKeyframe(request)
            retry.restart(request)
        }
        schedule.advanceBy(10_000)
        assertEquals(4, attempts.size)
        assertEquals(0, keys)
        assertTrue(schedule.pending.isEmpty())
    }

    @Test
    fun retrySuccessRequestsOneKeyAndResumesOnlyWithAFreshCurrentEpochKey() {
        val schedule = RetrySchedule()
        val losses = Losses()
        val frames = DecoderFrames(drop = losses::drop)
        val request = frames.configure(byteArrayOf(1), 1280, 576, 3)
        var attempts = 0
        var keys = 0
        val retry = DecoderConfigureRetry(schedule::post, schedule::cancel, {
            assertSame(request, it)
            attempts++
            if (attempts == 2) {
                frames.requireKey()
                true
            } else {
                frames.reset("dec_configure_failed")
                false
            }
        }, { keys++ })
        retry.configure(request)
        repeat(100) { retry.requestKeyframe(request) }
        assertEquals(0, keys)
        schedule.advanceBy(0)
        repeat(20) { seq ->
            val accepted = frames.offer(frame(seq, key = seq % 9 == 0), seq.toLong(), 3)
            if (accepted == DecoderFrames.Offer.NEED_KEY) retry.requestKeyframe(request)
        }
        assertEquals(0, keys)
        schedule.advanceBy(100)
        assertEquals(2, attempts)
        assertEquals(1, keys)
        assertEquals(0, frames.pendingCount)
        assertSame(request, frames.config)
        assertEquals(DecoderFrames.Offer.DROPPED, frames.offer(frame(20), 20, 2))
        assertEquals(DecoderFrames.Offer.DROPPED, frames.offer(frame(21, key = false), 21, 3))
        assertEquals(DecoderFrames.Offer.ACCEPTED, frames.offer(frame(22), 22, 3))
        val input = frames.submit()!!
        assertEquals(22, input.frame.seq)
        assertEquals(3L, input.codecEpoch)
        assertEquals(22L, losses.stats.framesDropped)
        assertEquals(1L, input.pts)
        schedule.advanceBy(10_000)
        assertEquals(2, attempts)
        assertEquals(1, keys)
        assertEquals(listOf(0L, 100L), schedule.delays)
        assertTrue(schedule.pending.isEmpty())
    }

    @Test
    fun newConfigCancelsOldRetryAndRefreshesItsFullBudget() {
        val schedule = RetrySchedule()
        val first = DecoderRequest(byteArrayOf(1), 1280, 576, 1)
        val second = DecoderRequest(byteArrayOf(2), 960, 432, 2)
        val attempts = mutableListOf<DecoderRequest>()
        var keys = 0
        val retry = DecoderConfigureRetry(schedule::post, schedule::cancel, {
            attempts += it
            false
        }, { keys++ })
        retry.configure(first)
        schedule.advanceBy(0)
        val stale = schedule.pending.single().action
        retry.configure(second)
        assertSame(stale, schedule.cancelled.single())
        stale.run()
        assertEquals(listOf(first), attempts)
        schedule.advanceBy(1400)
        assertEquals(listOf(first, second, second, second, second), attempts)
        assertEquals(listOf(0L, 100L, 0L, 100L, 300L, 1000L), schedule.delays)
        assertEquals(0, keys)
        assertTrue(schedule.pending.isEmpty())
    }

    @Test
    fun sameRequestRestartRejectsStaleScheduleGeneration() {
        val schedule = RetrySchedule()
        val request = DecoderRequest(byteArrayOf(1), 1280, 576, 1)
        var attempts = 0
        var keys = 0
        val retry = DecoderConfigureRetry(schedule::post, schedule::cancel, {
            attempts++
            attempts == 1
        }, { keys++ })
        retry.configure(request)
        schedule.advanceBy(0)
        assertEquals(1, keys)
        retry.restart(request)
        val stale = schedule.pending.single().action
        retry.configure(request)
        assertSame(stale, schedule.cancelled.single())
        stale.run()
        assertEquals(1, attempts)
        schedule.advanceBy(0)
        retry.restart(request)
        assertEquals(1, schedule.pending.size)
        schedule.advanceBy(1400)
        assertEquals(5, attempts)
        assertEquals(1, keys)
        assertEquals(listOf(0L, 0L, 0L, 100L, 300L, 1000L), schedule.delays)
        assertTrue(schedule.pending.isEmpty())
    }

    @Test
    fun closeCancelsInitialAndDelayedRetriesEvenIfDequeued() {
        listOf(false, true).forEach { delayed ->
            val schedule = RetrySchedule()
            val request = DecoderRequest(byteArrayOf(1), 1280, 576, 1)
            var attempts = 0
            var keys = 0
            val retry = DecoderConfigureRetry(schedule::post, schedule::cancel, {
                attempts++
                false
            }, { keys++ })
            retry.configure(request)
            if (delayed) schedule.advanceBy(0)
            val stale = schedule.pending.single().action
            retry.close()
            retry.close()
            assertSame(stale, schedule.cancelled.single())
            stale.run()
            retry.configure(request)
            retry.restart(request)
            retry.requestKeyframe(request)
            schedule.advanceBy(10_000)
            assertEquals(if (delayed) 1 else 0, attempts)
            assertEquals(0, keys)
            assertTrue(schedule.pending.isEmpty())
        }
    }

    @Test
    fun configOrCloseDuringAttemptRejectsLateSuccessAndFailure() {
        listOf(false, true).forEach { close ->
            listOf(false, true).forEach { success ->
                val schedule = RetrySchedule()
                val first = DecoderRequest(byteArrayOf(1), 1280, 576, 1)
                val second = DecoderRequest(byteArrayOf(1), 1280, 576, 1)
                val attempts = mutableListOf<DecoderRequest>()
                var keys = 0
                lateinit var retry: DecoderConfigureRetry
                retry = DecoderConfigureRetry(schedule::post, schedule::cancel, {
                    attempts += it
                    if (it === first) {
                        if (close) retry.close() else retry.configure(second)
                        success
                    } else {
                        true
                    }
                }, { keys++ })
                retry.configure(first)
                schedule.advanceBy(0)
                assertEquals(if (close) listOf(first) else listOf(first, second), attempts)
                assertEquals(if (close) 0 else 1, keys)
                assertFalse(schedule.delays.contains(100L))
                retry.requestKeyframe(first)
                retry.requestKeyframe(null)
                assertEquals(if (close) 0 else 1, keys)
                schedule.advanceBy(10_000)
                assertTrue(schedule.pending.isEmpty())
            }
        }
    }

    @Test
    fun configuredRequestsKeyButNewConfigImmediatelySuppressesIt() {
        val schedule = RetrySchedule()
        val first = DecoderRequest(byteArrayOf(1), 1280, 576, 1)
        val second = DecoderRequest(byteArrayOf(1), 1280, 576, 1)
        var keys = 0
        val retry = DecoderConfigureRetry(schedule::post, schedule::cancel, { true }, { keys++ })
        retry.configure(first)
        schedule.advanceBy(0)
        assertEquals(1, keys)
        retry.requestKeyframe(first)
        assertEquals(2, keys)
        retry.configure(second)
        retry.requestKeyframe(first)
        retry.requestKeyframe(second)
        assertEquals(2, keys)
        schedule.advanceBy(0)
        assertEquals(3, keys)
        retry.requestKeyframe(first)
        assertEquals(3, keys)
        retry.close()
        retry.requestKeyframe(second)
        assertEquals(3, keys)
    }

    @Test
    fun closeWaitsForKeyframeCallbackAndRejectsOldRequestsAfterReturning() {
        keyframeCallbackFencesLifecycle(close = true)
    }

    @Test
    fun configureWaitsForKeyframeCallbackAndRejectsOldRequestsAfterReturning() {
        keyframeCallbackFencesLifecycle(close = false)
    }

    private fun keyframeCallbackFencesLifecycle(close: Boolean) {
        val schedule = RetrySchedule()
        val first = DecoderRequest(byteArrayOf(1), 1280, 576, 1)
        val second = DecoderRequest(byteArrayOf(2), 960, 432, 2)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val lifecycleStarted = CountDownLatch(1)
        val lifecycleReturned = CountDownLatch(1)
        val blockCallback = AtomicBoolean(false)
        val keys = AtomicInteger()
        val failure = AtomicReference<Throwable?>(null)
        val retry = DecoderConfigureRetry(schedule::post, schedule::cancel, { true }, {
            if (blockCallback.get()) {
                callbackEntered.countDown()
                assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
            }
            keys.incrementAndGet()
        }, lock = Any())
        retry.configure(first)
        schedule.advanceBy(0)
        assertEquals(1, keys.get())
        blockCallback.set(true)
        val requester = Thread {
            try {
                retry.requestKeyframe(first)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }.apply { isDaemon = true }
        val lifecycle = Thread {
            try {
                lifecycleStarted.countDown()
                if (close) retry.close() else retry.configure(second)
                lifecycleReturned.countDown()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }.apply { isDaemon = true }
        try {
            requester.start()
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            lifecycle.start()
            assertTrue(lifecycleStarted.await(5, TimeUnit.SECONDS))
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (lifecycle.state != Thread.State.BLOCKED && lifecycleReturned.count != 0L && System.nanoTime() < deadlineNs) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, lifecycle.state)
            assertEquals(1L, lifecycleReturned.count)
            assertEquals(1, keys.get())
        } finally {
            releaseCallback.countDown()
            requester.join(5000)
            lifecycle.join(5000)
        }
        assertFalse(requester.isAlive)
        assertFalse(lifecycle.isAlive)
        assertNull(failure.get())
        assertEquals(0L, lifecycleReturned.count)
        assertEquals(2, keys.get())
        repeat(20) { retry.requestKeyframe(first) }
        retry.requestKeyframe(null)
        retry.requestKeyframe(second)
        assertEquals(2, keys.get())
        blockCallback.set(false)
        schedule.advanceBy(0)
        assertEquals(if (close) 2 else 3, keys.get())
        retry.requestKeyframe(first)
        assertEquals(if (close) 2 else 3, keys.get())
        retry.close()
        retry.requestKeyframe(second)
        assertEquals(if (close) 2 else 3, keys.get())
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
