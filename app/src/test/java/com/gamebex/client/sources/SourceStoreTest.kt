package com.gamebex.client.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SourceStoreTest {
    private fun store(): SourceStore {
        val root = Files.createTempDirectory("sources").toFile()
        root.deleteOnExit()
        return SourceStore(root)
    }

    @Test fun stateRoundTrip() {
        val store = store()
        assertEquals(SourceStateData(), store.loadState())
        val state = SourceStateData(
            selectedId = "a",
            installed = listOf(InstalledSource("a", "0".repeat(64), true, "A")),
        )
        store.saveState(state)
        assertEquals(state, store.loadState())
    }

    @Test fun stagePublishRead() {
        val store = store()
        val bytes = SourceTestFixtures.bytes("shop-a", "1.0.0")
        val rev = com.gamebex.source.sha256Hex(bytes)
        val staged = store.stage(bytes)
        assertTrue(staged.isFile)
        store.publish(staged, "shop-a", rev)
        assertFalse(staged.exists())
        val read = store.readDefinition("shop-a", rev)
        assertTrue(read != null && read.contentEquals(bytes))
        assertEquals(setOf(rev), store.listRevisions("shop-a"))
        assertEquals(setOf("shop-a"), store.listIds())
    }

    @Test fun publishIdenticalTwiceIsIdempotent() {
        val store = store()
        val bytes = SourceTestFixtures.bytes("shop-a", "1.0.0")
        val rev = com.gamebex.source.sha256Hex(bytes)
        store.publish(store.stage(bytes), "shop-a", rev)
        store.publish(store.stage(bytes), "shop-a", rev)
        assertEquals(setOf(rev), store.listRevisions("shop-a"))
    }

    @Test fun unsafePathsRejected() {
        val store = store()
        assertNull(store.readDefinition("../evil", "0".repeat(64)))
        assertNull(store.readDefinition("shop-a", "not-a-hash"))
        try {
            store.publish(store.stage(byteArrayOf(1)), "../evil", "0".repeat(64))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun stagingCleanupAndDelete() {
        val store = store()
        store.stage(byteArrayOf(1))
        store.stage(byteArrayOf(2))
        assertEquals(2, store.cleanStaging())
        assertEquals(0, store.cleanStaging())
        val bytes = SourceTestFixtures.bytes("shop-a", "1.0.0")
        val rev = com.gamebex.source.sha256Hex(bytes)
        store.publish(store.stage(bytes), "shop-a", rev)
        assertTrue(store.deleteDefinition("shop-a", rev))
        assertNull(store.readDefinition("shop-a", rev))
    }
}
