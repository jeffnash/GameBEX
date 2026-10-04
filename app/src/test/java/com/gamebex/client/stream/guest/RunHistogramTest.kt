package com.gamebex.client.stream.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunHistogramTest {
    @Test
    fun wholeRunKeepsEverySampleAndUsesExactQuantiles() {
        val h = RunHistogram()
        for (ns in listOf(250_001L, 0L, 100_001L, 99_999L)) h.add(ns)
        val s = h.snapshot()
        assertEquals(4, s.n)
        assertEquals(100_000L, s.p50Ns)
        assertEquals(227_501L, s.p95Ns)
        assertEquals(mapOf(0L to 2L, 1L to 1L, 2L to 1L), s.bins)
    }

    @Test
    fun invalidAndOverflowAreNotClampedOrSilentlyLost() {
        val h = RunHistogram(maxSamples = 2)
        h.add(-1L)
        h.add(0L)
        h.add(1L)
        val before = h.snapshot()
        h.add(2L)
        assertEquals(2, before.n)
        assertEquals(0L, before.overflow)
        val after = h.snapshot()
        assertEquals(1L, after.invalid)
        assertEquals(1L, after.overflow)
        assertEquals(2L, after.bins.values.sum())
    }

    @Test
    fun zeroSamplesAreMissingNotZeroLatency() {
        val s = RunHistogram().snapshot()
        assertEquals(0, s.n)
        assertNull(s.p50Ns)
        assertNull(s.p95Ns)
        assertEquals(emptyMap<Long, Long>(), s.bins)
    }
}
