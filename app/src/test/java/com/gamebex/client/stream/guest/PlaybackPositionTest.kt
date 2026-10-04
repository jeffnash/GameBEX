package com.gamebex.client.stream.guest

import org.junit.Assert.*
import org.junit.Test

class PlaybackPositionTest {
    @Test fun unsignedWrap() {
        val head = PlaybackPosition()
        assertEquals(0xffff_fffeL, head.update(-2))
        assertEquals(0x1_0000_0003L, head.update(3))
    }
    @Test fun backwardSmallStepRejected() {
        val head = PlaybackPosition()
        assertEquals(10L, head.update(10))
        assertNull(head.update(9))
        assertEquals(11L, head.update(11))
    }
    @Test fun flushExplicitlyResets() {
        val head = PlaybackPosition()
        head.update(100)
        head.reset()
        assertEquals(0L, head.update(0))
    }
    @Test fun startsFromObservedHead() {
        assertEquals(123L, PlaybackPosition().update(123))
    }
}
