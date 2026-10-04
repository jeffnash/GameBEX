package com.gamebex.client.stream.guest

import org.junit.Assert.*
import org.junit.Test

class StreamTouchPadTest {
    @Test fun touchTimestampUsesEventUptimeAgeNotComposeHandlingTime() {
        assertEquals(9_992_000_000L, touchSampleNs(992, 10_000_000_000L, 1000))
        assertEquals(10_000_000_000L, touchSampleNs(1001, 10_000_000_000L, 1000))
    }

    @Test fun touchReleasePreservesAnotherHeldControl() {
        val events = mutableListOf<Triple<Int, Boolean, Long>>()
        val pad = TouchPadState({ key, down, atNs -> events += Triple(key, down, atNs) })
        pad.press(1, 96, 10)
        pad.press(2, 97, 20)
        pad.release(1, 30)
        assertEquals(listOf(Triple(96, true, 10L), Triple(97, true, 20L), Triple(96, false, 30L)), events)
        pad.release(2, 40)
        assertEquals(Triple(97, false, 40L), events.last())
    }
}
