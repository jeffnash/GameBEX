package com.gamebex.client.stream.guest

import android.view.InputDevice
import org.junit.Assert.*
import org.junit.Test

class StreamViewerActivityTest {
    @Test fun onlyJoystickClassEnabled() {
        assertEquals(InputDevice.SOURCE_CLASS_JOYSTICK, unbufferedJoystickSources(30, true))
    }

    @Test fun disabledRestoresNone() {
        assertEquals(InputDevice.SOURCE_CLASS_NONE, unbufferedJoystickSources(36, false))
    }

    @Test fun oldApiDoesNotCallPlatform() {
        assertEquals(InputDevice.SOURCE_CLASS_NONE, unbufferedJoystickSources(29, true))
    }

    @Test fun displaySelectionKeepsPhysicalResolutionAndRejectsUnsupportedId() {
        val modes = listOf(ViewerDisplayMode(1, 1080, 2400, 60f), ViewerDisplayMode(2, 1080, 2400, 120f),
            ViewerDisplayMode(3, 1440, 3200, 144f))
        assertEquals(2, selectViewerDisplayMode(modes, 1080, 2400, 0)?.id)
        assertEquals(1, selectViewerDisplayMode(modes, 1080, 2400, 1)?.id)
        assertNull(selectViewerDisplayMode(modes, 1080, 2400, 3))
        assertNull(selectViewerDisplayMode(modes, 1080, 2400, 99))
        assertNull(selectViewerDisplayMode(modes, 1080, 2400, -1))
    }

    @Test fun optionsDefaultOffAndSampleEachPropertyOnce() {
        val calls = mutableListOf<String>()
        val options = ViewerOptions.sample(
            { name, default -> calls += name; default },
            { name, default, _ -> calls += name; default },
        )
        assertEquals(ViewerOptions(), options)
        assertEquals(7, calls.size)
        assertEquals(calls.size, calls.toSet().size)
        assertFalse(options.unbufferedInput)
        assertEquals(0, options.frameRateVote)
        repeat(20) { assertEquals(0, options.frameRateVote) }
        assertEquals(7, calls.size)
    }
}
