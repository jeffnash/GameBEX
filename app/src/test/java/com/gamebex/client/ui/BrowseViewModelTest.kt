package com.gamebex.client.ui

import androidx.lifecycle.SavedStateHandle
import com.gamebex.client.MainDispatcherRule
import com.gamebex.client.awaitTrue
import com.gamebex.client.data.CatalogFixtures
import com.gamebex.client.sources.FakeRomSource
import com.gamebex.client.sources.FakeSourceAccess
import com.gamebex.source.CatalogPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowseViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun access(vararg sources: FakeRomSource): FakeSourceAccess {
        val access = FakeSourceAccess()
        sources.forEach { access.install(it) }
        access.setReady()
        return access
    }

    private fun vm(access: FakeSourceAccess, saved: SavedStateHandle = SavedStateHandle()) =
        BrowseViewModel(access, null, saved, emptyFlow())

    @Test fun sourceSwitchDropsLatePage(): Unit = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<CatalogPage>()
        val slow = FakeRomSource("slow").apply {
            consolesResult = listOf(CatalogFixtures.console(id = "c1"))
            latestResult = { gate.await() }
        }
        val fast = FakeRomSource("fast").apply {
            consolesResult = listOf(CatalogFixtures.console(id = "c1"))
            latestResult = { CatalogPage(listOf(CatalogFixtures.entry(sourceId = "fast", itemKey = "gb/fast"))) }
        }
        val access = access(slow, fast)
        access.select("slow")
        val vm = vm(access)
        awaitTrue { slow.calls.any { it.startsWith("latest:") } }
        access.select("fast", generation = 2)
        awaitTrue { vm.items.value.any { it.ref.sourceId == "fast" } && !vm.loading.value }
        gate.complete(CatalogPage(listOf(CatalogFixtures.entry(sourceId = "slow", itemKey = "gb/slow"))))
        // Let the late page land if the guard is broken.
        kotlinx.coroutines.delay(200)
        assertTrue(vm.items.value.all { it.ref.sourceId == "fast" })
        assertEquals("Fake fast", vm.sourceName.value)
    }

    @Test fun sameConsoleIdAcrossSourcesDoesNotAppend(): Unit = runBlocking {
        fun source(id: String, item: String) = FakeRomSource(id).apply {
            consolesResult = listOf(CatalogFixtures.console(id = "c1"))
            browseResult = { consoleId, _, _ ->
                CatalogPage(
                    if (consoleId == "c1") listOf(CatalogFixtures.entry(sourceId = id, itemKey = item))
                    else emptyList(),
                )
            }
        }
        val access = access(source("a", "gb/a1"), source("b", "gb/b1"))
        access.select("a")
        val vm = vm(access)
        awaitTrue { !vm.loading.value }
        vm.selectConsole("c1")
        awaitTrue { vm.items.value.any { it.ref.itemKey == "gb/a1" } }
        access.select("b", generation = 2)
        awaitTrue { vm.selection.value?.id == "b" }
        awaitTrue { !vm.loading.value }
        vm.selectConsole("c1")
        awaitTrue { vm.items.value.any { it.ref.itemKey == "gb/b1" } }
        assertEquals(listOf("gb/b1"), vm.items.value.map { it.ref.itemKey })
    }

    @Test fun noSourceShowsImportState(): Unit = runBlocking {
        val access = FakeSourceAccess()
        access.setReady()
        val vm = vm(access)
        awaitTrue { !vm.loading.value }
        assertEquals(UserErrors.noSource(), vm.error.value)
        assertEquals(true, vm.sourceUnavailable.value)
        assertTrue(vm.items.value.isEmpty())
    }

    @Test fun staleSavedConsoleIsCleared(): Unit = runBlocking {
        val source = FakeRomSource("s").apply {
            consolesResult = listOf(CatalogFixtures.console(id = "c9"))
            latestResult = { CatalogPage(emptyList()) }
        }
        val access = access(source)
        access.select("s")
        val saved = SavedStateHandle(mapOf("console:s" to "gone"))
        val vm = vm(access, saved)
        awaitTrue { !vm.loading.value }
        assertEquals(null, vm.selectedConsole.value)
        assertEquals(null, saved.get<String>("console:s"))
    }

    @Test fun everyNonEmptyFeedIsListed(): Unit = runBlocking {
        val source = FakeRomSource("s").apply {
            consolesResult = (1..40).map { CatalogFixtures.console(id = "c$it", name = "Console $it") } +
                CatalogFixtures.console(id = "none", count = 0)
        }
        val access = access(source)
        access.select("s")
        val vm = vm(access)
        awaitTrue { !vm.loading.value }
        assertEquals((1..40).map { "c$it" }, vm.consoles.value.map { it.id })
    }

    @Test fun libraryFlowReachesGames(): Unit = runBlocking {
        val access = access(FakeRomSource("s"))
        access.select("s")
        val games = MutableStateFlow<List<com.gamebex.client.data.local.GameEntity>>(emptyList())
        val vm = BrowseViewModel(access, null, SavedStateHandle(), games)
        awaitTrue { !vm.loading.value }
        assertTrue(vm.games.value.isEmpty())
    }
}
