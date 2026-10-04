package com.gamebex.client.stream.guest

import org.junit.Assert.*
import org.junit.Test

class AudioDriftTest {
    @Test fun arrivalVersusActualPlayoutMeasuresBothSkewSigns() {
        for (ppm in listOf(-200.0, -50.0, 50.0, 200.0)) {
            val drift = AudioDrift()
            drift.observeArrival(0, 10_000_000_000L)
            drift.observePlayout(0, 20_000_000_000L)
            drift.observeArrival((480_000 * (1 + ppm / 1e6)).toLong(), 20_000_000_000L)
            drift.observePlayout(480_000, 30_000_000_000L)
            assertTrue(drift.valid)
            assertEquals(ppm, drift.ppm, 2.1)
        }
    }
    @Test fun unknownOriginsCancelAndReorderDoesNotPollute() {
        val drift = AudioDrift()
        drift.observePacket(Int.MAX_VALUE, 240, 1_000_000_000L)
        drift.observePacket(Int.MIN_VALUE, 240, 1_005_000_000L)
        drift.observePacket(Int.MAX_VALUE, 240, 1_006_000_000L)
        drift.observePlayout(0, 2_000_000_000L)
        drift.observePacket(Int.MIN_VALUE + 2000, 240, 11_005_000_000L)
        drift.observePlayout(480_000, 12_000_000_000L)
        assertTrue(drift.valid)
        assertEquals(0.0, drift.ppm, .01)
    }
    @Test fun frameSizeAndReconnectResetModelNotInventClockSkew() {
        val drift = AudioDrift()
        drift.observePacket(1, 240, 0)
        drift.observePacket(2001, 240, 10_000_000_000L)
        drift.observePlayout(0, 0)
        drift.observePlayout(480_000, 10_000_000_000L)
        assertTrue(drift.valid)
        drift.observePacket(2002, 256, 10_010_000_000L)
        assertFalse(drift.valid)
        drift.reset()
        assertEquals(0.0, drift.ppm, 0.0)
        assertFalse(drift.valid)
    }
}
