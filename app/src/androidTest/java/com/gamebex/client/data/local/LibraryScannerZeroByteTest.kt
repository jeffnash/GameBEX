package com.gamebex.client.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.source.DownloadProvenance
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScannerZeroByteTest {
    private fun scanRoot(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "gbx_zero_scan",
    ).apply {
        deleteRecursively()
        File(this, "gb").mkdirs()
    }

    private fun inMemoryDb(): LibraryDb = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        LibraryDb::class.java,
    ).build()

    @Test
    fun zeroByteFileIsNotImportedAsAGame() {
        val root = scanRoot()
        File(root, "gb/__zero.gb").writeBytes(ByteArray(0))
        val db = inMemoryDb()
        try {
            val (added, updated, _) = runBlocking { LibraryScanner.scanVolume(db, "zero", root) }
            assertEquals("0-byte file is not counted as added", 0, added)
            assertEquals("0-byte file is not counted as updated", 0, updated)
            assertNull(
                "0-byte file has no game row",
                runBlocking { db.games().findByPath("zero", "gb/__zero.gb") },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun zeroByteDownloadIsNotIngestedAsAGame() {
        // ingestDownload only attaches files under GameBEX-owned roots, so the scratch file
        // goes where a real download lands.
        val dir = File(RomStore.internalRoot(), "gb").apply { mkdirs() }
        val rom = File(dir, "__zerodl.gb")
        rom.writeBytes(ByteArray(0))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = inMemoryDb()
        try {
            val id = runBlocking {
                LibraryScanner.ingestDownload(
                    ctx, db, rom, "Zero", null, null, null,
                    DownloadProvenance(null, null),
                )
            }
            assertNull("0-byte download is not ingested", id)
            assertNull(
                "0-byte download has no game row",
                runBlocking { db.games().findByPath("internal", "gb/__zerodl.gb") },
            )
        } finally {
            db.close()
            runCatching { rom.delete() }
            runCatching { Sidecars.delete(rom) }
        }
    }

    @Test
    fun nonEmptyFileNextToAZeroByteOneStillImportsAndSurvivesRescan() {
        val root = scanRoot()
        File(root, "gb/__zero.gb").writeBytes(ByteArray(0))
        val rom = File(root, "gb/__one.gb").apply { writeBytes(ByteArray(4)) }
        val db = inMemoryDb()
        try {
            val (added1, _, _) = runBlocking { LibraryScanner.scanVolume(db, "zero", root) }
            assertEquals("only the non-empty neighbour imports", 1, added1)
            val row = runBlocking { db.games().findByPath("zero", "gb/__one.gb") }
            assertNotNull("non-empty neighbour has a game row", row)

            val (added2, updated2, unavailable2) =
                runBlocking { LibraryScanner.scanVolume(db, "zero", root) }
            assertEquals("rescan adds nothing", 0, added2)
            assertEquals("unchanged neighbour is not re-updated", 0, updated2)
            assertEquals("no row goes unavailable", 0, unavailable2)
            assertNotNull(
                "non-empty neighbour survives the rescan beside a 0-byte file",
                runBlocking { db.games().findByPath("zero", "gb/__one.gb") },
            )
            assertEquals(4L, runBlocking { db.games().findByPath("zero", "gb/__one.gb") }!!.sizeBytes)
            assertNull(runBlocking { db.games().findByPath("zero", "gb/__zero.gb") })
            rom.delete()
        } finally {
            db.close()
        }
    }
}
