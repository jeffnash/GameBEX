package com.gamebex.client.net

import android.util.Log
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.downloadFileNameFor
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.sources.SourceAccess
import com.gamebex.client.ui.QuickDownloadPlan
import com.gamebex.client.ui.UserErrors
import com.gamebex.client.ui.home.model.DownloadStart
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeDownloads
import com.gamebex.client.ui.quickDownloadPlan
import com.gamebex.source.DownloadFile
import com.gamebex.source.RomEntry
import com.gamebex.source.SourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * [downloadAgain] rebuilds the entry and picked variant from the tombstone
 * row's provenance JSON and re-enqueues onto the same file name, so the
 * download lands on the same relPath and ingest revives the row (play counts
 * and saves survive). Without a usable stored variant the site's variant rule
 * decides, as for a fresh site download.
 */
class HomeDownloadsImpl(
    private val engine: DownloadController,
    private val sources: SourceAccess,
    private val db: LibraryDb,
) : HomeDownloads {
    private val json = Json { ignoreUnknownKeys = true }

    override val jobs: StateFlow<Map<Long, DownloadEngine.DlSnapshot>> = engine.jobs

    override val armed: StateFlow<Set<Long>> = PlayWhenReady.armed

    override suspend fun downloadAgain(gameId: Long): DownloadStart {
        val game = db.games().findById(gameId)
            ?: return DownloadStart.Failed(HomeCopy.gameGone)
        val refJson = game.sourceRefJson
            ?: return DownloadStart.Failed(HomeCopy.sourceGone)
        val ref = try {
            json.decodeFromString(SourceRef.serializer(), refJson)
        } catch (e: Exception) {
            // A bad saved source record is a read error, not a removed plugin.
            Log.w(TAG, "unreadable source record for game $gameId", e)
            return DownloadStart.Failed(downloadInfoUnreadable)
        }
        val entry = RomEntry(
            ref = ref, title = game.title,
            platformId = game.folder, coverUrl = game.coverUrl,
        )
        val file = game.sourceVariantJson?.let { raw ->
            runCatching { json.decodeFromString(DownloadFile.serializer(), raw) }
                .onFailure { Log.w(TAG, "unreadable variant record for game $gameId", it) }
                .getOrNull()
        }?.takeIf { it.ref.sourceId == ref.sourceId && it.ref.itemKey == ref.itemKey }
        return if (file != null) {
            enqueue(entry, file, game.description, game.fileName)
        } else {
            downloadWithVariants(entry, game.description, game.fileName)
        }
    }

    override suspend fun downloadFromSite(entry: RomEntry): DownloadStart =
        downloadWithVariants(entry, description = null, targetFileName = null)

    override fun togglePlayWhenReady(downloadId: Long) = PlayWhenReady.toggle(downloadId)

    override fun pause(downloadId: Long) = engine.pause(downloadId)

    override fun resume(downloadId: Long) = engine.resume(downloadId)

    /**
     * Same variant rule as Browse's quick download: only an unambiguous, mapped
     * download queues; anything else goes to the entry page. [targetFileName]
     * keeps a re-download on its old relPath.
     */
    private suspend fun downloadWithVariants(
        entry: RomEntry,
        description: String?,
        targetFileName: String?,
    ): DownloadStart {
        val mapped = entry.platformId?.let { PlatformRegistry.byFolder.containsKey(it) } == true
        val files = try {
            withContext(Dispatchers.IO) {
                if (mapped) sources.open(entry.ref.sourceId).variants(entry.ref) else emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DownloadStart.Failed(UserErrors.forCatalog(e, sourceName(entry)))
        }
        return when (quickDownloadPlan(mapped, files.size)) {
            QuickDownloadPlan.ENQUEUE -> enqueue(entry, files.first(), description, targetFileName)
            QuickDownloadPlan.OPEN_DETAIL -> DownloadStart.NeedsChoice
        }
    }

    private suspend fun enqueue(
        entry: RomEntry,
        file: DownloadFile,
        description: String?,
        targetFileName: String?,
    ): DownloadStart = try {
        val id = withContext(Dispatchers.IO) {
            engine.enqueueSource(
                entry, file, description,
                targetFileName = targetFileName ?: downloadFileNameFor(file.name),
            )
        }
        DownloadStart.Started(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: DestinationBusyException) {
        DownloadStart.Failed(HomeCopy.alreadyDownloading(entry.title))
    } catch (e: Exception) {
        DownloadStart.Failed(UserErrors.forCatalog(e, sourceName(entry)))
    }

    private suspend fun sourceName(entry: RomEntry): String? =
        runCatching { sources.open(entry.ref.sourceId).name }.getOrNull()

    companion object {
        private const val TAG = "GameBEXDl"

        /** A saved source record that cannot be read back: a read error, not a removed plugin. */
        const val downloadInfoUnreadable = "Couldn't read this game's download info"
    }
}
