package com.gamebex.client.stream.p2

import com.gamebex.client.data.Emu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slot-workaround decision table: signer/version × host pad × emulator × flag. */
class P2SlotWorkaroundTest {
    private val official = P2SlotWorkaround.OFFICIAL_SIGNER_SHA256
    private val gbx = P2SlotWorkaround.GBX_SIGNER_SHA256

    @Test fun officialSignerIsTheCatalogPin() {
        assertEquals("d7a5eab30902c9b2775af6fb4eac2d294a64634fb47dd1382bc3ac5bf669982e", official)
        assertEquals(64, gbx.length)
        assertTrue(gbx.startsWith("ff4a8142"))
    }

    @Test fun patchedSignerNeverNeedsFix() {
        assertFalse(P2SlotWorkaround.needsFix(gbx, 0L))
        assertFalse(P2SlotWorkaround.needsFix(gbx.uppercase(), Long.MAX_VALUE))
    }

    @Test fun officialNeedsFixUntilTheFixedNightly() {
        assertTrue(P2SlotWorkaround.needsFix(official, 0L))
        assertTrue(P2SlotWorkaround.needsFix(official, Long.MAX_VALUE - 1))
        // No official build has the fix yet, so nothing skips on version alone.
        assertEquals(Long.MAX_VALUE, P2SlotWorkaround.OFFICIAL_FIXED_VERSION_CODE)
        assertFalse(P2SlotWorkaround.needsFix(official, Long.MAX_VALUE))
    }

    @Test fun unknownSignerFailsClosed() {
        assertTrue(P2SlotWorkaround.needsFix(null, 0L))
        assertTrue(P2SlotWorkaround.needsFix("0".repeat(64), 0L))
    }

    @Test fun autoAppliesOnlyForUnfixedRetroArch() {
        // Official RA, touch host → placeholder; host pad → gate.
        assertEquals(
            P2SlotMode.PLACEHOLDER,
            P2SlotWorkaround.decide(Emu.RETROARCH, official, 1L, hasRealHostPad = false, flag = "auto"),
        )
        assertEquals(
            P2SlotMode.GATE_REAL_PAD,
            P2SlotWorkaround.decide(Emu.RETROARCH, official, 1L, hasRealHostPad = true, flag = "auto"),
        )
        // Unset flag behaves as auto.
        assertEquals(
            P2SlotMode.PLACEHOLDER,
            P2SlotWorkaround.decide(Emu.RETROARCH, official, 1L, hasRealHostPad = false, flag = null),
        )
        // Patched v2/v3 stays off.
        assertEquals(
            P2SlotMode.OFF,
            P2SlotWorkaround.decide(Emu.RETROARCH, gbx, 1L, hasRealHostPad = false, flag = "auto"),
        )
        // Standalone emulators have no RA slots.
        assertEquals(
            P2SlotMode.OFF,
            P2SlotWorkaround.decide(Emu.DOLPHIN, official, 1L, hasRealHostPad = false, flag = "auto"),
        )
        assertEquals(
            P2SlotMode.OFF,
            P2SlotWorkaround.decide(null, official, 1L, hasRealHostPad = false, flag = "auto"),
        )
    }

    @Test fun onForcesItOffDisablesIt() {
        assertEquals(
            P2SlotMode.PLACEHOLDER,
            P2SlotWorkaround.decide(Emu.RETROARCH, gbx, 1L, hasRealHostPad = false, flag = "on"),
        )
        assertEquals(
            P2SlotMode.GATE_REAL_PAD,
            P2SlotWorkaround.decide(Emu.RETROARCH, gbx, 1L, hasRealHostPad = true, flag = "on"),
        )
        assertEquals(
            P2SlotMode.OFF,
            P2SlotWorkaround.decide(Emu.RETROARCH, official, 1L, hasRealHostPad = false, flag = "off"),
        )
        assertEquals(
            P2SlotMode.OFF,
            P2SlotWorkaround.decide(Emu.RETROARCH, official, 1L, hasRealHostPad = true, flag = "off"),
        )
        // Forcing can't invent RA slots on a standalone host.
        assertEquals(
            P2SlotMode.OFF,
            P2SlotWorkaround.decide(Emu.DOLPHIN, official, 1L, hasRealHostPad = false, flag = "on"),
        )
    }

    @Test fun wireRoundTripAndUnknownStaysOff() {
        assertEquals(P2SlotMode.OFF, P2SlotMode.fromWire("off"))
        assertEquals(P2SlotMode.PLACEHOLDER, P2SlotMode.fromWire("placeholder"))
        assertEquals(P2SlotMode.GATE_REAL_PAD, P2SlotMode.fromWire("gate-realpad"))
        assertEquals(P2SlotMode.OFF, P2SlotMode.fromWire(null))
        assertEquals(P2SlotMode.OFF, P2SlotMode.fromWire(""))
        assertEquals(P2SlotMode.OFF, P2SlotMode.fromWire("placeholder "))
        for (mode in P2SlotMode.entries) assertEquals(mode, P2SlotMode.fromWire(mode.wire))
    }
}
