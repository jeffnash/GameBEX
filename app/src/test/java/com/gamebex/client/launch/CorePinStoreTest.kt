package com.gamebex.client.launch

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TOFU pin store and hasher behind core verification. */
class CorePinStoreTest {
    private fun tmpDir(): File = Files.createTempDirectory("pins").toFile()

    @Test fun roundTrip() {
        val dir = tmpDir()
        val store = CorePinStore.inDir(dir)
        assertNull(store.get("snes9x"))
        store.put("snes9x", CorePin("zip".repeat(16), "so".repeat(32)))
        assertEquals("zip".repeat(16), store.get("snes9x")?.zip)
        assertEquals("so".repeat(32), store.get("snes9x")?.so)
        // Second instance sees the same file.
        assertEquals("zip".repeat(16), CorePinStore.inDir(dir).get("snes9x")?.zip)
    }

    @Test fun corruptStorePinsNothing() {
        val dir = tmpDir()
        File(dir, "core_pins.json").writeText("{not json")
        val store = CorePinStore.inDir(dir)
        assertNull(store.get("snes9x"))
        assertEquals(emptyMap<String, CorePin>(), store.load())
        // Re-pin works over corruption.
        store.put("mgba", CorePin("a", "b"))
        assertEquals("a", store.get("mgba")?.zip)
    }

    @Test fun concurrentPutsAcrossInstancesKeepAllPins() {
        val dir = tmpDir()
        val a = CorePinStore.inDir(dir)
        val b = CorePinStore.inDir(dir)
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(25) { i ->
                    (if ((t + i) % 2 == 0) a else b)
                        .put("core$t-$i", CorePin("z$t-$i", "s$t-$i"))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }
        assertTrue(threads.all { !it.isAlive })
        val all = CorePinStore.inDir(dir).load()
        assertEquals(200, all.size)
        assertEquals(CorePin("z3-7", "s3-7"), all["core3-7"])
    }

    @Test fun sha256Vector() {
        val f = File(tmpDir(), "v.bin")
        f.writeBytes("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RetroCoreFetcher.sha256Hex(f),
        )
        assertNull(RetroCoreFetcher.sha256Hex(File(tmpDir(), "missing.bin")))
    }
}
