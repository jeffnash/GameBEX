package com.gamebex.client.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The scan path takes no source dependency, so no source request can happen here. */
@RunWith(AndroidJUnit4::class)
class ScannerSidecarRecoveryTest {
    private fun sidecarJson(
        title: String,
        sourceId: String,
        itemKey: String,
        coverUrl: String = "https://art.example.com/box.jpg",
    ): String = """
        {
          "title": "$title",
          "coverUrl": "$coverUrl",
          "source": "SOURCE",
          "schemaVersion": 2,
          "sourceId": "$sourceId",
          "sourceRef": {
            "sourceId": "$sourceId",
            "itemKey": "$itemKey",
            "pageUrl": "https://catalog.example.com/item",
            "data": {}
          }
        }
    """.trimIndent()

    @Test
    fun sidecarOnlyRecoveryKeepsProvenanceWithoutPlugins() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(RomStore.internalRoot(), "gb").apply { mkdirs() }
        val rom = File(dir, "__siderecover.gb")
        rom.writeBytes(ByteArray(256))
        File(dir, "${rom.name}.gamebex.json")
            .writeText(sidecarJson("Sidecar Title", "shop-a", "gb/sidecar"))
        val db = Room.inMemoryDatabaseBuilder(ctx, LibraryDb::class.java).build()
        try {
            val result = runBlocking { LibraryScanner.rescan(ctx, db) }
            assertTrue(result.added >= 1)
            val row = runBlocking { db.games().findByPath("internal", "gb/__siderecover.gb") }
            assertNotNull(row)
            assertEquals("Sidecar Title", row!!.title)
            assertEquals("https://art.example.com/box.jpg", row.coverUrl)
            assertEquals("shop-a", row.sourceId)
            assertEquals("gb/sidecar", row.sourceItemKey)
            assertTrue(row.available)
            val bySource = runBlocking {
                db.games().observeBySourceItem("shop-a", "gb/sidecar").first()
            }
            assertEquals(row.id, bySource!!.id)
        } finally {
            db.close()
            runCatching { rom.delete() }
            runCatching { Sidecars.delete(rom) }
        }
    }

    @Test
    fun equalItemIdsAcrossProvidersStayQualified() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(RomStore.internalRoot(), "gb").apply { mkdirs() }
        val romA = File(dir, "__twoprovider_a.gb")
        val romB = File(dir, "__twoprovider_b.gb")
        romA.writeBytes(ByteArray(128))
        romB.writeBytes(ByteArray(129))
        File(dir, "${romA.name}.gamebex.json")
            .writeText(sidecarJson("Provider A", "shop-a", "gb/shared"))
        File(dir, "${romB.name}.gamebex.json")
            .writeText(sidecarJson("Provider B", "shop-b", "gb/shared"))
        val db = Room.inMemoryDatabaseBuilder(ctx, LibraryDb::class.java).build()
        try {
            runBlocking { LibraryScanner.rescan(ctx, db) }
            val rowA = runBlocking { db.games().findByPath("internal", "gb/__twoprovider_a.gb") }
            val rowB = runBlocking { db.games().findByPath("internal", "gb/__twoprovider_b.gb") }
            assertNotNull(rowA)
            assertNotNull(rowB)
            assertEquals("shop-a", rowA!!.sourceId)
            assertEquals("shop-b", rowB!!.sourceId)
            val matchA = runBlocking {
                db.games().observeBySourceItem("shop-a", "gb/shared").first()
            }
            val matchB = runBlocking {
                db.games().observeBySourceItem("shop-b", "gb/shared").first()
            }
            assertEquals(rowA.id, matchA!!.id)
            assertEquals(rowB.id, matchB!!.id)
        } finally {
            db.close()
            runCatching { romA.delete() }
            runCatching { Sidecars.delete(romA) }
            runCatching { romB.delete() }
            runCatching { Sidecars.delete(romB) }
        }
    }
}
