package com.gamebex.client.sources

import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Registry selection semantics: explicit switching, generations, clearing. */
class SourceSelectionTest {
    private fun registry(): SourceRegistry {
        val root: File = Files.createTempDirectory("selection").toFile()
        return SourceRegistry(SourceStore(root))
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

    @Test fun selectSwitchesAndBumpsGeneration() = runTest {
        val registry = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("s1", "1.0.0"), null)
        registry.install(SourceTestFixtures.bytes("s2", "1.0.0"), null)
        val first = registry.selected.value!!
        assertEquals("s1", first.id)
        registry.select("s2")
        val second = registry.selected.value!!
        assertEquals("s2", second.id)
        assertTrue(second.generation > first.generation)
    }

    @Test fun reselectSameIsStable() = runTest {
        val registry = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("s1", "1.0.0"), null)
        val gen = registry.selected.value!!.generation
        registry.select("s1")
        assertEquals(gen, registry.selected.value!!.generation)
    }

    @Test fun selectUnknownKeepsPrior() = runTest {
        val registry = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("s1", "1.0.0"), null)
        expectUnavailable { registry.select("nope") }
        assertEquals("s1", registry.selected.value!!.id)
    }

    @Test fun selectDisabledKeepsPrior() = runTest {
        val registry = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("s1", "1.0.0"), null)
        registry.install(SourceTestFixtures.bytes("s2", "1.0.0"), null)
        registry.setEnabled("s2", false)
        expectUnavailable { registry.select("s2") }
        assertEquals("s1", registry.selected.value!!.id)
    }

    @Test fun removeSelectedClearsSelection() = runTest {
        val registry = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("s1", "1.0.0"), null)
        registry.install(SourceTestFixtures.bytes("s2", "1.0.0"), null)
        registry.select("s2")
        registry.remove("s2")
        assertNull(registry.selected.value)
    }

    @Test fun removeUnselectedKeepsSelection() = runTest {
        val registry = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("s1", "1.0.0"), null)
        registry.install(SourceTestFixtures.bytes("s2", "1.0.0"), null)
        registry.remove("s2")
        assertEquals("s1", registry.selected.value!!.id)
    }

    @Test fun disableSelectedKeepsVisibleSelection() = runTest {
        val registry = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("s1", "1.0.0"), null)
        registry.setEnabled("s1", false)
        // The disabled entry stays selected (visible disabled state);
        // open() still refuses it until re-enabled.
        assertEquals("s1", registry.selected.value!!.id)
        expectUnavailable { registry.open("s1") }
        registry.setEnabled("s1", true)
        registry.open("s1")
    }

    @Test fun installWhileSelectedDisabledDoesNotSteal() = runTest {
        val registry = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("s1", "1.0.0"), null)
        registry.setEnabled("s1", false)
        registry.install(SourceTestFixtures.bytes("s2", "1.0.0"), null)
        assertEquals("s1", registry.selected.value!!.id)
    }

    @Test fun replaceKeepsSelectionOnNewRevision() = runTest {
        val registry = registry()
        registry.initialize()
        registry.install(SourceTestFixtures.bytes("s1", "1.0.0"), null)
        val before = registry.selected.value!!
        val v2 = SourceTestFixtures.bytes("s1", "1.1.0")
        registry.install(v2, registry.preview(v2).replacesRevision)
        val after = registry.selected.value!!
        assertEquals("s1", after.id)
        assertTrue(after.revision != before.revision)
    }
}
