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
}
