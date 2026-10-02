package com.gamebex.client.stream.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBTest {
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x29, 0x8D.toByte())
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x21)
    private val slice = byteArrayOf(0x41, 0x9A.toByte(), 0x02)

    @Test
    fun splitsFourAndThreeByteStartCodes() {
        val data = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps + byteArrayOf(0, 0, 0, 1) + idr
        val nals = AnnexB.split(data)
        assertEquals(listOf(AnnexB.NAL_SPS, AnnexB.NAL_PPS, AnnexB.NAL_IDR), nals.map { it.type })
        assertArrayEquals(sps, data.copyOfRange(nals[0].start, nals[0].end))
        assertArrayEquals(pps, data.copyOfRange(nals[1].start, nals[1].end))
        assertArrayEquals(idr, data.copyOfRange(nals[2].start, nals[2].end))
    }

    @Test
    fun theLeadingZeroOfAFourByteStartCodeIsNotPartOfTheNal() {
        val nals = AnnexB.split(AnnexB.join(listOf(idr, slice)))
        assertEquals(idr.size, nals[0].size)
        assertEquals(slice.size, nals[1].size)
        assertEquals(AnnexB.NAL_SLICE, nals[1].type)
    }

    @Test
    fun extractsParameterSetsAsCsd() {
        val data = AnnexB.join(listOf(sps, pps, idr))
        val csd = AnnexB.parameterSets(data)
        assertArrayEquals(AnnexB.join(listOf(sps, pps)), csd)
        assertTrue(AnnexB.hasIdr(data))
    }

    @Test
    fun noCsdWithoutBothSets() {
        assertNull(AnnexB.parameterSets(AnnexB.join(listOf(sps, idr))))
        assertFalse(AnnexB.hasIdr(AnnexB.join(listOf(slice))))
    }

    @Test
    fun ignoresGarbageBeforeTheFirstStartCode() {
        val data = byteArrayOf(7, 7) + AnnexB.join(listOf(slice))
        assertEquals(listOf(AnnexB.NAL_SLICE), AnnexB.split(data).map { it.type })
        assertTrue(AnnexB.split(byteArrayOf(1, 2, 3)).isEmpty())
    }
}
