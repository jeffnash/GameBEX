package com.gamebex.client.launch

import android.view.InputDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controller header status lines. */
class ControllerProbeTest {
    @Test fun checkingBeforeProbe() {
        assertEquals("Controller: checking…", ControllerProbe.statusLine(null))
    }

    @Test fun noneConnected() {
        assertEquals(
            "Controller: none — connect a pad, then rescan (X)",
            ControllerProbe.statusLine(emptyList()),
        )
    }

    @Test fun singlePadNamed() {
        val one = listOf(ControllerProbe.Controller("GameSir-X2s", "d1"))
        assertEquals("Controller: GameSir-X2s", ControllerProbe.statusLine(one))
    }

    @Test fun multiplePadsCounted() {
        val two = listOf(
            ControllerProbe.Controller("GameSir-X2s", "d1"),
            ControllerProbe.Controller("Xbox Pad", "d2"),
        )
        assertEquals("Controller: GameSir-X2s +1", ControllerProbe.statusLine(two))
    }

    @Test fun gamepadSourceMatching() {
        assertTrue(ControllerProbe.isGamepadSource(InputDevice.SOURCE_GAMEPAD))
        assertTrue(ControllerProbe.isGamepadSource(InputDevice.SOURCE_JOYSTICK))
        assertTrue(
            ControllerProbe.isGamepadSource(
                InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD,
            ),
        )
    }

    @Test fun keyboardIsNotAGamepad() {
        // Source classes share low bits: a naive nonzero AND matches these.
        assertTrue(!ControllerProbe.isGamepadSource(InputDevice.SOURCE_KEYBOARD))
        assertTrue(!ControllerProbe.isGamepadSource(InputDevice.SOURCE_DPAD))
        assertTrue(
            !ControllerProbe.isGamepadSource(
                InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_TOUCHSCREEN,
            ),
        )
        assertTrue(!ControllerProbe.isGamepadSource(InputDevice.SOURCE_MOUSE))
    }
}
