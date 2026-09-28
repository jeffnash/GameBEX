package com.gamebex.client.together.session

import com.gamebex.client.data.downloadFileNameFor
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.GameDao
import com.gamebex.client.net.DestinationBusyException
import com.gamebex.client.net.DownloadController
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.net.RomFingerprint
import com.gamebex.client.sources.InstalledSource
import com.gamebex.client.together.DownloadResult
import com.gamebex.client.together.DownloadState
import com.gamebex.client.together.MatchResult
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.TogetherLibrary
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherSupport
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.UserErrors
import com.gamebex.client.ui.home.model.DownloadStart
import com.gamebex.client.ui.home.model.HomeDownloads
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.GameChoice
import com.gamebex.source.DownloadFile
import com.gamebex.source.RomEntry
import com.gamebex.source.SourceRef
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class TogetherLibraryImpl(
    private val dao: GameDao,
    private val downloads: DownloadController,
    private val homeDownloads: HomeDownloads,
    private val sources: StateFlow<List<InstalledSource>>,
    private val fingerprint: suspend (GameEntity) -> RomFingerprint?,
    private val launchFile: suspend (GameEntity) -> File?,
    private val timing: TogetherTiming = TogetherTiming(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : TogetherLibrary {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun game(gameId: Long): GameEntity? = withContext(Dispatchers.IO) {
        dao.findById(gameId)
    }

    override suspend fun offer(gameId: Long): OfferedGame? = withContext(Dispatchers.IO) {
        val game = dao.findById(gameId) ?: return@withContext null
        if (!game.available || game.deletedAtMs > 0) return@withContext null
        val file = launchFile(game)?.takeIf { it.isFile } ?: return@withContext null
        val fp = fingerprint(game)
        val mode = TogetherSupport.modeFor(game) ?: return@withContext null
        OfferedGame(
            title = game.title,
            folder = game.folder,
            fileName = file.name,
            fileSize = file.length(),
            crc = fp?.crc,
            romSize = fp?.size,
            core = TogetherSupport.coreFor(game, mode),
            sourceId = game.sourceId,
            sourceItemKey = game.sourceItemKey,
            sourceRefJson = game.sourceRefJson,
            sourceVariantJson = game.sourceVariantJson,
        )
    }

    override suspend fun match(offer: OfferedGame, mode: TogetherMode): MatchResult =
        withContext(Dispatchers.IO) {
            val downloadable = sourceName(offer) != null && offer.sourceRefJson != null
            GameMatcher(
                rows = { folders -> dao.listPlayableInFolders(folders.toList()) },
                fingerprint = fingerprint,
                launchFileLength = { game -> launchFile(game)?.length() },
            ).match(offer, mode, downloadable)
        }

    override suspend fun gbChoices() = withContext(Dispatchers.IO) {
        dao.listPlayableInFolders(TogetherSupport.GB_FOLDERS.toList())
            .map { GameChoice(it.id, it.title, it.folder) }
    }

    override suspend fun sourceName(offer: OfferedGame): String? {
        val source = sources.value.firstOrNull {
            it.id == offer.sourceId && it.enabled && it.available
        }
        return source?.name?.takeIf { it.isNotBlank() } ?: source?.id
    }

    override suspend fun download(offer: OfferedGame): DownloadResult = withContext(Dispatchers.IO) {
        val sourceId = offer.sourceId
        val itemKey = offer.sourceItemKey
        if (sourceId == null || itemKey == null) {
            return@withContext DownloadResult.Failed(TogetherCopy.downloadFailed)
        }
        val known = dao.listPlayableBySourceItem(sourceId, itemKey).map { it.id }.toSet()
        val ref = runCatching {
            offer.sourceRefJson?.let { json.decodeFromString(SourceRef.serializer(), it) }
        }.getOrNull() ?: return@withContext DownloadResult.Failed(
            TogetherCopy.downloadFailed,
        )
        val entry = RomEntry(ref, offer.title, platformId = offer.folder)
        val file = runCatching {
            offer.sourceVariantJson?.let { json.decodeFromString(DownloadFile.serializer(), it) }
        }.getOrNull()?.takeIf {
            it.ref.sourceId == ref.sourceId && it.ref.itemKey == ref.itemKey
        }
        try {
            if (file != null) {
                val id = downloads.enqueueSource(entry, file, null, downloadFileNameFor(file.name))
                DownloadResult.Started(id, known)
            } else {
                when (val result = homeDownloads.downloadFromSite(entry)) {
                    is DownloadStart.Started -> DownloadResult.Started(result.downloadId, known)
                    DownloadStart.NeedsChoice -> DownloadResult.Failed(
                        TogetherCopy.downloadNeedsChoice,
                    )
                    is DownloadStart.Failed -> DownloadResult.Failed(result.message)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DestinationBusyException) {
            DownloadResult.Failed(TogetherCopy.alreadyDownloading(offer.title))
        } catch (e: Exception) {
            DownloadResult.Failed(UserErrors.forCatalog(e, sourceName(offer) ?: sourceId))
        }
    }

    override fun downloadState(
        jobId: Long,
        offer: OfferedGame,
        knownIds: Set<Long>,
    ): Flow<DownloadState> = flow {
        val sourceId = offer.sourceId
        val itemKey = offer.sourceItemKey
        if (sourceId == null || itemKey == null) {
            emit(DownloadState.Failed(TogetherCopy.downloadFailed))
            return@flow
        }
        val started = nowMs()
        while (nowMs() - started <= timing.downloadGiveUpMs) {
            val row = dao.findPlayableBySourceItem(sourceId, itemKey)
            if (row != null && row.id !in knownIds) {
                emit(DownloadState.Done(row.id))
                return@flow
            }
            val snapshot = downloads.jobs.value[jobId]
            when {
                snapshot == null -> {
                    val elapsed = nowMs() - started
                    emit(DownloadState.Progress(if (elapsed < 15_000L) 0 else 100))
                }
                snapshot.status == DownloadEngine.ST_QUEUED -> emit(
                    DownloadState.Progress(percent(snapshot.read, snapshot.total)),
                )
                snapshot.status == DownloadEngine.ST_ACTIVE -> emit(
                    DownloadState.Progress(percent(snapshot.read, snapshot.total)),
                )
                snapshot.status == DownloadEngine.ST_PAUSED -> emit(
                    DownloadState.Progress(percent(snapshot.read, snapshot.total)),
                )
                snapshot.status == DownloadEngine.ST_INGEST || snapshot.status == DownloadEngine.ST_DONE ->
                    emit(DownloadState.Progress(100))
                snapshot.status == DownloadEngine.ST_FAILED ||
                    snapshot.status == DownloadEngine.ST_SOURCE_UNAVAILABLE -> {
                    emit(DownloadState.Failed(snapshot.error ?: TogetherCopy.downloadFailed))
                    return@flow
                }
                snapshot.status == DownloadEngine.ST_NEEDS_ASSIST -> {
                    emit(DownloadState.Failed(TogetherCopy.downloadNeedsAssist))
                    return@flow
                }
                snapshot.status == DownloadEngine.ST_PARKED_SD -> {
                    emit(DownloadState.Failed(TogetherCopy.downloadParkedSd))
                    return@flow
                }
            }
            delay(timing.downloadTickMs)
        }
        emit(DownloadState.Failed(TogetherCopy.downloadFailed))
    }.flowOn(Dispatchers.IO)

    private fun percent(read: Long, total: Long): Int =
        if (total > 0L) (read * 100L / total).toInt() else 0
}
