package com.gamebex.client.stream.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadlineQueueTest {
    private val ms = 1_000_000L
    private fun frame(seq: Int, key: Boolean, encodedMs: Long = 100L, epoch: Long = 1L) =
        EncodedFrame(seq, key, 0L, encodedMs * ms, byteArrayOf(1), epoch)
    private fun queue(deadline: Int = 40) = DeadlineQueue(deadline).apply { reset(1L) }

    @Test
    fun dequeueRechecksAge() {
        val q = queue()
        q.offer(frame(1, true), 100 * ms)
        assertNull(q.poll(141 * ms).first)
        assertTrue(q.waitingForKey)
    }

    @Test
    fun deadlineBoundaryIsInclusiveAndStartsAtEncode() {
        val q = queue()
        q.offer(frame(1, true), 130 * ms)
        assertEquals(1, q.poll(140 * ms).first!!.frame.seq)
        assertEquals(1, q.offer(frame(2, true), 141 * ms).dropped)
    }

    @Test
    fun dropPredictiveRequiresNewIdr() {
        val q = queue()
        q.offer(frame(1, true), 100 * ms)
        q.offer(frame(2, false), 100 * ms)
        val result = q.offer(frame(3, false), 100 * ms)
        assertEquals(3, result.dropped)
        assertEquals(DeadlineQueue.Reason.COUNT, result.reason)
        assertEquals(DeadlineQueue.Reason.AWAIT_KEY, result.incomingReason)
        assertEquals(2, result.depth)
        assertEquals(0L, result.ageNs)
        assertTrue(result.requestKeyframe)
        assertEquals(1, q.offer(frame(4, false), 100 * ms).dropped)
        assertNull(q.poll(100 * ms).first)
    }

    @Test
    fun freshIdrCanReplaceExpiredGop() {
        val q = queue()
        q.offer(frame(1, true), 100 * ms)
        q.offer(frame(2, false), 100 * ms)
        val result = q.offer(frame(3, true, 141), 141 * ms)
        assertEquals(2, result.dropped)
        assertEquals(DeadlineQueue.Reason.COUNT_AND_AGE, result.reason)
        assertEquals(DeadlineQueue.Reason.NONE, result.incomingReason)
        assertEquals(2, result.depth)
        assertEquals(41 * ms, result.ageNs)
        assertFalse(result.requestKeyframe)
        assertEquals(3, q.poll(141 * ms).first!!.frame.seq)
    }

    @Test
    fun countEveryDiscardedAu() {
        val q = queue()
        q.offer(frame(1, true), 100 * ms)
        q.offer(frame(2, false), 100 * ms)
        val result = q.poll(141 * ms).second
        assertEquals(2, result.dropped)
        assertEquals(DeadlineQueue.Reason.AGE, result.reason)
        assertEquals(2, result.depth)
        assertEquals(41 * ms, result.ageNs)
        assertEquals(0, q.clear())
    }

    @Test
    fun oldEpochIdrDoesNotRecoverNewEpoch() {
        val q = queue()
        q.reset(2L)
        val result = q.offer(frame(1, true), 100 * ms)
        assertEquals(1, result.dropped)
        assertEquals(DeadlineQueue.Reason.EPOCH, result.reason)
        assertFalse(result.pressure)
        assertTrue(q.waitingForKey)
        assertNull(q.poll(100 * ms).first)
        q.offer(frame(2, true, epoch = 2L), 100 * ms)
        assertEquals(2, q.poll(100 * ms).first!!.frame.seq)
    }

    @Test
    fun polledFrameIsInvalidAfterConfigOrGopDiscard() {
        val q = queue()
        q.offer(frame(1, true), 100 * ms)
        val old = q.poll(100 * ms).first!!
        q.clear()
        assertFalse(q.valid(old, 100 * ms))
        q.offer(frame(2, true), 100 * ms)
        val next = q.poll(100 * ms).first!!
        q.reset(2L)
        assertFalse(q.valid(next, 100 * ms))
    }

    @Test
    fun defaultRetainsFourFramesAndAdmissionEnqueueAge() {
        val q = queue(0)
        repeat(4) { q.offer(frame(it, it == 0, 1), 100 * ms) }
        assertEquals(4, q.size)
        assertEquals(0, q.poll(1000 * ms).first!!.frame.seq)
        assertEquals(4, q.offer(frame(5, false), 1000 * ms).dropped)
    }

    @Test
    fun countAndAgeReasonsCaptureTheQueueBeforePressureClearsIt() {
        val count = queue(0)
        repeat(4) { count.offer(frame(it, it == 0), 100 * ms) }
        val full = count.offer(frame(5, false), 110 * ms)
        assertEquals(DeadlineQueue.Reason.COUNT, full.reason)
        assertEquals(DeadlineQueue.Reason.AWAIT_KEY, full.incomingReason)
        assertEquals(5, full.dropped)
        assertEquals(4, full.depth)
        assertEquals(10 * ms, full.ageNs)
        assertEquals(0, count.size)

        val age = queue(0)
        age.offer(frame(1, true), 100 * ms)
        val expired = age.offer(frame(2, true, 221), 221 * ms)
        assertEquals(DeadlineQueue.Reason.AGE, expired.reason)
        assertEquals(1, expired.dropped)
        assertEquals(1, expired.depth)
        assertEquals(121 * ms, expired.ageNs)
        assertFalse(expired.requestKeyframe)
        assertEquals(1, age.size)
    }

    @Test
    fun incomingExpiryCountsDependantsAndAwaitKeyIsNotPressure() {
        val q = queue()
        val awaiting = q.offer(frame(1, false), 100 * ms)
        assertEquals(DeadlineQueue.Reason.AWAIT_KEY, awaiting.reason)
        assertFalse(awaiting.pressure)
        q.offer(frame(2, true, 130), 130 * ms)
        val expired = q.offer(frame(3, false), 141 * ms)
        assertEquals(DeadlineQueue.Reason.AGE, expired.reason)
        assertEquals(DeadlineQueue.Reason.AGE, expired.incomingReason)
        assertEquals(2, expired.dropped)
        assertEquals(1, expired.depth)
        assertEquals(41 * ms, expired.ageNs)
        assertTrue(expired.pressure)
        assertTrue(q.waitingForKey)
    }

    @Test
    fun legacyResultConstructorKeepsDefaultMetadata() {
        val result = DeadlineQueue.Result(1, true, true)
        assertEquals(DeadlineQueue.Reason.NONE, result.reason)
        assertEquals(DeadlineQueue.Reason.NONE, result.incomingReason)
        assertEquals(0L, result.ageNs)
        assertEquals(0, result.depth)
    }

    @Test
    fun absentEncodeTimestampFallsBackAndIsCounted() {
        val q = queue()
        q.offer(frame(1, true, 0), 100 * ms)
        assertEquals(1L, q.ageFallbacks)
        assertEquals(1, q.poll(140 * ms).first!!.frame.seq)
    }
}
