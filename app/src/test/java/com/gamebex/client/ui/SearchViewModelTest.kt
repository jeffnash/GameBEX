package com.gamebex.client.ui

import androidx.lifecycle.SavedStateHandle
import com.gamebex.client.MainDispatcherRule
import com.gamebex.client.awaitTrue
import com.gamebex.client.data.CatalogFixtures
import com.gamebex.client.sources.FakeRomSource
import com.gamebex.client.sources.FakeSourceAccess
import com.gamebex.source.CatalogPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    /** Records each search's platform input; pages come from [searchResult]. */
    private class PlatformSource(id: String) : FakeRomSource(id) {
        val platforms: MutableList<String?> = java.util.concurrent.CopyOnWriteArrayList()

        override suspend fun search(query: String, platformId: String?, cursor: String?): CatalogPage {
            platforms += platformId
            return super.search(query, platformId, cursor)
        }
    }

    private fun ready(vararg sources: FakeRomSource): FakeSourceAccess {
        val access = FakeSourceAccess()
        sources.forEach { access.install(it) }
        access.setReady()
        return access
    }

    private fun <T : FakeRomSource> twoSystems(source: T): T = source.apply {
        consolesResult = listOf(
            CatalogFixtures.console(id = "game-boy", platformId = "gb"),
            CatalogFixtures.console(id = "gba", name = "Game Boy Advance", platformId = "gba"),
            CatalogFixtures.console(id = "nes", name = "NES", platformId = "nes", count = 0),
        )
        // Ignores the platform input: the client still narrows.
        searchResult = {
            CatalogPage(
                listOf(
                    CatalogFixtures.entry(itemKey = "gb/tetris", title = "Tetris", platformId = "gb"),
                    CatalogFixtures.entry(itemKey = "gba/tetris", title = "Tetris Worlds", platformId = "gba"),
                ),
            )
        }
    }

    @Test fun systemListsOnlyNonEmptyFeeds(): Unit = runBlocking {
        val access = ready(twoSystems(PlatformSource("s")))
        access.select("s")
        val vm = SearchViewModel(access, SavedStateHandle())
        awaitTrue { vm.consoles.value.isNotEmpty() }
        assertEquals(listOf("game-boy", "gba"), vm.consoles.value.map { it.id })
    }

    @Test fun systemChipPassesPlatformAndRerunsTheQuery(): Unit = runBlocking {
        val source = twoSystems(PlatformSource("s"))
        val access = ready(source)
        access.select("s")
        val vm = SearchViewModel(access, SavedStateHandle())
        awaitTrue { vm.consoles.value.isNotEmpty() }
        vm.onQuery("tetris")
        awaitTrue { vm.results.value.size == 2 && !vm.searching.value }
        assertEquals(listOf<String?>(null), source.platforms.toList())
        vm.selectSystem("gba")
        awaitTrue { source.platforms.lastOrNull() == "gba" }
        awaitTrue { !vm.searching.value && vm.results.value.map { it.platformId } == listOf("gba") }
        assertEquals("tetris", vm.query.value)
        vm.selectSystem(null)
        awaitTrue { source.platforms.size == 3 && !vm.searching.value && vm.results.value.size == 2 }
        assertEquals(null, source.platforms.last())
    }

    @Test fun staleSavedSystemNeverNarrows(): Unit = runBlocking {
        val source = twoSystems(PlatformSource("s"))
        val access = ready(source)
        access.select("s")
        val vm = SearchViewModel(access, SavedStateHandle(mapOf("sys:s" to "n64")))
        awaitTrue { vm.consoles.value.isNotEmpty() }
        vm.onQuery("tetris")
        awaitTrue { vm.results.value.size == 2 && !vm.searching.value }
        assertTrue(source.platforms.all { it == null })
    }

    @Test fun systemIsKeptPerSource(): Unit = runBlocking {
        val a = twoSystems(FakeRomSource("a"))
        val b = FakeRomSource("b").apply {
            consolesResult = listOf(CatalogFixtures.console(id = "snes", name = "SNES", platformId = "snes"))
        }
        val access = ready(a, b)
        access.select("a")
        val vm = SearchViewModel(access, SavedStateHandle())
        awaitTrue { vm.consoles.value.isNotEmpty() }
        vm.selectSystem("gb")
        access.select("b", generation = 2)
        awaitTrue { vm.system.value == null && vm.consoles.value.map { it.id } == listOf("snes") }
        access.select("a", generation = 3)
        awaitTrue { vm.system.value == "gb" && vm.consoles.value.any { it.platformId == "gb" } }
    }

    @Test fun retrySameSearchRunsAgain(): Unit = runBlocking {
        var attempts = 0
        val source = FakeRomSource("s").apply {
            searchResult = {
                attempts++
                if (attempts == 1) throw java.io.IOException("flaky")
                CatalogPage(listOf(CatalogFixtures.entry(title = "Tetris")))
            }
        }
        val access = FakeSourceAccess()
        access.install(source)
        access.setReady()
        access.select("s")
        val vm = SearchViewModel(access, SavedStateHandle())
        vm.onQuery("tetris")
        awaitTrue { vm.error.value != null }
        assertTrue(vm.results.value.isEmpty())
        vm.retry()
        awaitTrue { vm.results.value.isNotEmpty() }
        assertEquals("tetris", vm.query.value)
        assertEquals(2, attempts)
        assertEquals("Tetris", vm.results.value.first().title)
    }

    @Test fun sourceSwitchClearsAndRestoresQuery(): Unit = runBlocking {
        val access = FakeSourceAccess()
        access.install(FakeRomSource("a"))
        access.install(FakeRomSource("b"))
        access.setReady()
        access.select("a")
        val saved = SavedStateHandle()
        val vm = SearchViewModel(access, saved)
        awaitTrue { vm.query.value.isEmpty() && !vm.searching.value }
        vm.onQuery("mario")
        awaitTrue { vm.results.value.isNotEmpty() || !vm.searching.value }
        access.select("b", generation = 2)
        awaitTrue { vm.query.value.isEmpty() && vm.results.value.isEmpty() }
        access.select("a", generation = 3)
        awaitTrue { vm.query.value == "mario" }
    }

    @Test fun noSourcePresentsImport(): Unit = runBlocking {
        val access = FakeSourceAccess()
        access.setReady()
        val vm = SearchViewModel(access, SavedStateHandle())
        vm.onQuery("tetris")
        awaitTrue { vm.error.value != null }
        assertEquals(UserErrors.noSource(), vm.error.value)
        assertEquals(true, vm.sourceUnavailable.value)
    }

    @Test fun initialQuerySeedsWhenNoSavedQuery(): Unit = runBlocking {
        val source = FakeRomSource("s").apply {
            searchResult = { CatalogPage(listOf(CatalogFixtures.entry(title = "Sonic"))) }
        }
        val access = FakeSourceAccess()
        access.install(source)
        access.setReady()
        access.select("s")
        val vm = SearchViewModel(access, SavedStateHandle(mapOf("initialQuery" to "sonic")))
        assertEquals("sonic", vm.query.value)
        awaitTrue { vm.results.value.isNotEmpty() }
        assertEquals("Sonic", vm.results.value.first().title)
    }

    @Test fun savedQueryWinsOverInitialQuery(): Unit = runBlocking {
        val access = FakeSourceAccess()
        access.install(FakeRomSource("s"))
        access.setReady()
        access.select("s")
        val vm = SearchViewModel(
            access,
            SavedStateHandle(mapOf("initialQuery" to "sonic", "q:s" to "mario")),
        )
        assertEquals("mario", vm.query.value)
    }
}
