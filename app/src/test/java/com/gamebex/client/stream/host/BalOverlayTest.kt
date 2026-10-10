package com.gamebex.client.stream.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The BAL overlay state machine ([BalOverlay]); the framework half is verified on-device. */
class BalOverlayTest {
    private class FakeWindows(var addResult: Any? = Any()) : BalWindows {
        val events = mutableListOf<String>()
        var lastRemoved: Any? = null
        override fun add(): Any? {
            events += "add"
            return addResult
        }

        override fun remove(token: Any?) {
            events += "remove"
            lastRemoved = token
        }
    }

    @Test fun armAddsOnceAndDisarmRemoves() {
        val windows = FakeWindows()
        val overlay = BalOverlay(windows) { it.run() }
        assertFalse(overlay.armedForTest())
        overlay.arm()
        overlay.arm()
        assertTrue(overlay.armedForTest())
        assertEquals(listOf("add"), windows.events)
        overlay.disarm()
        overlay.disarm()
        assertFalse(overlay.armedForTest())
        assertEquals(listOf("add", "remove"), windows.events)
    }

    @Test fun disarmWithoutArmIsANoOp() {
        val windows = FakeWindows()
        val overlay = BalOverlay(windows) { it.run() }
        overlay.disarm()
        assertEquals(emptyList<String>(), windows.events)
        assertFalse(overlay.armedForTest())
    }

    @Test fun refusedAddLeavesDisarmedAndRetries() {
        val windows = FakeWindows(addResult = null)
        val overlay = BalOverlay(windows) { it.run() }
        overlay.arm()
        assertFalse(overlay.armedForTest())
        val token = Any()
        windows.addResult = token
        overlay.arm()
        assertTrue(overlay.armedForTest())
        overlay.disarm()
        assertEquals(token, windows.lastRemoved)
    }

    @Test fun throwingWindowsNeverEscape() {
        val windows = object : BalWindows {
            override fun add(): Any? = error("add refused")
            override fun remove(token: Any?) = error("remove refused")
        }
        val overlay = BalOverlay(windows) { it.run() }
        overlay.arm()
        assertFalse(overlay.armedForTest())
        overlay.disarm()
    }

    @Test fun armDisarmArmCycles() {
        val windows = FakeWindows()
        val overlay = BalOverlay(windows) { it.run() }
        overlay.arm()
        overlay.disarm()
        overlay.arm()
        assertTrue(overlay.armedForTest())
        assertEquals(listOf("add", "remove", "add"), windows.events)
    }
}
