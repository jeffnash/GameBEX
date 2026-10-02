package com.gamebex.client.together.session

import com.gamebex.client.data.local.FolderCount
import com.gamebex.client.data.local.GameDao
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.PrepCount
import com.gamebex.client.net.DestinationBusyException
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.net.FakeDownloadController
import com.gamebex.client.net.RomFingerprint
import com.gamebex.client.sources.InstalledSource
import com.gamebex.client.together.DownloadResult
import com.gamebex.client.together.DownloadState
import com.gamebex.client.together.GameChoice
import com.gamebex.client.together.MatchResult
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.ui.home.model.DownloadStart
import com.gamebex.client.ui.home.model.HomeDownloads
import com.gamebex.source.DownloadFile
import com.gamebex.source.RomEntry
import com.gamebex.source.SourceRef
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherLibraryImplTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun gameReturnsTheStoredRow() = runTest {
        val dao = MemoryDao()
        val row = game(7, "Zelda")
        dao.games[7] = row
        assertEquals(row, library(dao).game(7))
        assertNull(library(dao).game(8))
    }

    @Test fun offerRejectsMissingUnavailableDeletedAndUnplayableRows() = runTest {
        val dao = MemoryDao()
        val lib = library(dao, launchFile = { null })
        assertNull(lib.offer(1))
        dao.games[1] = game(1, "Gone", available = false)
        assertNull(lib.offer(1))
        dao.games[1] = game(1, "Tomb", deletedAtMs = 9)
        assertNull(lib.offer(1))
        dao.games[1] = game(1, "No file")
        assertNull(lib.offer(1))
 // Wii has a mode now (Stream); Game Boy Advance still has none.
        dao.games[1] = game(1, "GBA", folder = "gba")
        val file = File.createTempFile("gba", ".gba")
        assertNull(library(dao, launchFile = { file }).offer(1))
        file.delete()
    }

    @Test fun offerBuildsTheHostedGameFromTheLaunchFile() = runTest {
        val dao = MemoryDao()
        val file = File.createTempFile("zelda", ".sfc").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val ref = SourceRef("shop", "item", "https://shop/item")
        val variant = DownloadFile(ref, "usa", "zelda.sfc", pageUrl = ref.pageUrl)
        dao.games[1] = game(1, "Zelda").copy(
            sourceId = "shop",
            sourceItemKey = "item",
            sourceRefJson = json.encodeToString(SourceRef.serializer(), ref),
            sourceVariantJson = json.encodeToString(DownloadFile.serializer(), variant),
        )
        val offered = library(
            dao,
            launchFile = { file },
            fingerprint = { RomFingerprint("AABBCCDD", 4, file.name) },
        ).offer(1)
        assertEquals(
            OfferedGame(
                title = "Zelda",
                folder = "snes",
                fileName = file.name,
                fileSize = 4,
                crc = "AABBCCDD",
                romSize = 4,
                core = "snes9x",
                sourceId = "shop",
                sourceItemKey = "item",
                sourceRefJson = dao.games[1]!!.sourceRefJson,
                sourceVariantJson = dao.games[1]!!.sourceVariantJson,
            ),
            offered,
        )
        file.delete()
    }

    @Test fun gbChoicesListsOnlyGameBoyRows() = runTest {
        val dao = MemoryDao()
        dao.games[1] = game(1, "Blue", folder = "gb")
        dao.games[2] = game(2, "Zelda", folder = "snes")
        dao.games[3] = game(3, "Red", folder = "gbc")
        assertEquals(
            listOf(GameChoice(1, "Blue", "gb"), GameChoice(3, "Red", "gbc")),
            library(dao).gbChoices(),
        )
    }

    @Test fun sourceNameUsesTheEnabledSourceOrItsId() = runTest {
        val offer = offer().copy(sourceId = "shop")
        val named = library(sources = listOf(InstalledSource("shop", "1", name = "Shop")))
        assertEquals("Shop", named.sourceName(offer))
        val blank = library(sources = listOf(InstalledSource("shop", "1", name = " ")))
        assertEquals("shop", blank.sourceName(offer))
        val off = library(sources = listOf(InstalledSource("shop", "1", enabled = false, name = "Shop")))
        assertNull(off.sourceName(offer))
    }

    @Test fun matchUsesTheLibraryRows() = runTest {
        val dao = MemoryDao()
        val local = game(4, "Zelda").copy(sourceId = "shop", sourceItemKey = "item")
        dao.games[4] = local
        val file = File.createTempFile("match", ".sfc")
        val result = library(
            dao,
            launchFile = { file },
            fingerprint = { RomFingerprint("AABBCCDD", 4, "zelda.sfc") },
        ).match(offer().copy(crc = "AABBCCDD", romSize = 4, sourceId = "shop", sourceItemKey = "item"), TogetherMode.RA_NETPLAY)
        assertEquals(MatchResult.Found(4), result)
        file.delete()
    }

    @Test fun downloadCapturesKnownIdsAndMapsEachStart() = runTest {
        val dao = MemoryDao()
        dao.games[4] = game(4, "Old").copy(sourceId = "shop", sourceItemKey = "item")
        val downloads = FakeDownloadController()
        val home = FakeHomeDownloads()
        val ref = SourceRef("shop", "item", "https://shop/item")
        val variant = DownloadFile(ref, "usa", "zelda.sfc", pageUrl = ref.pageUrl)
        val started = library(dao, downloads, home).download(
            offer().copy(
                sourceId = "shop",
                sourceItemKey = "item",
                sourceRefJson = json.encodeToString(SourceRef.serializer(), ref),
                sourceVariantJson = json.encodeToString(DownloadFile.serializer(), variant),
            ),
        )
        assertEquals(DownloadResult.Started(1, setOf(4)), started)
        assertTrue(downloads.calls.single().contains(":usa:"))

        val other = DownloadFile(ref.copy(itemKey = "other"), "usa", "other.sfc", pageUrl = ref.pageUrl)
        home.result = DownloadStart.NeedsChoice
        val choice = library(dao, downloads, home).download(
            offer().copy(
                sourceId = "shop",
                sourceItemKey = "item",
                sourceRefJson = json.encodeToString(SourceRef.serializer(), ref),
                sourceVariantJson = json.encodeToString(DownloadFile.serializer(), other),
            ),
        )
        assertEquals(DownloadResult.Failed(TogetherCopy.downloadNeedsChoice), choice)

        home.result = DownloadStart.Failed("site down")
        val failed = library(dao, downloads, home).download(
            offer().copy(sourceId = "shop", sourceItemKey = "item", sourceRefJson = json.encodeToString(SourceRef.serializer(), ref)),
        )
        assertEquals(DownloadResult.Failed("site down"), failed)
    }

    @Test fun downloadRejectsAMissingSourceAndABusyDestination() = runTest {
        val plain = library(MemoryDao())
        assertEquals(DownloadResult.Failed(TogetherCopy.downloadFailed), plain.download(offer()))
        assertEquals(
            DownloadResult.Failed(TogetherCopy.downloadFailed),
            plain.download(offer().copy(sourceId = "shop", sourceItemKey = "item", sourceRefJson = "not-json")),
        )
        val downloads = FakeDownloadController().also { it.enqueueError = DestinationBusyException(3) }
        val ref = SourceRef("shop", "item", "https://shop/item")
        val variant = DownloadFile(ref, "usa", "zelda.sfc", pageUrl = ref.pageUrl)
        val busy = library(MemoryDao(), downloads).download(
            offer().copy(
                sourceId = "shop",
                sourceItemKey = "item",
                sourceRefJson = json.encodeToString(SourceRef.serializer(), ref),
                sourceVariantJson = json.encodeToString(DownloadFile.serializer(), variant),
            ),
        )
        assertEquals(DownloadResult.Failed(TogetherCopy.alreadyDownloading("Zelda")), busy)

        downloads.enqueueError = IOException("disk")
        val other = library(MemoryDao(), downloads).download(
            offer().copy(
                sourceId = "shop",
                sourceItemKey = "item",
                sourceRefJson = json.encodeToString(SourceRef.serializer(), ref),
                sourceVariantJson = json.encodeToString(DownloadFile.serializer(), variant),
            ),
        )
        assertEquals(DownloadResult.Failed("Couldn't load this list \u2014 retry."), other)

        downloads.enqueueError = CancellationException("stop")
        var cancelled = false
        try {
            library(MemoryDao(), downloads).download(
                offer().copy(
                    sourceId = "shop",
                    sourceItemKey = "item",
                    sourceRefJson = json.encodeToString(SourceRef.serializer(), ref),
                    sourceVariantJson = json.encodeToString(DownloadFile.serializer(), variant),
                ),
            )
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun downloadStateEmitsDoneWhenANewRowAppears() = runTest {
        val dao = MemoryDao()
        dao.games[9] = game(9, "New").copy(sourceId = "shop", sourceItemKey = "item")
        val state = library(dao).downloadState(1, offer().copy(sourceId = "shop", sourceItemKey = "item"), setOf(4)).first()
        assertEquals(DownloadState.Done(9), state)
    }

    @Test fun downloadStateMapsAMissingJobThenGivesUp() = runTest {
        val clock = ArrayDeque(listOf(0L, 0L, 0L, 0L, 16_000L, 40_000L))
        val lib = library(
            MemoryDao(),
            timing = TogetherTiming(downloadTickMs = 1, downloadGiveUpMs = 30_000),
            nowMs = { clock.removeFirst() },
        )
        val states = lib.downloadState(1, offer().copy(sourceId = "shop", sourceItemKey = "item"), emptySet()).toList()
        assertEquals(
            listOf(DownloadState.Progress(0), DownloadState.Progress(100), DownloadState.Failed(TogetherCopy.downloadFailed)),
            states,
        )
    }

    @Test fun downloadStateMapsQueuedFailedAssistAndParkedJobs() = runTest {
        val downloads = FakeDownloadController()
        val offer = offer().copy(sourceId = "shop", sourceItemKey = "item")
        downloads.emit(snapshot(1, DownloadEngine.ST_QUEUED, read = 25, total = 100))
        assertEquals(DownloadState.Progress(25), library(MemoryDao(), downloads).downloadState(1, offer, emptySet()).first())
        downloads.emit(snapshot(2, DownloadEngine.ST_FAILED, error = "disk"))
        assertEquals(DownloadState.Failed("disk"), library(MemoryDao(), downloads).downloadState(2, offer, emptySet()).first())
        downloads.emit(snapshot(3, DownloadEngine.ST_NEEDS_ASSIST))
        assertEquals(DownloadState.Failed(TogetherCopy.downloadNeedsAssist), library(MemoryDao(), downloads).downloadState(3, offer, emptySet()).first())
        downloads.emit(snapshot(4, DownloadEngine.ST_PARKED_SD))
        assertEquals(DownloadState.Failed(TogetherCopy.downloadParkedSd), library(MemoryDao(), downloads).downloadState(4, offer, emptySet()).first())
        downloads.emit(snapshot(5, DownloadEngine.ST_INGEST))
        val ingest = library(
            MemoryDao(),
            downloads,
            timing = TogetherTiming(downloadTickMs = 1, downloadGiveUpMs = 0),
            nowMs = { 0L },
        ).downloadState(5, offer, emptySet()).first()
        assertEquals(DownloadState.Progress(100), ingest)
    }

    private fun library(
        dao: MemoryDao = MemoryDao(),
        downloads: FakeDownloadController = FakeDownloadController(),
        home: FakeHomeDownloads = FakeHomeDownloads(),
        sources: List<InstalledSource> = emptyList(),
        launchFile: suspend (GameEntity) -> File? = { null },
        fingerprint: suspend (GameEntity) -> RomFingerprint? = { null },
        timing: TogetherTiming = TogetherTiming(),
        nowMs: () -> Long = { 0L },
    ) = TogetherLibraryImpl(
        dao,
        downloads,
        home,
        MutableStateFlow(sources),
        fingerprint,
        launchFile,
        timing,
        nowMs,
    )

    private fun offer() = OfferedGame("Zelda", "snes", "zelda.sfc", 4, core = "snes9x")

    private fun game(
        id: Long,
        title: String,
        folder: String = "snes",
        available: Boolean = true,
        deletedAtMs: Long = 0,
    ) = GameEntity(
        id = id,
        title = title,
        folder = folder,
        volumeId = "volume",
        relPath = "$title.sfc",
        fileName = "$title.sfc",
        sizeBytes = 4,
        mtimeMs = 1,
        ext = "sfc",
        available = available,
        deletedAtMs = deletedAtMs,
    )

    private fun snapshot(id: Long, status: String, read: Long = 0, total: Long = 0, error: String? = null) =
        DownloadEngine.DlSnapshot(id, "Zelda", "zelda.sfc", status, read, total, error, "https://shop/item")

    private class FakeHomeDownloads : HomeDownloads {
        override val jobs = MutableStateFlow<Map<Long, DownloadEngine.DlSnapshot>>(emptyMap())
        override val armed = MutableStateFlow(emptySet<Long>())
        var result: DownloadStart = DownloadStart.Started(9)
        override suspend fun downloadAgain(gameId: Long): DownloadStart = error("unused")
        override suspend fun downloadFromSite(entry: RomEntry): DownloadStart = result
        override fun togglePlayWhenReady(downloadId: Long) = Unit
        override fun pause(downloadId: Long) = Unit
        override fun resume(downloadId: Long) = Unit
    }

    private class MemoryDao : GameDao {
        val games = mutableMapOf<Long, GameEntity>()
        override fun observeAll(): Flow<List<GameEntity>> = flowOf(games.values.toList())
        override fun observeById(id: Long): Flow<GameEntity?> = flowOf(games[id])
        override suspend fun findByPath(vol: String, rel: String): GameEntity? = null
        override suspend fun listByVolume(vol: String): List<GameEntity> = emptyList()
        override suspend fun listPlayableInFolders(folders: List<String>): List<GameEntity> =
            games.values.filter { it.folder in folders && it.available && it.deletedAtMs == 0L }
                .sortedBy { it.title.lowercase() }
        override suspend fun listPlayableBySourceItem(sourceId: String, itemKey: String): List<GameEntity> =
            games.values.filter {
                it.sourceId == sourceId && it.sourceItemKey == itemKey && it.available && it.deletedAtMs == 0L
            }.sortedByDescending { it.id }
        override suspend fun findPlayableBySourceItem(sourceId: String, itemKey: String): GameEntity? =
            listPlayableBySourceItem(sourceId, itemKey).firstOrNull()
        override suspend fun markSeenIds(ids: List<Long>, gen: Long) = Unit
        override suspend fun markStaleUnavailable(vol: String, gen: Long): Int = 0
        override suspend fun markVolumeUnavailable(vol: String): Int = 0
        override suspend fun findById(id: Long): GameEntity? = games[id]
        override suspend fun idsMissingArt(): List<Long> = emptyList()
        override fun observeCountsByFolder(): Flow<List<FolderCount>> = flowOf(emptyList())
        override suspend fun prepCounts(): List<PrepCount> = emptyList()
        override suspend fun upsert(game: GameEntity): Long = game.id
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun upsertAll(games: List<GameEntity>): List<Long> = games.map { it.id }
        override suspend fun setFavorite(id: Long, fav: Boolean) = Unit
        override suspend fun recordPlay(id: Long, now: Long) = Unit
        override suspend fun setCoreOverride(id: Long, core: String?) = Unit
        override suspend fun setEmulatorOverride(id: Long, emu: String?) = Unit
        override suspend fun setPrep(id: Long, state: String, launch: String?, note: String?) = Unit
        override suspend fun setArtSlots(id: Long, box: String?, hero: String?, logo: String?, shot: String?, source: String, ss: Long) = Unit
        override suspend fun setScanRefreshed(id: Long, title: String, size: Long, mtime: Long, avail: Boolean, ss: Long) = Unit
        override suspend fun setAvailable(id: Long, avail: Boolean) = Unit
        override suspend fun markDeleted(id: Long, now: Long) = Unit
        override suspend fun setAvailableAll(ids: List<Long>) = Unit
        override suspend fun setIngested(
            id: Long, title: String, size: Long, mtime: Long,
            coverUrl: String?, desc: String?, source: String,
            sourceId: String?, itemKey: String?, refJson: String?, variantJson: String?,
        ) = Unit
        override fun observeBySourceItem(sourceId: String, itemKey: String): Flow<GameEntity?> = emptyFlow()
        override suspend fun setSourceReference(id: Long, sourceId: String, itemKey: String, refJson: String, variantJson: String?) = Unit
        override suspend fun setCoverPath(id: Long, path: String) = Unit
        override suspend fun setArtHero(id: Long, path: String) = Unit
    }
}
