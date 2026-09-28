package com.gamebex.client.ui.game

import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Detail hints follow the focus zone; Y only where it acts. */
class DetailHintsTest {
    private fun List<ControllerHint>.label(g: ControllerGlyph) = firstOrNull { it.glyph == g }?.text

    private fun hints(
        zone: Int = 0,
        ctaA: String? = "Play",
        advAction: String? = null,
        tab: Int = 0,
        downloading: Boolean = false,
        favorite: Boolean? = false,
    ) = detailHints(zone, ctaA, advAction, tab, downloading, favorite)

    @Test fun ctaZoneNamesTheCta() {
        val h = hints(zone = 0, ctaA = "Continue")
        assertEquals("Continue", h.label(ControllerGlyph.A))
        assertEquals("Back", h.label(ControllerGlyph.B))
        assertEquals("Advanced", h.label(ControllerGlyph.X))
        assertEquals("Favorite", h.label(ControllerGlyph.Y))
        assertEquals(4, h.size)
    }

    @Test fun parkedCtaDropsA() {
        assertNull(hints(zone = 0, ctaA = null).label(ControllerGlyph.A))
    }

    @Test fun tabsZoneDropsA() {
        val h = hints(zone = 1, tab = 1)
        assertNull(h.label(ControllerGlyph.A))
        assertEquals("Overview", h.label(ControllerGlyph.X))
    }

    @Test fun advancedZoneNamesTheRowAction() {
        assertEquals("Delete", hints(zone = 2, tab = 1, advAction = "Delete").label(ControllerGlyph.A))
        assertNull(hints(zone = 2, tab = 1, advAction = null).label(ControllerGlyph.A))
    }

    @Test fun catalogFaceHidesFavorite() {
        assertNull(hints(favorite = null).label(ControllerGlyph.Y))
        assertEquals("Unfavorite", hints(favorite = true).label(ControllerGlyph.Y))
    }

    @Test fun downloadingYCancels() {
        assertEquals("Cancel", hints(downloading = true, favorite = null).label(ControllerGlyph.Y))
    }

    @Test fun versionsCountOnlyUnfinishedAsQueued() {
        assertEquals("This file only", versionsSummary(emptyList()))
        assertEquals("1 version", versionsSummary(listOf(DownloadEngine.ST_DONE)))
        assertEquals("2 versions", versionsSummary(listOf(DownloadEngine.ST_DONE, DownloadEngine.ST_FAILED)))
        assertEquals("1 queued", versionsSummary(listOf(DownloadEngine.ST_QUEUED)))
        assertEquals(
            "3 versions · 2 queued",
            versionsSummary(listOf(DownloadEngine.ST_DONE, DownloadEngine.ST_ACTIVE, DownloadEngine.ST_PAUSED)),
        )
    }
}
