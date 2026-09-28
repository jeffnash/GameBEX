package com.gamebex.client.ui.system

import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.ControllerLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hint strip names what each button does right now, and every hinted action has a
 * glyph, so its pill can be tapped (the pill replays that button).
 */
class SystemHintsTest {
    @Test fun systemScreenHintsStayWithinFour() {
        assertEquals(
            listOf(
                ControllerHint(ControllerGlyph.A, "Select"),
                ControllerHint(ControllerGlyph.X, "Rescan"),
                ControllerHint(ControllerGlyph.Y, "Next issue"),
                ControllerHint(ControllerGlyph.Back, "Tuning"),
            ),
            systemHints(foldersOpen = false, picking = false, inFolder = false, hasIssues = true, hasRecs = true),
        )
        assertEquals(
            listOf(ControllerHint(ControllerGlyph.A, "Select"), ControllerHint(ControllerGlyph.X, "Rescan")),
            systemHints(foldersOpen = false, picking = false, inFolder = false, hasIssues = false, hasRecs = false),
        )
    }

    @Test fun theFoldersPanelHintsItsOwnActions() {
        // Close, Up and Use this folder are hinted, so each gets a tappable pill.
        assertEquals(
            listOf(ControllerHint(ControllerGlyph.A, "Select"), ControllerHint(ControllerGlyph.B, "Close")),
            systemHints(foldersOpen = true, picking = false, inFolder = false, hasIssues = true, hasRecs = true),
        )
        assertEquals(
            listOf(ControllerHint(ControllerGlyph.A, "Open"), ControllerHint(ControllerGlyph.B, "Back")),
            systemHints(foldersOpen = true, picking = true, inFolder = false, hasIssues = true, hasRecs = true),
        )
        assertEquals(
            listOf(
                ControllerHint(ControllerGlyph.A, "Open"),
                ControllerHint(ControllerGlyph.Y, "Use this folder"),
                ControllerHint(ControllerGlyph.B, "Up"),
            ),
            systemHints(foldersOpen = true, picking = true, inFolder = true, hasIssues = true, hasRecs = true),
        )
    }

    @Test fun thePanelCaptionMatchesTheStripInEveryState() {
        // The caption names B BACK where it goes back, B UP only inside a folder.
        assertEquals("A SELECT · B CLOSE", foldersCaption(foldersHints(false, false), ControllerLayout.Xbox, false))
        assertEquals("A OPEN · B BACK", foldersCaption(foldersHints(true, false), ControllerLayout.Xbox, false))
        assertEquals(
            "A OPEN · Y USE THIS FOLDER · B UP",
            foldersCaption(foldersHints(true, true), ControllerLayout.Xbox, false),
        )
        for ((picking, inFolder) in listOf(false to false, true to false, true to true)) {
            assertEquals(
                foldersHints(picking, inFolder),
                systemHints(foldersOpen = true, picking = picking, inFolder = inFolder, hasIssues = true, hasRecs = true),
            )
        }
    }

    @Test fun thePanelCaptionNamesKeysAsTheNintendoPadPrintsThem() {
        assertEquals(
            "B OPEN · X USE THIS FOLDER · A UP",
            foldersCaption(foldersHints(true, true), ControllerLayout.Nintendo, false),
        )
    }
}
