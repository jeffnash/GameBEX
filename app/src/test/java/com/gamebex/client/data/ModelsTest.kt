package com.gamebex.client.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Catalog filename preservation (no stacked extensions). */
class ModelsTest {
    @Test fun preservesRealExtensions() {
        assertEquals("game.7z", downloadFileNameFor("game.7z"))
        assertEquals("game.zip", downloadFileNameFor("game.zip"))
        assertEquals("game.iso", downloadFileNameFor("game.iso"))
        assertEquals("disc1.cue", downloadFileNameFor("disc1.cue"))
        assertEquals("part.7z.001", downloadFileNameFor("part.7z.001"))
    }

    @Test fun bareNamesDefaultToZip() {
        assertEquals("SUPER GAME.zip", downloadFileNameFor("SUPER GAME"))
        assertEquals("rom.zip", downloadFileNameFor(""))
        assertEquals("a_b.zip", downloadFileNameFor("a/b"))
        assertEquals("rom.zip", downloadFileNameFor("..."))
    }

    @Test fun weirdExtensionsGetZip() {
        assertEquals("game.tar.gz.bak!.zip", downloadFileNameFor("game.tar.gz.bak!"))
    }

    @Test fun choicePromptRule() {
        assertEquals(false, shouldPromptForChoice(0, false))
        assertEquals(false, shouldPromptForChoice(1, false))
        assertEquals(true, shouldPromptForChoice(2, false))
        assertEquals(true, shouldPromptForChoice(5, false))
        assertEquals(false, shouldPromptForChoice(5, true))
        assertEquals(false, shouldPromptForChoice(1, true))
    }
}
