package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AvcScreenSourceTest {
    @Test
    fun staleCodecIdentityAndGenerationCannotOwnCallbacks() {
        val old = Any()
        val live = Any()
        assertFalse(AvcScreenSource.identityMatches(live, 2, old, 1))
        assertFalse(AvcScreenSource.identityMatches(live, 2, live, 1))
        assertTrue(AvcScreenSource.identityMatches(live, 2, live, 2))
        assertFalse(AvcScreenSource.identityMatches(null, 3, live, 2))
    }

    @Test
    fun outputFormatReportsActualDimensionsAndRankNotRequested() {
        val actual = AvcScreenSource.actualLevelFor(StreamCaps.LEVELS[0], 1280, 576, 30)
        assertEquals(1, actual.index)
        assertEquals(1280, actual.width)
        assertEquals(576, actual.height)
        assertEquals(60, actual.fps)
    }

    @Test
    fun vendorRatesCannotKillKnownSizesOrChangeRequestedCadence() {
        for (requested in StreamCaps.LEVELS.flatMap { listOf(it, it.copy(fps = 59), it.copy(fps = 50)) }) {
            for (reportedFps in listOf(59, 50, 30, 0, -1)) {
                val actual = AvcScreenSource.actualLevelFor(requested, requested.width, requested.height, reportedFps)
                assertEquals(requested, actual)
            }
        }
    }

    @Test
    fun palEncoderCadenceDoesNotInventAWireRung() {
        val requested = StreamCaps.LEVELS[2]
        val configuration = AvcEncoders.configurationAttempts(requested,
            AvcEncoders.EncoderInfo("fake", true, emptySet(), false, 4096, 2304),
            AvcEncoders.Options(fps = 50)).first()
        assertEquals(50, configuration.fps)
        assertEquals(requested, AvcScreenSource.actualLevelFor(requested, 960, 432, configuration.fps))
    }

    @Test(expected = IllegalStateException::class)
    fun invalidOutputDimensionsFailInsteadOfLyingAboutRank() {
        AvcScreenSource.actualLevelFor(StreamCaps.LEVELS[0], 0, 720, 50)
    }

    @Test(expected = IllegalStateException::class)
    fun unknownOutputFormatFailsInsteadOfLyingAboutRank() {
        AvcScreenSource.actualLevelFor(StreamCaps.LEVELS[0], 1024, 512, 60)
    }

    @Test
    fun cropSizeMatchingTheRequestIsAcceptedWithoutALadderRung() {
        val requested = StreamCaps.LEVELS[1].copy(width = 768, height = 576)
        assertEquals(requested, AvcScreenSource.actualLevelFor(requested, 768, 576, 60))
    }

    @Test
    fun encoderCatalogIsDiscoveredOncePerSourceLifetime() {
        var calls = 0
        val info = AvcEncoders.EncoderInfo("fake", true, emptySet(), false, 4096, 2304, setOf(1, 3))
        val source = AvcScreenSource(encoders = { calls++; listOf(info) }, codecDiagnostics = false, options = AvcEncoders.Options())
        assertEquals(listOf(1, 3), source.eligibleLevels.map { it.index })
        assertEquals(listOf(1, 3), source.eligibleLevels.map { it.index })
        assertEquals(1, calls)
    }

    @Test
    fun noCaptureTargetStopsBeforeCodecDiscoveryOrSilentMirror() {
        var calls = 0
        val source = AvcScreenSource(encoders = { calls++; emptyList() }, codecDiagnostics = false, options = AvcEncoders.Options())
        val sink = object : VideoSink {
            override fun onConfig(csd: ByteArray) = Unit
            override fun onFrame(frame: EncodedFrame) = Unit
            override fun onFailure(reason: String) = Unit
        }
        try {
            source.start(StreamCaps.LEVELS[1], 4_000_000, sink)
            fail("missing consent must stop")
        } catch (f: StreamFailure) {
            assertEquals(StreamError.DISPLAY_CAPTURE, f.error)
        }
        assertEquals(0, calls)
    }

    @Test
    fun slowStartCheckJudgesTheMedianOnceAfterWarmUp() {
        val fast = SlowStartCheck(thresholdNs = 18_000_000L, skip = 2, window = 5)
        assertFalse(fast.add(90_000_000L))
        assertFalse(fast.add(90_000_000L))
        repeat(4) { assertFalse(fast.add(13_000_000L)) }
        assertFalse(fast.add(40_000_000L))
        assertFalse(fast.add(40_000_000L))
        val slow = SlowStartCheck(thresholdNs = 18_000_000L, skip = 0, window = 5)
        val answers = listOf(23L, 22L, 12L, 24L, 25L, 30L, 30L).map { slow.add(it * 1_000_000L) }
        assertEquals(listOf(false, false, false, false, true, false, false), answers)
    }
    @Test
    fun glPassAllowanceKeepsHealthyCropCapturesFromReopening() {
        val direct = SlowStartCheck(AvcScreenSource.slowThresholdNs(false), skip = 0, window = 3)
        val crop = SlowStartCheck(AvcScreenSource.slowThresholdNs(true), skip = 0, window = 3)
        repeat(2) { assertFalse(direct.add(23_000_000L)); assertFalse(crop.add(20_700_000L)) }
        assertTrue(direct.add(23_000_000L))
        assertFalse(crop.add(20_700_000L))
        assertEquals(26_000_000L, AvcScreenSource.slowThresholdNs(true))
        assertEquals(9_000_000L, AvcScreenSource.slowThresholdNs(true, 1_000_000L))
    }

    @Test
    fun reopenedEncoderReportsItsMedianWithoutAnotherReopen() {
        for (ms in listOf(14L, 32L)) {
            val check = SlowStartCheck(reportOnly = true, skip = 0, window = 3)
            repeat(3) { assertFalse(check.add(ms * 1_000_000L)) }
            assertTrue(check.done)
            assertEquals(ms * 1_000_000L, check.medianNs)
            assertFalse(check.add(99_000_000L))
            assertEquals(ms * 1_000_000L, check.medianNs)
        }
    }

    @Test
    fun consentWaitDoesNotConsumeTheStartupSampleDeadline() {
        var now = 0L
        val check = SlowStartCheck(skip = 0, window = 3, clock = { now })
        now = 10_000_000_000L
        repeat(3) { assertFalse(check.add(14_000_000L)) }
        assertEquals(14_000_000L, check.medianNs)
    }

    @Test
    fun invalidTimestampsCannotFillTheWindowAndExpireOnce() {
        var now = 0L
        val check = SlowStartCheck(skip = 0, window = 3, clock = { now })
        repeat(3) { assertFalse(check.add(0L)) }
        assertFalse(check.done)
        now = 5_000_000_000L
        assertFalse(check.add(0L))
        assertTrue(check.done)
        assertEquals(null, check.medianNs)
        assertFalse(check.add(25_000_000L))
    }

}
