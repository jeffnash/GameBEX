package com.gamebex.client.launch

import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.RomStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real installed RomProvider and library DB; the scratch ROM and row are removed afterwards. */
@RunWith(AndroidJUnit4::class)
class RomProviderDeviceTest {
    @Test
    fun servesRowAndRefusesUnknown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(RomStore.internalRoot(), "gb").apply { mkdirs() }
        val rom = File(dir, "__providertest.gb")
        val payload = ByteArray(1024) { (it % 251).toByte() }
        rom.writeBytes(payload)
        val db = LibraryDb.get(ctx)
        val id = runBlocking {
            db.games().upsert(
                GameEntity(
                    title = "__providertest", folder = "gb", volumeId = "internal",
                    relPath = "gb/__providertest.gb", fileName = "__providertest.gb",
                    sizeBytes = payload.size.toLong(), mtimeMs = rom.lastModified(),
                    ext = "gb", available = true,
                ),
            )
        }
        try {
            val cr = ctx.contentResolver
            val uri = RomProvider.uriFor(id)
            cr.query(uri, null, null, null, null)!!.use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(
                    "__providertest.gb",
                    c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)),
                )
                assertEquals(
                    payload.size.toLong(),
                    c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)),
                )
            }
            assertEquals("application/octet-stream", cr.getType(uri))
            cr.openInputStream(uri)!!.use { inp ->
                assertEquals(payload.toList(), inp.readBytes().toList())
            }
            assertNull(cr.query(RomProvider.uriFor(id + 999_999), null, null, null, null))
            assertNull(cr.query(android.net.Uri.parse("content://com.gamebex.client.roms/root"), null, null, null, null))
        } finally {
            runBlocking { db.games().deleteById(id) }
            runCatching { rom.delete() }
        }
    }
}
