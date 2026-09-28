package com.gamebex.client.ui.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pending selection, stable-ID rebind, key-repeat gating, key memory. */
class ShelfSelectionTest {
    @Test fun pendingInitialAppliesWhenDataArrives() {
        val s = ShelfState(0, 3)
        assertEquals(0, s.selected)
        s.resize(5)
        assertEquals(3, s.selected)
    }

    @Test fun selectWhileEmptyPendsUntilResize() {
        val s = ShelfState(0)
        s.select(2)
        assertEquals(0, s.selected)
        s.resize(4)
        assertEquals(2, s.selected)
    }

    @Test fun selectionSurvivesEmptyRoundTrip() {
        val s = ShelfState(5)
        s.select(4)
        s.resize(0)
        assertEquals(0, s.selected)
        s.resize(5)
        assertEquals(4, s.selected)
    }

    @Test fun stableIdRebindFollowsInsertions() {
        assertEquals(2, stableIndexFor(listOf("x", "a", "b", "c"), "b", 1))
        assertEquals(1, stableIndexFor(listOf("a", "b", "c"), "gone", 1))
        assertEquals(1, stableIndexFor(emptyList(), "b", 1))
        assertEquals(1, stableIndexFor(listOf("a", "b"), null, 1))
    }

    @Test fun selectStableIdKeepsItemAfterInsertion() {
        val s = ShelfState(3)
        s.select(1) // "b"
        s.resize(4)
        s.selectStableId("b", listOf("x", "a", "b", "c"))
        assertEquals(2, s.selected)
    }

    @Test fun repeatsAllowedForNavigationOnly() {
        assertTrue(allowKeyRepeat(true, 5))
        assertTrue(allowKeyRepeat(true, 0))
        assertTrue(allowKeyRepeat(false, 0))
        assertFalse(allowKeyRepeat(false, 1))
    }

    @Test fun keyMemoryRoundTrip() {
        val m = ConsoleFocusMemory()
        assertFalse(m.hasKey("system:row:id"))
        assertEquals(null, m.recallKey("system:row:id"))
        m.storeKey("system:row:id", "gc")
        assertTrue(m.hasKey("system:row:id"))
        assertEquals("gc", m.recallKey("system:row:id"))
    }
}
