package com.gamebex.client.launch

import android.view.InputDevice
import com.gamebex.client.stream.StreamCaps
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

    @Test fun streamPlayerTwoIsExcludedEvenWhenAndroidDoesNotMarkItVirtual() {
        val pads = ControllerRegistry.pads(listOf(
            dev(7, StreamCaps.PAD_NAME, "stream-p2"),
            dev(8, StreamCaps.PAD_NAME, "virtual-p2", virtual = true),
        ))
        assertTrue(pads.isEmpty())
    }

    @Test fun observerReportsInitialSnapshotAndRescansEveryDeviceEvent() {
        var devices = listOf(dev(7, StreamCaps.PAD_NAME, "stream-p2"))
        val seen = mutableListOf<List<ControllerRegistry.Pad>>()
        var scans = 0
        var registrations = 0
        var unregistrations = 0
        val observer = ControllerRegistry.PadObserver(
            scan = { scans++; ControllerRegistry.pads(devices) },
            onPadsChanged = { seen += it },
            register = { registrations++; true },
            unregister = { unregistrations++ },
        )
        assertEquals(listOf(emptyList<ControllerRegistry.Pad>()), seen)
        devices = devices + dev(12, "GameSir-X5s Consumer Control", "cc", InputDevice.SOURCE_KEYBOARD or padSources)
        observer.onInputDeviceAdded(12)
        assertEquals(listOf("GameSir-X5s"), seen.last().map { it.label })
        devices = devices + dev(13, "GameSir-X5s", "pad")
        observer.onInputDeviceChanged(12)
        assertEquals(listOf("GameSir-X5s"), seen.last().map { it.name })
        devices = devices.filter { it.id == 7 }
        observer.onInputDeviceRemoved(13)
        assertTrue(seen.last().isEmpty())
        observer.onInputDeviceChanged(7)
        observer.rescan()
        assertEquals(6, scans)
        assertEquals(6, seen.size)
        assertEquals(1, registrations)
        observer.close()
        observer.close()
        observer.onInputDeviceAdded(12)
        observer.onInputDeviceRemoved(12)
        observer.onInputDeviceChanged(12)
        observer.rescan()
        assertEquals(1, unregistrations)
        assertEquals(6, scans)
        assertEquals(6, seen.size)
    }

    @Test fun observerIncludesAPadThatWasConnectedBeforeRegistration() {
        val pads = ControllerRegistry.pads(listOf(dev(12, "GameSir-X5s Consumer Control", "cc")))
        val seen = mutableListOf<List<ControllerRegistry.Pad>>()
        val observer = ControllerRegistry.PadObserver(
            scan = { pads },
            onPadsChanged = { seen += it },
            register = { true },
            unregister = {},
        )
        assertEquals(listOf(pads), seen)
        observer.close()
    }

    @Test fun observerCanRescanWhenPlatformRegistrationIsUnavailable() {
        var pads = emptyList<ControllerRegistry.Pad>()
        val seen = mutableListOf<List<ControllerRegistry.Pad>>()
        var unregistrations = 0
        val observer = ControllerRegistry.PadObserver(
            scan = { pads },
            onPadsChanged = { seen += it },
            register = { false },
            unregister = { unregistrations++ },
        )
        pads = ControllerRegistry.pads(listOf(dev(12, "GameSir-X5s", "pad")))
        observer.rescan()
        assertEquals(listOf(emptyList<ControllerRegistry.Pad>(), pads), seen)
        observer.close()
        assertEquals(0, unregistrations)
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
