package com.gamebex.client.stream.guest

import android.view.KeyEvent
import com.gamebex.client.stream.proto.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPadStateTest {
    private data class KeyChange(val key: Int, val down: Boolean, val atNs: Long)

    @Test
    fun downIsHeldUntilItsOwnPointerReleases() {
        val events = mutableListOf<KeyChange>()
        val mapper = PadMapper()
        val pad = TouchPadState({ key, down, at ->
            events.add(KeyChange(key, down, at))
            mapper.onKey(key, down, at)
        })
        pad.press(1, KeyEvent.KEYCODE_DPAD_LEFT, 10)
        pad.press(2, KeyEvent.KEYCODE_BUTTON_A, 20)
        assertEquals(PadState.HAT_LEFT, mapper.state().hat)
        assertEquals(PadState.A, mapper.state().buttons)
        assertEquals(2, events.size) // No timer or synthetic click-up.
        pad.release(1, 30)
        assertEquals(PadState.HAT_CENTER, mapper.state().hat)
        assertEquals(PadState.A, mapper.state().buttons)
        pad.release(2, 40)
        assertEquals(PadState.NEUTRAL, mapper.state())
        assertEquals(KeyChange(KeyEvent.KEYCODE_BUTTON_A, false, 40), events.last())
    }

    @Test
    fun twoPointersOnOneKeyOnlyReleaseAfterTheLastFinger() {
        val events = mutableListOf<KeyChange>()
        val pad = TouchPadState({ key, down, at -> events.add(KeyChange(key, down, at)) })
        pad.press(1, KeyEvent.KEYCODE_BUTTON_B, 10)
        pad.press(1, KeyEvent.KEYCODE_BUTTON_B, 15)
        pad.press(2, KeyEvent.KEYCODE_BUTTON_B, 20)
        pad.release(1, 30)
        assertEquals(listOf(KeyChange(KeyEvent.KEYCODE_BUTTON_B, true, 10)), events)
        pad.release(2, 40)
        pad.release(2, 50)
        assertEquals(2, events.size)
        assertEquals(KeyChange(KeyEvent.KEYCODE_BUTTON_B, false, 40), events.last())
    }

    @Test
    fun cancelAllReleasesEachHeldKeyAndTheStickExactlyOnce() {
        val events = mutableListOf<KeyChange>()
        val axes = mutableListOf<Pair<Float, Float>>()
        val pad = TouchPadState(
            { key, down, at -> events.add(KeyChange(key, down, at)) },
            { x, y, _ -> axes.add(x to y) },
        )
        pad.press(1, KeyEvent.KEYCODE_BUTTON_A, 10)
        pad.press(2, KeyEvent.KEYCODE_BUTTON_A, 20)
        pad.press(3, KeyEvent.KEYCODE_BUTTON_L1, 30)
        assertTrue(pad.moveStick(4, -0.5f, 1f, 40))
        pad.releaseAll(50)
        pad.releaseAll(60)
        pad.release(1, 70)
        assertEquals(4, events.size)
        assertEquals(setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_L1), events.filter { !it.down }.map { it.key }.toSet())
        assertTrue(events.filter { !it.down }.all { it.atNs == 50L })
        assertEquals(listOf(-0.5f to 1f, 0f to 0f), axes)
        pad.press(1, KeyEvent.KEYCODE_BUTTON_A, 80)
        assertEquals(KeyChange(KeyEvent.KEYCODE_BUTTON_A, true, 80), events.last())
    }

    @Test
    fun theStickKeepsItsFirstPointerAndDoesNotReleaseAnotherButton() {
        val mapper = PadMapper()
        val pad = TouchPadState(
            { key, down, at -> mapper.onKey(key, down, at); Unit },
            { x, y, _ -> mapper.onAxes(x, y, 0f, 0f, 0f, 0f, 0f, 0f) },
        )
        pad.press(1, KeyEvent.KEYCODE_BUTTON_A, 1)
        assertTrue(pad.moveStick(2, -1f, 0.5f, 2))
        assertFalse(pad.moveStick(3, 1f, -1f, 3))
        pad.release(3, 4)
        assertEquals(-127, mapper.state().lx)
        pad.release(2, 5)
        assertEquals(PadState(buttons = PadState.A), mapper.state())
        assertTrue(pad.moveStick(3, 1f, 0f, 6))
        assertEquals(127, mapper.state().lx)
    }

    @Test
    fun stickClampsRadiallyAndCentersInTheDeadZone() {
        assertEquals(0f to 0f, touchStickAxes(3f, -2f, 50f))
        assertEquals(0f to -1f, touchStickAxes(0f, -100f, 50f))
        assertEquals(0.5f to 0f, touchStickAxes(25f, 0f, 50f))
        val (x, y) = touchStickAxes(100f, 100f, 50f)
        assertEquals(1f, x * x + y * y, 0.0001f)
        assertEquals(x, y, 0f)
        assertEquals(0f to 0f, touchStickAxes(1f, 1f, 0f))
    }

    @Test
    fun mergeUnionsButtonsAndTakesTheStrongerTrigger() {
        val physical = PadState(buttons = PadState.A or PadState.L2, lt = 255, rt = 51)
        val touch = PadState(buttons = PadState.A or PadState.B or PadState.R2, lt = 80, rt = 255)
        assertEquals(
            PadState(buttons = PadState.A or PadState.B or PadState.L2 or PadState.R2, lt = 255, rt = 255),
            mergePadStates(physical, touch),
        )
        assertEquals(physical, mergePadStates(physical, PadState.NEUTRAL))
        assertEquals(touch, mergePadStates(PadState.NEUTRAL, touch))
    }

    @Test
    fun mergeChoosesActivePhysicalStickPairsAndHatNotIndividualComponents() {
        val physical = PadState(lx = 32, ly = 0, rx = 0, ry = 0, hat = PadState.HAT_LEFT)
        val touch = PadState(lx = -127, ly = -127, rx = 64, ry = -64, hat = PadState.HAT_UP)
        val merged = mergePadStates(physical, touch)
        assertEquals(32, merged.lx)
        assertEquals(0, merged.ly) // A centered component is not stolen from touch.
        assertEquals(64, merged.rx)
        assertEquals(-64, merged.ry)
        assertEquals(PadState.HAT_LEFT, merged.hat)
        assertEquals(touch, mergePadStates(PadState.NEUTRAL, touch))
        assertEquals(physical, mergePadStates(physical, PadState.NEUTRAL))
    }

    @Test
    fun legacyPublisherRoundTripsButtonsHatAndLeftStick() {
        val mapper = PadMapper()
        val publisher = LegacyTouchPublisher(
            { key, down, at -> mapper.onKey(key, down, at); Unit },
            { x, y, _ -> mapper.onAxes(x, y, 0f, 0f, 0f, 0f, 0f, 0f) },
        )
        publisher.publish(PadState(buttons = PadState.A or PadState.B, lx = 64, ly = -32, hat = PadState.HAT_UP_RIGHT), 10)
        assertEquals(PadState.A or PadState.B, mapper.state().buttons)
        assertEquals(PadState.HAT_UP_RIGHT, mapper.state().hat)
        assertEquals(64, mapper.state().lx)
        assertEquals(-32, mapper.state().ly)
        publisher.publish(PadState(buttons = PadState.A, lx = 64, ly = -32, hat = PadState.HAT_UP), 20)
        assertEquals(PadState.A, mapper.state().buttons)
        assertEquals(PadState.HAT_UP, mapper.state().hat)
        publisher.publish(PadState.NEUTRAL, 30)
        assertEquals(PadState.NEUTRAL, mapper.state())
    }

    @Test
    fun legacyPublisherDropsRightStickAndAnalogTriggers() {
        var calls = 0
        val publisher = LegacyTouchPublisher({ _, _, _ -> calls++ }, { _, _, _ -> calls++ })
        publisher.publish(PadState(rx = 127, ry = -127, lt = 200, rt = 100), 10)
        assertEquals(0, calls)
        publisher.publish(PadState(buttons = PadState.L2, lt = 255), 20)
        assertEquals(1, calls) // The digital bit survives; the level rides it.
    }

    @Test
    fun legacyPublisherEmitsOnlyEdges() {
        val events = mutableListOf<KeyChange>()
        val publisher = LegacyTouchPublisher({ key, down, at -> events.add(KeyChange(key, down, at)) }, { _, _, _ -> })
        publisher.publish(PadState(buttons = PadState.A), 10)
        publisher.publish(PadState(buttons = PadState.A), 20)
        assertEquals(1, events.size)
        publisher.publish(PadState.NEUTRAL, 30)
        assertEquals(2, events.size)
        assertEquals(KeyChange(KeyEvent.KEYCODE_BUTTON_A, false, 30), events.last())
    }

    @Test
    fun touchBitAndHatTablesCoverEveryWireValue() {
        val bits = listOf(
            PadState.A, PadState.B, PadState.X, PadState.Y, PadState.L1, PadState.R1,
            PadState.L2, PadState.R2, PadState.SELECT, PadState.START, PadState.L3, PadState.R3,
        )
        assertEquals(12, bits.mapNotNull(::touchBitToKeyCode).toSet().size)
        assertEquals(null, touchBitToKeyCode(0))
        assertEquals(null, touchBitToKeyCode(PadState.MODE))
        (PadState.HAT_UP..PadState.HAT_CENTER).forEach { hat ->
            val mapper = PadMapper()
            touchHatKeys(hat).forEach { mapper.onKey(it, true, 1) }
            assertEquals("hat $hat", hat, mapper.state().hat)
        }
    }

    @Test
    fun setTouchStateBypassesDeadZoneAndTriggerCoupling() {
        val mapper = PadMapper()
        mapper.setTouchState(PadState(lx = 1, ly = -1, lt = 128))
        assertEquals(PadState(lx = 1, ly = -1, lt = 128), mapper.state())
        mapper.setTouchState(PadState(buttons = PadState.L2, lt = 0))
        val held = mapper.state()
        assertEquals(PadState.L2, held.buttons)
        assertEquals(0, held.lt) // The physical path would force 255 here.
        mapper.reset()
        assertEquals(PadState.NEUTRAL, mapper.state())
    }

    @Test
    fun legacyInputClearsTheDirectTouchState() {
        val mapper = PadMapper()
        mapper.setTouchState(PadState(lx = 100, buttons = PadState.B))
        mapper.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 1)
        assertEquals(PadState(buttons = PadState.A), mapper.state())
        mapper.setTouchState(PadState(rx = 64))
        mapper.onAxes(0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        // 0.5 * 127 rounds to 64; the direct rx is gone but the A hold stays.
        assertEquals(PadState(buttons = PadState.A, lx = 64), mapper.state())
    }

    @Test
    fun mergeKeepsDirectTouchAgainstPhysicalHolds() {
        val physical = PadMapper()
        physical.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 1)
        physical.onAxes(32 / 127f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val touch = PadMapper()
        touch.setTouchState(PadState(buttons = PadState.B, rx = 64, lt = 200))
        val merged = mergePadStates(physical.state(), touch.state())
        assertEquals(PadState.A or PadState.B, merged.buttons)
        assertEquals(32, merged.lx)
        assertEquals(64, merged.rx)
        assertEquals(200, merged.lt)
    }

    @Test
    fun touchPrefsValidationClampsToSpecRanges() {
        val clamped = TouchPrefs(sizePercent = 50, opacityPercent = 99, deadZonePercent = 77).validated()
        assertEquals(85, clamped.sizePercent)
        assertEquals(80, clamped.opacityPercent)
        assertEquals(20, clamped.deadZonePercent)
        assertEquals(TouchPrefs(), TouchPrefs().validated())
        assertEquals(TouchStickMode.FIXED, TouchPrefs().stickMode)
        assertEquals(TouchHaptics.OFF, TouchPrefs().haptics)
    }
}
