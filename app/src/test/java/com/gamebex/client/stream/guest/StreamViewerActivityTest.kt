package com.gamebex.client.stream.guest

import android.view.InputDevice
import android.view.KeyEvent
import android.view.Surface
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
        val options = ViewerOptions.sample { name -> calls += name; null }
        assertEquals(ViewerOptions(), options)
        assertEquals(10, calls.size)
        assertEquals(calls.size, calls.toSet().size)
        assertFalse(options.unbufferedInput)
        assertEquals(0, options.frameRateVote)
        repeat(20) { assertEquals(0, options.frameRateVote) }
        assertEquals(10, calls.size)
    }

    @Test fun lookupValuesMapToOptions() {
        val options = ViewerOptions.sample { name ->
            when (name) {
                "frame_rate_vote" -> "60"
                "display_mode" -> "-1"
                else -> "1"
            }
        }
        assertTrue(options.unbufferedInput)
        assertEquals(-1, options.displayMode)
        assertEquals(60, options.frameRateVote)
        assertTrue(options.hudOff)
        assertTrue(options.touchPadHidden)
        assertTrue(options.fixedSurface)
        assertTrue(options.surfaceOpaque)
        assertTrue(options.minimalPostProcessing)
        assertTrue(options.frameRateFixed)
        assertTrue(options.frameRateAlways)
    }

    @Test fun invalidViewerIntegersFallBackToNormal() {
        val options = ViewerOptions.sample { "-2" }
        assertEquals(ViewerOptions(), options)
        assertEquals(ViewerOptions(), ViewerOptions.sample { name ->
            if (name == "frame_rate_vote") "121" else null
        })
    }

    @Test fun displayArmsAreSampledOnlyOnceAndRemainIndependent() {
        val calls = mutableListOf<String>()
        var enabled = true
        val options = ViewerOptions.sample { name ->
            calls += name
            if (!enabled) error("resampled after construction")
            if (name == "frame_rate_vote") "60" else "1"
        }
        enabled = false
        repeat(10) {
            assertTrue(options.minimalPostProcessing)
            assertTrue(options.frameRateFixed)
            assertTrue(options.frameRateAlways)
            assertEquals(60, options.frameRateVote)
        }
        assertEquals(10, calls.size)
        assertEquals(10, calls.toSet().size)
    }

    @Test fun frameRateVotesKeepBaselineAndGateBothPlatformOverloads() {
        val normal = mutableListOf<Pair<Float, Int>>()
        val always = mutableListOf<Triple<Float, Int, Int>>()
        fun apply(sdk: Int, options: ViewerOptions) = applyViewerFrameRate(
            sdk, options,
            { rate, compatibility -> normal += rate to compatibility },
            { rate, compatibility, strategy -> always += Triple(rate, compatibility, strategy) },
        )
        val arms = ViewerOptions(frameRateVote = 60, frameRateFixed = true, frameRateAlways = true)
        apply(29, arms)
        apply(31, arms.copy(frameRateVote = 0))
        apply(31, arms.copy(frameRateVote = -1))
        apply(31, arms.copy(frameRateVote = 121))
        assertTrue(normal.isEmpty())
        assertTrue(always.isEmpty())
        apply(30, ViewerOptions(frameRateVote = 60))
        apply(31, ViewerOptions(frameRateVote = 60))
        assertEquals(listOf(60f to Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, 60f to Surface.FRAME_RATE_COMPATIBILITY_DEFAULT), normal)
        assertTrue(always.isEmpty())
        apply(30, arms)
        assertEquals(60f to Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, normal.last())
        apply(31, arms)
        apply(36, arms.copy(frameRateFixed = false))
        assertEquals(listOf(
            Triple(60f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS),
            Triple(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS),
        ), always)
    }

    @Test fun minimalPostProcessingSavesAndRestoresBothOriginalValues() {
        for (initial in listOf(false, true)) {
            var value = initial
            var reads = 0
            val writes = mutableListOf<Boolean>()
            val old = applyViewerMinimalPostProcessing(30, true, { reads++; value }, { value = it; writes += it })
            assertEquals(initial, old)
            assertTrue(value)
            restoreViewerMinimalPostProcessing(30, old) { value = it; writes += it }
            assertEquals(initial, value)
            assertEquals(1, reads)
            assertEquals(listOf(true, initial), writes)
        }
    }

    @Test fun minimalPostProcessingOffAndOldApisNeverTouchTheWindow() {
        assertNull(applyViewerMinimalPostProcessing(29, true, { error("old API read") }, { error("old API write") }))
        assertNull(applyViewerMinimalPostProcessing(30, false, { error("baseline read") }, { error("baseline write") }))
        restoreViewerMinimalPostProcessing(29, false) { error("old API restore") }
        restoreViewerMinimalPostProcessing(30, null) { error("baseline restore") }
    }
}
