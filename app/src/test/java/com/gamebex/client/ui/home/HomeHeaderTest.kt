package com.gamebex.client.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Header chrome rules: Downloads badge tone and System warning. */
class HomeHeaderTest {

    @Test fun downloadsBadgeIsAMintCountWhenNumeric() {
        assertEquals(BadgeTone.COUNT, downloadsBadgeTone("2"))
        assertEquals(BadgeTone.COUNT, downloadsBadgeTone("12"))
        assertEquals(BadgeTone.COUNT, downloadsBadgeTone("0"))
    }

    @Test fun downloadsBadgeIsOfflineInTertiaryWhenNotNumeric() {
        assertEquals(BadgeTone.OFFLINE, downloadsBadgeTone("offline"))
        assertEquals(BadgeTone.OFFLINE, downloadsBadgeTone("3 offline"))
    }

    @Test fun downloadsBadgeIsNoneWhenAbsent() {
        assertEquals(BadgeTone.NONE, downloadsBadgeTone(null))
        assertEquals(BadgeTone.NONE, downloadsBadgeTone(""))
    }

    @Test fun systemWarningShowsOnlyWhenAboveZero() {
        assertNull(systemWarnText(0))
        assertNull(systemWarnText(-1))
        assertNotNull(systemWarnText(1))
        assertNotNull(systemWarnText(2))
    }

    @Test fun systemWarningCarriesTheCount() {
        assertEquals("1", systemWarnText(1)?.takeLast(1))
        assertEquals("2", systemWarnText(2)?.takeLast(1))
    }

    @Test fun browseLabelParsesCloudLeadAndSite() {
        assertEquals(
            BrowseParts(cloud = true, lead = "Browse", site = "ROMS-I-Own.com"),
            parseBrowseLabel("☁ Browse ROMS-I-Own.com"),
        )
        assertEquals(
            BrowseParts(cloud = true, lead = "Browse", site = null),
            parseBrowseLabel("☁ Browse"),
        )
        assertEquals(
            BrowseParts(cloud = false, lead = "Discover", site = null),
            parseBrowseLabel("Discover"),
        )
    }

    @Test fun searchFieldShrinksBeforeNavItemsClip() {
        // Roomy row: the search field takes its 640 cap.
        assertEquals(640, searchWidthPx(rowWidthPx = 2000, fixedPx = 100, navPx = 800, maxSearchPx = 640))
        // Tight row: the search field shrinks to the leftover.
        assertEquals(200, searchWidthPx(rowWidthPx = 1100, fixedPx = 100, navPx = 800, maxSearchPx = 640))
        // No room: the search field goes to zero before nav clips.
        assertEquals(0, searchWidthPx(rowWidthPx = 800, fixedPx = 100, navPx = 800, maxSearchPx = 640))
    }

    @Test fun browseCollapsesOnlyWhenTheRowCannotFitTheSearchFloor() {
        assertFalse(browseCollapses(rowWidthPx = 1552, fixedPx = 100, navFullPx = 800, minSearchPx = 84))
        assertFalse(browseCollapses(rowWidthPx = 1552, fixedPx = 100, navFullPx = 1368, minSearchPx = 84))
        assertTrue(browseCollapses(rowWidthPx = 1552, fixedPx = 100, navFullPx = 1369, minSearchPx = 84))
    }
}
