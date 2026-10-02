package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Encoder choice: hardware first, Constrained Baseline, CBR, big enough. */
class AvcEncodersTest {
    private fun enc(name: String, hw: Boolean, profiles: Set<Int> = setOf(1), cbr: Boolean = false, w: Int = 4096, h: Int = 2304) =
        AvcEncoders.EncoderInfo(name, hw, profiles, cbr, w, h)

    @Test
    fun hardwareBeatsSoftware() {
        val ranked = AvcEncoders.rank(listOf(enc("c2.android.avc.encoder", false), enc("c2.mtk.avc.encoder", true)), StreamCaps.LEVELS[0])
        assertEquals(listOf("c2.mtk.avc.encoder", "c2.android.avc.encoder"), ranked.map { it.name })
    }

    @Test
    fun constrainedBaselineThenCbrBreakTies() {
        val a = enc("a", true, setOf(AvcEncoders.PROFILE_BASELINE), cbr = true)
        val b = enc("b", true, setOf(AvcEncoders.PROFILE_BASELINE, AvcEncoders.PROFILE_CONSTRAINED_BASELINE))
        val c = enc("c", true, setOf(AvcEncoders.PROFILE_BASELINE), cbr = false)
        assertEquals(listOf("b", "a", "c"), AvcEncoders.rank(listOf(c, a, b), StreamCaps.LEVELS[0]).map { it.name })
    }

    @Test
    fun tooSmallEncodersAreLeftOut() {
        val small = enc("small", true, w = 1280, h = 720)
        assertTrue(AvcEncoders.rank(listOf(small), StreamCaps.LEVELS[0]).isEmpty())
        assertEquals(listOf("small"), AvcEncoders.rank(listOf(small), StreamCaps.LEVELS[1]).map { it.name })
    }

    @Test
    fun profileFallsBackToBaselineThenDefault() {
        assertEquals(AvcEncoders.PROFILE_CONSTRAINED_BASELINE, AvcEncoders.profileFor(enc("x", true, setOf(1, 0x10000, 8))))
        assertEquals(AvcEncoders.PROFILE_BASELINE, AvcEncoders.profileFor(enc("x", true, setOf(1, 8))))
        assertNull(AvcEncoders.profileFor(enc("x", true, setOf(8))))
    }

    @Test
    fun softwareNamesAreRecognised() {
        assertTrue(AvcEncoders.looksSoftware("OMX.google.h264.encoder"))
        assertTrue(AvcEncoders.looksSoftware("c2.android.avc.encoder"))
        assertFalse(AvcEncoders.looksSoftware("c2.mtk.avc.encoder"))
        assertFalse(AvcEncoders.looksSoftware("OMX.MTK.VIDEO.ENCODER.AVC"))
    }

    @Test
    fun capturePtsUsesDequeueTimeAndIncludesAgeBoundary() {
        val dequeuedNs = 10_000_000_000L
        assertEquals(9_950_000_000L, AvcScreenSource.captureNsForPts(9_950_000L, dequeuedNs))
        assertEquals(dequeuedNs, AvcScreenSource.captureNsForPts(10_000_000L, dequeuedNs))
        assertEquals(9_000_000_000L, AvcScreenSource.captureNsForPts(9_000_000L, dequeuedNs))
    }

    @Test
    fun staleFutureAndMissingCapturePtsUseUnknownSentinel() {
        val dequeuedNs = 10_000_000_000L
        for (ptsUs in listOf(8_999_999L, 10_000_001L, 0L, -1L, Long.MIN_VALUE)) {
            assertEquals("ptsUs=$ptsUs", 0L, AvcScreenSource.captureNsForPts(ptsUs, dequeuedNs))
        }
    }

    @Test
    fun overflowedCapturePtsCannotWrapIntoValidWindow() {
        for (ptsUs in listOf(Long.MAX_VALUE / 1_000L + 1L, Long.MAX_VALUE, 18_446_744_083_709_551L)) {
            assertEquals("ptsUs=$ptsUs", 0L, AvcScreenSource.captureNsForPts(ptsUs, 10_000_000_000L))
        }
        val ptsUs = Long.MAX_VALUE / 1_000L
        assertEquals(ptsUs * 1_000L, AvcScreenSource.captureNsForPts(ptsUs, Long.MAX_VALUE))
    }
}
