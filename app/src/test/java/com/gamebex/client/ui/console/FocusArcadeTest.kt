package com.gamebex.client.ui.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier-1 focus architecture: explicit selection state. */
class FocusArcadeTest {
    @Test fun leftAtZeroEdgeReturnsFalse() {
        val s = ShelfState(5)
        assertFalse(s.left())
        assertEquals(0, s.selected)
    }

    @Test fun rightAtEndEdgeReturnsFalse() {
        val s = ShelfState(5)
        s.select(4)
        assertFalse(s.right())
        assertEquals(4, s.selected)
    }

    @Test fun selectCoercesIntoRange() {
        val s = ShelfState(5)
        s.select(-3)
        assertEquals(0, s.selected)
        s.select(99)
        assertEquals(4, s.selected)
        val empty = ShelfState(0)
        empty.select(3)
        assertEquals(0, empty.selected)
    }

    @Test fun resizeCoercesSelection() {
        val s = ShelfState(5)
        s.select(4)
        s.resize(2)
        assertEquals(1, s.selected)
    }

    @Test fun leftRightWalk() {
        val s = ShelfState(5)
        assertTrue(s.right())
        assertTrue(s.right())
        assertTrue(s.left())
        assertEquals(1, s.selected)
    }

    @Test fun memorySaveRecallRoundTrip() {
        val m = ConsoleFocusMemory()
        assertEquals(7, m.recall("missing", 7))
        assertFalse(m.has("discover:shelf"))
        m.store("discover:shelf", 3)
        assertTrue(m.has("discover:shelf"))
        assertEquals(3, m.recall("discover:shelf", 0))
    }

    @Test fun nearestIndexByXPicksClosest() {
        val centers = listOf(100f, 200f, 300f)
        assertEquals(0, nearestIndexByX(centers, 0f))
        assertEquals(1, nearestIndexByX(centers, 210f))
        assertEquals(2, nearestIndexByX(centers, 999f))
        assertEquals(0, nearestIndexByX(emptyList(), 150f))
    }
}
