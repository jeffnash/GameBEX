package com.gamebex.client.ui

import com.gamebex.client.MainDispatcherRule
import com.gamebex.client.awaitTrue
import com.gamebex.client.data.CatalogFixtures
import com.gamebex.client.data.local.FakeLibraryDb
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.net.FakeDownloadController
import com.gamebex.client.sources.FakeRomSource
import com.gamebex.client.sources.FakeSourceAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun game() = GameEntity(
        id = 1, title = "Emerald", folder = "gba", volumeId = "sd:x",
        relPath = "gba/emerald.zip", fileName = "emerald.zip",
        sizeBytes = 10, mtimeMs = 20, ext = "zip",
        source = "SOURCE",
        sourceId = "acme", sourceItemKey = "gba/emerald",
        sourceRefJson = kotlinx.serialization.json.Json.encodeToString(
            com.gamebex.source.SourceRef.serializer(),
            CatalogFixtures.ref(sourceId = "acme", itemKey = "gba/emerald"),
        ),
    )

    @Test fun missingPluginDoesNotDisableLocalPlay(): Unit = runBlocking {
        val db = FakeLibraryDb(mutableListOf(game()))
        val access = FakeSourceAccess()
        access.setReady()
        val vm = LocalDetailViewModel(
            database = db, sourceAccess = access, engine = FakeDownloadController(),
        )
        vm.load(1)
        awaitTrue { vm.game.value?.id == 1L }
        assertEquals("Emerald", vm.game.value!!.title)
        // Source-only actions degrade to the install copy; the row stays.
        vm.loadCatalogForGame()
        awaitTrue { vm.catalogBlocked.value != null }
        assertEquals(
            UserErrors.sourceNeededForCatalog("acme"),
            vm.catalogBlocked.value,
        )
        assertEquals(1L, vm.game.value!!.id)
        vm.loadVersions()
        kotlinx.coroutines.delay(200)
        assertEquals(0, vm.versions.value.size)
    }

    @Test fun variantChoiceSurvivesDetailRefresh(): Unit = runBlocking {
        val ref = CatalogFixtures.ref(sourceId = "s", itemKey = "gb/game")
        val f1 = CatalogFixtures.file(ref, "v1", "a.zip")
        val f2 = CatalogFixtures.file(ref, "v2", "b.zip")
        val source = FakeRomSource("s").apply {
            variantsResult = { listOf(f1, f2) }
            detailResult = { CatalogFixtures.detail(CatalogFixtures.entry(sourceId = "s")) }
        }
        val access = FakeSourceAccess()
        access.install(source)
        access.setReady()
        access.select("s")
        val vm = LocalDetailViewModel(
            database = FakeLibraryDb(), sourceAccess = access,
            engine = FakeDownloadController(),
        )
        val entry = CatalogFixtures.entry(sourceId = "s", itemKey = "gb/game", platformId = "gb")
        vm.loadCatalog(entry)
        awaitTrue { vm.catalogFiles.value.size == 2 }
        vm.selectFile(f1)
        vm.loadCatalog(entry)
        kotlinx.coroutines.delay(200)
        assertEquals(f1, vm.selectedFile.value)
        assertEquals(2, vm.catalogFiles.value.size)
    }

    @Test fun catalogBlockedOnMissingSource(): Unit = runBlocking {
        val access = FakeSourceAccess()
        access.setReady()
        val vm = LocalDetailViewModel(
            database = FakeLibraryDb(), sourceAccess = access,
            engine = FakeDownloadController(),
        )
        vm.loadCatalog(CatalogFixtures.entry(sourceId = "gone", itemKey = "gb/game"))
        awaitTrue { vm.catalogBlocked.value != null }
        assertEquals(
            UserErrors.sourceNeededForCatalog("gone"),
            vm.catalogBlocked.value,
        )
    }
}
