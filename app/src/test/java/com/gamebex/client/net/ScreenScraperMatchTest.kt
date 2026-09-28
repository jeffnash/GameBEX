package com.gamebex.client.net

import com.gamebex.client.net.ScreenScraper.Companion.HashStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checksum-first lookup: response classification, fallback, hit acceptance. */
class ScreenScraperMatchTest {
    @Test fun notFoundIsAMissThatFallsBackToNameSearch() {
        val s = ScreenScraper.classifyHashResponse(404, "Erreur : Rom/Iso/Dossier non trouvée !")
        assertEquals(HashStatus.MISS, s)
        assertTrue(ScreenScraper.shouldNameSearch(s))
        assertEquals(HashStatus.MISS, ScreenScraper.classifyHashResponse(400, "Erreur de paramètres"))
        assertEquals(HashStatus.MISS, ScreenScraper.classifyHashResponse(200, "Erreur : Jeu non trouvée !"))
    }

    @Test fun serviceFailuresDoNotFallBack() {
        for ((code, body) in listOf(
            null to null,
            401 to "API closed for non-members",
            430 to "Votre quota de scrape est dépassé",
            200 to "Le logiciel de scrap a été blacklisté",
            200 to "API totalement fermée",
            500 to "oops",
        )) {
            val s = ScreenScraper.classifyHashResponse(code, body)
            assertEquals("$code $body", HashStatus.UNAVAILABLE, s)
            assertFalse(ScreenScraper.shouldNameSearch(s))
        }
    }

    @Test fun jsonBodyParses() {
        assertEquals(HashStatus.PARSE, ScreenScraper.classifyHashResponse(200, "{\"response\":{}}"))
        assertFalse(ScreenScraper.shouldNameSearch(HashStatus.PARSE))
    }

    @Test fun noChecksumMeansNameSearch() {
        assertTrue(ScreenScraper.shouldNameSearch(null))
    }

    @Test fun hitAcceptedOnCrcMatchRegardlessOfTitle() {
        assertTrue(ScreenScraper.acceptHashHit("cbf43926", listOf("12345678", "CBF43926"), 0, 4, 4))
    }

    @Test fun hitRejectedWhenListedCrcsDisagree() {
        // SS fell back to fuzzy name matching: its ROMs aren't ours.
        assertFalse(ScreenScraper.acceptHashHit("CBF43926", listOf("12345678"), 100, 4, 4))
    }

    @Test fun hitWithoutRomCrcsNeedsTitleAgreement() {
        assertTrue(ScreenScraper.acceptHashHit("CBF43926", emptyList(), 60, null, 4))
        assertFalse(ScreenScraper.acceptHashHit("CBF43926", emptyList(), 10, null, 4))
    }

    @Test fun systemMismatchAlwaysRejects() {
        assertFalse(ScreenScraper.acceptHashHit("CBF43926", listOf("CBF43926"), 100, 1, 4))
        assertTrue(ScreenScraper.acceptHashHit("CBF43926", listOf("CBF43926"), 100, 1, null))
    }
}
