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

    private fun sysEntry(key: String, title: String, platformId: String?) =
        CatalogFixtures.entry(itemKey = key, title = title, platformId = platformId)

    private fun searchCalls(source: FakeRomSource): Int = source.calls.count { it.startsWith("search") }

    @Test fun ignoredPlatformFetchesFurtherPagesUpToTheBudget(): Unit = runBlocking {
        val source = twoSystems(PlatformSource("s"))
        val pages = mapOf<String?, CatalogPage>(
            null to CatalogPage(
                listOf(sysEntry("gb/a", "Tetris A", "gb"), sysEntry("gba/x", "Tetris X", "gba")),
                next = "c2",
            ),
            "c2" to CatalogPage(
                listOf(sysEntry("gb/b", "Tetris B", "gb"), sysEntry("gba/y", "Tetris Y", "gba")),
                next = "c3",
            ),
            "c3" to CatalogPage(listOf(sysEntry("gb/c", "Tetris C", "gb")), next = "c4"),
        )
        source.searchPaged = { _, _, cursor -> pages[cursor] ?: CatalogPage(emptyList()) }
        val access = ready(source)
        access.select("s")
        val vm = SearchViewModel(access, SavedStateHandle())
        awaitTrue { vm.consoles.value.isNotEmpty() }
        vm.onQuery("tetris")
        awaitTrue { !vm.searching.value && vm.results.value.size == 2 }
        source.calls.clear()
        vm.selectSystem("gb")
        awaitTrue { !vm.searching.value && vm.results.value.size == 3 }
        assertEquals(setOf("Tetris A", "Tetris B", "Tetris C"), vm.results.value.map { it.title }.toSet())
        assertEquals(3, searchCalls(source))
        assertTrue(source.calls.filter { it.startsWith("search") }.all { it.startsWith("search:tetris:gb:") })
    }

    @Test fun honoredPlatformAndUnfilteredSearchFetchOnePage(): Unit = runBlocking {
        val source = twoSystems(PlatformSource("s"))
        source.searchPaged = { _, platform, _ ->
            if (platform == "gb") CatalogPage(listOf(sysEntry("gb/a", "Tetris A", "gb")), next = "c2")
            else CatalogPage(
                listOf(sysEntry("gb/a", "Tetris A", "gb"), sysEntry("gba/x", "Tetris X", "gba")),
                next = "c2",
            )
        }
        val access = ready(source)
        access.select("s")
        val vm = SearchViewModel(access, SavedStateHandle())
        awaitTrue { vm.consoles.value.isNotEmpty() }
        vm.onQuery("tetris")
        awaitTrue { !vm.searching.value && vm.results.value.size == 2 }
        assertEquals(1, searchCalls(source))
        vm.selectSystem("gb")
        awaitTrue { !vm.searching.value && vm.results.value.size == 1 }
        assertEquals(2, searchCalls(source))
    }

    @Test fun enoughMatchesOrEndOfFeedStopsTheFollowUp(): Unit = runBlocking {
        val source = twoSystems(PlatformSource("s"))
        val many = (1..30).map { sysEntry("gb/g$it", "Tetris $it", "gb") } + sysEntry("gba/x", "Tetris X", "gba")
        source.searchPaged = { _, _, cursor ->
            when (cursor) {
                null -> CatalogPage(many, next = "c2")
                else -> CatalogPage(listOf(sysEntry("gb/more", "Tetris More", "gb")))
            }
        }
        val access = ready(source)
        access.select("s")
        val vm = SearchViewModel(access, SavedStateHandle())
        awaitTrue { vm.consoles.value.isNotEmpty() }
        vm.onQuery("tetris")
        awaitTrue { !vm.searching.value && vm.results.value.size == 31 }
        source.calls.clear()
        vm.selectSystem("gb")
        awaitTrue { !vm.searching.value && vm.results.value.size == 30 }
        assertEquals(1, searchCalls(source))
    }

    @Test fun ignoreDetectionIgnoresUnlabeledItems(): Unit {
        val gb = sysEntry("gb/a", "Tetris", "gb")
        val gba = sysEntry("gba/x", "Tetris", "gba")
        val unlabeled = sysEntry("x/u", "Tetris", null)
        assertTrue(searchPageIgnoresSystem(listOf(gb, gba), "gb"))
        assertTrue(searchPageIgnoresSystem(listOf(gba), "gb"))
        assertTrue(!searchPageIgnoresSystem(listOf(gb), "gb"))
        assertTrue(!searchPageIgnoresSystem(listOf(gb, unlabeled), "gb"))
        assertTrue(!searchPageIgnoresSystem(emptyList(), "gb"))
    }
}
