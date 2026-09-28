package com.gamebex.client.ui

import androidx.lifecycle.SavedStateHandle
import com.gamebex.client.MainDispatcherRule
import com.gamebex.client.awaitTrue
import com.gamebex.client.data.CatalogFixtures
import com.gamebex.client.net.FakeDownloadController
import com.gamebex.client.sources.FakeRomSource
import com.gamebex.client.sources.FakeSourceAccess
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Discover Start on a catalog tile: queue like Detail, or open Detail. */
class QuickDownloadTest {
    @get:Rule val main = MainDispatcherRule()

    private fun setup(files: Int): Triple<BrowseViewModel, FakeDownloadController, FakeRomSource> {
        val src = FakeRomSource("acme").apply {
            variantsResult = { ref ->
                (1..files).map { CatalogFixtures.file(ref = ref, variantKey = "v$it", name = "tetris-$it.zip") }
            }
            detailResult = { CatalogFixtures.detail() }
        }
        val access = FakeSourceAccess().apply {
            install(src)
            setReady()
        }
        val dl = FakeDownloadController()
        return Triple(BrowseViewModel(access, null, SavedStateHandle(), emptyFlow(), dl), dl, src)
    }

    @Test fun planQueuesOnlyUnambiguousDownloads() {
        assertEquals(QuickDownloadPlan.ENQUEUE, quickDownloadPlan(folderMapped = true, fileCount = 1))
        assertEquals(QuickDownloadPlan.OPEN_DETAIL, quickDownloadPlan(folderMapped = true, fileCount = 2))
        assertEquals(QuickDownloadPlan.OPEN_DETAIL, quickDownloadPlan(folderMapped = true, fileCount = 0))
        assertEquals(QuickDownloadPlan.OPEN_DETAIL, quickDownloadPlan(folderMapped = false, fileCount = 1))
    }

    @Test fun singleFileEnqueuesWithoutOpeningDetail(): Unit = runBlocking {
        val (vm, dl, _) = setup(files = 1)
        var opened = false
        vm.quickDownload(CatalogFixtures.entry()) { opened = true }
        awaitTrue { dl.calls.isNotEmpty() }
        assertTrue(dl.calls.single().startsWith("enqueue:"))
        assertTrue(dl.calls.single().endsWith(":tetris-1.zip"))
        awaitTrue { vm.message.value != null }
        assertTrue(vm.message.value!!.startsWith("Downloading Tetris"))
        assertEquals(false, opened)
    }

    @Test fun multiFileOpensDetailForTheChoice(): Unit = runBlocking {
        val (vm, dl, _) = setup(files = 2)
        var opened = false
        vm.quickDownload(CatalogFixtures.entry()) { opened = true }
        awaitTrue { opened }
        assertTrue(dl.calls.isEmpty())
    }

    @Test fun unmappedSystemOpensDetailImmediately() {
        val (vm, dl, src) = setup(files = 1)
        var opened = false
        vm.quickDownload(CatalogFixtures.entry(platformId = null)) { opened = true }
        assertTrue(opened)
        assertTrue(dl.calls.isEmpty())
        assertTrue(src.calls.none { it.startsWith("variants:") })
    }
}
