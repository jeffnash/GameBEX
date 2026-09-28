package com.gamebex.client.launch

import android.view.InputDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pad filtering (the GameSir-X5s exposes a "Consumer Control" sibling) + guide card choice. */
class ControllerRegistryTest {
    private val padSources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD
    private fun dev(id: Int, name: String, desc: String, sources: Int = padSources, virtual: Boolean = false) =
        ControllerRegistry.DeviceInfo(id, name, desc, sources, virtual)

    @Test fun mergedConsumerControlNodeIsThePad() {
        // Android exposes the GameSir only under this name.
        val pads = ControllerRegistry.pads(listOf(
            dev(12, "GameSir-X5s Consumer Control", "cc", InputDevice.SOURCE_KEYBOARD or padSources),
        ))
        assertEquals(
            listOf(ControllerRegistry.Pad(12, "GameSir-X5s Consumer Control", "cc", label = "GameSir-X5s")),
            pads,
        )
    }

    @Test fun plainPadNodeWinsOverSubDeviceSibling() {
        val pads = ControllerRegistry.pads(listOf(
            dev(11, "GameSir-X5s Consumer Control", "cc", InputDevice.SOURCE_KEYBOARD or padSources),
            dev(12, "GameSir-X5s", "pad"),
        ))
        assertEquals(listOf(ControllerRegistry.Pad(12, "GameSir-X5s", "pad")), pads)
    }

    @Test fun skipsVirtualKeyboardsAndNonPadDevices() {
        val pads = ControllerRegistry.pads(listOf(
            dev(-1, "Virtual", "v", padSources, virtual = true),
            dev(3, "gpio-keys", "k", InputDevice.SOURCE_KEYBOARD),
            dev(4, "Pad System Control", "s", InputDevice.SOURCE_KEYBOARD),
            dev(5, "Pad Mouse", "m", InputDevice.SOURCE_MOUSE),
            dev(6, "Touchscreen", "t", InputDevice.SOURCE_TOUCHSCREEN),
        ))
        assertTrue(pads.isEmpty())
    }

    @Test fun oneEntryPerPhysicalPad() {
        val pads = ControllerRegistry.pads(listOf(
            dev(12, "Xbox Wireless Controller", "same"),
            dev(13, "Xbox Wireless Controller", "same"),
            dev(14, "", "other", InputDevice.SOURCE_JOYSTICK),
        ))
        assertEquals(listOf("Xbox Wireless Controller", "Gamepad"), pads.map { it.name })
    }

    @Test fun subDeviceSuffixMatchIsWholeWord() {
        assertTrue(ControllerRegistry.isSubDevice("GameSir-X5s Consumer Control"))
        assertTrue(!ControllerRegistry.isSubDevice("GameSir-X5s"))
        assertTrue(!ControllerRegistry.isSubDevice("Keyboard"))
        assertEquals("GameSir-X5s", ControllerRegistry.baseName("GameSir-X5s Consumer Control"))
        assertEquals("GameSir-X5s", ControllerRegistry.baseName("GameSir-X5s"))
    }

    @Test fun guideCardOncePerPadUnlessNever() {
        val pad = ControllerRegistry.Pad(12, "GameSir-X5s", "pad")
        assertEquals(pad, ControllerGuide.padToGuide(false, emptySet(), listOf(pad)))
        assertNull(ControllerGuide.padToGuide(false, setOf("pad"), listOf(pad)))
        assertNull(ControllerGuide.padToGuide(true, emptySet(), listOf(pad)))
        assertNull(ControllerGuide.padToGuide(false, emptySet(), emptyList()))
        val other = ControllerRegistry.Pad(20, "8BitDo", "")
        assertEquals(other, ControllerGuide.padToGuide(false, setOf("pad"), listOf(pad, other)))
        assertEquals("8BitDo", ControllerGuide.padKey(other))
    }
}
