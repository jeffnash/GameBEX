package com.gamebex.client.stream.guest

import org.junit.Assert.*
import org.junit.Test

class StreamViewerScreenOptionsTest {
    @Test fun hudOffKeepsPadAndMenu() {
        val overlays = viewerOverlays(ViewerStatus.WATCHING, false, false, false)
        assertFalse(overlays.hud)
        assertTrue(overlays.menu)
        assertTrue(overlays.touchPad)
        assertFalse(overlays.loading)
    }

    @Test fun hiddenPadIsIndependentOfHud() {
        val overlays = viewerOverlays(ViewerStatus.WATCHING, false, true, true)
        assertTrue(overlays.hud)
        assertTrue(overlays.menu)
        assertFalse(overlays.touchPad)
    }

    @Test fun waitingForRenderedVideoAlwaysGatesControlsAndKeepsLoading() {
        for (hud in listOf(false, true)) {
            val overlays = viewerOverlays(ViewerStatus.WATCHING, true, hud, false)
            assertTrue(overlays.loading)
            assertFalse(overlays.menu)
            assertFalse(overlays.touchPad)
        }
    }

    @Test fun endedNeverLeavesInfiniteLoading() {
        val overlays = viewerOverlays(ViewerStatus.ENDED, true, false, true)
        assertFalse(overlays.loading)
        assertFalse(overlays.menu)
        assertFalse(overlays.touchPad)
    }
}
