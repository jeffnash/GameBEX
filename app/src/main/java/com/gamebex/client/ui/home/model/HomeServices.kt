package com.gamebex.client.ui.home.model

import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.net.DownloadEngine
import com.gamebex.source.RomEntry
import kotlinx.coroutines.flow.StateFlow

data class SaveInfo(val newestMs: Long, val count: Int, val thumbnail: java.io.File?)

/** Emulator saves for a library game. Blocking file IO: call off the main thread. */
interface SaveIndex {
    fun lookup(game: GameEntity): SaveInfo?
    /** Drop caches (Home re-entry, after an emulator exits). */
    fun invalidate()
}

interface SiteSearch {
    /** Selected ROM site plugin's display name; null = none installed/enabled. */
    val pluginName: StateFlow<String?>
    suspend fun search(query: String): SiteResult
    /** Default-download size for [entry]; cached per sourceId+itemKey; null = unknown. */
    suspend fun size(entry: RomEntry): SizeInfo?
}

sealed interface SiteResult {
    data class Found(val entries: List<RomEntry>) : SiteResult
    data object NoPlugin : SiteResult
    data object Offline : SiteResult
    data class Failed(val message: String) : SiteResult
}

interface HomeDownloads {
    val jobs: StateFlow<Map<Long, DownloadEngine.DlSnapshot>>
    /** Download ids armed for Play when ready. */
    val armed: StateFlow<Set<Long>>
    suspend fun downloadAgain(gameId: Long): DownloadStart
    suspend fun downloadFromSite(entry: RomEntry): DownloadStart
    fun togglePlayWhenReady(downloadId: Long)
    fun pause(downloadId: Long)
    fun resume(downloadId: Long)
}

sealed interface DownloadStart {
    data class Started(val downloadId: Long) : DownloadStart
    /** Several versions: open the site entry page. */
    data object NeedsChoice : DownloadStart
    data class Failed(val message: String) : DownloadStart
}

interface DeviceStatus {
    val online: StateFlow<Boolean>
    /** Free bytes on the volume holding [folder]'s games (SD or internal); null = unknown. */
    fun freeBytes(folder: String?): Long?
    /** Systems that have games and are not Ready (System screen attention rule). */
    suspend fun systemWarnings(): Int
}
