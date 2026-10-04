package com.gamebex.client.ui.discover

import com.gamebex.client.data.CatalogFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** System chips: Discover lists every feed, Search lists every searchable platform. */
class SystemChipsTest {
    private fun feed(id: String, name: String, platformId: String?, count: Int) =
        CatalogFixtures.console(id = id, name = name, platformId = platformId, count = count)

    @Test fun discoverStartsWithLatestThenEveryFeedInOrder() {
        val chips = discoverSystemChips(
            listOf(feed("game-boy", "GB Roms", "gb", 10), feed("arcade", "Arcade Classics", null, 5)),
        )
        assertEquals(listOf(null, "game-boy", "arcade"), chips.map { it.id })
        assertEquals(listOf("Latest", "Game Boy · 10", "Arcade Classics · 5"), chips.map { it.label })
    }

    @Test fun discoverListsMoreThanThirtyFeeds() {
        val feeds = (1..45).map { feed("c$it", "Console $it", null, it) }
        val chips = discoverSystemChips(feeds)
        assertEquals(46, chips.size)
        assertEquals("Console 45 · 45", chips.last().label)
    }

    @Test fun unknownPlatformFallsBackToFeedName() {
        val chips = discoverSystemChips(listOf(feed("m", "Mystery Box", "not-a-folder", 2)))
        assertEquals("Mystery Box · 2", chips[1].label)
    }

    @Test fun feedsSharingADisplayNameKeepTheirOwnNames() {
        val chips = discoverSystemChips(
            listOf(
                feed("gb", "Game Boy", "gb", 10),
                feed("gb-hb", "Game Boy Homebrew", "gb", 3),
                feed("gba", "GBA", "gba", 7),
            ),
        )
        assertEquals(
            listOf("Latest", "Game Boy · 10", "Game Boy Homebrew · 3", "Game Boy Advance · 7"),
            chips.map { it.label },
        )
    }

    @Test fun searchMergesFeedsPerPlatformAndSkipsUnmapped() {
        val chips = searchSystemChips(
            listOf(
                feed("gb", "Game Boy", "gb", 10),
                feed("arcade", "Arcade", null, 5),
                feed("gba", "GBA", "gba", 7),
                feed("gb-hb", "Game Boy Homebrew", "gb", 3),
            ),
        )
        assertEquals(listOf(null, "gb", "gba"), chips.map { it.id })
        assertEquals(listOf("All systems", "Game Boy · 13", "Game Boy Advance · 7"), chips.map { it.label })
    }

    @Test fun searchWithoutPlatformsHasOnlyAllSystems() {
        assertEquals(
            listOf(SystemChip(null, "All systems")),
            searchSystemChips(listOf(feed("arcade", "Arcade", null, 5))),
        )
    }

    @Test fun chipIndexFindsTheSelectionElseTheFirstChip() {
        val chips = discoverSystemChips(listOf(feed("game-boy", "Game Boy", "gb", 1), feed("gba", "GBA", "gba", 1)))
        assertEquals(0, systemChipIndex(chips, null))
        assertEquals(2, systemChipIndex(chips, "gba"))
        assertEquals(0, systemChipIndex(chips, "gone"))
    }
}
