package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.stream.shell.InputAuthority
import org.junit.Assert.*
import org.junit.Test

class ReliableTransitionsTest {
    private val press = PadState(buttons = PadState.A)
    private val queue = ReliableTransitions()

    @Test fun pressReleaseBeforeAckPreservesBoth() {
        queue.update(press, 1L)
        queue.update(PadState.NEUTRAL, 2L)
        val down = queue.next(2L)!!
        assertEquals(press, down.state)
        assertEquals(1L, down.originNs)
        assertTrue(queue.ack(down.generation!!))
        val up = queue.next(2L)!!
        assertTrue(up.state.isNeutral)
        assertEquals(2L, up.originNs)
        assertFalse(up.cancel)
        assertEquals(1, queue.queued())
    }

    @Test fun releaseRetriedWhileNeutralUntilAckEvenWithOrderedEdgesOff() {
        val control = ReliableTransitions(false)
        control.update(press, 1L)
        assertEquals(press, control.next(1L)!!.state)
        control.update(PadState.NEUTRAL, 2L)
        val up = control.next(2L)!!
        assertNull(control.next(8_000_001L))
        assertEquals(up, control.next(8_000_002L))
        assertTrue(control.ack(up.generation!!))
        assertNull(control.next(8_000_003L)!!.generation)
        assertNull(control.next(16_000_003L))
        assertNotNull(control.next(58_000_003L))
    }

    @Test fun analogOnlyNeutralReleaseIsRetriedAtEightMs() {
        queue.update(PadState(lx = 100), 1L)
        assertEquals(100, queue.next(1L)!!.state.lx)
        queue.update(PadState.NEUTRAL, 2L)
        val release = queue.next(2L)!!
        assertNotNull(release.generation)
        assertEquals(release, queue.next(8_000_002L))
    }

    @Test fun analogCoalescesWithoutDeletingEdges() {
        queue.update(press, 1L)
        queue.update(PadState.NEUTRAL, 2L)
        queue.update(PadState(lx = 100, lt = 200), 3L)
        val down = queue.next(3L)!!
        assertEquals(press.copy(lx = 100, lt = 200), down.state)
        assertEquals(2, queue.queued())
        queue.ack(down.generation!!)
        assertEquals(PadState(lx = 100, lt = 200), queue.next(3L)!!.state)
    }

    @Test fun cumulativeOldAckCannotPopNewHead() {
        queue.update(press, 1L)
        queue.update(PadState.NEUTRAL, 2L)
        val first = queue.next(2L)!!
        queue.ack(first.generation!!)
        val second = queue.next(2L)!!
        assertFalse(queue.ack(first.generation))
        assertEquals(1, queue.queued())
        assertTrue(queue.ack(second.generation!!))
    }

    @Test fun overflowCancelsAndRequiresFreshUpdate() {
        repeat(65) { queue.update(if (it % 2 == 0) press else PadState.NEUTRAL, it + 1L) }
        assertEquals(1, queue.queued())
        val cancel = queue.next(66L)!!
        assertTrue(cancel.cancel)
        assertTrue(cancel.state.isNeutral)
        assertEquals(1L, queue.cancellations)
        queue.ack(cancel.generation!!)
        assertTrue(queue.next(66L)!!.state.isNeutral)
        queue.update(press, 67L)
        assertEquals(press, queue.next(67L)!!.state)
    }

    @Test fun headTimeoutCancelsAndDoesNotReplayStaleHistory() {
        queue.update(press, 1L)
        val first = queue.next(1L)!!
        val cancel = queue.next(ReliableTransitions.MAX_AGE_NS + 2L)!!
        assertTrue(cancel.cancel)
        assertFalse(queue.ack(first.generation!!))
        assertEquals(1, queue.queued())
        queue.ack(cancel.generation!!)
        assertTrue(queue.next(ReliableTransitions.MAX_AGE_NS + 2L)!!.state.isNeutral)
    }

    @Test fun eventTimeDoesNotDecideQueueTimeout() {
        queue.update(press, 1L, 1_000_000_000L)
        assertFalse(queue.next(1_000_000_000L)!!.cancel)
    }

    @Test fun cancelGenerationWrapAndOldAckAreSafe() {
        val wrapping = ReliableTransitions(initialGeneration = Int.MAX_VALUE)
        wrapping.cancel(1L)
        val cancel = wrapping.next(1L)!!
        assertEquals(Int.MIN_VALUE, cancel.generation)
        assertFalse(wrapping.ack(Int.MAX_VALUE))
        assertTrue(wrapping.ack(Int.MIN_VALUE))
    }

    @Test fun cancelIsFullReleaseButNormalNeutralIsNotCancel() {
        queue.update(press, 1L)
        queue.cancel(2L)
        val cancel = queue.next(2L)!!
        assertTrue(cancel.cancel)
        assertTrue(cancel.state.isNeutral)
        queue.update(PadState(buttons = PadState.B), 3L)
        queue.ack(cancel.generation!!)
        assertEquals(PadState(buttons = PadState.B), queue.next(3L)!!.state)
    }

    @Test fun lossReorderDuplicateAckAndFreshRetrySequencesPreserveExactWrites() {
        var now = 1L
        val writes = mutableListOf<PadState>()
        val authority = InputAuthority({ writes += it; true }, { now })
        authority.attach(7L)
        queue.update(press, 1L)
        queue.update(PadState.NEUTRAL, 2L)
        var seq = Int.MAX_VALUE - 10
        val attempts = mutableListOf<Pair<Int, ReliableTransitions.Send>>()
        repeat(11) {
            attempts += ++seq to queue.next(now)!!
            now += ReliableTransitions.ACTIVE_NS
        }
        val latest = attempts.last()
        val applied = authority.apply(7L, latest.first, latest.second.originNs, latest.second.state)!!
        assertTrue(applied.wrote)
        attempts.takeLast(3).dropLast(1).reversed().forEach {
            assertNull(authority.apply(7L, it.first, it.second.originNs, it.second.state))
        }
        val retry = queue.next(now)!!
        val retryAck = authority.apply(7L, ++seq, retry.originNs, retry.state)!!
        assertFalse(retryAck.wrote)
        assertEquals(applied.appliedNs, retryAck.appliedNs)
        queue.ack(retry.generation!!)
        assertFalse(queue.ack(latest.second.generation!!))
        val release = queue.next(now)!!
        now += ReliableTransitions.ACTIVE_NS
        val releaseRetry = queue.next(now)!!
        assertEquals(release.generation, releaseRetry.generation)
        assertTrue(authority.apply(7L, ++seq, releaseRetry.originNs, releaseRetry.state)!!.wrote)
        queue.ack(releaseRetry.generation!!)
        assertEquals(listOf(PadState.NEUTRAL, press, PadState.NEUTRAL), writes)
        assertEquals(0, queue.queued())
    }
}
