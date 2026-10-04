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
        assertEquals(3, actual.index)
        assertEquals(1280, actual.width)
        assertEquals(576, actual.height)
        assertEquals(30, actual.fps)
    }

    @Test(expected = IllegalStateException::class)
    fun unknownOutputFormatFailsInsteadOfLyingAboutRank() {
        AvcScreenSource.actualLevelFor(StreamCaps.LEVELS[0], 1024, 512, 60)
    }

    @Test
    fun encoderCatalogIsDiscoveredOncePerSourceLifetime() {
        var calls = 0
        val info = AvcEncoders.EncoderInfo("fake", true, emptySet(), false, 4096, 2304, setOf(1, 3))
        val source = AvcScreenSource(encoders = { calls++; listOf(info) }, codecDiagnostics = false)
        assertEquals(listOf(1, 3), source.eligibleLevels.map { it.index })
        assertEquals(listOf(1, 3), source.eligibleLevels.map { it.index })
        assertEquals(1, calls)
    }

    @Test
    fun noCaptureTargetStopsBeforeCodecDiscoveryOrSilentMirror() {
        var calls = 0
        val source = AvcScreenSource(encoders = { calls++; emptyList() }, codecDiagnostics = false)
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
