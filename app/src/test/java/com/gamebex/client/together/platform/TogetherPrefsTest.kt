package com.gamebex.client.together.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TogetherPrefsTest {
    @Test
    fun sanitize_removesPunctuationAndCollapsesSpaces() {
        assertEquals("moto g 2025", TogetherPrefs.sanitize("  moto g — 2025!!  "))
    }

    @Test
    fun sanitize_usesPlayerForEmptyResult() {
        assertEquals("Player", TogetherPrefs.sanitize(" !!! "))
    }

    @Test
    fun sanitize_cutsToTwentyFourCharacters() {
        assertEquals(24, TogetherPrefs.sanitize("123456789012345678901234567890").length)
    }

    @Test
    fun customName_trimsCollapsesWhitespaceAndStripsControls() {
        assertEquals("Sam's Deck", TogetherPrefs.sanitizeCustomName("  Sam's    Deck  "))
        assertEquals("SamDeck", TogetherPrefs.sanitizeCustomName("Sam\nDeck"))
        assertEquals("Sam Deck", TogetherPrefs.sanitizeCustomName("Sam\u0007 \u00a0Deck\r"))
        assertEquals("Zoë's Switch ✨", TogetherPrefs.sanitizeCustomName("Zoë's Switch ✨"))
    }

    @Test
    fun customName_blankMeansUseTheDefault() {
        assertNull(TogetherPrefs.sanitizeCustomName(null))
        assertNull(TogetherPrefs.sanitizeCustomName(""))
        assertNull(TogetherPrefs.sanitizeCustomName(" \n\t "))
    }

    @Test
    fun customName_keepsAtMostTwentyFourCharacters() {
        assertEquals("a".repeat(24), TogetherPrefs.sanitizeCustomName("a".repeat(30)))
        val emoji = "\uD83D\uDE00"
        assertEquals(emoji.repeat(24), TogetherPrefs.sanitizeCustomName(emoji.repeat(30)))
        assertEquals("a".repeat(23), TogetherPrefs.sanitizeCustomName("a".repeat(23) + " bcd"))
    }

    @Test
    fun playerName_customHasNoSuffixAndTheDefaultKeepsIt() {
        assertEquals("Sam's Deck", TogetherPrefs.resolvePlayerName("Sam's Deck", "moto g 2025", "10.42.1.72"))
        assertEquals("moto g 2025 ·72", TogetherPrefs.resolvePlayerName(null, "moto g 2025", "10.42.1.72"))
        assertEquals("moto g 2025", TogetherPrefs.resolvePlayerName(null, "moto g — 2025!", null))
    }
}
