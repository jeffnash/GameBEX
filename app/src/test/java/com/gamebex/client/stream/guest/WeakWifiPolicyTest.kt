package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.DeliveryWire
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakWifiPolicyTest {
    private val second = 1_000_000_000L

    private fun policy(startLevel: Int = 1, maxBitrate: Int = 4_000_000) = WeakWifiPolicy().apply {
        quality(startLevel, maxBitrate, maxBitrate, 0L)
        codecEpoch(1L)
    }

    private fun report(at: Long, delay: Long = 50_000_000L, epoch: Long = 1L,
        frames: Int = 30, seq: Int = (at / 100_000_000L).toInt() + 1) =
        DeliveryWire.Report(epoch, seq, at, 500_000_000L, 100_000L, frames, delay, 0, 1)

    private fun evidence(policy: WeakWifiPolicy, at: Long, rtt: Long = 10_000_000L,
        delay: Long = 50_000_000L): Boolean {
        policy.roundTrip(rtt, true, at)
        return policy.delivery(report(at, delay))
    }

    private fun sustain(policy: WeakWifiPolicy, from: Long = 0L, until: Long = 10 * second,
        rtt: Long = 10_000_000L, delay: Long = 50_000_000L): Boolean {
        var visible = false
        var at = from
        while (at <= until) {
            visible = evidence(policy, at, rtt, delay)
            at += 100_000_000L
        }
        return visible
    }

    @Test
    fun oneDropToSeventyFivePercentNeverShowsNotice() {
        val policy = policy()
        assertFalse(policy.quality(1, 3_000_000, 4_000_000, 0L))
        assertFalse(sustain(policy, until = 20 * second))
    }

    @Test
    fun shortSevereReductionNeverShowsNotice() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        assertFalse(sustain(policy, until = 5 * second))
        assertFalse(policy.quality(1, 4_000_000, 4_000_000, 5 * second))
        assertFalse(sustain(policy, from = 5 * second + 100_000_000L, until = 20 * second))
    }

    @Test
    fun sustainedNoticeRequiresExactTenSecondBoundary() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        assertFalse(sustain(policy, until = 9 * second + 900_000_000L))
        assertFalse(policy.tick(10 * second - 1L))
        assertTrue(evidence(policy, 10 * second))
    }

    @Test
    fun healthyGuestNetworkSuppressesEvenSustainedReduction() {
        val policy = policy()
        policy.quality(3, 1_500_000, 3_000_000, 0L)
        assertFalse(sustain(policy, until = 20 * second, delay = 5_000_000L))
    }

    @Test
    fun sustainedHighRoundTripCanProvideNetworkDistress() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        assertTrue(sustain(policy, rtt = 60_000_000L, delay = 5_000_000L))
    }

    @Test
    fun unknownInvalidAndStaleTelemetryNeverBlamesWifi() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        assertFalse(policy.tick(20 * second))
        assertFalse(policy.roundTrip(60_000_000L, false, 20 * second))
        assertFalse(policy.delivery(report(20 * second)))
        policy.roundTrip(60_000_000L, true, 21 * second)
        policy.delivery(report(21 * second, frames = 0))
        assertFalse(policy.tick(22 * second))
        assertFalse(policy.tick(40 * second))
    }

    @Test
    fun sixtyPercentDoesNotEnterAndEightyPercentImmediatelyClears() {
        val policy = policy()
        policy.quality(1, 2_400_000, 4_000_000, 0L)
        assertFalse(sustain(policy))
        policy.quality(1, 2_399_999, 4_000_000, 10 * second)
        assertTrue(sustain(policy, from = 10 * second + 100_000_000L, until = 21 * second))
        assertTrue(policy.quality(1, 3_199_999, 4_000_000, 21 * second))
        assertFalse(policy.quality(1, 3_200_000, 4_000_000, 21 * second))
    }

    @Test
    fun reductionHysteresisDoesNotHuntBetweenSixtyAndEightyPercent() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        assertFalse(sustain(policy, until = 5 * second))
        policy.quality(1, 3_000_000, 4_000_000, 5 * second)
        assertTrue(sustain(policy, from = 5 * second + 100_000_000L))
    }

    @Test
    fun lowerLevelCanEnterButLowerStartingLevelIsNotReduction() {
        val policy = policy(3, 3_000_000)
        assertFalse(sustain(policy))
        val lowered = policy()
        lowered.quality(3, 3_000_000, 3_000_000, 0L)
        assertTrue(sustain(lowered))
        assertFalse(lowered.quality(1, 4_000_000, 4_000_000, 10 * second))
    }

    @Test
    fun healthyRecoveryResetsContinuousDistressClock() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        assertTrue(sustain(policy))
        assertFalse(evidence(policy, 10 * second + 100_000_000L, delay = 5_000_000L))
        assertFalse(sustain(policy, from = 10 * second + 200_000_000L, until = 20 * second + 100_000_000L))
        assertTrue(evidence(policy, 20 * second + 200_000_000L))
    }

    @Test
    fun missingFreshFeedbackBreaksContinuity() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        assertFalse(sustain(policy, until = 9 * second))
        assertFalse(policy.tick(11 * second))
        assertFalse(sustain(policy, from = 11 * second, until = 20 * second + 900_000_000L))
        assertTrue(evidence(policy, 21 * second))
    }

    @Test
    fun reconnectResetsReductionAndEvidenceButRetainsStartingQuality() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        assertTrue(sustain(policy))
        policy.connectionReset()
        assertFalse(policy.tick(11 * second))
        assertFalse(policy.delivery(report(11 * second)))
        policy.codecEpoch(1L)
        assertFalse(sustain(policy, from = 11 * second, until = 21 * second))
        policy.quality(3, 3_000_000, 3_000_000, 21 * second)
        assertTrue(sustain(policy, from = 21 * second + 100_000_000L, until = 32 * second))
    }

    @Test
    fun codecEpochRejectsOldReportsAndRestartsContinuousClock() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        assertTrue(sustain(policy))
        policy.codecEpoch(2L)
        assertFalse(policy.delivery(report(11 * second)))
        assertFalse(policy.tick(11 * second))
        for (n in 0..100) {
            val at = 11 * second + n * 100_000_000L
            policy.roundTrip(10_000_000L, true, at)
            val visible = policy.delivery(report(at, epoch = 2L))
            if (n < 100) assertFalse(visible) else assertTrue(visible)
        }
    }

    @Test
    fun asynchronousSampleTimesDoNotResetSustainedDistress() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        assertFalse(sustain(policy, until = 9 * second + 900_000_000L))
        policy.roundTrip(10_000_000L, true, 10 * second + 10_000_000L)
        assertTrue(policy.delivery(report(10 * second)))
        assertTrue(policy.tick(10 * second + 20_000_000L))
        assertTrue(policy.tick(9 * second + 900_000_000L))
        assertTrue(policy.roundTrip(10_000_000L, true, 10 * second))
    }

    @Test
    fun productionFeedbackCadenceAndTickerOrderingPreserveContinuity() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        for (n in 0..102) {
            val at = n * 100_000_000L
            if (n % 10 == 0) policy.roundTrip(10_000_000L, true, at + 10_000_000L)
            if (n % 5 == 0) policy.delivery(report(at))
            val visible = policy.tick(at)
            if (n < 100) assertFalse(visible) else assertTrue(visible)
        }
    }

    @Test
    fun duplicateReportsCannotExtendFreshness() {
        val policy = policy()
        policy.quality(1, 2_000_000, 4_000_000, 0L)
        assertTrue(sustain(policy))
        policy.roundTrip(10_000_000L, true, 12 * second)
        assertFalse(policy.delivery(report(12 * second, seq = 1)))
        assertFalse(policy.tick(12 * second))
    }
}
