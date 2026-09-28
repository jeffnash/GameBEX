package com.gamebex.client.ui.presentation

import com.gamebex.client.data.CatalogFixtures.entry
import com.gamebex.client.data.local.GameEntity
import com.gamebex.source.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catalog ↔ library identity: Play/Get honesty on Discover. */
class CatalogMatchTest {
    private fun game(
        id: Long,
        title: String,
        folder: String,
        sourceId: String? = null,
        itemKey: String? = null,
        available: Boolean = true,
        deletedAtMs: Long = 0,
    ) = GameEntity(
        id = id, title = title, folder = folder, volumeId = "v",
        relPath = "r$id", fileName = "f", sizeBytes = 1, mtimeMs = 0,
        ext = "zip", available = available, deletedAtMs = deletedAtMs,
        sourceId = sourceId, sourceItemKey = itemKey,
    )

    /** The owned row a match resolved to (null = not owned). */
    private fun ownedGame(map: Map<SourceKey, CatalogMatch>, key: SourceKey): GameEntity? =
        (map[key] as? CatalogMatch.Owned)?.game

    @Test fun sourceKeyHitWinsRegardlessOfTitle() {
        val g = game(1, "Totally Different Name", "gb", sourceId = "acme", itemKey = "gb/tetris")
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        assertEquals(g, ownedGame(matchCatalog(listOf(e), listOf(g), "gb"), e.key))
    }

    @Test fun sameItemKeyNeverOwnsAnotherSource() {
        val g = game(2, "Whatever", "nds", sourceId = "acme", itemKey = "nds/nsmb")
        val e = entry(sourceId = "other", itemKey = "nds/nsmb", title = "NSMB", platformId = "nds")
        assertTrue(matchCatalog(listOf(e), listOf(g), "nds").isEmpty())
    }

    @Test fun titleFolderHitInConsoleFeed() {
        // USER scan: filename carries no id; normalized title + feed folder.
        val g = game(3, "Tetris (Japan) (En)", "gb")
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        assertEquals(g, ownedGame(matchCatalog(listOf(e), listOf(g), "gb"), e.key))
    }

    @Test fun titleFolderHitInMixedFeedViaPlatformId() {
        val g = game(4, "Dr Mario", "gb")
        val e = entry(itemKey = "gb/drmario", title = "Dr. Mario", platformId = "gb")
        assertEquals(g, ownedGame(matchCatalog(listOf(e), listOf(g), null), e.key))
    }

    @Test fun providerRowNeverTitleMatches() {
        // A record downloaded from another plugin is never an exact owned variant.
        val g = game(5, "Tetris", "gb", sourceId = "other", itemKey = "gb/tetris-other")
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        assertTrue(matchCatalog(listOf(e), listOf(g), "gb").isEmpty())
    }

    @Test fun wrongFolderIsNegative() {
        // Same title on another console must NOT match (no wrong boot).
        val g = game(5, "Tetris", "nes")
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        assertTrue(matchCatalog(listOf(e), listOf(g), "gb").isEmpty())
    }

    @Test fun unknownPlatformInMixedFeedIsNegative() {
        val g = game(6, "Tetris", "gb")
        val e = entry(itemKey = "xx/tetris", title = "Tetris", platformId = "watara", consoleName = "Watara Supervision")
        assertTrue(matchCatalog(listOf(e), listOf(g), null).isEmpty())
    }

    @Test fun emptyInputsMatchNothing() {
        val g = game(7, "Tetris", "gb")
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        assertTrue(matchCatalog(emptyList(), listOf(g), "gb").isEmpty())
        assertTrue(matchCatalog(listOf(e), emptyList(), "gb").isEmpty())
    }

    @Test fun onlyMatchingEntriesMap() {
        val g = game(8, "Alleyway", "gb")
        val owned = entry(itemKey = "gb/alleyway", title = "Alleyway")
        val other = entry(itemKey = "gb/zelda", title = "Zelda")
        val m = matchCatalog(listOf(owned, other), listOf(g), "gb")
        assertEquals(1, m.size)
        assertEquals(g, ownedGame(m, owned.key))
    }

    @Test fun platformIdBeatsDisplayName() {
        val gb = game(20, "Tetris", "gb")
        val nes = game(21, "Tetris", "nes")
        val e = entry(itemKey = "gb/tetris", title = "Tetris", platformId = "gb", consoleName = "NES")
        assertEquals(gb, ownedGame(matchCatalog(listOf(e), listOf(gb, nes), null), e.key))
    }

    @Test fun ambiguousTitleOnlyIsRejected() {
        val a = game(23, "Tetris", "gb")
        val b = game(24, "Tetris (World)", "gb")
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        assertTrue(matchCatalog(listOf(e), listOf(a, b), "gb").isEmpty())
    }

    @Test fun unavailableTitleOnlyIsRejected() {
        val g = game(25, "Tetris", "gb", available = false)
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        assertTrue(matchCatalog(listOf(e), listOf(g), "gb").isEmpty())
    }

    @Test fun exactIdentityPrefersAvailableRow() {
        val gone = game(26, "Tetris", "gb", sourceId = "acme", itemKey = "gb/tetris", available = false)
        val here = game(27, "Tetris", "gb", sourceId = "acme", itemKey = "gb/tetris", available = true)
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        assertEquals(here, ownedGame(matchCatalog(listOf(e), listOf(gone, here), "gb"), e.key))
    }

    @Test fun exactIdentityPrefersNewestOnTie() {
        val old = game(26, "Tetris", "gb", sourceId = "acme", itemKey = "gb/tetris")
        val new = game(27, "Tetris", "gb", sourceId = "acme", itemKey = "gb/tetris")
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        assertEquals(new, ownedGame(matchCatalog(listOf(e), listOf(new, old), "gb"), e.key))
    }

    @Test fun deletedRowMapsToDownloadAgainNeverOwned() {
        // A on a deleted game must not play a missing file.
        val gone = game(
            30, "Tetris", "gb", sourceId = "acme", itemKey = "gb/tetris",
            available = false, deletedAtMs = 1_000L,
        )
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        val m = matchCatalog(listOf(e), listOf(gone), "gb")
        assertEquals(CatalogMatch.DownloadAgain(gone), m[e.key])
    }

    @Test fun deletedRowNeverWinsOverAnAvailableRow() {
        val gone = game(
            31, "Tetris", "gb", sourceId = "acme", itemKey = "gb/tetris",
            available = false, deletedAtMs = 1_000L,
        )
        val here = game(32, "Tetris", "gb", sourceId = "acme", itemKey = "gb/tetris")
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        assertEquals(here, ownedGame(matchCatalog(listOf(e), listOf(gone, here), "gb"), e.key))
    }

    @Test fun deletedRowIsNotAnOwnedFallback() {
        // The pick fallback must skip tombstones: only the deleted row is
        // left, so the entry is a Download again, not an owned game.
        val gone = game(
            33, "Tetris", "gb", sourceId = "acme", itemKey = "gb/tetris",
            available = false, deletedAtMs = 5L,
        )
        val e = entry(itemKey = "gb/tetris", title = "Tetris")
        val m = matchCatalog(listOf(e), listOf(gone), "gb")
        assertEquals(null, ownedGame(m, e.key))
        assertTrue(m[e.key] is CatalogMatch.DownloadAgain)
    }
}
