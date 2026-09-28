package com.gamebex.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.gamebex.client.cocoon.CocoonDirs
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.net.DownloadEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One user-visible download: durable Room history with live engine
 * progress overlaid by id. Artwork transfers are filtered out (they are
 * library enrich, not user downloads).
 */
data class DownloadRow(
    val id: Long,
    val title: String,
    val status: String,
    val read: Long,
    val total: Long,
    val error: String?,
    val live: Boolean,
    val sourceId: String? = null,
    /** Resolved display name for the owning source, if known. */
    val sourceLabel: String? = null,
    val transferKind: String = "INTERNAL",
    /** Target file name; disambiguates duplicate titles. */
    val fileName: String = "",
    /** Platform folder (PlatformRegistry key), when known. */
    val platformFolder: String? = null,
    /** Last durable state change (completion time for done rows). */
    val updatedAtMs: Long = 0,
)

/**
 * Host-owned art recognition: raw dest-dir jobs and ScreenScraper's
 * `ss://` page marker (source artwork uses transferKind=ART).
 */
fun isArtTransfer(pageUrl: String?, destDirAbs: String?): Boolean =
    destDirAbs != null || pageUrl?.startsWith("ss://") == true

fun downloadStateLabel(status: String): String = when (status) {
    DownloadEngine.ST_QUEUED -> "Queued"
    DownloadEngine.ST_ACTIVE -> "Downloading"
    DownloadEngine.ST_PAUSED -> "Paused"
    DownloadEngine.ST_PARKED_SD -> "SD ejected — parked"
    DownloadEngine.ST_NEEDS_ASSIST -> "Needs verification"
    DownloadEngine.ST_SOURCE_UNAVAILABLE -> "Source unavailable"
    DownloadEngine.ST_FAILED -> "Failed"
    DownloadEngine.ST_INGEST -> "Finishing…"
    DownloadEngine.ST_DONE -> "Done"
    else -> status
}

/** Artwork filter: transferKind first, then host-owned art markers. */
private fun isArtRow(transferKind: String, pageUrl: String?, destDirAbs: String?): Boolean =
    transferKind == "ART" || isArtTransfer(pageUrl, destDirAbs)

/**
 * Merge durable history (newest first) with live snapshots by id. Live
 * jobs missing from the history window are prepended; art transfers are
 * dropped from both sides. Pure; tested.
 */
fun mergeDownloadRows(
    history: List<DownloadRecord>,
    live: Map<Long, DownloadEngine.DlSnapshot>,
    sourceNames: Map<String, String> = emptyMap(),
): List<DownloadRow> {
    val rows = history
        .filterNot { isArtRow(it.transferKind, it.pageUrl, it.destDirAbs) }
        .map { rec ->
            val snap = live[rec.id]
            val sourceId = snap?.sourceId ?: rec.sourceId
            DownloadRow(
                id = rec.id, title = rec.title,
                status = snap?.status ?: rec.status,
                read = snap?.read ?: rec.bytes,
                total = snap?.total ?: rec.totalBytes,
                error = snap?.error ?: rec.error,
                live = snap != null,
                sourceId = sourceId,
                sourceLabel = sourceId?.let { sourceNames[it] ?: it },
                transferKind = snap?.transferKind ?: rec.transferKind,
                fileName = snap?.fileName?.takeIf { it.isNotEmpty() } ?: rec.fileName,
                platformFolder = rec.platformFolder,
                updatedAtMs = rec.updatedAtMs,
            )
        }
    val extras = live.values
        .filter { snap -> history.none { it.id == snap.id } && !isArtRow(snap.transferKind, snap.pageUrl, null) }
        .map { snap ->
            DownloadRow(
                id = snap.id, title = snap.title, status = snap.status,
                read = snap.read, total = snap.total, error = snap.error, live = true,
                sourceId = snap.sourceId,
                sourceLabel = snap.sourceId?.let { sourceNames[it] ?: it },
                transferKind = snap.transferKind,
                fileName = snap.fileName,
            )
        }
    return extras + rows
}

class DownloadsViewModel(
    val engine: DownloadEngine,
    /** Hides finished rows from the console list; Room history is kept. */
    val dismissals: DownloadDismissals = DownloadDismissals(null),
) : ViewModel() {
    /** Durable history incl. completions — survives restarts. */
    val history: StateFlow<List<DownloadRecord>> = engine.history(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun pause(id: Long) = engine.pause(id)
    fun resume(id: Long) = engine.resume(id)
    fun cancel(id: Long) = engine.cancel(id)

    /** Console lists hide completed rows; the history holder supports it. */
    val canClearCompleted: Boolean get() = true

    /**
     * Completed rows are removed from the Downloads list only. The Room
     * record is kept on purpose: Game Detail's versions list and version
     * delete read it (findBySourceItem), so deleting it would orphan files.
     */
    fun removeCompleted(id: Long) = removeCompleted(listOf(id))
    fun removeCompleted(ids: Collection<Long>) {
        dismissals.dismiss(ids, history.value.mapTo(HashSet()) { it.id })
    }

    private val _storage = MutableStateFlow<DownloadStorage?>(null)
    val storage: StateFlow<DownloadStorage?> = _storage

    /** Download destination root + free space (IO; screen refreshes it). */
    fun refreshStorage(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            _storage.value = runCatching {
                val root = CocoonDirs.root(app)
                val existing = generateSequence(root) { it.parentFile }.firstOrNull { it.exists() } ?: root
                DownloadStorage(
                    label = downloadVolumeLabel(root.absolutePath),
                    path = root.absolutePath,
                    freeBytes = existing.usableSpace,
                )
            }.getOrNull()
        }
    }

    /** Library row for a finished download (null until ingest lands). */
    fun openGame(id: Long, cb: (Long?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val gid = engine.gameIdForDownload(id)
            withContext(Dispatchers.Main) { cb(gid) }
        }
    }
}
