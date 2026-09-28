package com.gamebex.client.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TogetherDaoTest {
    private lateinit var db: LibraryDb

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun listPlayableInFoldersExcludesUnavailableAndDeletedAndOrdersTitleCaseInsensitive(): Unit = runBlocking {
        db.games().upsert(game(1, "zulu"))
        db.games().upsert(game(2, "Alpha"))
        db.games().upsert(game(3, "bravo", available = false))
        db.games().upsert(game(4, "charlie", deletedAtMs = 99))

        val rows = db.games().listPlayableInFolders(listOf("gb"))
        assertEquals(listOf(2L, 1L), rows.map { it.id })
        assertTrue(rows.all { it.available })
        assertTrue(rows.all { it.deletedAtMs == 0L })
    }

    @Test
    fun sourceItemQueriesIgnoreTombstones(): Unit = runBlocking {
        db.games().upsert(game(1, "old", sourceId = "shop", itemKey = "item"))
        db.games().upsert(game(2, "deleted", sourceId = "shop", itemKey = "item", deletedAtMs = 99))
        db.games().upsert(game(3, "unavailable", sourceId = "shop", itemKey = "item", available = false))

        val list = db.games().listPlayableBySourceItem("shop", "item")
        val one = db.games().findPlayableBySourceItem("shop", "item")
        assertEquals(listOf(1L), list.map { it.id })
        assertEquals(1L, one?.id)
        assertFalse(list.any { it.deletedAtMs > 0L })
    }

    private fun game(
        id: Long,
        title: String,
        available: Boolean = true,
        deletedAtMs: Long = 0L,
        sourceId: String? = null,
        itemKey: String? = null,
    ) = GameEntity(
        id = id,
        title = title,
        folder = "gb",
        volumeId = "internal",
        relPath = "$id.zip",
        fileName = "$id.zip",
        sizeBytes = 1,
        mtimeMs = 1,
        ext = "zip",
        available = available,
        deletedAtMs = deletedAtMs,
        source = if (sourceId == null) "USER" else "SOURCE",
        sourceId = sourceId,
        sourceItemKey = itemKey,
    )
}
