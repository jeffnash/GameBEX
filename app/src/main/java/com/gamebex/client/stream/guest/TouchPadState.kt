package com.gamebex.client.stream.guest

import android.view.KeyEvent
import com.gamebex.client.stream.proto.PadState
import kotlin.math.sqrt

/** Pointer ownership, independent of Compose/Android so cancellation and overlapping holds are testable. */
internal class TouchPadState(
    private val onKey: (Int, Boolean, Long) -> Unit,
    private val onStick: (Float, Float, Long) -> Unit = { _, _, _ -> },
) {
    private val keys = mutableMapOf<Long, Int>()
    private var stickPointer: Long? = null

    fun press(pointer: Long, key: Int, atNs: Long) {
        if (keys[pointer] == key) return
        release(pointer, atNs)
        val alreadyHeld = key in keys.values
        keys[pointer] = key
        if (!alreadyHeld) onKey(key, true, atNs)
    }

    fun moveStick(pointer: Long, x: Float, y: Float, atNs: Long): Boolean {
        if (stickPointer != null && stickPointer != pointer) return false
        stickPointer = pointer
        onStick(x, y, atNs)
        return true
    }

    fun release(pointer: Long, atNs: Long) {
        val key = keys.remove(pointer)
        if (key != null && key !in keys.values) onKey(key, false, atNs)
        if (stickPointer == pointer) {
            stickPointer = null
            onStick(0f, 0f, atNs)
        }
    }

    fun releaseAll(atNs: Long) {
        val held = keys.values.toSet()
        keys.clear()
        held.forEach { onKey(it, false, atNs) }
        if (stickPointer != null) {
            stickPointer = null
            onStick(0f, 0f, atNs)
        }
    }
}

/**
 * Publishes complete touch states through the legacy key/left-axis callbacks.
 * Buttons and the hat survive the round trip; the right stick and analog
 * trigger levels cannot and are dropped, never guessed.
 */
internal class LegacyTouchPublisher(
    private val onKey: (Int, Boolean, Long) -> Unit,
    private val onStick: (Float, Float, Long) -> Unit,
) {
    private var last = PadState.NEUTRAL

    fun publish(state: PadState, atNs: Long) {
        val next = state.sanitized()
        val prev = last
        if (next == prev) return
        last = next
        val changed = next.buttons xor prev.buttons
        if (changed != 0) {
            TOUCH_BUTTON_BITS.forEach { bit ->
                if (changed and bit != 0) {
                    touchBitToKeyCode(bit)?.let { key -> onKey(key, next.buttons and bit != 0, atNs) }
                }
            }
        }
        if (next.hat != prev.hat) {
            val old = touchHatKeys(prev.hat)
            val new = touchHatKeys(next.hat)
            old.forEach { key -> if (key !in new) onKey(key, false, atNs) }
            new.forEach { key -> if (key !in old) onKey(key, true, atNs) }
        }
        if (next.lx != prev.lx || next.ly != prev.ly) {
            onStick(next.lx / 127f, next.ly / 127f, atNs)
        }
    }

    fun reset() {
        last = PadState.NEUTRAL
    }

    companion object {
        private val TOUCH_BUTTON_BITS: IntArray = intArrayOf(
            PadState.A, PadState.B, PadState.X, PadState.Y,
            PadState.L1, PadState.R1, PadState.L2, PadState.R2,
            PadState.SELECT, PadState.START, PadState.L3, PadState.R3,
        )
    }
}

/** Reverse of [PadMapper.KEY_BITS] for the legacy touch path. */
internal fun touchBitToKeyCode(bit: Int): Int? = when (bit) {
    PadState.A -> KeyEvent.KEYCODE_BUTTON_A
    PadState.B -> KeyEvent.KEYCODE_BUTTON_B
    PadState.X -> KeyEvent.KEYCODE_BUTTON_X
    PadState.Y -> KeyEvent.KEYCODE_BUTTON_Y
    PadState.L1 -> KeyEvent.KEYCODE_BUTTON_L1
    PadState.R1 -> KeyEvent.KEYCODE_BUTTON_R1
    PadState.L2 -> KeyEvent.KEYCODE_BUTTON_L2
    PadState.R2 -> KeyEvent.KEYCODE_BUTTON_R2
    PadState.SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
    PadState.START -> KeyEvent.KEYCODE_BUTTON_START
    PadState.L3 -> KeyEvent.KEYCODE_BUTTON_THUMBL
    PadState.R3 -> KeyEvent.KEYCODE_BUTTON_THUMBR
    else -> null
}

/** The DPAD keys whose combination the mapper folds back into [hat]. */
internal fun touchHatKeys(hat: Int): IntArray = when (hat) {
    PadState.HAT_UP -> intArrayOf(KeyEvent.KEYCODE_DPAD_UP)
    PadState.HAT_UP_RIGHT -> intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
    PadState.HAT_RIGHT -> intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT)
    PadState.HAT_DOWN_RIGHT -> intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
    PadState.HAT_DOWN -> intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN)
    PadState.HAT_DOWN_LEFT -> intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
    PadState.HAT_LEFT -> intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT)
    PadState.HAT_UP_LEFT -> intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT)
    else -> intArrayOf()
}

/** A radial clamp and small center dead zone; Android's left-stick convention is up = negative Y. */
internal fun touchStickAxes(x: Float, y: Float, radius: Float): Pair<Float, Float> {
    if (radius <= 0f) return 0f to 0f
    val distance = sqrt(x * x + y * y)
    if (distance < radius * 0.12f) return 0f to 0f
    val scale = maxOf(radius, distance)
    return x / scale to y / scale
}

/**
 * Buttons are a union, triggers take the stronger hold. Each stick is chosen as a pair, and the
 * hat as a whole: a non-centered physical control wins, otherwise touch supplies it. Releasing
 * either source can never clear a button still held by the other source.
 */
internal fun mergePadStates(physical: PadState, touch: PadState): PadState {
    val p = physical.sanitized()
    val t = touch.sanitized()
    val physicalLeft = p.lx != 0 || p.ly != 0
    val physicalRight = p.rx != 0 || p.ry != 0
    return PadState(
        buttons = p.buttons or t.buttons,
        lx = if (physicalLeft) p.lx else t.lx,
        ly = if (physicalLeft) p.ly else t.ly,
        rx = if (physicalRight) p.rx else t.rx,
        ry = if (physicalRight) p.ry else t.ry,
        lt = maxOf(p.lt, t.lt),
        rt = maxOf(p.rt, t.rt),
        hat = if (p.hat != PadState.HAT_CENTER) p.hat else t.hat,
    )
}
