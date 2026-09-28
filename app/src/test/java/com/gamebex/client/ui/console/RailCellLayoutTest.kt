package com.gamebex.client.ui.console

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Placement math for the rail hit boxes: [railHitInset] centres a hit
 * box on the cell's `i * x(72)` slot, and that centring must cancel exactly
 * with the `Alignment.Center` placement of the visual cell inside the hit
 * box. Any rounding mismatch between the two would slide the visible rail
 * stack by a pixel.
 */
class RailCellLayoutTest {
    @Test
    fun railHitInset_centresAnOversizedHitBoxOnTheSlotRoundingHalfUp() {
        // 108 px slot (x(72) at the handheld ~0.573 scale, density 2.625)
        // vs a 126 px hit box (hitMin = 48 dp): 9 px overhang per side.
        assertEquals(-9, railHitInset(108, 126))
        // Odd excess rounds half-up exactly like BiasAlignment's centring.
        assertEquals(-10, railHitInset(108, 127))
        // A hit box that fits the slot exactly is not shifted.
        assertEquals(0, railHitInset(126, 126))
    }

    @Test
    fun railHitInset_cancelsComposeCenterAlignmentSoEveryVisualCellLandsExactlyOnItsSlotTop() {
        // The rail only ever has hitPx >= slotPx (hitMin raises, never
        // lowers). Hit-box top + the visual cell's Center offset must equal
        // the slot top exactly, for even and odd excesses and any slot index.
        val cases = listOf(
            108 to 126, 108 to 127, 107 to 126, 107 to 127,
            126 to 126, 210 to 237, 1 to 48,
        )
        for ((slot, hit) in cases) {
            for (index in 0..4) {
                val slotTop = index * slot
                val hitTop = slotTop + railHitInset(slot, hit)
                val visualTop =
                    hitTop + Alignment.Center.align(
                        IntSize(0, slot),
                        IntSize(0, hit),
                        LayoutDirection.Ltr,
                    ).y
                assertEquals("slot=$slot hit=$hit index=$index", slotTop, visualTop)
            }
        }
    }
}
