package com.gamebex.client.stream.proto

/**
 * One complete controller state for player 2. The guest sends the whole
 * state in every input packet, so a lost packet is corrected by the next one.
 *
 * [buttons] uses the HID button order of [PadHid.DESCRIPTOR]: bit n is HID
 * button n+1, which Linux maps to BTN_GAMEPAD+n and Android's Generic.kl to the
 * keycode named in each constant's comment. Sticks are -127..127 (up and left
 * are negative), triggers 0..255, [hat] 0..7 clockwise from up or [HAT_CENTER].
 */
data class PadState(
    val buttons: Int = 0,
    val lx: Int = 0,
    val ly: Int = 0,
    val rx: Int = 0,
    val ry: Int = 0,
    val lt: Int = 0,
    val rt: Int = 0,
    val hat: Int = HAT_CENTER,
) {
    val isNeutral: Boolean get() = this == NEUTRAL

    /** The same state with every field forced into its wire range. */
    fun sanitized(): PadState = PadState(
        buttons = buttons and ALL_BUTTONS,
        lx = axis(lx),
        ly = axis(ly),
        rx = axis(rx),
        ry = axis(ry),
        lt = trigger(lt),
        rt = trigger(rt),
        hat = if (hat in HAT_UP..HAT_CENTER) hat else HAT_CENTER,
    )

    companion object {
        /** BTN_SOUTH (304), Android KEYCODE_BUTTON_A (96). */
        const val A: Int = 1 shl 0
        /** BTN_EAST (305), KEYCODE_BUTTON_B (97). */
        const val B: Int = 1 shl 1
        /** BTN_NORTH (307), KEYCODE_BUTTON_X (99). Bit 2 (BTN_C) stays unused. */
        const val X: Int = 1 shl 3
        /** BTN_WEST (308), KEYCODE_BUTTON_Y (100). Bit 5 (BTN_Z) stays unused. */
        const val Y: Int = 1 shl 4
        /** BTN_TL (310), KEYCODE_BUTTON_L1 (102). */
        const val L1: Int = 1 shl 6
        /** BTN_TR (311), KEYCODE_BUTTON_R1 (103). */
        const val R1: Int = 1 shl 7
        /** BTN_TL2 (312), KEYCODE_BUTTON_L2 (104). */
        const val L2: Int = 1 shl 8
        /** BTN_TR2 (313), KEYCODE_BUTTON_R2 (105). */
        const val R2: Int = 1 shl 9
        /** BTN_SELECT (314), KEYCODE_BUTTON_SELECT (109). */
        const val SELECT: Int = 1 shl 10
        /** BTN_START (315), KEYCODE_BUTTON_START (108). */
        const val START: Int = 1 shl 11
        /** BTN_MODE (316), KEYCODE_BUTTON_MODE (110). The guest keeps it for "leave"; never sent. */
        const val MODE: Int = 1 shl 12
        /** BTN_THUMBL (317), KEYCODE_BUTTON_THUMBL (106). */
        const val L3: Int = 1 shl 13
        /** BTN_THUMBR (318), KEYCODE_BUTTON_THUMBR (107). */
        const val R3: Int = 1 shl 14

        /** Every bit the pad may report. MODE is excluded on purpose (see [MODE]). */
        const val ALL_BUTTONS: Int = A or B or X or Y or L1 or R1 or L2 or R2 or SELECT or START or L3 or R3

        const val HAT_UP: Int = 0
        const val HAT_UP_RIGHT: Int = 1
        const val HAT_RIGHT: Int = 2
        const val HAT_DOWN_RIGHT: Int = 3
        const val HAT_DOWN: Int = 4
        const val HAT_DOWN_LEFT: Int = 5
        const val HAT_LEFT: Int = 6
        const val HAT_UP_LEFT: Int = 7
        const val HAT_CENTER: Int = 8

        /** Released buttons, centered sticks, released triggers. */
        val NEUTRAL: PadState = PadState()

        /** The hat value for four direction flags; opposite directions cancel out. */
        fun hatFrom(up: Boolean, down: Boolean, left: Boolean, right: Boolean): Int {
            val v = (if (down) 1 else 0) - (if (up) 1 else 0)
            val h = (if (right) 1 else 0) - (if (left) 1 else 0)
            return when {
                v < 0 && h == 0 -> HAT_UP
                v < 0 && h > 0 -> HAT_UP_RIGHT
                v == 0 && h > 0 -> HAT_RIGHT
                v > 0 && h > 0 -> HAT_DOWN_RIGHT
                v > 0 && h == 0 -> HAT_DOWN
                v > 0 && h < 0 -> HAT_DOWN_LEFT
                v == 0 && h < 0 -> HAT_LEFT
                v < 0 && h < 0 -> HAT_UP_LEFT
                else -> HAT_CENTER
            }
        }

        /** Android axis value (-1..1) to a wire stick value. */
        fun axisFromFloat(value: Float): Int = axis(Math.round(value * 127f))

        /** Android trigger value (0..1) to a wire trigger value. */
        fun triggerFromFloat(value: Float): Int = trigger(Math.round(value * 255f))

        fun axis(v: Int): Int = v.coerceIn(-127, 127)
        fun trigger(v: Int): Int = v.coerceIn(0, 255)
    }
}

/**
 * The virtual pad's HID report descriptor and input report. Every usage
 * maps through Linux hid-input and Android's Generic.kl to a standard gamepad
 * keycode or axis: sticks on X/Y and Z/RZ (the Android convention RetroArch reads),
 * triggers on Brake/Accelerator (ABS_BRAKE/ABS_GAS, Android LTRIGGER/RTRIGGER).
 */
object PadHid {
    const val REPORT_ID: Int = 1
    /** Report ID, 2 button bytes, 4 stick bytes, 2 trigger bytes, 1 hat byte. */
    const val REPORT_SIZE: Int = 10

    val DESCRIPTOR: ByteArray = bytes(
        0x05, 0x01,        // Usage Page (Generic Desktop)
        0x09, 0x05,        // Usage (Game Pad)
        0xA1, 0x01,        // Collection (Application)
        0x85, 0x01,        //   Report ID (1)
        0x05, 0x09,        //   Usage Page (Button)
        0x19, 0x01,        //   Usage Minimum (1)
        0x29, 0x0F,        //   Usage Maximum (15)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, 0x01,        //   Logical Maximum (1)
        0x75, 0x01,        //   Report Size (1)
        0x95, 0x0F,        //   Report Count (15)
        0x81, 0x02,        //   Input (Data, Variable, Absolute)
        0x75, 0x01,        //   Report Size (1)
        0x95, 0x01,        //   Report Count (1)
        0x81, 0x03,        //   Input (Constant): pad to 16 bits
        0x05, 0x01,        //   Usage Page (Generic Desktop)
        0x09, 0x30,        //   Usage (X): left stick X
        0x09, 0x31,        //   Usage (Y): left stick Y
        0x09, 0x32,        //   Usage (Z): right stick X
        0x09, 0x35,        //   Usage (Rz): right stick Y
        0x15, 0x81,        //   Logical Minimum (-127)
        0x25, 0x7F,        //   Logical Maximum (127)
        0x75, 0x08,        //   Report Size (8)
        0x95, 0x04,        //   Report Count (4)
        0x81, 0x02,        //   Input (Data, Variable, Absolute)
        0x05, 0x02,        //   Usage Page (Simulation Controls)
        0x09, 0xC5,        //   Usage (Brake): left trigger
        0x09, 0xC4,        //   Usage (Accelerator): right trigger
        0x15, 0x00,        //   Logical Minimum (0)
        0x26, 0xFF, 0x00,  //   Logical Maximum (255)
        0x75, 0x08,        //   Report Size (8)
        0x95, 0x02,        //   Report Count (2)
        0x81, 0x02,        //   Input (Data, Variable, Absolute)
        0x05, 0x01,        //   Usage Page (Generic Desktop)
        0x09, 0x39,        //   Usage (Hat switch)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, 0x07,        //   Logical Maximum (7)
        0x35, 0x00,        //   Physical Minimum (0)
        0x46, 0x3B, 0x01,  //   Physical Maximum (315)
        0x65, 0x14,        //   Unit (Degrees)
        0x75, 0x04,        //   Report Size (4)
        0x95, 0x01,        //   Report Count (1)
        0x81, 0x42,        //   Input (Data, Variable, Absolute, Null State)
        0x65, 0x00,        //   Unit (None)
        0x75, 0x04,        //   Report Size (4)
        0x95, 0x01,        //   Report Count (1)
        0x81, 0x03,        //   Input (Constant): pad to 8 bits
        0xC0,              // End Collection
    )

    /** The input report for [state] (sanitized first). */
    fun report(state: PadState): ByteArray {
        val s = state.sanitized()
        return bytes(
            REPORT_ID,
            s.buttons and 0xFF,
            (s.buttons ushr 8) and 0x7F,
            s.lx and 0xFF,
            s.ly and 0xFF,
            s.rx and 0xFF,
            s.ry and 0xFF,
            s.lt,
            s.rt,
            s.hat and 0x0F,
        )
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
