package com.gamebex.client.stream.guest

import android.view.KeyEvent
import com.gamebex.client.stream.proto.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guest controller to player-2 state. */
class PadMapperTest {
    private val ms = 1_000_000L

    @Test
    fun faceAndShoulderButtons() {
        val m = PadMapper()
        assertTrue(m.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 0))
        assertTrue(m.onKey(KeyEvent.KEYCODE_BUTTON_R1, true, 0))
        assertEquals(PadState.A or PadState.R1, m.state().buttons)
        m.onKey(KeyEvent.KEYCODE_BUTTON_A, false, 0)
        assertEquals(PadState.R1, m.state().buttons)
    }

    @Test
    fun keysThatAreNotThePadAreLeftToTheSystem() {
        val m = PadMapper()
        assertFalse(m.onKey(KeyEvent.KEYCODE_VOLUME_UP, true, 0))
        assertFalse(m.onKey(KeyEvent.KEYCODE_BACK, true, 0))
    }

    @Test
    fun dpadKeysAndHatAxesBothSetTheHat() {
        val m = PadMapper()
        m.onKey(KeyEvent.KEYCODE_DPAD_UP, true, 0)
        m.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0)
        assertEquals(PadState.HAT_UP_RIGHT, m.state().hat)
        m.onKey(KeyEvent.KEYCODE_DPAD_UP, false, 0)
        m.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, false, 0)
        m.onAxes(0f, 0f, 0f, 0f, 0f, 0f, -1f, 1f)
        assertEquals(PadState.HAT_DOWN_LEFT, m.state().hat)
    }

    @Test
    fun sticksUseADeadZone() {
        val m = PadMapper()
        m.onAxes(0.03f, -1f, 0.5f, 0f, 0f, 0f, 0f, 0f)
        val s = m.state()
        assertEquals(0, s.lx)
        assertEquals(-127, s.ly)
        assertEquals(64, s.rx)
    }

    @Test
    fun triggersFillInButtonsAndButtonsFillInTriggers() {
        val m = PadMapper()
        m.onAxes(0f, 0f, 0f, 0f, 0.8f, 0.2f, 0f, 0f)
        var s = m.state()
        assertEquals(204, s.lt)
        assertTrue(s.buttons and PadState.L2 != 0)
        assertEquals(51, s.rt)
        assertEquals(0, s.buttons and PadState.R2)
        m.onAxes(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        m.onKey(KeyEvent.KEYCODE_BUTTON_R2, true, 0)
        s = m.state()
        assertEquals(255, s.rt)
        assertTrue(s.buttons and PadState.R2 != 0)
    }

    @Test
    fun theHomeButtonLeavesAndIsNeverSent() {
        val m = PadMapper()
        assertTrue(m.onKey(KeyEvent.KEYCODE_BUTTON_MODE, true, 0))
        assertEquals(0, m.state().buttons)
        assertTrue(m.leaveRequested(0))
    }

    @Test
    fun selectAndStartHeldTwoSecondsLeaves() {
        val m = PadMapper()
        m.onKey(KeyEvent.KEYCODE_BUTTON_SELECT, true, 1_000 * ms)
        m.onKey(KeyEvent.KEYCODE_BUTTON_START, true, 1_100 * ms)
        assertEquals(PadState.SELECT or PadState.START, m.state().buttons)
        assertFalse(m.leaveRequested(3_000 * ms))
        assertTrue(m.leaveRequested(3_100 * ms))
        m.onKey(KeyEvent.KEYCODE_BUTTON_START, false, 3_050 * ms)
        assertFalse(m.leaveRequested(9_000 * ms))
    }

    @Test
    fun touchResetKeepsPhysicalHoldsAndSharedButtonsPressed() {
        val physical = PadMapper()
        val touch = PadMapper()
        physical.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 0)
        physical.onAxes(0.5f, -1f, 0f, 0f, 0.8f, 0f, 1f, 0f)
        touch.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 1)
        touch.onKey(KeyEvent.KEYCODE_BUTTON_B, true, 1)
        touch.onAxes(-1f, 1f, 0f, 0f, 0f, 0f, 0f, -1f)
        touch.reset() // The exact operation used by releaseTouchInput, not a physical reset.
        assertEquals(physical.state(), mergePadStates(physical.state(), touch.state()))
        assertEquals(PadState.A or PadState.L2, physical.state().buttons)
        assertEquals(64, physical.state().lx)
        assertEquals(PadState.HAT_RIGHT, physical.state().hat)
    }

    @Test
    fun releasingPhysicalStillKeepsTouchAndFullResetIsNeutral() {
        val physical = PadMapper()
        val touch = PadMapper()
        physical.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 0)
        touch.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 1)
        touch.onKey(KeyEvent.KEYCODE_DPAD_UP, true, 1)
        physical.onKey(KeyEvent.KEYCODE_BUTTON_A, false, 2)
        assertEquals(PadState.A, mergePadStates(physical.state(), touch.state()).buttons)
        assertEquals(PadState.HAT_UP, mergePadStates(physical.state(), touch.state()).hat)
        physical.reset()
        touch.reset() // Pause/end resets both.
        assertEquals(PadState.NEUTRAL, mergePadStates(physical.state(), touch.state()))
    }

    @Test
    fun resetReleasesEverything() {
        val m = PadMapper()
        m.onKey(KeyEvent.KEYCODE_BUTTON_Y, true, 0)
        m.onAxes(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
        m.reset()
        assertEquals(PadState.NEUTRAL, m.state())
    }
}
