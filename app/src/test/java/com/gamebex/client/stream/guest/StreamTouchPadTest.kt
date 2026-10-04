package com.gamebex.client.stream.guest

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class StreamTouchPadTest {
    @Test fun touchTimestampUsesEventUptimeAgeNotComposeHandlingTime() {
        assertEquals(9_992_000_000L, touchSampleNs(992, 10_000_000_000L, 1000))
        assertEquals(10_000_000_000L, touchSampleNs(1001, 10_000_000_000L, 1000))
    }

    @Test fun controllerPresenceReleasesTouchHoldsWithoutClearingPhysicalInput() {
        val physical = PadMapper()
        val touch = PadMapper()
        val pad = TouchPadState(
            { key, down, atNs -> touch.onKey(key, down, atNs); Unit },
            { x, y, _ -> touch.onAxes(x, y, 0f, 0f, 0f, 0f, 0f, 0f) },
        )
        physical.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 1)
        physical.onAxes(0.5f, -0.5f, 0.75f, 0f, 1f, 0f, -1f, 0f)
        val heldPhysical = physical.state()
        var connected = false
        var releases = 0
        val releaseTouch = {
            pad.releaseAll(30)
            touch.reset()
            releases++
            Unit
        }
        pad.press(1, KeyEvent.KEYCODE_BUTTON_A, 10)
        pad.press(2, KeyEvent.KEYCODE_BUTTON_B, 20)
        pad.moveStick(3, -1f, 1f, 25)
        connected = updateViewerControllerPresence(connected, true, releaseTouch)
        assertTrue(connected)
        assertEquals(1, releases)
        assertTrue(touch.state().isNeutral)
        assertEquals(heldPhysical, mergePadStates(physical.state(), touch.state()))
        connected = updateViewerControllerPresence(connected, false, releaseTouch)
        assertFalse(connected)
        assertEquals(2, releases)
        assertEquals(heldPhysical, mergePadStates(physical.state(), touch.state()))
        pad.release(1, 40)
        pad.release(2, 40)
        pad.release(3, 40)
        assertTrue(touch.state().isNeutral)
        assertEquals(heldPhysical, physical.state())
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
