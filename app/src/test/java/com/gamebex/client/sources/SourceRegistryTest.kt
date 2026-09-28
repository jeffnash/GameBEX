package com.gamebex.client.sources

import com.gamebex.source.EvaluatedRequest
import com.gamebex.source.NetworkPurpose
import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import com.gamebex.source.sha256Hex
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SourceRegistryTest {
    private class Hook : SourceEngineHook {
        val unavailable = ArrayList<String>()
        var pins: Set<RevisionPin> = emptySet()
        override suspend fun sourceUnavailable(id: String) {
            unavailable.add(id)
        }
        override suspend fun activeRevisionPins(): Set<RevisionPin> = pins
    }

    private fun registry(hook: Hook = Hook()): Triple<File, SourceStore, SourceRegistry> {
        val root = Files.createTempDirectory("registry").toFile()
        val store = SourceStore(root)
        val registry = SourceRegistry(store)
        registry.engineHook = hook
        return Triple(root, store, registry)
    }

    private suspend fun expectUnavailable(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: SourceException) {
            assertEquals(SourceFailure.UNAVAILABLE, e.failure)
            return
        }
        fail("expected UNAVAILABLE")
    }

    @Test fun initializeEmpty() = runTest {
        val (_, _, registry) = registry()
        assertFalse(registry.ready.value)
        registry.initialize()
        registry.initialize()
        assertTrue(registry.ready.value)
        assertNull(registry.selected.value)
        assertTrue(registry.list().value.isEmpty())
    }

    @Test fun installFirstBecomesSelected() = runTest {
        val (_, _, registry) = registry()
        registry.initialize()
        val bytes = SourceTestFixtures.bytes("shop-a", "1.0.0")
        val preview = registry.preview(bytes)
        assertEquals(sha256Hex(bytes), preview.revision)
        assertNull(preview.replacesRevision)
        registry.install(bytes, null)
        val selected = registry.selected.value!!
        assertEquals("shop-a", selected.id)
        assertEquals(preview.revision, selected.revision)
        assertEquals(1, registry.list().value.size)
        assertTrue(registry.list().value[0].enabled)
    }

    @Test fun duplicateInstallIsNoop() = runTest {
        val (_, _, registry) = registry()
        registry.initialize()
        val bytes = SourceTestFixtures.bytes("shop-a", "1.0.0")
        registry.install(bytes, null)
        val first = registry.selected.value!!
        registry.install(bytes, first.revision)
        assertEquals(first, registry.selected.value)
        assertEquals(1, registry.list().value.size)
    }

    @Test fun replaceComparesAndSets() = runTest {
        val (_, _, registry) = registry()
        registry.initialize()
        val rev1 = SourceTestFixtures.bytes("shop-a", "1.0.0")
        val rev2 = SourceTestFixtures.bytes("shop-a", "2.0.0")
        registry.install(rev1, null)
        val preview = registry.preview(rev2)
        assertEquals(sha256Hex(rev1), preview.replacesRevision)
        assertTrue(preview.addedOrigins.isEmpty())
        registry.install(rev2, preview.replacesRevision)
        assertEquals(sha256Hex(rev2), registry.selected.value!!.revision)
        // Stale preview against the old revision is rejected; state keeps rev2.
        try {
            registry.install(rev1, sha256Hex(rev1))
            fail("expected stale preview")
        } catch (_: StaleSourcePreviewException) {
        }
        assertEquals(sha256Hex(rev2), registry.selected.value!!.revision)
    }

    @Test fun concurrentReplaceRacesSafely() = runTest {
        val (_, _, registry) = registry()
        registry.initialize()
        val rev1 = SourceTestFixtures.bytes("shop-a", "1.0.0")
        registry.install(rev1, null)
        val rev2 = SourceTestFixtures.bytes("shop-a", "2.0.0")
        val rev3 = SourceTestFixtures.bytes("shop-a", "3.0.0")
        val expected = sha256Hex(rev1)
        val results = listOf(
            async {
                runCatching { registry.install(rev2, expected) }.exceptionOrNull()
            },
            async {
                runCatching { registry.install(rev3, expected) }.exceptionOrNull()
            },
        ).map { it.await() }
        assertEquals(1, results.count { it == null })
        assertEquals(1, results.count { it is StaleSourcePreviewException })
        assertEquals(1, registry.list().value.size)
    }

    @Test fun invalidDefinitionChangesNothing() = runTest {
        val (_, _, registry) = registry()
        registry.initialize()
        val good = SourceTestFixtures.bytes("shop-a", "1.0.0")
        registry.install(good, null)
        val bad = SourceTestFixtures.definition("shop-a", "9.9.9").copy(schemaVersion = 2)
        val badBytes = kotlinx.serialization.json.Json.encodeToString(
            com.gamebex.source.SourceDefinition.serializer(), bad,
        ).toByteArray()
        try {
            registry.preview(badBytes)
            fail("expected invalid")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.INVALID_DEFINITION, e.failure)
        }
        try {
            registry.install(badBytes, sha256Hex(good))
            fail("expected invalid")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.INVALID_DEFINITION, e.failure)
        }
        assertEquals(sha256Hex(good), registry.selected.value!!.revision)
    }

    @Test fun secondImportDoesNotSwitch() = runTest {
        val (_, _, registry) = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("shop-a", "1.0.0"), null)
        registry.install(SourceTestFixtures.bytes("shop-b", "1.0.0"), null)
        assertEquals("shop-a", registry.selected.value!!.id)
        registry.select("shop-b")
        assertEquals("shop-b", registry.selected.value!!.id)
    }

    @Test fun disabledSelectionRules() = runTest {
        val (_, _, registry) = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("shop-a", "1.0.0"), null)
        registry.install(SourceTestFixtures.bytes("shop-b", "1.0.0"), null)
        registry.select("shop-b")
        registry.setEnabled("shop-b", false)
        // The disabled entry stays selected (visible disabled state); explicit
        // selection of a disabled source is refused.
        assertEquals("shop-b", registry.selected.value!!.id)
        expectUnavailable { registry.select("shop-b") }
        registry.setEnabled("shop-b", true)
        registry.select("shop-b")
        assertEquals("shop-b", registry.selected.value!!.id)
    }

    @Test fun removeClearsSelectionOnlyWhenSelected() = runTest {
        val (_, _, registry) = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("shop-a", "1.0.0"), null)
        registry.install(SourceTestFixtures.bytes("shop-b", "1.0.0"), null)
        registry.select("shop-b")
        registry.remove("shop-a")
        assertEquals("shop-b", registry.selected.value!!.id)
        registry.remove("shop-b")
        assertNull(registry.selected.value)
        assertTrue(registry.list().value.isEmpty())
    }

    @Test fun openResolvesInstalledAndPinned() = runTest {
        val hook = Hook()
        val (_, store, registry) = registry(hook)
        registry.initialize()
        val rev1 = SourceTestFixtures.bytes("shop-a", "1.0.0")
        registry.install(rev1, null)
        val opened = registry.open("shop-a")
        assertEquals("shop-a", opened.id)
        assertEquals(sha256Hex(rev1), opened.revision)
        expectUnavailable { registry.open("missing") }
        expectUnavailable { registry.open("shop-a", "0".repeat(64)) }
        // Pinned old revision stays openable after replacement while enabled.
        hook.pins = setOf(RevisionPin("shop-a", sha256Hex(rev1)))
        val rev2 = SourceTestFixtures.bytes("shop-a", "2.0.0")
        registry.install(rev2, sha256Hex(rev1))
        assertTrue(store.readDefinition("shop-a", sha256Hex(rev1)) != null)
        val pinned = registry.open("shop-a", sha256Hex(rev1))
        assertEquals(sha256Hex(rev1), pinned.revision)
        // Removal revokes even retained revisions.
        registry.remove("shop-a")
        expectUnavailable { registry.open("shop-a", sha256Hex(rev1)) }
    }

    @Test fun disableRevokesInstances() = runTest {
        val hook = Hook()
        val (_, _, registry) = registry(hook)
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("shop-a", "1.0.0"), null)
        val http = registry.httpFor("shop-a", null)
        registry.setEnabled("shop-a", false)
        expectUnavailable { registry.open("shop-a") }
        // The handed-out client is revoked without touching the network.
        try {
            http.execute(EvaluatedRequest("GET", NetworkPurpose.CATALOG, "https://catalog.example.com/", emptyMap(), emptyMap(), emptyMap()))
            fail("expected revocation")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.UNAVAILABLE, e.failure)
        }
        assertEquals(listOf("shop-a"), hook.unavailable)
    }

    @Test fun garbageRetainsPins() = runTest {
        val hook = Hook()
        val (_, store, registry) = registry(hook)
        registry.initialize()
        val rev1 = SourceTestFixtures.bytes("shop-a", "1.0.0")
        registry.install(rev1, null)
        val pin = RevisionPin("shop-a", sha256Hex(rev1))
        hook.pins = setOf(pin)
        registry.install(SourceTestFixtures.bytes("shop-a", "2.0.0"), pin.revision)
        registry.collectGarbage()
        assertTrue(store.readDefinition("shop-a", pin.revision) != null)
        hook.pins = emptySet()
        registry.collectGarbage()
        assertNull(store.readDefinition("shop-a", pin.revision))
    }

    @Test fun corruptEntryQuarantinedOnRestart() = runTest {
        val (root, store, registry) = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("shop-a", "1.0.0"), null)
        registry.install(SourceTestFixtures.bytes("shop-b", "1.0.0"), null)
        registry.select("shop-a")
        // Corrupt shop-a behind the registry's back, then restart.
        val rev = sha256Hex(SourceTestFixtures.bytes("shop-a", "1.0.0"))
        File(root, "definitions/shop-a/$rev.json").writeText("{corrupt")
        val restarted = SourceRegistry(store)
        restarted.initialize()
        assertTrue(restarted.ready.value)
        val entries = restarted.list().value
        assertEquals(2, entries.size)
        val broken = entries.first { it.id == "shop-a" }
        assertFalse(broken.available)
        assertTrue(restarted.list().value.first { it.id == "shop-b" }.available)
        assertNull(restarted.selected.value)
        expectUnavailable { restarted.open("shop-a") }
        assertEquals("shop-b", restarted.open("shop-b").id)
    }

    @Test fun stagedCrashKeepsOldState() = runTest {
        val (root, store, registry) = registry()
        registry.initialize()
        val rev1 = SourceTestFixtures.bytes("shop-a", "1.0.0")
        registry.install(rev1, null)
        // Simulate a crash between definition publish and manifest write.
        val rev2 = SourceTestFixtures.bytes("shop-a", "2.0.0")
        store.publish(store.stage(rev2), "shop-a", sha256Hex(rev2))
        val restarted = SourceRegistry(store)
        restarted.initialize()
        assertEquals(sha256Hex(rev1), restarted.selected.value!!.revision)
        restarted.collectGarbage()
        assertNull(store.readDefinition("shop-a", sha256Hex(rev2)))
        assertTrue(store.readDefinition("shop-a", sha256Hex(rev1)) != null)
        assertNotEquals(
            0,
            File(root, "definitions/shop-a/${sha256Hex(rev1)}.json").length(),
        )
    }

    @Test fun previewReportsAddedOrigins() = runTest {
        val (_, _, registry) = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("shop-a", "1.0.0"), null)
        val preview = registry.preview(
            SourceTestFixtures.bytes("shop-a", "2.0.0", extraArtwork = setOf("https://cdn.example.com")),
        )
        assertEquals(setOf("https://cdn.example.com"), preview.addedOrigins)
    }

    @Test fun engineHookOnlyOnDisableAndRemove() = runTest {
        val hook = Hook()
        val (_, _, registry) = registry(hook)
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("shop-a", "1.0.0"), null)
        registry.setEnabled("shop-a", true)
        assertTrue(hook.unavailable.isEmpty())
        registry.setEnabled("shop-a", false)
        registry.setEnabled("shop-a", true)
        registry.remove("shop-a")
        assertEquals(listOf("shop-a", "shop-a"), hook.unavailable)
    }
}
