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
}
