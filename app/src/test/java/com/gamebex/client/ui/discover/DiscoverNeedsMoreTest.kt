package com.gamebex.client.ui.discover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touch-scrolling the shelf to its end loads the next page, like selecting the last tile. */
class DiscoverNeedsMoreTest {
    @Test fun nearTheEndLoadsMore() {
        assertTrue(discoverNeedsMore(lastVisible = 19, count = 20, ownedOnly = false))
        assertTrue(discoverNeedsMore(lastVisible = 18, count = 20, ownedOnly = false))
    }

    @Test fun elsewhereOwnedOnlyOrEmptyDoesNot() {
        assertFalse(discoverNeedsMore(lastVisible = 10, count = 20, ownedOnly = false))
        assertFalse(discoverNeedsMore(lastVisible = 19, count = 20, ownedOnly = true))
        assertFalse(discoverNeedsMore(lastVisible = null, count = 20, ownedOnly = false))
        assertFalse(discoverNeedsMore(lastVisible = 0, count = 0, ownedOnly = false))
    }
}
