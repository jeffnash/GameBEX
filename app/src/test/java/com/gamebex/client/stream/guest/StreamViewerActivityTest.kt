package com.gamebex.client.stream.guest

import android.view.InputDevice
import android.view.KeyEvent
import com.gamebex.client.launch.ControllerRegistry
import com.gamebex.client.stream.StreamCaps
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

    @Test fun initiallyConnectedControllerReleasesTouchOnceAcrossRepeatedResumeScans() {
        var releases = 0
        var connected = false
        connected = updateViewerControllerPresence(connected, true) { releases++ }
        assertTrue(connected)
        assertEquals(1, releases)
        repeat(3) {
            connected = updateViewerControllerPresence(connected, true) { releases++ }
        }
        assertEquals(1, releases)
        connected = updateViewerControllerPresence(connected, false) { releases++ }
        assertFalse(connected)
        assertEquals(2, releases)
        connected = updateViewerControllerPresence(connected, false) { releases++ }
        assertFalse(connected)
        assertEquals(2, releases)
    }

    @Test fun livePadSnapshotsHideOnlyPhysicalControllersAndReleaseBothPresenceChanges() {
        val sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        val playerTwo = ControllerRegistry.DeviceInfo(7, StreamCaps.PAD_NAME, "p2", sources, false)
        val physicalPad = ControllerRegistry.DeviceInfo(12, "GameSir-X5s Consumer Control", "cc", sources or InputDevice.SOURCE_KEYBOARD, false)
        val virtualPad = ControllerRegistry.DeviceInfo(13, "Virtual pad", "virtual", sources, true)
        var devices = listOf(playerTwo, virtualPad)
        var connected = false
        var releases = 0
        val physical = PadMapper()
        val touch = PadMapper()
        physical.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 1)
        physical.onAxes(0.5f, -0.75f, 0f, 0f, 1f, 0f, -1f, 0f)
        val heldPhysical = physical.state()
        val observer = ControllerRegistry.PadObserver(
            scan = { ControllerRegistry.pads(devices) },
            onPadsChanged = { pads ->
                connected = updateViewerControllerPresence(connected, pads.isNotEmpty()) {
                    releases++
                    touch.reset()
                }
            },
            register = { true },
            unregister = {},
        )
        assertFalse(connected)
        assertEquals(0, releases)
        touch.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 2)
        touch.onKey(KeyEvent.KEYCODE_BUTTON_B, true, 3)
        devices = devices + physicalPad
        observer.onInputDeviceAdded(12)
        assertTrue(connected)
        assertEquals(1, releases)
        assertTrue(touch.state().isNeutral)
        assertEquals(heldPhysical, mergePadStates(physical.state(), touch.state()))
        observer.onInputDeviceChanged(12)
        observer.rescan()
        assertEquals(1, releases)
        touch.onKey(KeyEvent.KEYCODE_BUTTON_Y, true, 4)
        devices = listOf(playerTwo, virtualPad)
        observer.onInputDeviceRemoved(12)
        assertFalse(connected)
        assertEquals(2, releases)
        assertTrue(touch.state().isNeutral)
        assertEquals(heldPhysical, mergePadStates(physical.state(), touch.state()))
        observer.close()
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
