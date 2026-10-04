package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CongestionPolicyTest {
    private val ms = 1_000_000L

    @Test
    fun startsAtTheLevelMaximum() {
        val p = CongestionPolicy(startLevel = 1)
        assertEquals(StreamCaps.LEVELS[1], p.level)
        assertEquals(4_000_000, p.bitrate)
    }

    @Test
    fun aDropCutsTheBitrateByAQuarter() {
        val p = CongestionPolicy()
        assertEquals(CongestionPolicy.Action.SetBitrate(6_000_000), p.onDrop(1_000 * ms))
        assertEquals(6_000_000, p.bitrate)
    }

    @Test
    fun dropsCloseTogetherCutOnce() {
        val p = CongestionPolicy()
        p.onDrop(1_000 * ms)
        assertNull(p.onDrop(1_200 * ms))
        assertEquals(CongestionPolicy.Action.SetBitrate(4_500_000), p.onDrop(1_600 * ms))
    }

    @Test
    fun neverGoesBelowTheFloor() {
        val p = CongestionPolicy()
        var t = 1_000 * ms
        repeat(12) {
            p.onDrop(t)
            t += 600 * ms
        }
        assertEquals(StreamCaps.MIN_BITRATE, p.bitrate)
        assertEquals(0, p.level.index)
    }

    @Test
    fun levelDropsAfterFiveSecondsAtTheFloor() {
        val p = CongestionPolicy()
        var t = 1_000 * ms
        while (p.bitrate > StreamCaps.MIN_BITRATE) {
            p.onDrop(t)
            t += 600 * ms
        }
        val floorAt = t - 600 * ms
        assertNull(p.onDrop(floorAt + 4_000 * ms))
        val action = p.onDrop(floorAt + 5_000 * ms)
        assertEquals(CongestionPolicy.Action.ChangeLevel(StreamCaps.LEVELS[1], StreamCaps.MIN_BITRATE), action)
        assertEquals(1, p.level.index)
    }

    @Test
    fun theLowestLevelStaysPut() {
        val p = CongestionPolicy(startLevel = 3)
        var t = 1_000 * ms
        repeat(40) {
            p.onDrop(t)
            t += 600 * ms
        }
        assertEquals(3, p.level.index)
        assertEquals(StreamCaps.MIN_BITRATE, p.bitrate)
    }

    @Test
    fun cleanStretchesStepBackUpToTheLevelMaximum() {
        val p = CongestionPolicy()
        p.onDrop(1_000 * ms)
        assertNull(p.tick(5_000 * ms))
        assertEquals(CongestionPolicy.Action.SetBitrate(6_600_000), p.tick(6_000 * ms))
        assertNull(p.tick(8_000 * ms))
        assertEquals(CongestionPolicy.Action.SetBitrate(7_260_000), p.tick(11_000 * ms))
        assertEquals(CongestionPolicy.Action.SetBitrate(7_986_000), p.tick(16_000 * ms))
        assertEquals(CongestionPolicy.Action.SetBitrate(8_000_000), p.tick(21_000 * ms))
        assertNull(p.tick(40_000 * ms))
    }

    @Test
    fun normalAdaptationSkipsExperimentalRung() {
        val p = CongestionPolicy(startLevel = 1)
        var now = 0L
        repeat(40) {
            p.onDrop(now)
            now += 600 * ms
        }
        assertEquals(3, p.level.index)
    }

    @Test
    fun debugStartCanUseExperimentalRung() {
        val p = CongestionPolicy(startLevel = 2)
        assertEquals(2, p.level.index)
        assertEquals(960, p.level.width)
    }

    @Test
    fun filteredLadderUsesRankNotListPosition() {
        val p = CongestionPolicy(levels = listOf(StreamCaps.LEVELS[1], StreamCaps.LEVELS[3]), startLevel = 1)
        var now = 0L
        repeat(40) {
            p.onDrop(now)
            now += 600 * ms
        }
        assertEquals(3, p.level.index)
    }

    @Test
    fun aLowerLevelNeverStepsBackUpALevel() {
        val p = CongestionPolicy(startLevel = 1)
        var t = 0L
        repeat(200) {
            p.tick(t)
            t += 1_000 * ms
        }
        assertEquals(1, p.level.index)
        assertEquals(4_000_000, p.bitrate)
    }
}
