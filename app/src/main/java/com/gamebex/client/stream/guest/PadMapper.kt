package com.gamebex.client.stream.guest

import android.view.KeyEvent
import android.view.MotionEvent
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.proto.PadState
import kotlin.math.abs

/**
 * Turns the guest's physical controller into one [PadState]. The Android
 * constants used here are compile-time ints, so JVM tests run it unchanged.
 *
 * - The d-pad arrives as keys (DPAD_*) or as hat axes; either sets the hat.
 * - L2/R2 arrive as buttons, trigger axes or both; each fills in the other, so
 *   a game reading either sees the press.
 * - MODE (the controller's home button) never reaches the host: it leaves the
 *   stream, as does holding SELECT and START for [StreamCaps.LEAVE_HOLD_MS].
 */
class PadMapper(private val deadZone: Float = DEAD_ZONE) {
    private var buttons = 0
    private var dpadUp = false
    private var dpadDown = false
    private var dpadLeft = false
    private var dpadRight = false
    private var hatX = 0f
    private var hatY = 0f
    private var lx = 0f
    private var ly = 0f
    private var rx = 0f
    private var ry = 0f
    private var lt = 0f
    private var rt = 0f
    private var comboSinceNs = NONE
    private var modePressed = false
    private var directState: PadState? = null

    /**
     * A complete touch state, stored verbatim. Unlike the physical path, this
     * skips the dead zone and the trigger/button coupling: the touch engine
     * already outputs exact wire values. Legacy [onKey]/[onAxes] input clears
     * it (last writer wins); [reset] clears both.
     */
    fun setTouchState(state: PadState) {
        directState = state.sanitized()
    }

    /** Applies one key; true when the key belongs to the pad (the viewer then consumes it). */
    fun onKey(keyCode: Int, down: Boolean, atNs: Long): Boolean {
        directState = null
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (down) modePressed = true
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> dpadUp = down
            KeyEvent.KEYCODE_DPAD_DOWN -> dpadDown = down
            KeyEvent.KEYCODE_DPAD_LEFT -> dpadLeft = down
            KeyEvent.KEYCODE_DPAD_RIGHT -> dpadRight = down
            else -> {
                val bit = KEY_BITS[keyCode] ?: return false
                buttons = if (down) buttons or bit else buttons and bit.inv()
            }
        }
        updateCombo(atNs)
        return true
    }

    /** Axis values as MotionEvent reports them: sticks -1..1, triggers 0..1, hat -1..1. */
    fun onAxes(
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
        leftTrigger: Float,
        rightTrigger: Float,
        hatXValue: Float,
        hatYValue: Float,
    ) {
        directState = null
        lx = dz(leftX)
        ly = dz(leftY)
        rx = dz(rightX)
        ry = dz(rightY)
        lt = leftTrigger.coerceIn(0f, 1f)
        rt = rightTrigger.coerceIn(0f, 1f)
        hatX = hatXValue
        hatY = hatYValue
    }

    fun state(): PadState {
        directState?.let { return it }
        var b = buttons
        if (lt > TRIGGER_PRESSED) b = b or PadState.L2
        if (rt > TRIGGER_PRESSED) b = b or PadState.R2
        val ltValue = if (buttons and PadState.L2 != 0) 255 else PadState.triggerFromFloat(lt)
        val rtValue = if (buttons and PadState.R2 != 0) 255 else PadState.triggerFromFloat(rt)
        val hat = PadState.hatFrom(
            up = dpadUp || hatY < -HAT_PRESSED,
            down = dpadDown || hatY > HAT_PRESSED,
            left = dpadLeft || hatX < -HAT_PRESSED,
            right = dpadRight || hatX > HAT_PRESSED,
        )
        return PadState(
            buttons = b,
            lx = PadState.axisFromFloat(lx),
            ly = PadState.axisFromFloat(ly),
            rx = PadState.axisFromFloat(rx),
            ry = PadState.axisFromFloat(ry),
            lt = ltValue,
            rt = rtValue,
            hat = hat,
        ).sanitized()
    }

    fun leaveRequested(nowNs: Long): Boolean =
        modePressed || (comboSinceNs != NONE && nowNs - comboSinceNs >= StreamCaps.LEAVE_HOLD_MS * 1_000_000L)

    fun reset() {
        directState = null
        buttons = 0
        dpadUp = false
        dpadDown = false
        dpadLeft = false
        dpadRight = false
        onAxes(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        comboSinceNs = NONE
        modePressed = false
    }

    private fun updateCombo(atNs: Long) {
        val held = buttons and (PadState.SELECT or PadState.START) == (PadState.SELECT or PadState.START)
        comboSinceNs = if (!held) NONE else if (comboSinceNs == NONE) atNs else comboSinceNs
    }

    private fun dz(v: Float): Float = if (abs(v) < deadZone) 0f else v.coerceIn(-1f, 1f)

    companion object {
        const val DEAD_ZONE: Float = 0.04f
        private const val TRIGGER_PRESSED = 0.5f
        private const val HAT_PRESSED = 0.5f
        private const val NONE = Long.MIN_VALUE

        /** MotionEvent axes the viewer reads; the right stick is Z/RZ, or RX/RY on pads that use those. */
        val AXES: IntArray = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
        )

        val KEY_BITS: Map<Int, Int> = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to PadState.A,
            KeyEvent.KEYCODE_BUTTON_B to PadState.B,
            KeyEvent.KEYCODE_BUTTON_X to PadState.X,
            KeyEvent.KEYCODE_BUTTON_Y to PadState.Y,
            KeyEvent.KEYCODE_BUTTON_L1 to PadState.L1,
            KeyEvent.KEYCODE_BUTTON_R1 to PadState.R1,
            KeyEvent.KEYCODE_BUTTON_L2 to PadState.L2,
            KeyEvent.KEYCODE_BUTTON_R2 to PadState.R2,
            KeyEvent.KEYCODE_BUTTON_SELECT to PadState.SELECT,
            KeyEvent.KEYCODE_BUTTON_START to PadState.START,
            KeyEvent.KEYCODE_BUTTON_THUMBL to PadState.L3,
            KeyEvent.KEYCODE_BUTTON_THUMBR to PadState.R3,
        )
    }
}
