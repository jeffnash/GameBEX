package com.gamebex.client.ui.console

import org.junit.Assert.assertEquals
import org.junit.Test

/** Shelf scroll math: actual offset from layout, not the requested target. */
class AnchoredShelfTest {
    @Test fun actualScrollFromFirstVisibleItem() {
        // pad=100, item=200, gap=20: item 2 content offset is 540.
        assertEquals(440, shelfActualScrollPx(100f, 200f, 20f, 2, 100))
        // At rest the first item sits exactly on the padding: zero scroll.
        assertEquals(0, shelfActualScrollPx(100f, 200f, 20f, 0, 100))
    }

    @Test fun actualScrollClampsAtEnds() {
        assertEquals(0, shelfActualScrollPx(100f, 200f, 20f, 0, 500))
    }

    @Test fun centersShiftWithActualScroll() {
        val still = shelfCenterDesignPx(2, 148, 38, 160, 0, 2f)
        val scrolled = shelfCenterDesignPx(2, 148, 38, 160, 200, 2f)
        assertEquals(100f, still - scrolled, 0.001f)
    }
}
