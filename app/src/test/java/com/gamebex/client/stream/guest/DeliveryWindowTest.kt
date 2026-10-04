package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.DeliveryWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryWindowTest {
    @Test
    fun constantClockOffsetCancels() {
        fun delay(offset: Long): Long {
            val w = DeliveryWindow()
            w.begin(1L, 10_000_000_000L)
            w.received(1L, 1, 10_100_000_000L, 10_080_000_000L + offset, 20)
            w.received(1L, 2, 10_200_000_000L, 10_170_000_000L + offset, 30)
            return w.report(10_500_000_000L, 1)!!.relativeP95Ns
        }
        assertEquals(9_500_000L, delay(0L))
        assertEquals(delay(0L), delay(5_000_000_000L))
    }

    @Test
    fun noFramesUsesUnknownDelayAndOnlyCompleteWindowsEmit() {
        val w = DeliveryWindow()
        assertNull(w.report(1_000_000_000L, 0))
        w.begin(1L, 0L)
        assertNull(w.report(499_999_999L, 0))
        val report = w.report(500_000_000L, 40)!!
        assertEquals(-1L, report.relativeP95Ns)
        assertEquals(0, report.frames)
        assertEquals(0L, report.bytes)
        assertEquals(32, report.pending)
        assertNotNull(DeliveryWire.decode(DeliveryWire.encode(report)))
    }

    @Test
    fun codecChangeClearsBaselineButKeepsConnectionReportSequence() {
        val w = DeliveryWindow()
        w.begin(1L, 0L)
        w.received(1L, 10, 200_000_000L, 100_000_000L, 20)
        assertEquals(1, w.report(500_000_000L, 0)!!.seq)
        w.begin(2L, 600_000_000L)
        w.received(1L, 11, 700_000_000L, 100_000_000L, 1000)
        w.received(2L, 12, 700_000_000L, 500_000_000L, 30)
        val report = w.report(1_100_000_000L, 0)!!
        assertEquals(2L, report.codecEpoch)
        assertEquals(2, report.seq)
        assertEquals(1, report.frames)
        assertEquals(30L, report.bytes)
        assertEquals(12, report.lastFrameSeq)
        assertEquals(0L, report.relativeP95Ns)
    }

    @Test
    fun oneWindowNotCumulativeAndHistoryExpires() {
        val w = DeliveryWindow()
        w.begin(1L, 0L)
        w.received(1L, 1, 200_000_000L, 100_000_000L, 20)
        assertEquals(20L, w.report(500_000_000L, 0)!!.bytes)
        val idle = w.report(1_000_000_000L, 0)!!
        assertEquals(0L, idle.bytes)
        assertEquals(0, idle.frames)
        assertEquals(-1L, idle.relativeP95Ns)
        for (second in 2..6) w.report(second * 1_000_000_000L, 0)
        w.received(1L, 2, 6_100_000_000L, 5_800_000_000L, 30)
        assertEquals(0L, w.report(6_500_000_000L, 0)!!.relativeP95Ns)
    }

    @Test
    fun overflowIsInvalidAndRecoveryUsesNextCleanWindow() {
        val reasons = mutableListOf<String>()
        val w = DeliveryWindow { reasons += it }
        w.begin(1L, 0L)
        repeat(501) { w.received(1L, it, 200_000_000L, 100_000_000L, 1) }
        assertNull(w.report(500_000_000L, 0))
        assertEquals(listOf("delivery_window_invalid"), reasons)
        assertNotNull(w.report(1_000_000_000L, 0))
        w.received(1L, 1000, 1_100_000_000L, 1_000_000_000L, 64_000_001)
        assertNull(w.report(1_500_000_000L, 0))
        assertNull(w.report(4_000_000_001L, 0))
        assertEquals(3, reasons.size)
    }
}
