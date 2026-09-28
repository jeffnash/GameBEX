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
