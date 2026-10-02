package com.gamebex.client.stream.shell

/** Player 2's pad: /dev/uhid first, the AOSP hid tool when uhid refuses. */
object VirtualPads {
    fun open(): VirtualPad? = UhidPad.open() ?: HidToolPad.open()

    fun uhidAvailable(): Boolean = UhidPad.available()

    fun hidToolAvailable(): Boolean = HidToolPad.available()
}
