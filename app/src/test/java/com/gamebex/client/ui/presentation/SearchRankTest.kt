package com.gamebex.client.ui.presentation

import com.gamebex.client.data.CatalogFixtures.entry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Client-side search ranking: the real game first, hacks last, ties stable. */
class SearchRankTest {
    private fun e(
        id: Int,
        title: String,
        consoleName: String? = null,
        platformId: String? = null,
    ) = entry(itemKey = "k/$id", title = title, consoleName = consoleName, platformId = platformId)

    private fun keyOf(index: Int) = "k/$index"

    private val entries = listOf(
        e(1, "Mario Kart 64 (USA)"),
        e(2, "Super Mario 64 DS (USA)"),
        e(3, "Super Mario 64 (USA)"),
        e(4, "Super Mario 64: Kaizo Hack"),
        e(5, "Super Mario Bros. (USA)"),
    )

    @Test fun exactMatchFirst() {
        val ranked = rankSearchResults("super mario 64", entries)
        assertEquals(keyOf(3), ranked.first().key.itemKey)
    }

    @Test fun prefixBeatsPartial() {
        val ranked = rankSearchResults("super mario 64", entries)
        val ids = ranked.map { it.key.itemKey }
        // Exact, then the DS prefix-sibling, before partial-word hits.
        assert(ids.indexOf(keyOf(3)) < ids.indexOf(keyOf(2)))
        assert(ids.indexOf(keyOf(2)) < ids.indexOf(keyOf(1)))
        assert(ids.indexOf(keyOf(2)) < ids.indexOf(keyOf(5)))
    }

    @Test fun hackDemotedBelowOfficial() {
        val ranked = rankSearchResults("super mario 64", entries)
        val ids = ranked.map { it.key.itemKey }
        assert(ids.indexOf(keyOf(3)) < ids.indexOf(keyOf(4)))
        // Same prefix tier as the DS sibling, but demoted below it.
        assert(ids.indexOf(keyOf(2)) < ids.indexOf(keyOf(4)))
    }

    @Test fun partialTypingFindsGame() {
        val ranked = rankSearchResults("mario kar", entries)
        assertEquals(keyOf(1), ranked.first().key.itemKey)
    }

    @Test fun tiesKeepServerOrder() {
        val tied = listOf(e(10, "Zelda (USA)"), e(11, "Zelda (Europe)"))
        val ranked = rankSearchResults("mario", tied)
        assertEquals(listOf(keyOf(10), keyOf(11)), ranked.map { it.key.itemKey })
    }

    @Test fun blankQueryUnchanged() {
        assertEquals(entries, rankSearchResults("  ", entries))
    }

    @Test fun consoleTokenFloatsOriginalAbovePorts() {
        val ports = listOf(
            e(21, "Super Mario 64", "PlayStation 2", "ps2"),
            e(22, "Super Mario 64", "PSP", "psp"),
            e(23, "Super Mario 64", "Nintendo 64", "n64"),
        )
        val ranked = rankSearchResults("super mario 64", ports)
        assertEquals(keyOf(23), ranked.first().key.itemKey)
    }

    @Test fun consoleAbbreviationBoosts() {
        val ports = listOf(
            e(31, "Super Mario 64", "PlayStation 2", "ps2"),
            e(32, "Super Mario 64", "Nintendo 64", "n64"),
        )
        val ranked = rankSearchResults("super mario n64", ports)
        assertEquals(keyOf(32), ranked.first().key.itemKey)
    }

    @Test fun platformIdAloneResolvesConsole() {
        // No display name from the source: the platform folder still scopes.
        val ports = listOf(
            e(41, "Super Mario 64", null, "ps2"),
            e(42, "Super Mario 64", null, "n64"),
        )
        val ranked = rankSearchResults("super mario 64", ports)
        assertEquals(keyOf(42), ranked.first().key.itemKey)
    }
}
