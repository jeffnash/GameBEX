package com.gamebex.client.ui.discover

import org.junit.Assert.assertEquals
import org.junit.Test

/** Discover Left: rail reachable in every content state. */
class DiscoverNavTest {
    @Test fun shelfMovesLeftWhenPossible() {
        assertEquals(DiscoverLeft.SHELF_LEFT, resolveDiscoverLeft(1, false, 3))
    }

    @Test fun shelfStartGoesToHeader() {
        assertEquals(DiscoverLeft.TO_HEADER, resolveDiscoverLeft(1, false, 0))
    }

    @Test fun headerGoesToRail() {
        assertEquals(DiscoverLeft.RAIL, resolveDiscoverLeft(0, false, 0))
    }

    @Test fun emptyShelfStillReachesRail() {
        assertEquals(DiscoverLeft.RAIL, resolveDiscoverLeft(1, true, 0))
        assertEquals(DiscoverLeft.RAIL, resolveDiscoverLeft(0, true, 0))
    }

    @Test fun restoreLoadsLatestWhenNothingSaved() {
        assertEquals(DiscoverRestore.LOAD_LATEST, resolveDiscoverRestore(false, null))
    }

    @Test fun restoreRebrowsesSavedConsole() {
        assertEquals(DiscoverRestore.SELECT_CONSOLE, resolveDiscoverRestore(false, "95"))
    }

    @Test fun restoreRebrowsesNonnumericCategory() {
        assertEquals(DiscoverRestore.SELECT_CONSOLE, resolveDiscoverRestore(false, "game-boy"))
    }

    @Test fun restoreSkipsWhenItemsPresent() {
        assertEquals(DiscoverRestore.NONE, resolveDiscoverRestore(true, "95"))
        assertEquals(DiscoverRestore.NONE, resolveDiscoverRestore(true, null))
    }
}
