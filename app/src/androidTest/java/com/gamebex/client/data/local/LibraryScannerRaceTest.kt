package com.gamebex.client.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.source.DownloadProvenance
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The in-memory DB leaves the real library untouched; the scratch ROM is deleted after. */
@RunWith(AndroidJUnit4::class)
class LibraryScannerRaceTest {
    @Test
    fun ingestSurvivesConcurrentRescan() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(RomStore.internalRoot(), "gb").apply { mkdirs() }
        val rom = File(dir, "__racetest.gb")
        rom.writeBytes(ByteArray(256))
        try {
            val db = Room.inMemoryDatabaseBuilder(ctx, LibraryDb::class.java).build()
            try {
                repeat(10) {
                    runBlocking {
                        val scan = async(Dispatchers.IO) { LibraryScanner.rescan(ctx, db) }
                        val ingest = async(Dispatchers.IO) {
                            LibraryScanner.ingestDownload(
                                ctx, db, rom, "Race", null, null, null,
                                DownloadProvenance(null, null),
                            )
                        }
                        scan.await()
                        ingest.await()
                    }
                    val row = runBlocking { db.games().findByPath("internal", "gb/__racetest.gb") }
                    assertNotNull("ingested row survived race, round $it", row)
                    assertTrue("ingested row stayed available, round $it", row!!.available)
                }
            } finally {
                db.close()
            }
        } finally {
            runCatching { rom.delete() }
            runCatching { Sidecars.delete(rom) }
        }
    }
}
