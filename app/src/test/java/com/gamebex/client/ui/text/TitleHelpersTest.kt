package com.gamebex.client.ui.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared title helpers live outside `ui/home/model/HomeCopy` so
 * `ui/console` uses them without the home model: the surrogate-pair /
 * whitespace initials contract and the No-Intro article move.
 */
class TitleHelpersTest {
    @Test fun initialsTakeFirstTwoWords() {
        assertEquals("CT", initialsOf("Chrono Trigger"))
        assertEquals("MR", initialsOf("Metal Runner (USA)"))
    }

    @Test fun initialsSingleWordAndPunctuation() {
        assertEquals("T", initialsOf("Tetris"))
        assertEquals("PM", initialsOf("Pokémon: Mystery"))
        assertEquals("?", initialsOf("  ---  "))
    }

    @Test fun initialsSurviveSurrogatePairsAndWhitespaceOnlyTitles() {
        val boldA = "\uD835\uDC00" // 𝐀
        val boldB = "\uD835\uDC01" // 𝐁
        assertEquals(boldA + boldB, initialsOf("$boldA $boldB Quest"))
        assertEquals("SNES", initialsOf("   ", "SNES"))
        assertEquals("?", initialsOf("\t \n"))
    }

    @Test fun displayTitleMovesNoIntroArticlesKeepingTheSubtitle() {
        assertEquals("The Legend of Zelda", displayTitle("Legend of Zelda, The"))
        assertEquals(
            "The Legend of Zelda - A Link to the Past",
            displayTitle("Legend of Zelda, The - A Link to the Past"),
        )
        assertEquals("Chrono Trigger", displayTitle("Chrono Trigger"))
    }
}
