package com.gamebex.client.ui

import com.gamebex.client.data.CatalogFixtures
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.ui.presentation.CatalogMatch
import com.gamebex.source.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Console Search pure policy: recent cap, B routing, states, availability, keys. */
class ConsoleSearchLogicTest {
    private fun ownedMatch(): CatalogMatch = CatalogMatch.Owned(
        GameEntity(
            id = 1, title = "Tetris", folder = "gb", volumeId = "v",
            relPath = "gb/tetris.gb", fileName = "tetris.gb", sizeBytes = 1, mtimeMs = 0,
            ext = "gb",
        ),
    )

    private fun deletedMatch(): CatalogMatch = CatalogMatch.DownloadAgain(
        GameEntity(
            id = 2, title = "Tetris", folder = "gb", volumeId = "v",
            relPath = "gb/tetris.gb", fileName = "tetris.gb", sizeBytes = 1, mtimeMs = 0,
            ext = "gb", available = false, deletedAtMs = 1_000L,
        ),
    )

    @Test fun recentCappedAtSix() {
        val eight = (1..8).map { "q$it" }
        assertEquals((1..6).map { "q$it" }, consoleRecent(eight))
        assertEquals(listOf("a"), consoleRecent(listOf("a")))
    }

    @Test fun backFromListOrPreviewReturnsToField() {
        assertEquals(SearchBack.TO_FIELD, resolveSearchBack(SearchZone.LIST, false, false))
        assertEquals(SearchBack.TO_FIELD, resolveSearchBack(SearchZone.PREVIEW, false, true))
    }

    @Test fun backFromSystemChipsReturnsToField() {
        assertEquals(SearchBack.TO_FIELD, resolveSearchBack(SearchZone.CHIPS, false, false))
    }

    @Test fun systemFilterKeepsMatchesAndUnknownSystemsInOrder() {
        val gb = CatalogFixtures.entry(itemKey = "gb/tetris", platformId = "gb")
        val gba = CatalogFixtures.entry(itemKey = "gba/tetris", platformId = "gba")
        val unknown = CatalogFixtures.entry(itemKey = "x/tetris", platformId = null)
        val page = listOf(gba, gb, unknown)
        assertEquals(page, filterSearchBySystem(page, null))
        assertEquals(listOf(gb, unknown), filterSearchBySystem(page, "gb"))
        assertEquals(listOf(unknown), filterSearchBySystem(page, "n64"))
    }

    @Test fun backOnChangedEditClosesEditFirst() {
        assertEquals(SearchBack.STOP_EDITING, resolveSearchBack(SearchZone.FIELD, true, true))
    }

    @Test fun backOnUnchangedFieldLeaves() {
        assertEquals(SearchBack.LEAVE, resolveSearchBack(SearchZone.FIELD, true, false))
        assertEquals(SearchBack.LEAVE, resolveSearchBack(SearchZone.FIELD, false, false))
    }

    @Test fun listCountPerState() {
        assertEquals(6, searchListCount(SearchState.IDLE, 0, 8))
        assertEquals(12, searchListCount(SearchState.RESULTS, 12, 3))
        assertEquals(1, searchListCount(SearchState.FAILURE, 0, 3))
        assertEquals(0, searchListCount(SearchState.LOADING, 5, 3))
        assertEquals(0, searchListCount(SearchState.EMPTY, 0, 3))
    }

    @Test fun emptyWaitsForQueryToSettle() {
        assertEquals(SearchState.LOADING, consoleSearchState(SearchState.EMPTY, queryStable = false))
        assertEquals(SearchState.EMPTY, consoleSearchState(SearchState.EMPTY, queryStable = true))
        assertEquals(SearchState.RESULTS, consoleSearchState(SearchState.RESULTS, queryStable = false))
    }

    @Test fun libraryOwnershipWinsOverDownloadStatus() {
        assertEquals(SearchAvailability.IN_LIBRARY, searchAvailability(ownedMatch(), DownloadEngine.ST_FAILED))
    }

    @Test fun downloadStatusesMap() {
        assertEquals(SearchAvailability.DOWNLOADING, searchAvailability(null, DownloadEngine.ST_ACTIVE))
        assertEquals(SearchAvailability.QUEUED, searchAvailability(null, DownloadEngine.ST_QUEUED))
        assertEquals(SearchAvailability.PAUSED, searchAvailability(null, DownloadEngine.ST_PARKED_SD))
        assertEquals(SearchAvailability.NEEDS_ATTENTION, searchAvailability(null, DownloadEngine.ST_NEEDS_ASSIST))
        assertEquals(SearchAvailability.FAILED, searchAvailability(null, DownloadEngine.ST_FAILED))
        // Done but not (yet) matched in the library: still just available.
        assertEquals(SearchAvailability.AVAILABLE, searchAvailability(null, DownloadEngine.ST_DONE))
        assertEquals(SearchAvailability.AVAILABLE, searchAvailability(null, null))
        assertNull(searchAvailabilityTag(SearchAvailability.AVAILABLE))
    }

    @Test fun tombstoneNeverReadsAsOwned() {
        assertEquals(SearchAvailability.DELETED, searchAvailability(deletedMatch(), null))
        assertEquals(SearchAvailability.DELETED, searchAvailability(deletedMatch(), DownloadEngine.ST_DONE))
        assertEquals("Download again", searchAvailabilityLabel(SearchAvailability.DELETED))
        assertEquals("REMOVED", searchAvailabilityTag(SearchAvailability.DELETED))
    }

    @Test fun liveRedownloadBeatsTheTombstoneState() {
        // While the game re-downloads, the live state is the useful one.
        assertEquals(SearchAvailability.QUEUED, searchAvailability(deletedMatch(), DownloadEngine.ST_QUEUED))
        assertEquals(SearchAvailability.FAILED, searchAvailability(deletedMatch(), DownloadEngine.ST_FAILED))
    }

    @Test fun downloadingLabelShowsPercentOnlyWhenDeterminate() {
        assertEquals("Downloading 50%", searchAvailabilityLabel(SearchAvailability.DOWNLOADING, 5, 10))
        assertEquals("Downloading", searchAvailabilityLabel(SearchAvailability.DOWNLOADING, 5, 0))
    }

    @Test fun latestDownloadPicksNewestForSameEntryOnly() {
        fun rec(id: Long, src: String, key: String, at: Long, status: String) = DownloadRecord(
            id = id, pageUrl = "p", title = "t", destRelPath = "g.zip", volumeId = "internal",
            bytes = 0, status = status, updatedAtMs = at, sourceId = src, sourceItemKey = key,
        )
        val records = listOf(
            rec(1, "acme", "gb/tetris", 10, DownloadEngine.ST_FAILED),
            rec(2, "acme", "gb/tetris", 20, DownloadEngine.ST_ACTIVE),
            rec(3, "other", "gb/tetris", 30, DownloadEngine.ST_QUEUED),
        )
        assertEquals(2L, latestDownloadFor(records, SourceKey("acme", "gb/tetris"))?.id)
        assertNull(latestDownloadFor(records, SourceKey("acme", "gb/zelda")))
    }

    @Test fun resultKeysAreUniqueEvenForDuplicates() {
        val a = CatalogFixtures.entry(itemKey = "gb/tetris")
        val b = CatalogFixtures.entry(itemKey = "gb/zelda", title = "Zelda")
        val keys = searchResultKeys(listOf(a, b, a))
        assertEquals(3, keys.toSet().size)
        assertEquals(keys[0] + "#1", keys[2])
    }
}
