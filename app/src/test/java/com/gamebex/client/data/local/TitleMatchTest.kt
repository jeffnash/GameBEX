package com.gamebex.client.data.local

import com.gamebex.client.data.CatalogFixtures.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** GameBEX art fallback matching: exact normalized titles, console-scoped. */
class TitleMatchTest {
    private fun entry(id: Int, title: String, platformId: String?) =
        entry(itemKey = "k/$id", title = title, platformId = platformId)

    @Test fun regionTagsDoNotDistinguish() {
        assertEquals("tetris", normTitle("Tetris (Japan) (En)"))
        assertEquals("newsupermariobros", normTitle("New Super Mario Bros. (USA)"))
    }

    @Test fun exactTitleSameConsoleWins() {
        val entries = listOf(
            entry(1, "Tetris", "nes"),
            entry(2, "Tetris (Japan)", "gb"),
        )
        assertEquals("k/2", matchSourceEntry("Tetris (Japan) (En)", "gb", entries)?.ref?.itemKey)
    }

    @Test fun titleExactWrongConsoleIsMiss() {
        // The Arcade Tetris box must never land on the Game Boy page.
        val entries = listOf(
            entry(3, "Tetris", "mame"),
            entry(7, "Tetris", null),
        )
        assertNull(matchSourceEntry("Tetris", "gb", entries))
    }

    @Test fun noExactTitleIsMiss() {
        val entries = listOf(entry(1, "Tetrisphere", "gb"))
        assertNull(matchSourceEntry("Tetris", "gb", entries))
    }

    @Test fun folderSlugRoundTrip() {
        assertEquals("game-boy", consoleSlugForFolder("gb"))
        assertEquals("gb", folderForConsoleSlug("game-boy"))
        assertNull(consoleSlugForFolder("no-such-folder"))
        assertNull(folderForConsoleSlug(null))
    }

    @Test fun blankTitleNeverMatches() {
        val entries = listOf(entry(1, "Tetris", "gb"))
        assertNull(matchSourceEntry("   ", "gb", entries))
        assertNull(matchSourceEntry("Tetris", "gb", emptyList()))
    }
}
