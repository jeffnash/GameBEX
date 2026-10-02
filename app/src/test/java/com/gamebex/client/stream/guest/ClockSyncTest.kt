package com.gamebex.client.stream.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clock offset from ping/pong. */
class ClockSyncTest {
    private val ms = 1_000_000L

 /** Host clock is guest clock + 5 s; one-way delays [up] and [down] ms. */
    private fun ClockSync.exchange(t0: Long, up: Long, down: Long, hold: Long = 1) {
        val offset = 5_000 * ms
        val t1 = t0 + up * ms + offset
        val t2 = t1 + hold * ms
        val t3 = t2 - offset + down * ms
        add(t0, t1, t2, t3)
    }

    @Test
    fun symmetricDelaysGiveTheExactOffset() {
        val c = ClockSync()
        assertFalse(c.ready)
        c.exchange(1_000 * ms, 3, 3)
        assertTrue(c.ready)
        assertEquals(5_000 * ms, c.offsetNs)
        assertEquals(6 * ms, c.rttNs)
    }

    @Test
    fun theFastestExchangeWins() {
        val c = ClockSync()
        c.exchange(1_000 * ms, 40, 2)
        c.exchange(2_000 * ms, 2, 2)
        c.exchange(3_000 * ms, 2, 30)
        assertEquals(5_000 * ms, c.offsetNs)
        assertEquals(4 * ms, c.rttNs)
    }

    @Test
    fun convertsBothWays() {
        val c = ClockSync()
        c.exchange(0, 1, 1)
        assertEquals(10 * ms, c.hostToGuest(5_010 * ms))
        assertEquals(5_010 * ms, c.guestToHost(10 * ms))
    }

    @Test
    fun oldSamplesLeaveTheWindow() {
        val c = ClockSync(window = 2)
        c.exchange(0, 1, 1)
        c.exchange(1_000 * ms, 10, 10)
        c.exchange(2_000 * ms, 10, 10)
        assertEquals(20 * ms, c.rttNs)
    }

    @Test
    fun impossibleSamplesAreIgnored() {
        val c = ClockSync()
        c.add(100, 50, 40, 200)
        c.add(100, 150, 160, 90)
        assertFalse(c.ready)
    }
}
