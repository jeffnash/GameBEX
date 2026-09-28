package com.gamebex.client.ui.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Versioned artwork cache keys: replacements must not serve stale art. */
class ArtCacheKeyTest {
    @Test fun explicitRevisionWinsOverMtime() {
        assertEquals("p#7", artCacheKey("p", 7, 123L))
    }

    @Test fun fallsBackToMtimeWithoutRevision() {
        assertEquals("p@123", artCacheKey("p", null, 123L))
    }

    @Test fun keyChangesWhenFileReplaced() {
        assertNotEquals(artCacheKey("p", null, 100L), artCacheKey("p", null, 200L))
        assertNotEquals(artCacheKey("p", 1, 100L), artCacheKey("p", 2, 100L))
    }
}
