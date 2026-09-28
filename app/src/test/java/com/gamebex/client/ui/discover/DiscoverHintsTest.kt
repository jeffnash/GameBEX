package com.gamebex.client.ui.discover

import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discover hints: ≤4, state-aware, Start named by the focused action. */
class DiscoverHintsTest {
    private fun List<ControllerHint>.label(g: ControllerGlyph) = firstOrNull { it.glyph == g }?.text

    private fun hints(
        selectorOpen: Boolean = false,
        noSource: Boolean = false,
        zone: Int = 1,
        empty: Boolean = false,
        error: Boolean = false,
        ownedOnly: Boolean = false,
        startLabel: String? = "Download",
    ) = discoverHints(selectorOpen, noSource, zone, empty, error, ownedOnly, startLabel)

    @Test fun shelfHintsFitAndNameStart() {
        val h = hints()
        assertTrue(h.size <= 4)
        assertEquals("Open", h.label(ControllerGlyph.A))
        assertEquals("Download", h.label(ControllerGlyph.Start))
        assertEquals("Owned Only", h.label(ControllerGlyph.Y))
        assertTrue(h.none { it.glyph == null })
    }

    @Test fun errorStateSaysRetry() {
        val h = hints(empty = true, error = true, startLabel = null)
        assertEquals("Retry", h.label(ControllerGlyph.A))
        assertNull(h.label(ControllerGlyph.Start))
    }

    @Test fun emptyWithoutErrorHasNoA() {
        assertNull(hints(empty = true, ownedOnly = true).label(ControllerGlyph.A))
        assertEquals("All Games", hints(empty = true, ownedOnly = true).label(ControllerGlyph.Y))
    }

    @Test fun noSourceImports() {
        val h = hints(noSource = true, empty = true)
        assertEquals("Import source", h.label(ControllerGlyph.A))
        assertNull(h.label(ControllerGlyph.Start))
    }

    @Test fun headerAndSelectorStates() {
        assertEquals("Change source", hints(zone = 2).label(ControllerGlyph.A))
        val sel = hints(selectorOpen = true)
        assertEquals("Select", sel.label(ControllerGlyph.A))
        assertEquals("Close", sel.label(ControllerGlyph.B))
        assertNull(sel.label(ControllerGlyph.Start))
    }
}
