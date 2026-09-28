package com.gamebex.client.data.local

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A destructive 1→2 fallback would wipe play counts, favorites and source refs, so the
 * migration is checked both as a raw schema step and through [LibraryDb.builder].
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
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
    fun migration1To2KeepsUserStateAndDefaultsTheTombstone() {
        val v1 = helper.createDatabase(TEST_DB, 1)
        v1.insert("games", android.database.sqlite.SQLiteDatabase.CONFLICT_NONE, crazyTaxiRow())
        v1.close()

        val v2 = helper.runMigrationsAndValidate(TEST_DB, 2, true, LibraryDb.MIGRATION_1_2)
        v2.query(
            "SELECT title, playCount, favorite, lastPlayedMs, sourceId, " +
                "sourceItemKey, sourceRefJson, deletedAtMs FROM games",
        ).use { c ->
            assertTrue("row survived the migration", c.moveToFirst())
            assertEquals("Crazy Taxi", c.getString(0))
            assertEquals(7, c.getInt(1))
            assertEquals(1, c.getInt(2))
            assertEquals(1_700_000_000_000L, c.getLong(3))
            assertEquals("roms-i-own", c.getString(4))
            assertEquals("gc/crazy-taxi", c.getString(5))
            assertTrue(c.getString(6).contains("gc/crazy-taxi"))
            assertEquals("new column defaults to 0", 0L, c.getLong(7))
            assertFalse(c.moveToNext())
        }
        v2.close()
    }

    /** A migration missing from [LibraryDb.builder] would silently drop every table here instead of failing. */
    @Test
    fun theBuilderMigratesAV1FileInsteadOfRecreatingIt() = runBlocking {
        ctx.deleteDatabase(TEST_DB)
        val v1 = helper.createDatabase(TEST_DB, 1)
        v1.insert("games", android.database.sqlite.SQLiteDatabase.CONFLICT_NONE, crazyTaxiRow())
        v1.close()

        val room = LibraryDb.builder(ctx, TEST_DB).allowMainThreadQueries().build()
        try {
            val rows = room.games().observeAll().first()
            assertEquals("the owner's rows survive the upgrade", 1, rows.size)
            val row = rows.first()
            assertEquals(7, row.playCount)
            assertEquals(1_700_000_000_000L, row.lastPlayedMs)
            assertTrue(row.favorite)
            assertEquals("roms-i-own", row.sourceId)
            assertEquals(0L, row.deletedAtMs)
            assertEquals(2, room.openHelper.writableDatabase.version)
        } finally {
            room.close()
        }
    }

    @Test
    fun aTombstoneSurvivesReopenAndIngestRevivesIt() = runBlocking {
        helper.createDatabase(TEST_DB, 2).close()
        val room = LibraryDb.builder(ctx, TEST_DB).allowMainThreadQueries().build()
        try {
            val id = room.games().upsert(
                GameEntity(
                    title = "Crazy Taxi", folder = "gc", volumeId = "internal",
                    relPath = "gc/crazytaxi.iso", fileName = "crazytaxi.iso",
                    sizeBytes = 10, mtimeMs = 20, ext = "iso",
                    source = "SOURCE", sourceId = "roms-i-own", sourceItemKey = "gc/crazy-taxi",
                    sourceRefJson = """{"sourceId":"roms-i-own","itemKey":"gc/crazy-taxi"}""",
                ),
            )
            room.games().recordPlay(id, 999L)
            room.games().setFavorite(id, true)

            room.games().markDeleted(id, 1234L)
            val gone = room.games().findById(id)!!
            assertFalse(gone.available)
            assertEquals(1234L, gone.deletedAtMs)
            assertEquals(1, gone.playCount)
            assertTrue(gone.favorite)

            room.games().setIngested(
                id, "Crazy Taxi", 10, 21L, null, null, "SOURCE",
                "roms-i-own", "gc/crazy-taxi", """{"sourceId":"roms-i-own"}""", null,
            )
            val back = room.games().findById(id)!!
            assertTrue(back.available)
            assertEquals(0L, back.deletedAtMs)
            assertEquals("play count survived the round trip", 1, back.playCount)
            assertEquals(999L, back.lastPlayedMs)
            assertTrue("favorite survived the round trip", back.favorite)
        } finally {
            room.close()
        }
    }

    /** Every v1 column is NOT NULL (Room always writes them all). */
    private fun crazyTaxiRow() = ContentValues().apply {
        put("title", "Crazy Taxi")
        put("folder", "gc")
        put("volumeId", "internal")
        put("relPath", "gc/crazytaxi.iso")
        put("fileName", "crazytaxi.iso")
        put("sizeBytes", 1_288_490_188L)
        put("mtimeMs", 42L)
        put("ext", "iso")
        put("source", "SOURCE")
        put("favorite", 1)
        put("playCount", 7)
        put("lastPlayedMs", 1_700_000_000_000L)
        put("addedAtMs", 1_600_000_000_000L)
        put("available", 1)
        put("artSource", "NONE")
        put("ssGameId", 0L)
        put("prepState", "NONE")
        put("scanGen", 1L)
        put("sourceId", "roms-i-own")
        put("sourceItemKey", "gc/crazy-taxi")
        put("sourceRefJson", """{"sourceId":"roms-i-own","itemKey":"gc/crazy-taxi"}""")
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
