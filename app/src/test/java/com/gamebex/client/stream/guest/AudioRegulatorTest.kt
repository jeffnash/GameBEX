package com.gamebex.client.stream.guest

import org.junit.Assert.*
import org.junit.Test

class AudioRegulatorTest {
    @Test fun initialTotalFortyMsAndBounds() {
        val regulator = AudioRegulator()
        assertEquals(1_920, regulator.targetFrames)
        repeat(200) { i ->
            regulator.step(i * 200_000_000L, 50_000.0, 240)
            assertTrue(regulator.targetFrames in 960..2_880)
            assertTrue(regulator.ratio in .997..1.003)
        }
    }
    @Test fun sameGapDoesNotGrowEveryQuantum() {
        val regulator = AudioRegulator()
        repeat(20) { regulator.step(it * 5_000_000L, 1_920.0, 240) }
        assertEquals(2_160, regulator.targetFrames)
    }
    @Test fun shrinkOneMsPerFiveSecondsAndIgnoreReorderedPrimary() {
        val regulator = AudioRegulator()
        repeat(100) { regulator.observe(it * 240L, it * 5_000_000L + 777_000_000L) }
        repeat(100) { regulator.observe(it * 240L, it * 5_000_000L + 900_000_000L) }
        regulator.step(4_000_000_000L, 1_920.0, 0)
        assertEquals(1_920, regulator.targetFrames)
        regulator.step(5_000_000_000L, 1_920.0, 0)
        assertEquals(1_872, regulator.targetFrames)
        regulator.step(6_000_000_000L, 1_872.0, 0)
        assertEquals(1_872, regulator.targetFrames)
    }
    @Test fun thirtyMinuteFakeClockReserveStaysBoundedWithBurstAndSkew() {
        for (skew in listOf(-200.0, -50.0, 50.0, 200.0)) {
            val regulator = AudioRegulator()
            var reserve = 1_920.0
            var delivered = 0.0
            var produced = 0.0
            repeat(18_000) { tick ->
                val now = tick * 100_000_000L
                produced += 4_800 * (1 + skew / 1e6)
                val arrived = kotlin.math.floor(produced / 1_440) * 1_440
                reserve += arrived - delivered - 4_800
                delivered = arrived
                val ratio = regulator.step(now, reserve, 0, skew)
                reserve -= 4_800 * (ratio - 1)
                if (tick > 600) assertTrue("skew=$skew reserve=$reserve", reserve in 960.0..2_880.0)
                assertTrue(ratio in .997..1.003)
            }
        }
    }
    @Test fun queuedTrackFramesArePartOfTotalNotAnAdditionalTarget() {
        val a = AudioRegulator()
        val b = AudioRegulator()
        assertEquals(a.step(1_000_000L, 1_600.0 + 320, 0), b.step(1_000_000L, 1_920.0, 0), 0.0)
    }
}
