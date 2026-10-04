package com.gamebex.client.stream.guest

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AudioPlcTest {
    @Test fun defaultOffWritesZerosForLoss() {
        val plc = AudioPlc()
        val out = ShortArray(2)
        plc.sample(true, 20_000, -20_000, out)
        plc.sample(false, 0, 0, out)
        assertArrayEquals(shortArrayOf(0, 0), out)
        assertTrue(plc.gapStarted)
        plc.sample(false, 0, 0, out)
        assertFalse(plc.gapStarted)
    }
    @Test fun plcReturnCrossfadeGainContinuous() {
        val plc = AudioPlc(true)
        val out = ShortArray(2)
        repeat(100) { plc.sample(true, 10_000, -10_000, out) }
        repeat(240) { plc.sample(false, 0, 0, out) }
        var previous = out[0].toInt()
        repeat(96) {
            plc.sample(true, -10_000, 10_000, out)
            assertTrue(abs(out[0].toInt() - previous) < 220)
            previous = out[0].toInt()
        }
        assertEquals(-10_000, previous)
        assertEquals(10_000.toShort(), out[1])
    }
    @Test fun periodicToneFaultDecaysWithoutChannelSwapOrWrap() {
        val plc = AudioPlc(true)
        val out = ShortArray(2)
        repeat(960) {
            val tone = (sin(2 * Math.PI * it / 48) * 20_000).toInt()
            plc.sample(true, tone, -tone, out)
        }
        var energy = 0L
        repeat(480) {
            plc.sample(false, 0, 0, out)
            energy += out[0].toLong() * out[0]
            assertTrue(abs(out[0].toInt() + out[1].toInt()) <= 1)
        }
        assertTrue(energy > 1_000_000)
        plc.reset()
        plc.sample(false, 0, 0, out)
        assertArrayEquals(shortArrayOf(0, 0), out)
    }
    @Test fun impulseLossRemainsBounded() {
        val plc = AudioPlc(true)
        val out = ShortArray(2)
        plc.sample(true, Int.MAX_VALUE, Int.MIN_VALUE, out)
        assertEquals(32767.toShort(), out[0])
        assertEquals((-32768).toShort(), out[1])
        repeat(4800) { plc.sample(false, 0, 0, out) }
        assertTrue(abs(out[0].toInt()) < 250)
    }
}
