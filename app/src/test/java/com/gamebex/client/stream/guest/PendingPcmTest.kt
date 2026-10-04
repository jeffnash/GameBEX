package com.gamebex.client.stream.guest

import org.junit.Assert.*
import org.junit.Test

class PendingPcmTest {
    private fun block() = PendingPcm(ByteArray(8) { it.toByte() }, AudioRenderInfo(1, 10, .25, 2, false, 2.0004), 12)
    @Test fun partialWritesPreserveBytes() {
        val block = block()
        block.wrote(3)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), block.bytes.copyOfRange(block.offset, block.bytes.size))
        block.wrote(5)
        assertTrue(block.complete)
        assertEquals(3L, block.outputFirstFrame)
        assertEquals(2.0004, block.info.sourceFrames, 0.0)
    }
    @Test fun zeroWriteDoesNotAdvance() {
        val block = block()
        block.wrote(0)
        assertEquals(0, block.offset)
        assertFalse(block.complete)
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedReturnRejected() { block().wrote(9) }
    @Test fun twoBytePartialNotOneStereoFrame() {
        val block = block()
        block.wrote(2)
        assertEquals(2, block.offset)
        assertEquals(0, block.offset / 4)
    }
}
