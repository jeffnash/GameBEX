package com.gamebex.client.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDbTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LibraryDb::class.java,
    )

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        ctx.deleteDatabase(TEST_DB)
    }

    @Test
    fun exportedSchemaOpensAndRoundTripsAGame(): Unit = runBlocking {
        ctx.deleteDatabase(TEST_DB)
        // Creates the DB from the exported 1.json; Room then validates it
        // against the compiled entities when it opens the same file.
        helper.createDatabase(TEST_DB, 1).close()
        val room = LibraryDb.builder(ctx, TEST_DB).allowMainThreadQueries().build()
        try {
            val id = room.games().upsert(game())
            room.games().setFavorite(id, true)
            room.games().recordPlay(id, 1234L)
            val row = room.games().findById(id)!!
            assertEquals("Emerald", row.title)
            assertEquals("acme", row.sourceId)
            assertEquals("gba/emerald", row.sourceItemKey)
            assertTrue(row.favorite)
            assertEquals(1, row.playCount)
            assertEquals(1234L, row.lastPlayedMs)
            assertEquals(row, room.games().observeBySourceItem("acme", "gba/emerald").first())
        } finally {
            room.close()
        }
    }

    @Test
    fun foreignSchemaVersionIsDroppedNotFatal(): Unit = runBlocking {
        ctx.deleteDatabase(TEST_DB)
        // A higher-version on-disk DB with an unrelated games shape: opening must downgrade destructively.
        val old = object : android.database.sqlite.SQLiteOpenHelper(ctx, TEST_DB, null, 7) {
            override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
                db.execSQL("CREATE TABLE games (id INTEGER PRIMARY KEY, title TEXT, junk TEXT)")
                db.execSQL("INSERT INTO games (id, title, junk) VALUES (1, 'Old', 'x')")
            }

            override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, o: Int, n: Int) = Unit
        }
        old.writableDatabase.close()
        old.close()
        val room = LibraryDb.builder(ctx, TEST_DB).allowMainThreadQueries().build()
        try {
            assertTrue(room.games().observeAll().first().isEmpty())
            val id = room.games().upsert(game())
            assertEquals("Emerald", room.games().findById(id)!!.title)
        } finally {
            room.close()
        }
    }

    @Test
    fun scannerRevivesTombstonesAndArtSkipsThem(): Unit = runBlocking {
        ctx.deleteDatabase(TEST_DB)
        val room = LibraryDb.builder(ctx, TEST_DB).allowMainThreadQueries().build()
        try {
            val id = room.games().upsert(game())
            assertEquals(listOf(id), room.games().idsMissingArt())

            room.games().markDeleted(id, 55L)
            assertEquals(55L, room.games().findById(id)!!.deletedAtMs)
            assertTrue(room.games().idsMissingArt().isEmpty())

            room.games().setAvailableAll(listOf(id))
            var row = room.games().findById(id)!!
            assertTrue(row.available)
            assertEquals(0L, row.deletedAtMs)

            // setScanRefreshed with the file present revives the row...
            room.games().markDeleted(id, 66L)
            room.games().setScanRefreshed(id, "Emerald", 10, 20, true, 0)
            row = room.games().findById(id)!!
            assertTrue(row.available)
            assertEquals(0L, row.deletedAtMs)

            // ...while a refresh with the file still absent keeps the tombstone.
            room.games().markDeleted(id, 77L)
            room.games().setScanRefreshed(id, "Emerald", 10, 20, false, 0)
            assertEquals(77L, room.games().findById(id)!!.deletedAtMs)
        } finally {
            room.close()
        }
    }

    private fun game() = GameEntity(
        title = "Emerald", folder = "gba", volumeId = "internal",
        relPath = "gba/emerald.zip", fileName = "emerald.zip",
        sizeBytes = 10, mtimeMs = 20, ext = "zip",
        source = "SOURCE", sourceId = "acme", sourceItemKey = "gba/emerald",
    )

    private companion object {
        const val TEST_DB = "library-db-test"
    }
}
