package com.gamebex.client.stream.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncTest {
    private val ms = 1_000_000L
    private var now = 0L

    private fun ClockSync.exchange(t0: Long, up: Long, down: Long, hold: Long = 1) {
        val offset = 5_000 * ms
        val t1 = t0 + up * ms + offset
        val t2 = t1 + hold * ms
        now = t2 - offset + down * ms
        add(t0, t1, t2, now)
    }

    @Test
    fun symmetricDelaysGiveTheExactOffsetAfterWarmup() {
        val c = ClockSync(clock = { now })
        assertFalse(c.ready)
        c.exchange(1_000 * ms, 3, 3)
        assertFalse(c.ready)
        assertNull(c.estimateHost(now + 5_000 * ms))
        repeat(3) { c.exchange((1_100 + it * 100) * ms, 3, 3) }
        assertTrue(c.ready)
        assertEquals(5_000 * ms, c.offsetNs)
        assertEquals(6 * ms, c.rttNs)
        val interval = c.interval()!!
        assertTrue(interval.lowerNs <= 5_000 * ms && interval.upperNs >= 5_000 * ms)
        assertTrue(c.errorBoundNs >= 3 * ms)
    }

    @Test
    fun theFastestExchangeWinsWithoutAssumingSymmetricBounds() {
        val c = ClockSync(clock = { now })
        c.exchange(1_000 * ms, 40, 2)
        c.exchange(2_000 * ms, 2, 2)
        c.exchange(3_000 * ms, 2, 30)
        c.exchange(4_000 * ms, 2, 2)
        assertEquals(5_000 * ms, c.offsetNs)
        assertEquals(4 * ms, c.rttNs)
        assertTrue(c.interval()!!.lowerNs <= 5_000 * ms)
        assertTrue(c.interval()!!.upperNs >= 5_000 * ms)
    }

    @Test
    fun convertsBothWaysWithRetainedError() {
        val c = ClockSync(clock = { now })
        repeat(4) { c.exchange(it * 100 * ms, 1, 1) }
        val estimate = c.estimateHost(5_310 * ms)!!
        assertEquals(310 * ms, estimate.guestNs)
        assertTrue(estimate.errorBoundNs >= ms)
        assertEquals(310 * ms, c.hostToGuest(5_310 * ms))
        assertEquals(5_310 * ms, c.guestToHost(310 * ms))
    }

    @Test
    fun oldSamplesLeaveTheWindow() {
        val c = ClockSync(window = 2, clock = { now })
        c.exchange(0, 1, 1)
        c.exchange(1_000 * ms, 10, 10)
        c.exchange(2_000 * ms, 10, 10)
        assertEquals(20 * ms, c.rttNs)
        assertFalse(c.ready)
    }

    @Test
    fun impossibleAndReorderedSamplesAreIgnored() {
        val c = ClockSync(clock = { now })
        assertFalse(c.add(100, 50, 40, 200))
        assertFalse(c.add(100, 150, 160, 90))
        c.exchange(1_000 * ms, 1, 1)
        assertFalse(c.add(0L, 5_001 * ms, 5_002 * ms, 3 * ms))
        assertFalse(c.ready)
    }

    @Test
    fun staleAndReconnectHaveNoFalseReadiness() {
        val c = ClockSync(clock = { now })
        repeat(4) { c.exchange(it * 100 * ms, 1, 1) }
        assertNotNull(c.estimateHost(now + 5_000 * ms))
        now += 3_000 * ms + 1L
        assertFalse(c.ready)
        assertNull(c.interval())
        assertEquals(-1L, c.errorBoundNs)
        c.reset()
        assertEquals(-1L, c.rttNs)
        c.exchange(5_000 * ms, 1, 1)
        assertFalse(c.ready)
    }

    @Test
    fun asymmetricPathsAndClockJumpDoNotCreateFalseExactness() {
        val c = ClockSync(clock = { now })
        repeat(4) { c.exchange((1_000 + it * 100) * ms, 8, 1) }
        val e = c.estimateHost(now + 5_000 * ms)!!
        assertTrue(kotlin.math.abs(e.guestNs - now) <= e.errorBoundNs)
        assertTrue(e.errorBoundNs >= 4 * ms)
        val t0 = 2_000 * ms
        now = t0 + 3 * ms
        assertFalse(c.add(t0, t0 + 6_001 * ms, t0 + 6_002 * ms, now))
        assertFalse(c.ready)
        assertEquals(1L, c.resets)
    }

    @Test
    fun twoHundredPpmOverThirtyMinutesStaysWithinBounds() {
        val c = ClockSync(clock = { now })
        for (second in 0..1800) {
            val t0 = second * 1_000 * ms
            val offset = 5_000 * ms + (t0 * .0002).toLong()
            val t1 = t0 + ms + offset
            val t2 = t1 + ms
            now = t0 + 3 * ms
            assertTrue(c.add(t0, t1, t2, now))
            if (second >= 3) {
                val host = now + 5_000 * ms + (now * .0002).toLong()
                val e = c.estimateHost(host)!!
                assertTrue(kotlin.math.abs(e.guestNs - now) <= e.errorBoundNs)
                if (second >= 15) {
                    assertTrue(e.driftReady)
                    assertEquals(200.0, e.driftPpm, 1.0)
                }
            }
        }
    }
}
