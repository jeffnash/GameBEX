package com.gamebex.client.ui.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerHintBarStyleTest {
    @Test fun aGlyphIsFilledOnlyWhenEmphasizePrimaryIsTrue() {
        assertTrue(hintPillFilled(ControllerGlyph.A, emphasizePrimary = true))
        assertFalse(hintPillFilled(ControllerGlyph.A, emphasizePrimary = false))
    }

    @Test fun pillBackgroundIsAlwaysOnWhenEmphasizePrimaryIsFalse() {
        assertEquals(HintPillBackground.AlwaysOn, hintPillBackgroundMode(emphasizePrimary = false))
    }

    @Test fun pillBackgroundIsPressOnlyWhenEmphasizePrimaryIsTrue() {
        assertEquals(HintPillBackground.PressOnly, hintPillBackgroundMode(emphasizePrimary = true))
    }

    @Test fun nonAGlyphsAreNeverFilledEvenWhenEmphasizePrimaryIsTrue() {
        assertFalse(hintPillFilled(ControllerGlyph.B, emphasizePrimary = true))
        assertFalse(hintPillFilled(ControllerGlyph.Start, emphasizePrimary = true))
        assertFalse(hintPillFilled(null, emphasizePrimary = true))
    }
}
