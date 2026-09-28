package com.gamebex.client.net

import com.gamebex.client.data.CatalogFixtures
import com.gamebex.client.data.local.FakeLibraryDb
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.sources.FakeRomSource
import com.gamebex.client.sources.FakeSourceAccess
import com.gamebex.client.ui.home.model.DownloadStart
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.source.DownloadFile
import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import com.gamebex.source.SourceRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's download lifecycle: the variant rule for site downloads, and
 * Download again re-queuing a tombstone's provenance.
 */
class HomeDownloadsImplTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val source = FakeRomSource("acme")
    private val access = FakeSourceAccess().apply {
        install(source)
        setReady()
    }
    private val engine = FakeDownloadController()

    private fun impl(game: GameEntity? = null): HomeDownloadsImpl {
        val db = FakeLibraryDb(gamesList = mutableListOf<GameEntity>().apply { game?.let { add(it) } })
        return HomeDownloadsImpl(engine, access, db)
    }

    /** A tombstone with the source provenance on the row. */
    private fun tombstone(variant: DownloadFile?): GameEntity = GameEntity(
        id = 42, title = "Tetris", folder = "gb", volumeId = "internal",
        relPath = "gb/tetris.gb", fileName = "tetris.gb", sizeBytes = 32, mtimeMs = 1, ext = "gb",
        source = "SOURCE",
        sourceRefJson = json.encodeToString(SourceRef.serializer(), CatalogFixtures.ref()),
        sourceVariantJson = variant?.let { json.encodeToString(DownloadFile.serializer(), it) },
    )

    private fun oneFile(name: String = "tetris.zip") = listOf(CatalogFixtures.file(name = name))

    @Test
    fun singleVariantQueuesWithTheVariantFileName(): Unit = runBlocking {
        source.variantsResult = { oneFile("sonic.zip") }
        val out = impl().downloadFromSite(CatalogFixtures.entry(title = "Sonic"))
        assertEquals(1L, (out as DownloadStart.Started).downloadId)
        assertTrue(engine.calls.single().endsWith(":sonic.zip"))
    }

    @Test
    fun severalVariantsOpenTheEntryPageForAChoice(): Unit = runBlocking {
        source.variantsResult = { ref -> (1..2).map { CatalogFixtures.file(ref = ref, variantKey = "v$it") } }
        assertEquals(DownloadStart.NeedsChoice, impl().downloadFromSite(CatalogFixtures.entry()))
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun noVariantsOpenTheEntryPageToo(): Unit = runBlocking {
        source.variantsResult = { emptyList() }
        assertEquals(DownloadStart.NeedsChoice, impl().downloadFromSite(CatalogFixtures.entry()))
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun unmappedSystemOpensTheEntryPageWithoutFetchingVariants(): Unit = runBlocking {
        val out = impl().downloadFromSite(CatalogFixtures.entry(platformId = "mystery"))
        assertEquals(DownloadStart.NeedsChoice, out)
        assertTrue(engine.calls.isEmpty())
        assertTrue(source.calls.none { it.startsWith("variants:") })
    }

    @Test
    fun busyDestinationReportsAlreadyDownloading(): Unit = runBlocking {
        source.variantsResult = { oneFile() }
        engine.enqueueError = DestinationBusyException(3L)
        val out = impl().downloadFromSite(CatalogFixtures.entry())
        assertEquals(DownloadStart.Failed(HomeCopy.alreadyDownloading("Tetris")), out)
    }

    @Test
    fun variantFailureMapsToAUserMessage(): Unit = runBlocking {
        source.variantsResult = { throw SourceException(SourceFailure.NETWORK, "acme", null) }
        val out = impl().downloadFromSite(CatalogFixtures.entry()) as DownloadStart.Failed
        assertTrue(out.message.startsWith("Couldn't reach"))
    }

    @Test
    fun storedVariantRequeuesUnderTheSameFileName(): Unit = runBlocking {
        val variant = CatalogFixtures.file(name = "tetris.zip")
        val out = impl(tombstone(variant)).downloadAgain(42L)
        assertEquals(1L, (out as DownloadStart.Started).downloadId)
        // Same target file name: ingest lands on the old relPath and revives the row.
        assertTrue(engine.calls.single().endsWith(":tetris.gb"))
        assertTrue(engine.calls.single().contains(variant.variantKey))
    }

    @Test
    fun missingStoredVariantFallsBackToTheVariantsRule(): Unit = runBlocking {
        source.variantsResult = { ref -> listOf(CatalogFixtures.file(ref = ref, name = "tetris-v2.zip")) }
        val out = impl(tombstone(variant = null)).downloadAgain(42L)
        assertEquals(1L, (out as DownloadStart.Started).downloadId)
        assertTrue(source.calls.contains("variants:gb/tetris"))
        assertTrue(engine.calls.single().endsWith(":tetris.gb"))
    }

    @Test
    fun missingRowFails(): Unit = runBlocking {
        assertTrue(impl().downloadAgain(99L) is DownloadStart.Failed)
    }
}
