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

    @Test fun physicalControllerHidesOnlyThePadAndDisconnectRestoresIt() {
        val disconnected = viewerOverlays(ViewerStatus.WATCHING, false, true, false)
        val connected = viewerOverlays(ViewerStatus.WATCHING, false, true, false, physicalControllerConnected = true)
        assertTrue(disconnected.touchPad)
        assertFalse(connected.touchPad)
        assertTrue(connected.menu)
        assertTrue(connected.hud)
        assertFalse(connected.loading)
        assertEquals(disconnected, viewerOverlays(ViewerStatus.WATCHING, false, true, false, physicalControllerConnected = false))
    }

    @Test fun disconnectNeverOverridesDebugHiddenPadOrVideoGate() {
        for (connected in listOf(false, true)) {
            val hidden = viewerOverlays(ViewerStatus.WATCHING, false, false, true, connected)
            assertFalse(hidden.touchPad)
            assertTrue(hidden.menu)
            assertFalse(hidden.hud)
            val waiting = viewerOverlays(ViewerStatus.WATCHING, true, true, false, connected)
            assertFalse(waiting.touchPad)
            assertFalse(waiting.menu)
            assertTrue(waiting.loading)
        }
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
