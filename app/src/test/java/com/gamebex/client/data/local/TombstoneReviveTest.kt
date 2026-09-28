package com.gamebex.client.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tombstone revive: when a scan sees a deleted game's file again, the row
 * comes back (`deletedAtMs` clears) instead of reading "Download again".
 */
class TombstoneReviveTest {
    private fun tombstone(id: Long) = GameEntity(
        id = id, title = "Tetris", folder = "gb", volumeId = "internal",
        relPath = "gb/tetris$id.gb", fileName = "tetris.gb", sizeBytes = 1, mtimeMs = 1,
        ext = "gb", available = false, deletedAtMs = 1_000L,
        source = "SOURCE", sourceId = "acme", sourceItemKey = "gb/tetris$id",
    )

    @Test
    fun restoredFileRevivesTheTombstone(): Unit = runBlocking {
        val db = FakeLibraryDb(gamesList = mutableListOf(tombstone(1)))
        db.games().setAvailableAll(listOf(1L))
        val row = db.games().findById(1L)!!
        assertTrue(row.available)
        assertEquals(0L, row.deletedAtMs)
    }

    @Test
    fun scanRefreshRevivesOnlyWhenTheFileIsBack(): Unit = runBlocking {
        val db = FakeLibraryDb(gamesList = mutableListOf(tombstone(2)))
        // Still absent: the tombstone must survive the refresh.
        db.games().setScanRefreshed(2L, "Tetris", 10, 20, false, 0)
        assertEquals(1_000L, db.games().findById(2L)!!.deletedAtMs)
        // File restored at the tombstone's path: revive.
        db.games().setScanRefreshed(2L, "Tetris", 10, 20, true, 0)
        val row = db.games().findById(2L)!!
        assertTrue(row.available)
        assertEquals(0L, row.deletedAtMs)
    }

    @Test
    fun tombstonesNeverQueueForArt(): Unit = runBlocking {
        val db = FakeLibraryDb(
            gamesList = mutableListOf(
                tombstone(3),
                tombstone(4).copy(id = 5, relPath = "gb/live.gb", available = true, deletedAtMs = 0),
            ),
        )
        assertEquals(listOf(5L), db.games().idsMissingArt())
    }
}
