package com.gamebex.client.launch

import android.content.Context
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import com.gamebex.client.stream.StreamCaps

/**
 * Connected gamepads, as emulators should see them. A Bluetooth pad shows up
 * as several InputDevices (e.g. "GameSir-X5s" plus "GameSir-X5s Consumer
 * Control"); only the real pad is worth configuring — Flycast mapped the
 * Consumer Control sub-device on one phone, which is the bug this
 * filter exists to avoid. The pure part ([pads]) is unit-tested; the
 * Android part only converts InputDevice into [DeviceInfo].
 */
object ControllerRegistry {
    private const val TAG = "GameBEXPads"

    /** Plain copy of the InputDevice fields the filter needs. */
    data class DeviceInfo(
        val id: Int,
        val name: String,
        val descriptor: String,
        val sources: Int,
        val isVirtual: Boolean,
        /** MotionEvent axis ids the device reports (sticks, triggers, hat). */
        val axes: Set<Int> = emptySet(),
    )

    /**
     * [name] is the InputDevice name emulators key their configs on; [label]
     * is what players see. Android merges a Bluetooth pad's HID collections
     * into ONE app-visible device and may name it after a sibling (one
     * GameSir-X5s shows up to apps only as "GameSir-X5s Consumer
     * Control" with GAMEPAD|JOYSTICK sources) — so the label strips that.
     */
    data class Pad(
        val id: Int,
        val name: String,
        val descriptor: String,
        val label: String = name,
        /** Axis ids this pad reports: configs follow the pad, not one model. */
        val axes: Set<Int> = emptySet(),
    )

    /** Sibling HID collections a pad exposes next to its gamepad node. */
    private val SUB_DEVICE_SUFFIXES = listOf(
        "consumer control", "system control", "keyboard", "mouse", "touchpad",
        "motion sensors", "headset", "wireless controller touchpad",
    )

    /** True for "<pad> Consumer Control"-style sibling nodes. Pure. */
    fun isSubDevice(name: String): Boolean {
        val n = name.trim().lowercase()
        return SUB_DEVICE_SUFFIXES.any { n.endsWith(" $it") }
    }

    /** "GameSir-X5s Consumer Control" -> "GameSir-X5s". Pure. */
    fun baseName(name: String): String {
        val t = name.trim()
        val lower = t.lowercase()
        val suffix = SUB_DEVICE_SUFFIXES.firstOrNull { lower.endsWith(" $it") } ?: return t
        return t.dropLast(suffix.length + 1).trim()
    }

    /**
     * Real gamepads: gamepad/joystick sources, not virtual. A sub-device name
     * alone never disqualifies (Android may merge the pad under it); it only
     * loses to a plain-named gamepad node of the same pad. One entry per
     * physical pad. Stream's "GameBEX Player 2" is never one: it
     * belongs to the guest, and player 1's setup must not pick it. Pure.
     */
    fun pads(devices: List<DeviceInfo>): List<Pad> {
        val candidates = devices.filter {
            !it.isVirtual && it.name != StreamCaps.PAD_NAME && ControllerProbe.isGamepadSource(it.sources)
        }
        val plain = candidates.filterNot { isSubDevice(it.name) }.map { it.name.trim().lowercase() }.toSet()
        return candidates
            .filterNot { isSubDevice(it.name) && baseName(it.name).lowercase() in plain }
            .distinctBy { it.descriptor.ifBlank { "id:${it.id}" } }
            .map {
                val name = it.name.ifBlank { "Gamepad" }
                Pad(it.id, name, it.descriptor, label = baseName(name).ifBlank { "Gamepad" }, axes = it.axes)
            }
    }

    private fun info(dev: InputDevice) = DeviceInfo(
        dev.id, dev.name.orEmpty(), dev.descriptor.orEmpty(), dev.sources, dev.isVirtual,
        axes = dev.motionRanges.map { it.axis }.toSet(),
    )

    /** Currently connected pads. Never throws. */
    fun snapshot(): List<Pad> = runCatching {
        pads(InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id)?.let(::info) })
    }.getOrElse {
        Log.w(TAG, "input device scan failed", it)
        emptyList()
    }

    fun observe(ctx: Context, onPadsChanged: (List<Pad>) -> Unit): PadObserver {
        val im = ctx.getSystemService(Context.INPUT_SERVICE) as? InputManager
        return PadObserver(
            scan = ::snapshot,
            onPadsChanged = { pads ->
                runCatching { onPadsChanged(pads) }.onFailure { Log.w(TAG, "pad observer failed", it) }
            },
            register = { listener ->
                if (im == null) false else {
                    im.registerInputDeviceListener(listener, null)
                    true
                }
            },
            unregister = { listener -> im?.unregisterInputDeviceListener(listener) },
        )
    }

    class PadObserver internal constructor(
        private val scan: () -> List<Pad>,
        private val onPadsChanged: (List<Pad>) -> Unit,
        register: (InputManager.InputDeviceListener) -> Boolean,
        private val unregister: (InputManager.InputDeviceListener) -> Unit,
    ) : InputManager.InputDeviceListener, AutoCloseable {
        private var closed = false
        private val registered = runCatching { register(this) }.getOrDefault(false)

        init {
            rescan()
        }

        fun rescan() {
            if (!closed) onPadsChanged(scan())
        }

        override fun onInputDeviceAdded(deviceId: Int) = rescan()
        override fun onInputDeviceRemoved(deviceId: Int) = rescan()
        override fun onInputDeviceChanged(deviceId: Int) = rescan()

        override fun close() {
            if (closed) return
            closed = true
            if (registered) runCatching { unregister(this) }
        }
    }

    /**
     * Calls [onPadConnected] (on the registering thread's looper) whenever a
     * real pad appears. Returns the listener so callers can unregister it.
     */
    fun listen(ctx: Context, onPadConnected: (Pad) -> Unit): InputManager.InputDeviceListener? {
        val im = ctx.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return null
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val dev = runCatching { InputDevice.getDevice(deviceId) }.getOrNull() ?: return
                pads(listOf(info(dev))).firstOrNull()?.let { pad ->
                    Log.i(TAG, "pad connected: ${pad.name}")
                    runCatching { onPadConnected(pad) }.onFailure { Log.w(TAG, "pad callback failed", it) }
                }
            }

            override fun onInputDeviceRemoved(deviceId: Int) = Unit
            override fun onInputDeviceChanged(deviceId: Int) = Unit
        }
        return runCatching { im.registerInputDeviceListener(listener, null); listener }.getOrNull()
    }
}
