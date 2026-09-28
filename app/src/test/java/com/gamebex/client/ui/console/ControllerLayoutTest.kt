package com.gamebex.client.ui.console

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerLayoutTest {
    @Test fun xboxLabelsAreIdentity() {
        assertEquals("A", glyphLabel(ControllerGlyph.A, ControllerLayout.Xbox, swap = false))
        assertEquals("Y", glyphLabel(ControllerGlyph.Y, ControllerLayout.Xbox, swap = false))
    }

    @Test fun nintendoWithSwapConfirmsOnItsAButton() {
        // Confirm lives on the east button, which a Nintendo pad prints "A".
        assertEquals("A", glyphLabel(ControllerGlyph.A, ControllerLayout.Nintendo, swap = true))
        assertEquals("B", glyphLabel(ControllerGlyph.B, ControllerLayout.Nintendo, swap = true))
    }

    @Test fun nintendoWithoutSwapNamesTheSouthButtonB() {
        assertEquals("B", glyphLabel(ControllerGlyph.A, ControllerLayout.Nintendo, swap = false))
        assertEquals("L", glyphLabel(ControllerGlyph.LB, ControllerLayout.Nintendo, swap = false))
    }

    @Test fun playStationShapes() {
        assertEquals("✕", glyphLabel(ControllerGlyph.A, ControllerLayout.PlayStation, swap = false))
        assertEquals("○", glyphLabel(ControllerGlyph.A, ControllerLayout.PlayStation, swap = true))
        assertEquals("≡", glyphLabel(ControllerGlyph.Start, ControllerLayout.PlayStation, swap = true))
    }

    @Test fun remapSwapsFaceButtonsOnly() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, remapGamepadKey(KeyEvent.KEYCODE_BUTTON_A, true))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, remapGamepadKey(KeyEvent.KEYCODE_BUTTON_Y, true))
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, remapGamepadKey(KeyEvent.KEYCODE_BUTTON_START, true))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, remapGamepadKey(KeyEvent.KEYCODE_BUTTON_A, false))
    }
}
