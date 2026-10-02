package com.gamebex.client.stream.proto

import com.gamebex.client.stream.StreamToken
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadStateTest {
    @Test fun neutralReportCentersEverything() {
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 8), PadHid.report(PadState.NEUTRAL))
        assertTrue(PadState.NEUTRAL.isNeutral)
    }

    @Test fun reportLaysOutButtonsSticksTriggersAndHat() {
        val s = PadState(PadState.A or PadState.R3, -127, 127, -1, 64, 255, 128, PadState.HAT_DOWN_LEFT)
        val r = PadHid.report(s)
        assertEquals(PadHid.REPORT_SIZE, r.size)
        assertEquals(1, r[0].toInt())
        assertEquals(0x01, r[1].toInt() and 0xFF)
        assertEquals(0x40, r[2].toInt() and 0xFF)
        assertEquals(-127, r[3].toInt())
        assertEquals(127, r[4].toInt())
        assertEquals(-1, r[5].toInt())
        assertEquals(64, r[6].toInt())
        assertEquals(255, r[7].toInt() and 0xFF)
        assertEquals(128, r[8].toInt() and 0xFF)
        assertEquals(PadState.HAT_DOWN_LEFT, r[9].toInt())
    }

    @Test fun sanitizeClampsAndDropsModeAndUnusedBits() {
        val s = PadState(0xFFFF, -500, 500, 0, 0, -1, 999, 42).sanitized()
        assertEquals(PadState.ALL_BUTTONS, s.buttons)
        assertFalse(s.buttons and PadState.MODE != 0)
        assertEquals(-127, s.lx)
        assertEquals(127, s.ly)
        assertEquals(0, s.lt)
        assertEquals(255, s.rt)
        assertEquals(PadState.HAT_CENTER, s.hat)
    }

    @Test fun hatFromDirections() {
        assertEquals(PadState.HAT_UP, PadState.hatFrom(true, false, false, false))
        assertEquals(PadState.HAT_UP_RIGHT, PadState.hatFrom(true, false, false, true))
        assertEquals(PadState.HAT_DOWN, PadState.hatFrom(false, true, false, false))
        assertEquals(PadState.HAT_UP_LEFT, PadState.hatFrom(true, false, true, false))
        assertEquals(PadState.HAT_CENTER, PadState.hatFrom(true, true, true, true))
        assertEquals(PadState.HAT_CENTER, PadState.hatFrom(false, false, false, false))
    }

    @Test fun floatsConvertToWireRanges() {
        assertEquals(127, PadState.axisFromFloat(1f))
        assertEquals(-127, PadState.axisFromFloat(-1.5f))
        assertEquals(0, PadState.axisFromFloat(0.001f))
        assertEquals(255, PadState.triggerFromFloat(1f))
        assertEquals(0, PadState.triggerFromFloat(-0.2f))
    }

    @Test fun descriptorIsTheReviewedGamepadLayout() {
        val d = PadHid.DESCRIPTOR
        assertEquals(97, d.size)
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01), d.copyOfRange(0, 8))
        assertEquals(0xC0.toByte(), d.last())
        // Walk the short items; collect every local Usage (tag 0x08).
        val usages = mutableListOf<Int>()
        var i = 0
        while (i < d.size) {
            val prefix = d[i].toInt() and 0xFF
            val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            if (prefix and 0xFC == 0x08 && size == 1) usages += d[i + 1].toInt() and 0xFF
            i += 1 + size
        }
        assertEquals(d.size, i)
        // Right stick on Z and Rz: the axes RetroArch's Android driver reads.
        assertEquals(listOf(0x05, 0x30, 0x31, 0x32, 0x35, 0xC5, 0xC4, 0x39), usages)
    }

    @Test fun tokenHelpers() {
        val t = StreamToken.generate()
        assertTrue(StreamToken.isValid(t))
        assertFalse(StreamToken.isValid(t.uppercase()))
        assertFalse(StreamToken.isValid(t.dropLast(1)))
        val fixed = "0102030405060708090a0b0c0d0e0f10"
        assertArrayEquals(ByteArray(16) { (it + 1).toByte() }, StreamToken.bytes(fixed))
        assertEquals(0x0102030405060708L, StreamToken.key(fixed))
    }
}
