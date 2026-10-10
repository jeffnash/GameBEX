package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps

/** Player 2's pad: /dev/uhid first, the AOSP hid tool when uhid refuses. */
object VirtualPads {
    fun open(
        name: String = StreamCaps.PAD_NAME,
        uniq: String = StreamCaps.PAD_UNIQ,
    ): VirtualPad? = UhidPad.open(name, uniq) ?: HidToolPad.open(name)

    fun uhidAvailable(): Boolean = UhidPad.available()

    fun hidToolAvailable(): Boolean = HidToolPad.available()
}
