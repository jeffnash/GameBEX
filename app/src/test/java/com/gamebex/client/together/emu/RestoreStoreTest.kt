package com.gamebex.client.together.emu

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreStoreTest {
    @Test fun saveLoadAndClear() {
        val dir = createTempDirectory("restore-store").toFile()
        val store = RestoreStore(dir)
        val record = RestoreRecord(listOf(RestoreFile("/tmp/x", listOf(RestoreKey("Network", "PortOffset", null)))))
        store.save("ppsspp", record)
        assertEquals(record, store.load("ppsspp"))
        store.clear("ppsspp")
        assertNull(store.load("ppsspp"))
    }

    @Test fun corruptRecordIsDeleted() {
        val dir = createTempDirectory("restore-corrupt").toFile()
        File(dir, "restore-ppsspp.json").writeText("not json")
        val store = RestoreStore(dir)
        assertNull(store.load("ppsspp"))
        assertTrue(!File(dir, "restore-ppsspp.json").exists())
    }
}
