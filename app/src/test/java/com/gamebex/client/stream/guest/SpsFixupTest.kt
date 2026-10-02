package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.AnnexB
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPS rewrite. The real SPS units come from x264 (ffmpeg 7.0.2, libx264):
 * baseline 1600x720 with timing info and a bitstream restriction, and High
 * 1280x576 with NAL HRD parameters, so the VUI walk and the emulation-prevention
 * bytes are covered. [baselineFixed] and [noVuiFixed] were checked by decoding
 * the x264 stream with the SPS swapped in: ffmpeg's framemd5 output was identical.
 */
class SpsFixupTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val baseline = hex("6742c02ad9006405bb0110000003001000000788f1832480")
    private val baselineFixed = hex("6742c02ad9006405bb0110000003001000000788da08846480")
    private val noVuiReal = hex("6742c02ad9006405b9")
    private val noVuiFixed = hex("6742c02ad9006405ba01b41108c9")
    private val high = hex("6764001facd94050049b0110000003001000000303ce040002dc6c001e84e0c30078c18cb0")

    /** Baseline 1600x720, POC type 2, one reference frame, no VUI; built bit by bit. */
    private fun noVui(): ByteArray {
        val w = SpsFixup.BitWriter()
        w.bits(66, 8)
        w.bits(0xC0, 8)
        w.bits(42, 8)
        w.ue(0)
        w.ue(0)
        w.ue(2)
        w.ue(1)
        w.bit(0)
        w.ue(99)
        w.ue(44)
        w.bit(1)
        w.bit(1)
        w.bit(0)
        w.bit(0)
        w.bit(1)
        w.alignZero()
        return byteArrayOf(0x67) + SpsFixup.escape(w.toByteArray())
    }

    @Test
    fun readsTheRealBaselineSps() {
        val p = SpsFixup.inspect(baseline)
        assertEquals(66, p.profileIdc)
        assertEquals(100, p.widthMbs)
        assertEquals(45, p.heightMapUnits)
        assertTrue(p.frameMbsOnly)
        assertTrue(p.hasVui)
        assertTrue(p.hasRestriction)
        assertEquals(3, p.maxNumRefFrames)
    }

    @Test
    fun rewritesTheRestrictionOfTheRealBaselineSps() {
        val fixed = SpsFixup.fixSps(baseline)!!
        assertArrayEquals(baselineFixed, fixed)
        val p = SpsFixup.inspect(fixed)
        assertEquals(100, p.widthMbs)
        assertEquals(45, p.heightMapUnits)
        assertEquals(0, p.maxNumReorderFrames)
        assertEquals(3, p.maxDecFrameBuffering)
    }

    @Test
    fun addsAVuiToTheRealSpsWithItsVuiRemoved() {
        assertFalse(SpsFixup.inspect(noVuiReal).hasVui)
        assertArrayEquals(noVuiFixed, SpsFixup.fixSps(noVuiReal))
    }

    @Test
    fun rewritesTheRealHighSpsWithHrd() {
        val before = SpsFixup.inspect(high)
        assertEquals(100, before.profileIdc)
        assertEquals(80, before.widthMbs)
        assertEquals(36, before.heightMapUnits)
        val p = SpsFixup.inspect(SpsFixup.fixSps(high)!!)
        assertEquals(80, p.widthMbs)
        assertEquals(36, p.heightMapUnits)
        assertEquals(0, p.maxNumReorderFrames)
        assertEquals(before.maxNumRefFrames, p.maxDecFrameBuffering)
    }

    @Test
    fun addsAVuiWhenThereIsNone() {
        val sps = noVui()
        assertFalse(SpsFixup.inspect(sps).hasVui)
        val p = SpsFixup.inspect(SpsFixup.fixSps(sps)!!)
        assertTrue(p.hasVui)
        assertTrue(p.hasRestriction)
        assertEquals(0, p.maxNumReorderFrames)
        assertEquals(1, p.maxDecFrameBuffering)
        assertEquals(100, p.widthMbs)
    }

    @Test
    fun fixingTwiceChangesNothing() {
        val once = SpsFixup.fixSps(high)!!
        assertArrayEquals(once, SpsFixup.fixSps(once))
    }

    @Test
    fun theOutputHasNoStartCodeInside() {
        for (sps in listOf(baseline, high, noVui())) {
            val fixed = SpsFixup.fixSps(sps)!!
            assertEquals(1, AnnexB.split(AnnexB.join(listOf(fixed))).size)
        }
    }

    @Test
    fun fixesTheSpsInsideCsdAndKeepsThePps() {
        val pps = hex("68ebe3cb22c0")
        val csd = AnnexB.join(listOf(baseline, pps))
        val out = AnnexB.split(SpsFixup.fixCsd(csd))
        assertEquals(2, out.size)
        val fixedCsd = SpsFixup.fixCsd(csd)
        assertArrayEquals(pps, fixedCsd.copyOfRange(out[1].start, out[1].end))
        assertTrue(SpsFixup.inspect(fixedCsd.copyOfRange(out[0].start, out[0].end)).hasRestriction)
    }

    @Test
    fun garbageIsLeftAlone() {
        assertNull(SpsFixup.fixSps(byteArrayOf(0x67, 0x42)))
        val csd = AnnexB.join(listOf(byteArrayOf(0x67, 0x42), hex("68ebe3cb22c0")))
        assertArrayEquals(csd, SpsFixup.fixCsd(csd))
    }

    @Test
    fun escapingRoundTrips() {
        val raw = byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 2, 5, 0, 0, 3)
        val escaped = SpsFixup.escape(raw)
        assertArrayEquals(byteArrayOf(0, 0, 3, 0, 0, 3, 0, 1, 0, 0, 3, 2, 5, 0, 0, 3, 3), escaped)
        assertArrayEquals(raw, SpsFixup.unescape(escaped, 0))
    }
}
