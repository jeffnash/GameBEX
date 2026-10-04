package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryControlTest {
    private val second = 1_000_000_000L
    private val ms = 1_000_000L
    private fun control() = DeliveryControl(StreamCaps.LEVELS, 1)

    @Test
    fun twoBadWindowsReduce15Percent() {
        val c = control()
        assertNull(c.window(second, 31 * ms, 0L, 0L, 0, false))
        assertEquals(DeliveryControl.Action.Bitrate(3_400_000), c.window(2 * second, 31 * ms, 0L, 0L, 0, false))
    }

    @Test
    fun normalAbsoluteRelativeP95DoesNotRatchetBitrateOrLevel() {
        val c = control()
        for (n in 1L..120L) {
            val p95 = (12 + (n % 14)) * ms
            assertNull(c.window(n * 500 * ms, p95, 0L, 0L, 0, false))
        }
        assertEquals(4_000_000, c.bitrate)
        assertEquals(1, c.level.index)
    }

    @Test
    fun thirtyMsIsNotDistressAndThirtyOneMsStillReduces() {
        val c = control()
        for (n in 1L..5L) assertNull(c.window(n * second, 30 * ms, 0L, 0L, 0, false))
        assertNull(c.window(6 * second, 31 * ms, 0L, 0L, 0, false))
        assertEquals(DeliveryControl.Action.Bitrate(3_400_000), c.window(7 * second, 31 * ms, 0L, 0L, 0, false))
    }

    @Test
    fun cleanThresholdIsTwentyMsNotTheNeutralBandAboveIt() {
        val c = control()
        c.localPressure(second, 0L, true)
        for (n in 2L..23L) assertNull(c.window(n * 500 * ms, 21 * ms, 0L, 0L, 0, false))
        assertEquals(3_400_000, c.bitrate)
        for (n in 24L..43L) assertNull(c.window(n * 500 * ms, 20 * ms, 0L, 0L, 0, false))
        assertEquals(DeliveryControl.Action.Bitrate(3_570_000), c.window(22 * second, 20 * ms, 0L, 0L, 0, false))
    }

    @Test
    fun oneBadWindowNoDropDoesNotReact() {
        val c = control()
        assertNull(c.window(second, 31 * ms, 0L, 0L, 0, false))
        assertNull(c.window(2 * second, 0L, 0L, 0L, 0, false))
        assertNull(c.window(3 * second, 31 * ms, 0L, 0L, 0, false))
    }

    @Test
    fun hardQueueActsWithoutFeedbackAndDownSpacingOneSecond() {
        val c = control()
        assertEquals(DeliveryControl.Action.Bitrate(3_400_000), c.localPressure(second, 51 * ms, false))
        assertNull(c.localPressure(second + 999 * ms, 51 * ms, false))
        assertEquals(DeliveryControl.Action.Bitrate(2_890_000), c.localPressure(2 * second, 0L, true))
        assertFalse(c.feedbackFresh(2 * second))
    }

    @Test
    fun floorThreeSecondsThenNextEligibleRankSkippingExperimentalRung() {
        val c = control()
        var now = second
        while (c.bitrate > StreamCaps.MIN_BITRATE) {
            c.localPressure(now, 0L, true)
            now += second
        }
        val floorAt = now - second
        assertNull(c.localPressure(floorAt + 2 * second, 0L, true))
        assertEquals(DeliveryControl.Action.Level(StreamCaps.LEVELS[3], StreamCaps.MIN_BITRATE),
            c.localPressure(floorAt + 3 * second, 0L, true))
        assertEquals(3, c.level.index)
    }

    @Test
    fun riseOnlyAfterTenSecondsCleanKnownFeedback() {
        val c = control()
        c.localPressure(second, 0L, true)
        for (n in 2L..21L) assertNull(c.window(n * 500 * ms, 0L, 0L, 0L, 0, false))
        assertEquals(DeliveryControl.Action.Bitrate(3_570_000), c.window(11 * second, 0L, 0L, 0L, 0, false))
    }

    @Test
    fun missingUnknownOrStaleFeedbackCannotRaise() {
        val c = control()
        c.localPressure(second, 0L, true)
        assertNull(c.window(20 * second, null, 0L, 0L, 0, false))
        assertTrue(c.feedbackFresh(21 * second))
        assertFalse(c.feedbackFresh(22 * second))
        assertNull(c.localPressure(30 * second, 0L, false))
        assertNull(c.window(31 * second, 0L, 0L, 0L, 0, false))
        assertEquals(3_400_000, c.bitrate)
    }

    @Test
    fun writerAndDecoderPressureDoNotRaise() {
        val c = control()
        c.localPressure(second, 0L, true)
        assertNull(c.window(20 * second, 0L, 0L, 16 * ms, 0, false))
        assertNull(c.window(21 * second, 0L, 0L, 0L, 2, false))
        assertEquals(3_400_000, c.bitrate)
    }

    @Test
    fun filteredLadderAndDebugStartUseRankIdentity() {
        val c = DeliveryControl(listOf(StreamCaps.LEVELS[1], StreamCaps.LEVELS[3]), 3)
        assertEquals(3, c.level.index)
        assertEquals(2, DeliveryControl(StreamCaps.LEVELS, 2).level.index)
    }
}
