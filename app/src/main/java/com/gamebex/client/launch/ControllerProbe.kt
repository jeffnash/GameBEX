package com.gamebex.client.launch

import android.view.InputDevice

/** Gamepad/joystick presence; sub-device filtering lives in [ControllerRegistry]. */
object ControllerProbe {
    data class Controller(val name: String, val descriptor: String)

    /**
     * Source constants share low bits across classes (KEYBOARD matches a naive
     * nonzero AND), so each class must match in full.
     */
    fun isGamepadSource(sources: Int): Boolean {
        val gamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        val stick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        return gamepad || stick
    }

    /** Physical gamepads/joysticks, excluding virtual/keyboard devices. */
    fun connected(): List<Controller> =
        ControllerRegistry.snapshot().map { Controller(it.label, it.descriptor) }

    fun statusLine(controllers: List<Controller>?): String = when {
        controllers == null -> "Controller: checking…"
        controllers.isEmpty() -> "Controller: none — connect a pad, then rescan (X)"
        controllers.size == 1 -> "Controller: ${controllers[0].name}"
        else -> "Controller: ${controllers[0].name} +${controllers.size - 1}"
    }
}
