package com.gamebex.client.sources

import com.gamebex.client.data.CatalogFixtures
import com.gamebex.client.ui.UserErrors
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.SiteResult
import com.gamebex.source.CatalogPage
import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SiteSearchImpl] over the shared fakes. Virtual time drives the 8 s
 * size timeout and the 2-in-flight throttle without real waiting.
 */
class SiteSearchImplTest {

    private fun TestScope.impl(
        access: FakeSourceAccess,
        online: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ): SiteSearchImpl = SiteSearchImpl(
        sources = access,
        online = online,
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
    )

    private fun source(id: String = "s", name: String = "ROMS-I-Own.com") =
        FakeRomSource(id, name = name)

    private fun TestScope.installed(
        source: FakeRomSource,
        online: MutableStateFlow<Boolean> = MutableStateFlow(true),
        select: Boolean = true,
    ): Pair<FakeSourceAccess, SiteSearchImpl> {
        val access = FakeSourceAccess()
        access.install(source)
        access.setReady()
        if (select) access.select(source.id)
        return access to impl(access, online)
    }

    private fun entry(itemKey: String, title: String = itemKey) =
        CatalogFixtures.entry(sourceId = "s", itemKey = itemKey, title = title)

    @Test
    fun pluginNameFollowsSelection() = runTest {
        val access = FakeSourceAccess()
        access.install(source(name = "ROMS-I-Own.com"))
        access.setReady()
        val s = impl(access)
        assertNull(s.pluginName.value)
        access.select("s")
        assertEquals("ROMS-I-Own.com", s.pluginName.first { it != null })
        access.select(null)
        assertNull(s.pluginName.first { it == null })
    }

    @Test
    fun pluginNameIsNullWhenOpenFails() = runTest {
        val access = FakeSourceAccess()
        access.setReady()
        access.select("gone") // not installed: open() throws UNAVAILABLE
        val s = impl(access)
        testScheduler.advanceUntilIdle()
        assertNull(s.pluginName.value)
    }

    @Test
    fun searchWithoutSelectionIsNoPlugin() = runTest {
        val src = source()
        val (_, s) = installed(src, select = false)
        assertEquals(SiteResult.NoPlugin, s.search("sonic"))
        assertTrue(src.calls.isEmpty())
    }

    @Test
    fun searchOfflineIsOfflineBeforeFetching() = runTest {
        val src = source()
        val (_, s) = installed(src, online = MutableStateFlow(false))
        assertEquals(SiteResult.Offline, s.search("sonic"))
        assertTrue(src.calls.none { it.startsWith("search") })
    }

    @Test
    fun searchFoundRanksResults() = runTest {
        val src = source().apply {
            searchResult = {
                CatalogPage(
                    listOf(
                        entry("shadow", "Shadow the Hedgehog"),
                        entry("sonic", "Sonic"),
                    ),
                )
            }
        }
        val (_, s) = installed(src)
        assertEquals(
            SiteResult.Found(listOf(entry("sonic", "Sonic"), entry("shadow", "Shadow the Hedgehog"))),
            s.search("sonic"),
        )
    }

    @Test
    fun searchDedupesDuplicateEntries() = runTest {
        val src = source().apply {
            searchResult = {
                CatalogPage(
                    listOf(
                        entry("a", "Alpha"),
                        entry("b", "Beta"),
                        entry("a", "Alpha"),
                    ),
                )
            }
        }
        val (_, s) = installed(src)
        val result = s.search("a") as SiteResult.Found
        assertEquals(2, result.entries.size)
        assertEquals(setOf("a", "b"), result.entries.map { it.ref.itemKey }.toSet())
    }

    @Test
    fun searchFailureMapsThroughUserErrors() = runTest {
        val exc = SourceException(SourceFailure.HTTP, "s", "search", statusCode = 429)
        val src = source().apply { searchResult = { throw exc } }
        val (_, s) = installed(src)
        s.pluginName.first { it != null }
        assertEquals(
            SiteResult.Failed(UserErrors.forCatalog(exc, "ROMS-I-Own.com")),
            s.search("sonic"),
        )
    }

    @Test
    fun networkFailureWhileOfflineReportsOffline() = runTest {
        val online = MutableStateFlow(true)
        val src = source().apply {
            searchResult = {
                online.value = false
                throw IOException("connection reset")
            }
        }
        val (_, s) = installed(src, online)
        assertEquals(SiteResult.Offline, s.search("sonic"))
    }

    @Test
    fun nonNetworkFailureStaysFailedWhileOffline() = runTest {
        val online = MutableStateFlow(true)
        val src = source().apply {
            searchResult = {
                online.value = false
                throw IllegalStateException("boom")
            }
        }
        val (_, s) = installed(src, online)
        assertTrue(s.search("sonic") is SiteResult.Failed)
    }

    @Test
    fun sizeFromSingleVariant() = runTest {
        val src = source().apply {
            variantsResult = { listOf(CatalogFixtures.file().copy(size = "480 MB")) }
        }
        val (_, s) = installed(src)
        val info = s.size(entry("g1"))
        assertEquals(HomeCopy.parseSize("480 MB"), info?.bytes)
        assertEquals("480 MB", info?.label)
    }

    @Test
    fun sizeFallsBackToDetailWhenVariantsAmbiguous() = runTest {
        val src = source().apply {
            variantsResult = {
                listOf(
                    CatalogFixtures.file().copy(size = "480 MB"),
                    CatalogFixtures.file(variantKey = "v2").copy(size = "900 MB"),
                )
            }
            detailResult = { CatalogFixtures.detail().copy(size = "1,2 Go") }
        }
        val (_, s) = installed(src)
        val info = s.size(entry("g1"))
        assertEquals(HomeCopy.parseSize("1,2 Go"), info?.bytes)
        assertEquals("1.2 GB", info?.label)
    }

    @Test
    fun sizeFallsBackToDetailWhenVariantSizeUnparseable() = runTest {
        val src = source().apply {
            variantsResult = { listOf(CatalogFixtures.file().copy(size = "big")) }
            detailResult = { CatalogFixtures.detail().copy(size = "1.4 GB") }
        }
        val (_, s) = installed(src)
        assertEquals(HomeCopy.parseSize("1.4 GB"), s.size(entry("g1"))?.bytes)
    }

    @Test
    fun sizeUnknownIsCached() = runTest {
        val src = source().apply {
            variantsResult = { emptyList() }
            detailResult = { CatalogFixtures.detail().copy(size = null) }
        }
        val (_, s) = installed(src)
        assertNull(s.size(entry("g1")))
        assertNull(s.size(entry("g1")))
        assertEquals(1, src.calls.count { it.startsWith("variants") })
        assertEquals(1, src.calls.count { it.startsWith("detail") })
    }

    @Test
    fun sizeCacheHitsSkipLookups() = runTest {
        val src = source().apply {
            variantsResult = { listOf(CatalogFixtures.file().copy(size = "1 MB")) }
        }
        val (_, s) = installed(src)
        val a = s.size(entry("g1"))
        val b = s.size(entry("g1"))
        assertEquals(a, b)
        assertEquals(1, src.calls.count { it.startsWith("variants") })
        s.size(entry("g2"))
        assertEquals(2, src.calls.count { it.startsWith("variants") })
    }

    @Test
    fun sizeCacheEvictsBeyond200() = runTest {
        val src = source().apply {
            variantsResult = { listOf(CatalogFixtures.file().copy(size = "1 MB")) }
        }
        val (_, s) = installed(src)
        repeat(201) { s.size(entry("g$it")) }
        assertEquals(1, src.calls.count { it == "variants:g0" })
        s.size(entry("g200")) // last in: still cached
        assertEquals(1, src.calls.count { it == "variants:g200" })
        s.size(entry("g0")) // evicted: looked up again
        assertEquals(2, src.calls.count { it == "variants:g0" })
    }

    @Test
    fun atMostTwoSizeLookupsInFlight() = runTest {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val src = source().apply {
            variantsResult = {
                val n = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, n) }
                delay(1_000)
                active.decrementAndGet()
                listOf(CatalogFixtures.file().copy(size = "1 MB"))
            }
        }
        val (_, s) = installed(src)
        val jobs = (1..4).map { i -> launch { s.size(entry("g$i")) } }
        jobs.forEach { it.join() }
        assertEquals(2, maxActive.get())
        assertEquals(4, src.calls.count { it.startsWith("variants") })
    }

    @Test
    fun sizeTimeoutReportsUnknownWithoutCaching() = runTest {
        val src = source().apply {
            variantsResult = {
                delay(20_000)
                listOf(CatalogFixtures.file().copy(size = "1 MB"))
            }
        }
        val (_, s) = installed(src)
        assertNull(s.size(entry("g1")))
        assertNull(s.size(entry("g1")))
        // Transient timeouts retry: the failed lookup was not pinned in cache.
        assertEquals(2, src.calls.count { it.startsWith("variants") })
    }

    /**
     * A site search that hangs past 15 s must not hang the SEARCH screen —
     * it resolves as SiteResult.Failed("…took too long").
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun searchHangingPastFifteenSecondsFailsWithTookTooLong() = runTest {
        val src = source().apply { searchResult = { awaitCancellation() } }
        val (_, s) = installed(src)
        val pending = backgroundScope.async { s.search("sonic") }
        testScheduler.advanceTimeBy(16_000)
        testScheduler.runCurrent()
        assertTrue("a hanging search must resolve within 15 s", pending.isCompleted)
        val result = pending.getCompleted()
        assertTrue(result is SiteResult.Failed)
        assertTrue((result as SiteResult.Failed).message.contains("took too long"))
    }

    /** A slow search that lands inside 15 s succeeds. */
    @Test
    fun searchCompletingWithinFifteenSecondsStillSucceeds() = runTest {
        val src = source().apply {
            searchResult = {
                delay(14_000)
                CatalogPage(listOf(entry("sonic", "Sonic")))
            }
        }
        val (_, s) = installed(src)
        val result = s.search("sonic")
        assertTrue(result is SiteResult.Found)
    }
}
