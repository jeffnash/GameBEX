package com.gamebex.client.stream.guest

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
