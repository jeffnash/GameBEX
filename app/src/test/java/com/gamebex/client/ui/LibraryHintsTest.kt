package com.gamebex.client.ui

import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Library hints: state-aware, Start named by the game's own action. */
class LibraryHintsTest {
    // Zones mirror ConsoleLibrary: 0 top, 1 chips, 2 list, 3 preview.
    private fun hints(
        zone: Int = 2,
        topCol: Int = 0,
        editing: Boolean = false,
        settled: Boolean = false,
        settledAction: String? = null,
        previewA: String? = "Continue",
        startLabel: String? = "Continue",
        favorite: Boolean? = false,
        emptyA: String? = null,
    ) = libraryHints(zone, topCol, editing, settled, settledAction, previewA, startLabel, favorite, emptyA)

    private fun List<ControllerHint>.label(g: ControllerGlyph) = firstOrNull { it.glyph == g }?.text

    @Test fun listStartFollowsPrimaryAction() {
        val h = hints(startLabel = "Continue")
        assertEquals("Open", h.label(ControllerGlyph.A))
        assertEquals("Continue", h.label(ControllerGlyph.Start))
        assertEquals("Delete", h.label(ControllerGlyph.Back))
        assertTrue(h.size <= 4)
    }

    @Test fun unsupportedGameDropsStart() {
        assertNull(hints(startLabel = null).label(ControllerGlyph.Start))
    }

    @Test fun emptyLibraryOffersBrowseNotOpen() {
        val h = hints(emptyA = "Browse games", startLabel = null, favorite = null, previewA = null)
        assertEquals("Browse games", h.label(ControllerGlyph.A))
        assertNull(h.label(ControllerGlyph.Y))
        assertNull(h.label(ControllerGlyph.Back))
    }

    /** Rescan has its own always-visible header pill, so the strip never repeats it. */
    @Test fun rescanIsNeverInTheStrip() {
        val states = listOf(
            hints(), hints(zone = 0), hints(zone = 0, topCol = 1), hints(zone = 1), hints(zone = 3),
            hints(emptyA = "Browse games"), hints(settled = true, settledAction = "Install"),
        )
        for (h in states) assertNull(h.toString(), h.label(ControllerGlyph.X))
    }

    @Test fun filteredEmptyOffersClearFilters() {
        assertEquals("Clear filters", hints(emptyA = "Clear filters").label(ControllerGlyph.A))
    }

    @Test fun previewZoneNamesFocusedButton() {
        val h = hints(zone = 3, previewA = "Start fresh", startLabel = "Continue", favorite = true)
        assertEquals("Start fresh", h.label(ControllerGlyph.A))
        assertEquals("Back", h.label(ControllerGlyph.B))
        assertEquals("Unfavorite", h.label(ControllerGlyph.Y))
    }

    @Test fun settledPanelOwnsA() {
        val h = hints(settled = true, settledAction = "Install")
        assertEquals("Install", h.label(ControllerGlyph.A))
        assertEquals("Dismiss", h.label(ControllerGlyph.B))
    }

    @Test fun topAndChipZonesNameTheirA() {
        assertEquals("Search", hints(zone = 0, topCol = 0).label(ControllerGlyph.A))
        assertEquals("Sort", hints(zone = 0, topCol = 1).label(ControllerGlyph.A))
        assertEquals("Filter", hints(zone = 1).label(ControllerGlyph.A))
        assertNull(hints(zone = 1).label(ControllerGlyph.Back))
        assertEquals("Done", hints(zone = 0, editing = true).label(ControllerGlyph.A))
    }

    @Test fun pluralsAgree() {
        assertEquals("1 download", countOf(1, "download"))
        assertEquals("3 downloads", countOf(3, "download"))
        assertEquals("0 new origins", countOf(0, "new origin"))
        assertEquals("uses", verbFor(1, "uses", "use"))
        assertEquals("use", verbFor(2, "uses", "use"))
    }
}
