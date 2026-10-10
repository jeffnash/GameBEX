package com.gamebex.client.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay viewport a default-cutout-mode emulator gets.
 * RetroArch-class apps letterbox off any display edge a cutout touches;
 * the overlay resolves against that window, not the display.
 */
class TogetherViewportTest {
    @Test fun noCutoutIsFullDisplay() {
        assertEquals(
            HostViewport(0, 0, 1604, 720),
            defaultCutoutViewport(1604, 720, emptyList()),
        )
    }

    @Test fun rightNotchLetterboxesRightSide() {
        // Lab phones in landscape: notch at the right; RA gets [0,1534).
        assertEquals(
            HostViewport(0, 0, 1534, 720),
            defaultCutoutViewport(1604, 720, listOf(HostViewport(1534, 300, 70, 120))),
        )
    }

    @Test fun leftNotchShiftsOrigin() {
        assertEquals(
            HostViewport(70, 0, 1534, 720),
            defaultCutoutViewport(1604, 720, listOf(HostViewport(0, 300, 70, 120))),
        )
    }

    @Test fun topNotchInPortraitShiftsDown() {
        assertEquals(
            HostViewport(0, 80, 720, 1524),
            defaultCutoutViewport(720, 1604, listOf(HostViewport(300, 0, 120, 80))),
        )
    }

    @Test fun floatingHolePunchLetterboxesNothing() {
        assertEquals(
            HostViewport(0, 0, 1604, 720),
            defaultCutoutViewport(1604, 720, listOf(HostViewport(700, 300, 60, 60))),
        )
    }

    @Test fun degenerateCutoutFallsBackToFullDisplay() {
        assertEquals(
            HostViewport(0, 0, 1604, 720),
            defaultCutoutViewport(1604, 720, listOf(HostViewport(0, 0, 1604, 720))),
        )
    }

    @Test fun viewportEmptiness() {
        assertTrue(HostViewport(0, 0, 0, 720).isEmpty)
        assertTrue(HostViewport(0, 0, 1604, 0).isEmpty)
        assertFalse(HostViewport(0, 0, 1534, 720).isEmpty)
    }
}
