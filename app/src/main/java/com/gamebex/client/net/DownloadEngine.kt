package com.gamebex.client.net

import android.content.Context
import android.util.Log
import com.gamebex.client.cocoon.CocoonDirs
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.LibraryScanner
import com.gamebex.client.data.local.PrepPipeline
import com.gamebex.client.data.local.RomStore
import com.gamebex.client.data.local.Sidecars
import com.gamebex.client.data.downloadFileNameFor
import com.gamebex.client.sources.RevisionPin
import com.gamebex.client.sources.SourceAccess
import com.gamebex.client.sources.SourceEngineHook
import com.gamebex.source.AssistPolicy
import com.gamebex.source.DownloadFile
import com.gamebex.source.RomEntry
import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import com.gamebex.source.SourceHttp
import com.gamebex.source.TransferRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import com.gamebex.source.SOURCE_DL_CONNECT_TIMEOUT_MS
import com.gamebex.source.SOURCE_DL_READ_TIMEOUT_MS
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Another download identity owns the destination; the existing job is kept. */
class DestinationBusyException(val jobId: Long) :
    Exception("Destination is already downloading (job $jobId).")

/** Verified browser session: resolved URL plus the origins it may touch. */
data class AssistSession(
    val jobId: Long,
    val title: String,
    val sourceId: String,
    val revision: String,
    val url: String,
    val allowedOrigins: Set<String>,
    val assist: AssistPolicy,
)

/** UI-facing download surface: implemented by [DownloadEngine], faked in tests. */
interface DownloadController {
    val jobs: StateFlow<Map<Long, DownloadEngine.DlSnapshot>>
    suspend fun enqueueSource(
        entry: RomEntry,
        file: DownloadFile,
        description: String?,
        targetFileName: String = downloadFileNameFor(file.name),
    ): Long
    fun pause(id: Long)
    fun resume(id: Long)
    fun cancel(id: Long)
    fun delete(id: Long)
}

/**
 * Downloads engine: durable ROM queue with pause/resume/cancel,
 * per-file progress, SD-eject parking, and WebView-assist handoff for
 * Cloudflare-challenged file hosts (Donkey Kong case).
 *
 * Headless and UI-agnostic: state in [jobs], history in Room
 * (`downloads`), completion pipeline (scan + library ingest) built in.
 * The console Downloads and game detail screens own the transfer UI.
 *
 * Fetch rules (verified live):
 * mint-then-fetch, same session, no delay, never retry a signed URL.
 * Signed URLs are single-use: every (re)start re-mints; resume sends
 * `Range` and the host answers 206 (append) or 200 (restart from 0).
 */
class DownloadEngine(
    private val appContext: Context,
    val sources: SourceAccess? = null,
) : DownloadController, SourceEngineHook {
    companion object {
        private const val TAG = "GameBEXDl"
        const val ST_QUEUED = "queued"
        const val ST_ACTIVE = "active"
        const val ST_PAUSED = "paused"
        const val ST_PARKED_SD = "parked_sd"
        const val ST_NEEDS_ASSIST = "needs_assist"
        const val ST_FAILED = "failed"
        const val ST_DONE = "done"
        /**
         * Owning source missing/disabled/stale-pinned. Resumable,
         * nonterminal, excluded from getRunnable; never consumes the
         * network retry budget. Explicit resume re-checks availability.
         */
        const val ST_SOURCE_UNAVAILABLE = "source_unavailable"
        /** File published; library ingest pending (durable, retried on launch). */
        const val ST_INGEST = "ingest"
        const val MAX_CONCURRENT = 2
        /** Auto-retry budget for flaky networks: 3 retries, then manual. */
        const val MAX_AUTO_RETRIES = 3
        private val BACKOFF_MS = longArrayOf(2_000L, 8_000L, 30_000L)
        private const val PROGRESS_EMIT_MS = 250L
        private const val PROGRESS_PERSIST_MS = 2_000L

        /** Parsed `Content-Range: bytes <start>-<end>/<total|*>`. */
        data class ContentRange(val start: Long, val end: Long, val total: Long?)

        fun parseContentRange(header: String?): ContentRange? {
            if (header == null) return null
            val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
                .matchEntire(header.trim()) ?: return null
            return ContentRange(
                m.groupValues[1].toLong(), m.groupValues[2].toLong(),
                m.groupValues[3].toLongOrNull(),
            )
        }

        /**
         * Canonical writer identity: same key = same file. SD volumes
         * (FAT) compare case-blind; raw jobs key on the absolute dir.
         */
        fun destKeyFor(volumeId: String, destRelPath: String, destDirAbs: String?): String {
            val raw = (destDirAbs?.let { "$it/" } ?: "$volumeId:") + destRelPath
            return if (volumeId.startsWith("sd:")) raw.lowercase() else raw
        }

        /**
         * Source-job duplicate identity: (sourceId, sourceItemKey,
         * sourceVariantKey, canonical destination). Pure; tested.
         */
        fun sameSourceIdentity(
            rec: DownloadRecord,
            sourceId: String,
            itemKey: String,
            variantKey: String,
            destKey: String,
        ): Boolean =
            rec.sourceId == sourceId && rec.sourceItemKey == itemKey &&
                rec.sourceVariantKey == variantKey &&
                destKeyFor(rec.volumeId, rec.destRelPath, rec.destDirAbs) == destKey

        /** Host-boundary filename gate: slashes become underscores; anything else suspicious is rejected. */
        fun sanitizeTargetFileName(raw: String): String {
            val name = raw.replace('/', '_').replace('\\', '_').trim().trim('.')
            require(name.isNotEmpty() && name != "." && name != "..") { "rejected file name" }
            require(name.none { it == '\u0000' || it < ' ' || it == '\u007f' }) { "rejected file name" }
            return name
        }

        private val LIVE_STATUSES = listOf(
            ST_QUEUED, ST_ACTIVE, ST_PAUSED, ST_PARKED_SD, ST_NEEDS_ASSIST, ST_FAILED, ST_INGEST,
            ST_SOURCE_UNAVAILABLE,
        )

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /**
         * Origin identity for cookie scoping, matching the declared-origin
         * format (`https://host`, default ports omitted).
         */
        fun originOf(url: okhttp3.HttpUrl): String {
            val default = if (url.scheme == "http") 80 else 443
            return "${url.scheme}://${url.host}" + if (url.port == default) "" else ":${url.port}"
        }

        fun sameOrigin(a: okhttp3.HttpUrl, b: okhttp3.HttpUrl): Boolean =
            a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }

    private val provenanceJson = Json { ignoreUnknownKeys = true }

    /** Host-owned direct transfers (cores, BIOS, ScreenScraper art): default UA, redirects followed. */
    private val hostClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(SOURCE_DL_CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(SOURCE_DL_READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(SOURCE_DL_READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder().header("User-Agent", HOST_UA).build(),
                )
            }
            .build()
    }

    /** Live per-file snapshot for progress UI. note = transient "retrying…" line. */
    data class DlSnapshot(
        val id: Long,
        val title: String,
        val fileName: String,
        val status: String,
        val read: Long,
        val total: Long,
        val error: String?,
        val pageUrl: String,
        val note: String? = null,
        val sourceId: String? = null,
        val transferKind: String = "INTERNAL",
    )

    private sealed interface Outcome {
        data object Done : Outcome
        data object Stop : Outcome
        data class Retry(val delayMs: Long) : Outcome
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val db by lazy { LibraryDb.get(appContext) }
    private val running = ConcurrentHashMap<Long, Job>()
    /** Running jobs the player started (not cover art): only these hold the foreground service. */
    private val visibleRunning: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val calls = ConcurrentHashMap<Long, Call>()
    /** Cancel is delete-after-stop: tombstoned so pump can't restart mid-cancel. */
    private val dead = ConcurrentHashMap.newKeySet<Long>()
    private val pumpMutex = Mutex()
    /** Serializes queue-control transitions (pause/resume/cancel/park...). */
    private val controlMutex = Mutex()
    /** Serializes enqueue's lookup-and-insert so double-taps can't double-insert. */
    private val enqueueMutex = Mutex()
    /** Publication + library attach for finished downloads. */
    private val ingest by lazy { DownloadIngest(db, appContext, scope, ::destDir) }

    /**
     * Stop a live job: unblock its socket read, then join the coroutine.
     * Ownership stays registered until the worker's own finally retires
     * it under the pump lock — removing here would let pump start a
     * duplicate run for the same id mid-join.
     */
    private suspend fun stopJob(id: Long) {
        calls.remove(id)?.cancel()
        running[id]?.cancelAndJoin()
    }

    private val _jobs = MutableStateFlow<Map<Long, DlSnapshot>>(emptyMap())
    override val jobs: StateFlow<Map<Long, DlSnapshot>> = _jobs

    /** History incl. completions, newest first. */
    fun history(limit: Int = 100): Flow<List<DownloadRecord>> = db.downloads().observeRecent(limit)

    /**
     * Enqueue a host-owned direct transfer (artwork, cores, BIOS). All ROM
     * callers use [enqueueSource]; this path requires an explicit directUrl.
     * Returns the existing live job id when the same page+file — or the same
     * canonical destination — is already queued (double-tap safe, one writer
     * per file). Unknown platform folders are rejected; a null folder with
     * an explicit dest dir means raw delivery (skips ROM ingest).
     */
    suspend fun enqueue(
        pageUrl: String,
        title: String,
        fileName: String,
        platformFolder: String?,
        coverUrl: String? = null,
        description: String? = null,
        consoleSlug: String? = null,
        directUrl: String? = null,
        /** Raw delivery: absolute dest dir (e.g. art store). Skips ROM ingest. */
        destDirAbs: File? = null,
    ): Long = withContext(Dispatchers.IO) {
        require(!directUrl.isNullOrBlank()) { "host-owned transfers require directUrl" }
        val name = sanitizeTargetFileName(fileName)
        // Claim-check + insert run under one lock: two concurrent enqueues
        // for the same page+file must not both insert (same .part race).
        // (return@withContext is legal here — withLock is inline.)
        enqueueMutex.withLock {
            if (destDirAbs == null && !platformFolder.isNullOrBlank() &&
                !PlatformRegistry.byFolder.containsKey(platformFolder)
            ) {
                throw IllegalArgumentException("Unknown platform folder: $platformFolder")
            }
            val dao = db.downloads()
            val dir = destDirAbs ?: CocoonDirs.dirFor(appContext, platformFolder)
            val vol = volumeIdFor(dir) ?: "internal"
            val destRelPath = relativeToRoot(vol, dir, name)
            val destKey = destKeyFor(vol, destRelPath, destDirAbs?.absolutePath)
            val live = dao.getByStatuses(LIVE_STATUSES)
            live.firstOrNull { it.pageUrl == pageUrl && it.fileName == name }?.let {
                if (it.status == ST_FAILED || it.status == ST_PAUSED) resume(it.id)
                return@withContext it.id
            }
            // One writer per canonical destination: a different caller
            // targeting the same file joins the live job instead of racing it.
            live.firstOrNull { destKeyFor(it.volumeId, it.destRelPath, it.destDirAbs) == destKey }?.let {
                return@withContext it.id
            }
            insertAndStart(
                dao,
                DownloadRecord(
                    pageUrl = pageUrl, title = title,
                    destRelPath = destRelPath, volumeId = vol,
                    bytes = 0, status = ST_QUEUED,
                    createdAtMs = now(), updatedAtMs = now(),
                    fileName = name, platformFolder = platformFolder,
                    coverUrl = coverUrl, description = description,
                    consoleSlug = consoleSlug, directUrl = directUrl,
                    destDirAbs = destDirAbs?.absolutePath,
                ),
            )
        }
    }

    /**
     * Enqueue a source ROM download. Duplicate identity is (sourceId,
     * sourceItemKey, sourceVariantKey, canonical destination): same-identity
     * double-taps return the existing job, while another identity owning
     * the destination throws [DestinationBusyException] (the existing job is
     * kept — never returned as though it were this game). Completed-file
     * replacement keeps the existing explicit confirmation workflow.
     */
    override suspend fun enqueueSource(
        entry: RomEntry,
        file: DownloadFile,
        description: String?,
        targetFileName: String,
    ): Long = withContext(Dispatchers.IO) {
        require(file.ref.sourceId == entry.ref.sourceId && file.ref.itemKey == entry.ref.itemKey) {
            "file does not belong to entry"
        }
        require(file.variantKey.isNotBlank()) { "blank variantKey" }
        val folder = entry.platformId?.takeIf { PlatformRegistry.byFolder.containsKey(it) }
            ?: throw IllegalArgumentException("Unknown platform: ${entry.platformId}")
        val name = sanitizeTargetFileName(targetFileName)
        val access = sources ?: throw IllegalStateException("sources unavailable")
        // Pins the enabled immutable revision; throws when missing/disabled.
        val revision = access.open(entry.ref.sourceId).revision
        enqueueMutex.withLock {
            val dao = db.downloads()
            val dir = CocoonDirs.dirFor(appContext, folder)
            requireContained(dir, File(dir, name))
            val vol = volumeIdFor(dir) ?: "internal"
            val destRelPath = relativeToRoot(vol, dir, name)
            val destKey = destKeyFor(vol, destRelPath, null)
            val live = dao.getByStatuses(LIVE_STATUSES)
            live.firstOrNull {
                sameSourceIdentity(it, entry.ref.sourceId, entry.ref.itemKey, file.variantKey, destKey)
            }?.let {
                if (it.status == ST_FAILED || it.status == ST_PAUSED) resume(it.id)
                return@withContext it.id
            }
            live.firstOrNull { destKeyFor(it.volumeId, it.destRelPath, it.destDirAbs) == destKey }?.let {
                throw DestinationBusyException(it.id)
            }
            insertAndStart(
                dao,
                DownloadRecord(
                    pageUrl = file.pageUrl, title = entry.title,
                    destRelPath = destRelPath, volumeId = vol,
                    bytes = 0, status = ST_QUEUED,
                    createdAtMs = now(), updatedAtMs = now(),
                    fileName = name, platformFolder = folder,
                    coverUrl = entry.coverUrl, description = description,
                    consoleSlug = PlatformRegistry.byFolder[folder]?.slug,
                    sourceId = entry.ref.sourceId,
                    sourceItemKey = entry.ref.itemKey,
                    sourceVariantKey = file.variantKey,
                    sourceRevision = revision,
                    sourceFileJson = provenanceJson.encodeToString(DownloadFile.serializer(), file),
                    transferKind = "ROM",
                ),
            )
        }
    }

    /**
     * Internal source-artwork entry (ArtWorker only). Pins the source
     * revision; the fetch path re-validates availability and the
     * definition's artwork-origin policy before every attempt.
     * [destDir] is host-computed (ArtStore); the schema cannot supply it.
     * Page marker is `art://<gameId>/<slot>`; classification uses
     * transferKind=ART, not marker parsing.
     */
    suspend fun enqueueSourceArt(
        sourceId: String,
        revision: String,
        url: String,
        gameId: Long,
        slot: String,
        destDir: File,
        fileName: String,
    ): Long = withContext(Dispatchers.IO) {
        require(sourceId.isNotBlank() && revision.isNotBlank()) { "source art needs a pinned revision" }
        require(url.isNotBlank()) { "source art needs a URL" }
        val access = sources ?: throw IllegalStateException("sources unavailable")
        // Fail fast when the pin is stale/missing; fetch rechecks anyway.
        access.open(sourceId, revision)
        val name = sanitizeTargetFileName(fileName)
        enqueueMutex.withLock {
            val dao = db.downloads()
            requireContained(destDir, File(destDir, name))
            val vol = volumeIdFor(destDir) ?: "internal"
            val destRelPath = relativeToRoot(vol, destDir, name)
            val destKey = destKeyFor(vol, destRelPath, destDir.absolutePath)
            val marker = "art://$gameId/$slot"
            val live = dao.getByStatuses(LIVE_STATUSES)
            live.firstOrNull { it.pageUrl == marker && it.fileName == name }?.let {
                if (it.status == ST_FAILED || it.status == ST_PAUSED) resume(it.id)
                return@withContext it.id
            }
            live.firstOrNull { destKeyFor(it.volumeId, it.destRelPath, it.destDirAbs) == destKey }?.let {
                return@withContext it.id
            }
            insertAndStart(
                dao,
                DownloadRecord(
                    pageUrl = marker, title = "art $gameId/$slot",
                    destRelPath = destRelPath, volumeId = vol,
                    bytes = 0, status = ST_QUEUED,
                    createdAtMs = now(), updatedAtMs = now(),
                    fileName = name, directUrl = url,
                    destDirAbs = destDir.absolutePath,
                    sourceId = sourceId, sourceRevision = revision,
                    transferKind = "ART",
                ),
            )
        }
    }

    /** Shared insert + snapshot + pump; callers hold [enqueueMutex]. */
    private suspend fun insertAndStart(
        dao: com.gamebex.client.data.local.DownloadDao,
        rec: DownloadRecord,
    ): Long {
        val id = dao.insert(rec)
        emitSnapshot(dao.getById(id)!!)
        pump()
        return id
    }

    /** Canonical path must stay inside the chosen folder. */
    private fun requireContained(dir: File, dest: File) {
        val root = runCatching { dir.canonicalPath }.getOrNull() ?: return
        val path = runCatching { dest.canonicalPath }.getOrNull()
            ?: throw IllegalArgumentException("rejected file name")
        require(path.startsWith(root + File.separator)) { "destination escapes folder" }
    }

    override fun pause(id: Long) {
        scope.launch(Dispatchers.IO) {
            controlMutex.withLock {
                val dao = db.downloads()
                val rec = dao.getById(id) ?: return@withLock
                if (rec.status != ST_ACTIVE && rec.status != ST_QUEUED) return@withLock
                val have = partFile(rec)?.length() ?: rec.bytes
                dao.update(rec.copy(status = ST_PAUSED, bytes = have, updatedAtMs = now()))
                emitSnapshot(dao.getById(id)!!)
            }
            // Join outside the lock: the worker's terminal write needs it.
            stopJob(id)
            pump()
        }
    }

    override fun resume(id: Long) {
        scope.launch(Dispatchers.IO) {
            val dao = db.downloads()
            if (dao.getById(id)?.status == ST_INGEST) {
                // Stuck library attach: retry ingest, never re-download.
                val gameId = ingest.ingest(dao.getById(id)!!)
                controlMutex.withLock {
                    val cur = dao.getById(id) ?: return@withLock
                    if (gameId != null && gameId > 0) {
                        dao.update(cur.copy(status = ST_DONE, error = null, cookies = null, updatedAtMs = now()))
                    } else if (cur.status == ST_INGEST) {
                        // Terminal ingest outcomes (e.g. an empty published
                        // file) already wrote their own error copy.
                        dao.update(cur.copy(error = "Saved, waiting for library retry.", updatedAtMs = now()))
                    }
                    emitSnapshot(dao.getById(id)!!)
                }
                return@launch
            }
            controlMutex.withLock {
                val rec = dao.getById(id) ?: return@withLock
                if (rec.status !in setOf(
                        ST_PAUSED, ST_FAILED, ST_NEEDS_ASSIST, ST_PARKED_SD,
                        ST_QUEUED, ST_SOURCE_UNAVAILABLE,
                    )
                ) {
                    return@withLock
                }
                if (!volumePresent(rec.volumeId)) {
                    dao.update(rec.copy(status = ST_PARKED_SD, updatedAtMs = now()))
                    emitSnapshot(dao.getById(id)!!)
                    return@withLock
                }
                // Source jobs re-check availability; stale pins stay parked
                // (explicit retry-with-current-revision rebinds them).
                if (needsSource(rec) && !sourceReady(rec)) {
                    parkSource(rec, "Source unavailable.")
                    return@withLock
                }
                dao.update(rec.copy(status = ST_QUEUED, error = null, attempts = 0, updatedAtMs = now()))
                emitSnapshot(dao.getById(id)!!)
            }
            pump()
        }
    }

    /**
     * Explicit stale-pin retry: rebind a source_unavailable job to the
     * current revision after validating the stored variant is still
     * offered. Never silently retargets a changed file list.
     */
    fun retryWithCurrentRevision(id: Long) {
        scope.launch(Dispatchers.IO) {
            val dao = db.downloads()
            val access = sources
            val rec = dao.getById(id)
            if (rec == null || access == null || rec.sourceId == null) return@launch
            val file = rec.sourceFileJson?.let {
                runCatching { provenanceJson.decodeFromString(DownloadFile.serializer(), it) }.getOrNull()
            }
            if (file == null) {
                controlMutex.withLock {
                    dao.getById(id)?.let {
                        dao.update(it.copy(error = "No stored file to retry.", updatedAtMs = now()))
                        emitSnapshot(dao.getById(id)!!)
                    }
                }
                return@launch
            }
            try {
                val source = access.open(rec.sourceId, null)
                val offered = source.variants(file.ref)
                if (offered.none { it.variantKey == file.variantKey }) {
                    controlMutex.withLock {
                        dao.getById(id)?.let {
                            dao.update(
                                it.copy(
                                    error = "That file is no longer offered by the source.",
                                    updatedAtMs = now(),
                                ),
                            )
                            emitSnapshot(dao.getById(id)!!)
                        }
                    }
                    return@launch
                }
                controlMutex.withLock {
                    dao.getById(id)?.let {
                        dao.update(
                            it.copy(
                                sourceRevision = source.revision, status = ST_QUEUED,
                                error = null, attempts = 0, updatedAtMs = now(),
                            ),
                        )
                        emitSnapshot(dao.getById(id)!!)
                    }
                }
            } catch (e: SourceException) {
                if (e.failure == SourceFailure.UNAVAILABLE) {
                    controlMutex.withLock { dao.getById(id)?.let { parkSource(it, "Source unavailable.") } }
                } else {
                    controlMutex.withLock {
                        dao.getById(id)?.let {
                            dao.update(it.copy(error = sourceFailureCopy(e), updatedAtMs = now()))
                            emitSnapshot(dao.getById(id)!!)
                        }
                    }
                }
                return@launch
            }
            pump()
        }
    }

    override fun cancel(id: Long) {
        scope.launch(Dispatchers.IO) {
            dead.add(id)
            try {
                stopJob(id)
                controlMutex.withLock {
                    val dao = db.downloads()
                    val rec = dao.getById(id)
                    runCatching { partFile(rec)?.delete() }
                    if (rec != null) dao.deleteById(id)
                    _jobs.update { it - id }
                }
            } finally {
                dead.remove(id)
            }
            pump()
        }
    }

    /**
     * Version delete: cancel's row+part cleanup PLUS the published file,
     * its sidecar, and prep-owned extract output (marker-gated, never
     * foreign dirs). The library row goes unavailable on next rescan.
     */
    override fun delete(id: Long) {
        scope.launch(Dispatchers.IO) {
            dead.add(id)
            try {
                stopJob(id)
                controlMutex.withLock {
                    val dao = db.downloads()
                    val rec = dao.getById(id)
                    runCatching { partFile(rec)?.delete() }
                    if (rec != null) {
                        runCatching {
                            finalFile(rec)?.let { final ->
                                File(final.parentFile, final.name + ".bak").delete()
                                Sidecars.delete(final)
                                val outDir = File(final.parentFile, final.nameWithoutExtension)
                                if (File(outDir, PrepPipeline.STAGE_MARKER).isFile) {
                                    outDir.deleteRecursively()
                                }
                                final.delete()
                            }
                        }
                        dao.deleteById(id)
                    }
                    _jobs.update { it - id }
                }
            } finally {
                dead.remove(id)
            }
            pump()
        }
    }

    /**
     * WebView assist completed: bind cookie handoff to job ID + source +
     * pinned revision + resolved origin, then re-resolve fresh. Late
     * handoffs after cancellation or source disable are rejected.
     */
    fun provideAssist(id: Long, cookies: String, origin: String) {
        scope.launch(Dispatchers.IO) {
            controlMutex.withLock {
                val dao = db.downloads()
                val rec = dao.getById(id) ?: return@withLock
                if (id in dead || rec.status != ST_NEEDS_ASSIST) {
                    Log.d(TAG, "assist handoff for $id rejected (state=${rec.status})")
                    return@withLock
                }
                if (needsSource(rec) && !sourceReady(rec)) {
                    Log.d(TAG, "assist handoff for $id rejected (source gone)")
                    parkSource(rec, "Source unavailable.")
                    return@withLock
                }
                Log.d(TAG, "assist cookies for $id (${cookies.length} chars, $origin)")
                dao.update(
                    rec.copy(
                        cookies = cookies, assistOrigin = origin,
                        status = ST_QUEUED, error = null, updatedAtMs = now(),
                    ),
                )
                emitSnapshot(dao.getById(id)!!)
            }
            stopJob(id)
            pump()
        }
    }

    /** Registry revocation: stop source-owned workers, park dependent rows. Never deletes queue/partial state. */
    override suspend fun sourceUnavailable(id: String) {
        val stopping = controlMutex.withLock {
            db.downloads().getByStatuses(LIVE_STATUSES)
                .filter { it.sourceId == id && needsSource(it) }
                .map { it.id }
        }
        // Writers stop first; state is written only after they exit.
        stopping.forEach { stopJob(it) }
        if (stopping.isEmpty()) return
        val dao = db.downloads()
        controlMutex.withLock {
            for (jobId in stopping) {
                val rec = dao.getById(jobId) ?: continue
                if (rec.status == ST_DONE || rec.status == ST_INGEST) continue
                val have = partFile(rec)?.length() ?: rec.bytes
                dao.update(
                    rec.copy(
                        status = ST_SOURCE_UNAVAILABLE,
                        error = "Source unavailable.", bytes = have,
                        cookies = null, assistOrigin = null, updatedAtMs = now(),
                    ),
                )
                emitSnapshot(dao.getById(jobId)!!)
            }
        }
    }

    override suspend fun activeRevisionPins(): Set<RevisionPin> =
        db.downloads().revisionPins().toSet()

    /**
     * Verified browser session for a needs_assist job: fresh resolution
     * for the WebView (separate from any later transfer URL), pinned
     * revision, and the definition's declared origins. Null when the job
     * moved on, the variant is gone, or the source is unavailable.
     */
    suspend fun assistSession(jobId: Long): AssistSession? = withContext(Dispatchers.IO) {
        val dao = db.downloads()
        val rec = dao.getById(jobId) ?: return@withContext null
        if (rec.status != ST_NEEDS_ASSIST) return@withContext null
        if (rec.transferKind != "ROM" || rec.sourceId == null) return@withContext null
        val file = decodeSourceFile(rec) ?: return@withContext null
        val access = sources ?: return@withContext null
        try {
            val http = access.httpFor(rec.sourceId, rec.sourceRevision)
            val source = access.open(rec.sourceId, rec.sourceRevision)
            if (!source.assist.enabled) return@withContext null
            val transfer = source.resolveDownload(file)
            val url = http.checkAssistUrl(transfer.url)
            AssistSession(
                jobId = jobId, title = rec.title,
                sourceId = rec.sourceId, revision = rec.sourceRevision ?: source.revision,
                url = url.toString(),
                allowedOrigins = http.allowedOrigins(
                    com.gamebex.source.NetworkPurpose.CATALOG,
                    com.gamebex.source.NetworkPurpose.DOWNLOAD,
                    com.gamebex.source.NetworkPurpose.ASSIST,
                ),
                assist = source.assist,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SourceException) {
            if (e.failure == SourceFailure.UNAVAILABLE) parkSource(rec, "Source unavailable.")
            null
        } catch (e: Exception) {
            Log.w(TAG, "assist session failed for $jobId", e)
            null
        }
    }

    /** Pre-handoff revalidation: job still waiting, source still available. */
    suspend fun validateAssistHandoff(id: Long): Boolean = withContext(Dispatchers.IO) {
        val rec = db.downloads().getById(id) ?: return@withContext false
        if (id in dead || rec.status != ST_NEEDS_ASSIST) return@withContext false
        !needsSource(rec) || sourceReady(rec)
    }

    /** App start: crashed actives re-queue; parked resume only if volume back. */
    fun reconcileAfterLaunch() {
        scope.launch(Dispatchers.IO) {
            val dao = db.downloads()
            controlMutex.withLock {
                dao.getByStatuses(listOf(ST_ACTIVE)).forEach {
                    // Published but never marked (crash in the status write):
                    // skip re-download, go straight to ingest.
                    val final = if (it.destDirAbs == null) finalFile(it) else null
                    if (final?.isFile == true && partFile(it)?.exists() != true) {
                        dao.update(it.copy(status = ST_INGEST, bytes = final.length(), updatedAtMs = now()))
                    } else if (needsSource(it) && !sourceReady(it)) {
                        parkSource(it, "Source unavailable.")
                    } else {
                        dao.update(it.copy(status = ST_QUEUED, updatedAtMs = now()))
                    }
                }
                dao.getByStatuses(listOf(ST_PARKED_SD)).forEach {
                    if (!volumePresent(it.volumeId)) return@forEach
                    if (needsSource(it) && !sourceReady(it)) parkSource(it, "Source unavailable.")
                    else dao.update(it.copy(status = ST_QUEUED, updatedAtMs = now()))
                }
            }
            // Durable ingest: rows the previous process published but never
            // attached to the library. Runs outside the lock (network I/O).
            dao.getByStatuses(listOf(ST_INGEST)).forEach { rec ->
                destDir(rec)?.let { dir ->
                    runCatching { File(dir, rec.fileName + ".tmp").delete() }
                    runCatching { File(dir, rec.fileName + ".bak").delete() }
                }
                val gameId = ingest.ingest(rec)
                controlMutex.withLock {
                    val cur = dao.getById(rec.id)
                    if (cur != null && cur.status == ST_INGEST) {
                        if (gameId != null && gameId > 0) {
                            dao.update(cur.copy(status = ST_DONE, error = null, cookies = null, updatedAtMs = now()))
                        } else {
                            dao.update(cur.copy(error = "Saved, waiting for library retry.", updatedAtMs = now()))
                        }
                        emitSnapshot(dao.getById(rec.id)!!)
                    }
                }
            }
            dao.getByStatuses(
                listOf(
                    ST_QUEUED, ST_PAUSED, ST_FAILED, ST_NEEDS_ASSIST,
                    ST_PARKED_SD, ST_INGEST, ST_SOURCE_UNAVAILABLE,
                ),
            ).forEach { emitSnapshot(it) }
            pump()
        }
    }

    /** Same code path as the MEDIA_EJECT receiver (also the test hook). */
    fun parkVolume(volumeId: String) {
        scope.launch(Dispatchers.IO) {
            val stopping = mutableListOf<Long>()
            controlMutex.withLock {
                val dao = db.downloads()
                dao.getByStatuses(listOf(ST_ACTIVE, ST_QUEUED))
                    .filter { it.volumeId == volumeId }.forEach { rec ->
                        val have = partFile(rec)?.length() ?: rec.bytes
                        dao.update(rec.copy(status = ST_PARKED_SD, bytes = have, updatedAtMs = now()))
                        emitSnapshot(dao.getById(rec.id)!!)
                        stopping += rec.id
                        Log.d(TAG, "parked ${rec.id} (volume $volumeId gone)")
                    }
            }
            stopping.forEach { stopJob(it) }
            pump()
        }
    }

    /** Same code path as the MEDIA_MOUNTED receiver (also the test hook). */
    fun unparkVolume(volumeId: String) {
        scope.launch(Dispatchers.IO) {
            val resumed = controlMutex.withLock {
                val dao = db.downloads()
                var n = false
                dao.getByStatuses(listOf(ST_PARKED_SD))
                    .filter { it.volumeId == volumeId }.forEach { rec ->
                        // Parked rows never auto-requeue for a disabled source.
                        if (needsSource(rec) && !sourceReady(rec)) {
                            parkSource(rec, "Source unavailable.")
                        } else {
                            dao.update(rec.copy(status = ST_QUEUED, updatedAtMs = now()))
                            emitSnapshot(dao.getById(rec.id)!!)
                            n = true
                            Log.d(TAG, "unparked ${rec.id} (volume $volumeId back)")
                        }
                    }
                n
            }
            if (resumed) pump()
        }
    }

    private suspend fun pump(retired: Long? = null) = pumpMutex.withLock {
        // Single ownership handoff: retire-then-schedule is atomic, so a
        // retiring worker and a scheduling pass can't overlap on one id.
        if (retired != null) {
            running.remove(retired)
            visibleRunning.remove(retired)
        }
        val dao = db.downloads()
        while (running.size < MAX_CONCURRENT) {
            val next = dao.getRunnable().firstOrNull { !running.containsKey(it.id) && it.id !in dead } ?: break
            if (!volumePresent(next.volumeId)) {
                dao.update(next.copy(status = ST_PARKED_SD, updatedAtMs = now()))
                emitSnapshot(dao.getById(next.id)!!)
                continue
            }
            if (needsSource(next) && !sourceReady(next)) {
                parkSource(next, "Source unavailable.")
                continue
            }
            dao.update(next.copy(status = ST_ACTIVE, updatedAtMs = now()))
            emitSnapshot(dao.getById(next.id)!!)
            if (next.transferKind != "ART") visibleRunning += next.id
            running[next.id] = scope.launch(Dispatchers.IO) { runJob(next.id) }
        }
        // Background execution: hold a foreground service while the
        // player's downloads run. Cover-art fetches are tiny and finish in
        // milliseconds; starting the service for them raced its stop
        // against startForeground and crashed the app (FGS not started in time).
        visibleRunning.retainAll(running.keys)
        DownloadService.sync(appContext, visibleRunning.isNotEmpty())
    }

    private suspend fun runJob(id: Long) {
        try {
            while (currentCoroutineContext().isActive && id !in dead) {
                val rec = db.downloads().getById(id) ?: return
                // External state change (pause/cancel/park/retry) ends this run.
                if (rec.status != ST_ACTIVE) return
                when (val o = attemptOnce(id, rec)) {
                    is Outcome.Done, is Outcome.Stop -> return
                    is Outcome.Retry -> kotlinx.coroutines.delay(o.delayMs)
                }
            }
        } finally {
            pump(retired = id)
        }
    }

    /** One fetch attempt: Done/Stop, or Retry with backoff (bounded). */
    private suspend fun attemptOnce(id: Long, startRec: DownloadRecord): Outcome {
        val dao = db.downloads()
        var rec = startRec
        try {
            val dir = destDir(rec) ?: run {
                park(rec, "Storage volume went away.")
                return Outcome.Stop
            }
            if (!dir.exists()) dir.mkdirs()
            val part = File(dir, rec.fileName + ".part")
            var have = if (part.exists()) part.length() else 0L

            val resolved = resolveFetch(id, rec, have)
            if (resolved is ResolveResult.Finish) return resolved.outcome
            resolved as ResolveResult.Ready
            rec = resolved.rec
            val plan = resolved.plan
            val assist = (plan as? FetchPlan.Source)?.assist ?: AssistPolicy()

            val client: OkHttpClient
            val first: Request
            when (plan) {
                is FetchPlan.Source -> {
                    client = plan.http.downloadClient(plan.transfer.freshConnection)
                    first = sourceRequest(plan, rec, have)
                }
                is FetchPlan.Direct -> {
                    client = hostClient
                    first = Request.Builder().url(plan.url)
                        .header("Accept", "*/*")
                        .apply { if (have > 0) header("Range", "bytes=$have-") }
                        .build()
                }
            }
            val resp = executeFollowingRedirects(id, client, first, plan, rec) ?: return Outcome.Stop
            resp.use { resp ->
                when {
                    assist.enabled && resp.code in assist.triggerStatuses -> {
                        if (!rec.cookies.isNullOrBlank()) {
                            fail(rec, "Verification didn't clear the block.")
                        } else {
                            rec = dao.getById(id) ?: rec
                            // Fresh verification cycle: drop stale cookies first.
                            dao.update(
                                rec.copy(
                                    status = ST_NEEDS_ASSIST, error = null,
                                    cookies = null, assistOrigin = null, updatedAtMs = now(),
                                ),
                            )
                            emitSnapshot(dao.getById(id)!!)
                            Log.d(TAG, "job $id needs WebView assist (HTTP ${resp.code})")
                        }
                        return Outcome.Stop
                    }
                    resp.code == 416 -> {
                        // Beyond EOF: partial is stale, restart clean.
                        // Routed through the retry budget (not a free requeue).
                        part.delete()
                        have = 0
                        val cur = dao.getById(id) ?: rec
                        dao.update(cur.copy(bytes = 0, updatedAtMs = now()))
                        emitSnapshot(dao.getById(id)!!)
                        return transient(rec, "Server rejected the resume position.")
                    }
                    !resp.isSuccessful -> {
                        val code = resp.code
                        return if (code == 408 || code == 429 || code >= 500) {
                            transient(rec, "Download failed (HTTP $code).")
                        } else {
                            fail(rec, "Download failed (HTTP $code).")
                            Outcome.Stop
                        }
                    }
                }
                val body = resp.body
                if (body == null || body.contentType()?.toString()?.contains("html", true) == true) {
                    fail(rec, "Server returned a page, not the file.")
                    return Outcome.Stop
                }
                var rangeTotal: Long? = null
                if (resp.code == 200 && have > 0) {
                    // Host ignored Range: restart from zero, honestly.
                    Log.d(TAG, "job $id host ignored Range, restarting from 0")
                    part.delete()
                    have = 0
                } else if (resp.code == 206) {
                    // Never blind-append: the range must start where our
                    // bytes end, and the object must be the same one.
                    val range = parseContentRange(resp.header("Content-Range"))
                    if (range == null || range.start != have) {
                        Log.w(TAG, "job $id bad 206 (range=$range, have=$have); restarting from 0")
                        part.delete()
                        val cur = dao.getById(id) ?: rec
                        dao.update(cur.copy(bytes = 0, updatedAtMs = now()))
                        return transient(rec, "Server sent an unexpected range.")
                    }
                    if (range.total != null && rec.totalBytes > 0 && range.total != rec.totalBytes) {
                        Log.w(TAG, "job $id object changed (was ${rec.totalBytes}, now ${range.total}); restarting")
                        part.delete()
                        val cur = dao.getById(id) ?: rec
                        dao.update(cur.copy(bytes = 0, totalBytes = 0, updatedAtMs = now()))
                        return transient(rec, "File changed on the server.")
                    }
                    rangeTotal = range.total
                    Log.d(TAG, "job $id resuming at $have (206)")
                }
                val chunkTotal = body.contentLength().takeIf { it > 0 }
                val total = if (resp.code == 206) rangeTotal ?: chunkTotal?.plus(have) else chunkTotal
                var read = have
                var lastEmit = 0L
                var lastPersist = 0L
                body.byteStream().use { input ->
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(have)
                        val buf = ByteArray(256 * 1024)
                        while (currentCoroutineContext().isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            read += n
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastEmit >= PROGRESS_EMIT_MS) {
                                lastEmit = nowMs
                                emitSnapshot(rec, readOverride = read, totalOverride = total)
                            }
                            if (nowMs - lastPersist >= PROGRESS_PERSIST_MS) {
                                lastPersist = nowMs
                                dao.update(dao.getById(id)!!.copy(bytes = read, totalBytes = total ?: 0))
                            }
                        }
                    }
                }
                calls.remove(id)
                // Paused/cancelled mid-stream: owner persists, end this run.
                if (!currentCoroutineContext().isActive) return Outcome.Stop
                return complete(id, part, read, total)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // pause/cancel/park: owner already persisted; just exit.
            throw e
        } catch (e: java.io.IOException) {
            // Socket/reset/timeout/airplane: the flaky-network path.
            return transient(rec, "Download failed (${e.message ?: "network error"}).")
        } catch (e: Exception) {
            Log.w(TAG, "job $id failed", e)
            fail(rec, "Download failed (${e.message ?: "unexpected error"}).")
            return Outcome.Stop
        }
    }

    private sealed interface FetchPlan {
        data class Source(
            val http: SourceHttp,
            val transfer: TransferRequest,
            val url: okhttp3.HttpUrl,
            val assist: AssistPolicy,
            /** ROM validates download origins; ART validates artwork origins. */
            val checkUrl: (String) -> okhttp3.HttpUrl,
        ) : FetchPlan

        data class Direct(val url: String) : FetchPlan
    }

    private sealed interface ResolveResult {
        data class Ready(val plan: FetchPlan, val rec: DownloadRecord) : ResolveResult
        data class Finish(val outcome: Outcome) : ResolveResult
    }

    /**
     * Resolve this attempt's fetch plan. Source transfers re-resolve every
     * attempt (no single-use token is persisted or reused).
     */
    private suspend fun resolveFetch(id: Long, rec: DownloadRecord, have: Long): ResolveResult {
        val dao = db.downloads()
        val access = sources
        if (rec.transferKind == "ROM") {
            if (access == null) {
                fail(rec, "Sources unavailable.")
                return ResolveResult.Finish(Outcome.Stop)
            }
            emitSnapshot(rec, readOverride = have)
            val ready = decodeSourceFile(rec)
            val sourceId = rec.sourceId
            if (ready == null || sourceId == null) {
                fail(rec, "Couldn't map this download to its source file.")
                return ResolveResult.Finish(Outcome.Stop)
            }
            val http: SourceHttp
            val transfer: TransferRequest
            val assist: AssistPolicy
            try {
                http = access.httpFor(sourceId, rec.sourceRevision)
                val source = access.open(sourceId, rec.sourceRevision)
                assist = source.assist
                transfer = source.resolveDownload(ready)
                http.checkTransferHeaders(transfer.headers)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SourceException) {
                return ResolveResult.Finish(resolveFailure(rec, e))
            } catch (e: Exception) {
                Log.w(TAG, "resolve failed for $id", e)
                fail(rec, "Couldn't prepare the download link.")
                return ResolveResult.Finish(Outcome.Stop)
            }
            val url = try {
                http.checkTransferUrl(transfer.url)
            } catch (e: SourceException) {
                fail(rec, sourceFailureCopy(e))
                return ResolveResult.Finish(Outcome.Stop)
            }
            // A pause/cancel/remove that won during resolution prevents the Call.
            val cur = dao.getById(id) ?: return ResolveResult.Finish(Outcome.Stop)
            if (cur.status != ST_ACTIVE || id in dead) return ResolveResult.Finish(Outcome.Stop)
            if (!sourceReady(cur)) {
                parkSource(cur, "Source unavailable.")
                return ResolveResult.Finish(Outcome.Stop)
            }
            return ResolveResult.Ready(
                FetchPlan.Source(http, transfer, url, assist, http::checkTransferUrl), cur,
            )
        }
        if (rec.transferKind == "ART" && rec.sourceId != null) {
            if (access == null) {
                fail(rec, "Sources unavailable.")
                return ResolveResult.Finish(Outcome.Stop)
            }
            val http = try {
                access.httpFor(rec.sourceId, rec.sourceRevision)
            } catch (e: SourceException) {
                return ResolveResult.Finish(resolveFailure(rec, e))
            }
            val direct = rec.directUrl
            if (direct.isNullOrBlank()) {
                fail(rec, "Artwork is missing its URL.")
                return ResolveResult.Finish(Outcome.Stop)
            }
            val url = try {
                http.checkArtworkUrl(direct)
            } catch (e: SourceException) {
                fail(rec, sourceFailureCopy(e))
                return ResolveResult.Finish(Outcome.Stop)
            }
            val cur = dao.getById(id) ?: return ResolveResult.Finish(Outcome.Stop)
            if (cur.status != ST_ACTIVE || id in dead) return ResolveResult.Finish(Outcome.Stop)
            return ResolveResult.Ready(
                FetchPlan.Source(http, TransferRequest(direct), url, AssistPolicy(), http::checkArtworkUrl),
                cur,
            )
        }
        val direct = rec.directUrl
        if (direct.isNullOrBlank()) {
            fail(rec, "No download URL.")
            return ResolveResult.Finish(Outcome.Stop)
        }
        return ResolveResult.Ready(FetchPlan.Direct(direct), rec)
    }

    /** Map a resolve-phase SourceException to parking, retry, or failure. */
    private suspend fun resolveFailure(rec: DownloadRecord, e: SourceException): Outcome {
        if (e.failure == SourceFailure.UNAVAILABLE) {
            parkSource(rec, "Source unavailable.")
            return Outcome.Stop
        }
        val code = e.statusCode
        if (e.failure == SourceFailure.NETWORK ||
            (e.failure == SourceFailure.HTTP && (code == 408 || code == 429 || (code ?: 0) >= 500))
        ) {
            return transient(rec, sourceFailureCopy(e))
        }
        fail(rec, sourceFailureCopy(e))
        return Outcome.Stop
    }

    private fun decodeSourceFile(rec: DownloadRecord): DownloadFile? =
        rec.sourceFileJson?.let {
            runCatching { provenanceJson.decodeFromString(DownloadFile.serializer(), it) }.getOrNull()
        }

    /** Transfer headers first, then engine-owned Range and origin-matched cookies. */
    private fun sourceRequest(plan: FetchPlan.Source, rec: DownloadRecord, have: Long): Request {
        val b = Request.Builder().url(plan.url).header("Accept", "*/*")
        for ((name, value) in plan.transfer.headers) b.header(name, value)
        if (have > 0) b.header("Range", "bytes=$have-")
        buildCookieHeader(plan.http, plan.url, rec)?.let { b.header("Cookie", it) }
        return b.build()
    }

    private fun buildCookieHeader(http: SourceHttp, url: okhttp3.HttpUrl, rec: DownloadRecord): String? {
        val parts = http.cookiesFor(url).map { "${it.name}=${it.value}" }.toMutableList()
        val assist = rec.cookies
        if (!assist.isNullOrBlank() && rec.assistOrigin != null && originOf(url) == rec.assistOrigin) {
            parts += assist
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * Execute with manual redirect handling for source transfers (their
     * clients never auto-follow). Every hop re-validates the origin;
     * verification cookies are stripped when leaving the origin.
     */
    private suspend fun executeFollowingRedirects(
        id: Long,
        client: OkHttpClient,
        first: Request,
        plan: FetchPlan,
        rec: DownloadRecord,
    ): okhttp3.Response? {
        var req = first
        var hops = 0
        while (true) {
            if (id in dead || !currentCoroutineContext().isActive) return null
            val call = client.newCall(req)
            calls[id] = call
            val resp = try {
                call.execute()
            } catch (e: Exception) {
                calls.remove(id)
                throw e
            }
            if (plan !is FetchPlan.Source || resp.code !in REDIRECT_CODES) return resp
            val location = resp.header("Location")
            resp.close()
            if (location.isNullOrBlank() || ++hops > com.gamebex.source.SOURCE_MAX_REDIRECTS) {
                fail(rec, "Too many redirects.")
                return null
            }
            val next = try {
                plan.checkUrl(req.url.resolve(location)?.toString() ?: location)
            } catch (e: SourceException) {
                fail(rec, sourceFailureCopy(e))
                return null
            }
            val b = req.newBuilder().url(next)
            if (!sameOrigin(next, req.url)) b.removeHeader("Cookie")
            req = b.build()
        }
    }

    private suspend fun complete(id: Long, part: File, read: Long, total: Long?): Outcome {
        val dao = db.downloads()
        val rec = dao.getById(id) ?: return Outcome.Stop
        val dir = destDir(rec)
        val final = if (dir != null) File(dir, rec.fileName) else null
        if (dir == null || final == null) {
            park(rec, "Storage volume went away.")
            return Outcome.Stop
        }
        if (total != null && total > 0 && read < total) {
            // Truncated body: keep partial, auto-resume.
            return transient(rec, "Connection dropped at ${read * 100 / total}%.", total)
        }
        // Staged publish: the previous valid file survives until the new
        // bytes are validated; DONE requires byte-count proof, never
        // existence alone. Publish failures are transient (bounded retries).
        DownloadIngest.publishStaged(part, dir, rec.fileName, read)
            ?: return transient(rec, "Couldn't save the file.")
        // RAW deliveries (explicit dest dir or source artwork) complete
        // directly, skipping library ingest.
        val raw = rec.destDirAbs != null || rec.transferKind == "ART"
        if (!raw) CocoonDirs.scan(appContext, final)
        controlMutex.withLock {
            val cur = dao.getById(id) ?: return Outcome.Stop
            if (cur.status != ST_ACTIVE) return Outcome.Stop // pause/cancel/park won
            // Raw jobs (art covers) skip library ingest: landing them in
            // INGEST first lets waiters wake on a transient state and,
            // worse, cancel a published job as "suspended". Straight to DONE.
            dao.update(cur.copy(bytes = final.length(), totalBytes = total ?: final.length(),
                status = if (raw) ST_DONE else ST_INGEST, error = null, updatedAtMs = now()))
            emitSnapshot(dao.getById(id)!!)
        }
        Log.d(TAG, "job $id published: ${final.length()} bytes (attempts=${rec.attempts})")
        // Durable ingest, inline: INGEST rows are retried on reconcile,
        // so a crash between DONE and ingest never orphans the file.
        val gameId = if (!raw) ingest.ingest(rec) else -1L
        controlMutex.withLock {
            val cur = dao.getById(id) ?: return Outcome.Stop
            if (cur.status == ST_INGEST) {
                if (raw || (gameId != null && gameId > 0)) {
                    dao.update(cur.copy(status = ST_DONE, cookies = null, assistOrigin = null, updatedAtMs = now()))
                } else {
                    dao.update(cur.copy(error = "Saved, waiting for library retry.", updatedAtMs = now()))
                }
            }
            // Emit regardless: a terminal ingest outcome (empty file) set
            // its own status before this block and must still reach Home.
            emitSnapshot(dao.getById(id)!!)
        }
        return Outcome.Done
    }

    /** Terminal failure. Partial kept; manual Resume retries (attempts reset). */
    private suspend fun fail(rec: DownloadRecord, reason: String) {
        val dao = db.downloads()
        val cur = dao.getById(rec.id) ?: rec
        if (cur.status != ST_ACTIVE && cur.status != ST_QUEUED) return // pause/cancel/park won
        val have = partFile(cur)?.length() ?: cur.bytes
        dao.update(
            cur.copy(
                status = ST_FAILED, error = reason, bytes = have,
                cookies = null, assistOrigin = null, updatedAtMs = now(),
            ),
        )
        emitSnapshot(dao.getById(rec.id)!!)
    }

    private suspend fun park(rec: DownloadRecord, reason: String) {
        val dao = db.downloads()
        val cur = dao.getById(rec.id) ?: rec
        if (cur.status != ST_ACTIVE && cur.status != ST_QUEUED) return // pause/cancel won
        val have = partFile(rec)?.length() ?: cur.bytes
        dao.update(cur.copy(status = ST_PARKED_SD, error = reason, bytes = have, updatedAtMs = now()))
        emitSnapshot(dao.getById(rec.id)!!)
    }

    /**
     * Park on a missing/disabled/stale-pinned source. Bytes and partials
     * are retained, the retry budget untouched; origin-scoped verification
     * cookies are dropped (re-verify after the source returns).
     */
    private suspend fun parkSource(rec: DownloadRecord, reason: String) {
        val dao = db.downloads()
        val cur = dao.getById(rec.id) ?: rec
        if (cur.status != ST_ACTIVE && cur.status != ST_QUEUED &&
            cur.status != ST_PAUSED && cur.status != ST_FAILED && cur.status != ST_NEEDS_ASSIST
        ) {
            return // pause/cancel/delete won, or already parked
        }
        val have = partFile(cur)?.length() ?: cur.bytes
        dao.update(
            cur.copy(
                status = ST_SOURCE_UNAVAILABLE, error = reason, bytes = have,
                cookies = null, assistOrigin = null, updatedAtMs = now(),
            ),
        )
        emitSnapshot(dao.getById(rec.id)!!)
    }

    /** A job needs its source's availability checked before fetching. */
    private fun needsSource(rec: DownloadRecord): Boolean =
        rec.sourceId != null && (rec.transferKind == "ROM" || rec.transferKind == "ART")

    private fun sourceReady(rec: DownloadRecord): Boolean =
        rec.sourceId?.let { sources?.isAvailable(it, rec.sourceRevision) } == true

    /** Queue-row copy for typed source failures (never raw internals). */
    private fun sourceFailureCopy(e: SourceException): String = when (e.failure) {
        SourceFailure.NETWORK -> "Couldn't reach the source."
        SourceFailure.HTTP -> "Source request failed" +
            (e.statusCode?.let { " (HTTP $it)" } ?: "") + "."
        SourceFailure.POLICY -> "Source requested an unapproved address."
        SourceFailure.INVALID_RESPONSE, SourceFailure.UNSUPPORTED ->
            "Source changed its response."
        SourceFailure.INCOMPATIBLE, SourceFailure.INVALID_DEFINITION ->
            "Source needs a newer GameBEX version."
        SourceFailure.UNAVAILABLE -> "Source unavailable."
    }

    /**
     * Transient failure (flaky network): retry with backoff while the
     * persisted budget lasts, else terminal fail. attempts survives
     * process death, so the bound holds across restarts; manual Resume
     * resets it.
     */
    private suspend fun transient(rec: DownloadRecord, reason: String, total: Long? = null): Outcome {
        val dao = db.downloads()
        val cur = dao.getById(rec.id) ?: rec
        val have = partFile(cur)?.length() ?: cur.bytes
        val attempts = cur.attempts + 1
        if (attempts > MAX_AUTO_RETRIES) {
            val msg = "$reason (gave up after $MAX_AUTO_RETRIES tries.)"
            dao.update(cur.copy(status = ST_FAILED, error = msg, bytes = have,
                totalBytes = total ?: cur.totalBytes, updatedAtMs = now()))
            emitSnapshot(dao.getById(rec.id)!!)
            Log.d(TAG, "job ${rec.id} gave up: $reason")
            return Outcome.Stop
        }
        dao.update(cur.copy(attempts = attempts, bytes = have,
            totalBytes = total ?: cur.totalBytes, updatedAtMs = now()))
        emitSnapshot(dao.getById(rec.id)!!,
            noteOverride = "Connection lost — retrying ($attempts/$MAX_AUTO_RETRIES)…")
        val backoff = BACKOFF_MS[(attempts - 1).coerceIn(0, BACKOFF_MS.size - 1)]
        Log.d(TAG, "job ${rec.id} attempt $attempts failed ($reason); retry in ${backoff}ms")
        return Outcome.Retry(backoff)
    }

    private fun now() = System.currentTimeMillis()

    private fun volumePresent(volumeId: String): Boolean {
        // App-private storage (raw art jobs) never ejects; only the SD
        // cocoon can disappear. (Internal cocoon dir may also be unseeded
        // on SD-first devices — downloads mkdirs as needed.)
        if (volumeId == "internal") return true
        return RomStore.volumeRoots(appContext)
            .firstOrNull { it.first == volumeId }?.second?.exists() ?: false
    }

    private fun volumeIdFor(dir: File): String? {
        val canon = try { dir.canonicalPath } catch (_: Exception) { return null }
        for ((vol, root) in RomStore.volumeRoots(appContext)) {
            val rc = try { root.canonicalPath } catch (_: Exception) { continue }
            if (canon == rc || canon.startsWith("$rc${File.separator}")) return vol
        }
        return null
    }

    private fun destDir(rec: DownloadRecord): File? {
        rec.destDirAbs?.let { return File(it) }
        val root = RomStore.volumeRoots(appContext).firstOrNull { it.first == rec.volumeId }?.second
            ?: return null
        // destRelPath = "<folder>/<file>": parent dir is the dest.
        val folder = rec.destRelPath.substringBeforeLast('/', "")
        return if (folder.isEmpty()) root else File(root, folder)
    }

    private fun partFile(rec: DownloadRecord?): File? {
        if (rec == null || rec.fileName.isEmpty()) return null
        val dir = destDir(rec) ?: return null
        return File(dir, rec.fileName + ".part")
    }

    private fun finalFile(rec: DownloadRecord): File? {
        if (rec.fileName.isEmpty()) return null
        val dir = destDir(rec) ?: return null
        return File(dir, rec.fileName)
    }

    /**
     * Stored path relative to the actual volume root ("folder/file", or a
     * bare "file" at the root) — never prefixed with the root's own name.
     */
    private fun relativeToRoot(vol: String, dir: File, fileName: String): String {
        val root = RomStore.volumeRoots(appContext).firstOrNull { it.first == vol }?.second
        if (root != null) {
            val rc = runCatching { root.canonicalPath }.getOrNull()
            val dc = runCatching { dir.canonicalPath }.getOrNull()
            if (rc != null && dc != null && (dc == rc || dc.startsWith("$rc${File.separator}"))) {
                val inner = dc.removePrefix(rc).trim('/')
                return if (inner.isEmpty()) fileName else "$inner/$fileName"
            }
        }
        return "${dir.name}/$fileName"
    }

    private fun emitSnapshot(
        rec: DownloadRecord,
        readOverride: Long? = null,
        totalOverride: Long? = null,
        noteOverride: String? = null,
    ) {
        val snap = DlSnapshot(
            id = rec.id, title = rec.title, fileName = rec.fileName,
            status = rec.status, read = readOverride ?: rec.bytes,
            total = totalOverride ?: rec.totalBytes,
            error = rec.error, pageUrl = rec.pageUrl, note = noteOverride,
            sourceId = rec.sourceId, transferKind = rec.transferKind,
        )
        _jobs.update { it + (rec.id to snap) }
    }

    /** Library game id for a finished row (null until ingest lands). */
    suspend fun gameIdForDownload(id: Long): Long? {
        val rec = db.downloads().getById(id) ?: return null
        return db.games().findByPath(rec.volumeId, rec.destRelPath)?.id
    }

    /** Nonterminal jobs owned by one source (Sources disable/remove dialog). */
    suspend fun countAffectedJobs(sourceId: String): Int =
        runCatching { db.downloads().countLiveBySource(sourceId) }.getOrDefault(0)

    fun shutdown() {
        scope.launch(Dispatchers.IO) {
            running.keys.toList().forEach { runCatching { stopJob(it) } }
        }
    }
}
