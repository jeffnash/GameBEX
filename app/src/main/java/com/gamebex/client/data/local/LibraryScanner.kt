package com.gamebex.client.data.local

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.room.withTransaction
import com.gamebex.client.cocoon.CocoonDirs
import com.gamebex.client.cocoon.CocoonMap
import com.gamebex.client.data.PlatformAliases
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.source.DownloadFile
import com.gamebex.source.DownloadProvenance
import com.gamebex.source.SourceRef
import java.io.File
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Volume-aware ROM roots. Identity ("sd:<uuid>" / "internal") is stored in the
 *  DB separately from the mount path so re-mounted volumes reconcile. */
object RomStore {
    @Suppress("DEPRECATION")
    fun internalRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        CocoonMap.ROM_ROOT,
    )

    /** GameBEX-owned volumes: current SD root (when mounted) + internal root. */
    fun builtInRoots(context: Context): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        if (CocoonDirs.usingSd(context)) {
            val sd = CocoonDirs.root(context)
            val uuid = sd.absolutePath.removePrefix("/storage/").substringBefore('/')
            out += "sd:$uuid" to sd
        }
        out += "internal" to internalRoot()
        return out
    }

    /** All scannable volumes: built-ins first, then user game folders ([GameFolders]). */
    fun volumeRoots(context: Context): List<Pair<String, File>> {
        val builtIns = builtInRoots(context)
        val custom = GameFolders.effective(context, builtIns.map { it.second })
            .map { GameFolders.volumeIdFor(it) to File(it) }
        return builtIns + custom
    }

    /** User-owned root (added game folder): GameBEX never writes there. */
    fun isUserVolume(volumeId: String): Boolean = volumeId.startsWith(GameFolders.VOL_PREFIX)

    /** Launch paths under the app-owned prep root carry this prefix. */
    const val PREP_REL_PREFIX = "gbxprep:"

    /**
     * App-owned output for prep of user-volume ROMs: a hidden dir inside
     * the GameBEX root (public, so emulators can read it; the scanner only
     * walks platform-named dirs there, so it never scans back in).
     */
    fun extPrepRoot(context: Context): File = File(CocoonDirs.root(context), ".gamebex_ext")

    /** Containment on canonical paths: [f] must be strictly inside [root]. */
    fun isInside(root: File, f: File): Boolean {
        val rc = runCatching { root.canonicalPath }.getOrNull() ?: return false
        val fc = runCatching { f.canonicalPath }.getOrNull() ?: return false
        return fc.startsWith(rc + File.separator)
    }

    fun resolveFile(context: Context, volumeId: String, relPath: String): File? {
        if (relPath.startsWith(PREP_REL_PREFIX)) {
            val root = extPrepRoot(context)
            val f = File(root, relPath.removePrefix(PREP_REL_PREFIX))
            return if (isInside(root, f)) f else null
        }
        val root = volumeRoots(context).firstOrNull { it.first == volumeId }?.second
            ?: return null
        val f = File(root, relPath)
        // Containment: relPath must stay inside the root.
        return if (isInside(root, f)) f else null
    }

    /**
     * Where prep may write outputs for [rom]: next to it on GameBEX volumes,
     * the app-owned prep root for user folders (never the user's tree).
     */
    fun prepBaseDir(context: Context, game: GameEntity, rom: File): File? =
        if (isUserVolume(game.volumeId)) {
            GameFolders.prepDirFor(extPrepRoot(context), game.volumeId, game.relPath)
        } else rom.parentFile

    /** ROM dir GameBEX may write into (BIOS drops, sidecars); null for user folders. */
    fun writableRomDir(game: GameEntity, rom: File?): File? =
        if (isUserVolume(game.volumeId)) null else rom?.parentFile

    /** Relative launch path for a prep output (volume-relative or prep-root-prefixed). */
    fun launchRelFor(context: Context, volumeId: String, file: File): String? {
        val (root, prefix) = if (isUserVolume(volumeId)) {
            extPrepRoot(context) to PREP_REL_PREFIX
        } else {
            (volumeRoots(context).firstOrNull { it.first == volumeId }?.second ?: return null) to ""
        }
        val rc = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val fc = runCatching { file.canonicalPath }.getOrNull() ?: return null
        if (!fc.startsWith("$rc${File.separator}")) return null
        return prefix + fc.removePrefix("$rc${File.separator}")
    }

    /** Boot target — the prepared cue/m3u when prep set one, else the ROM. */
    fun resolveLaunchFile(context: Context, game: GameEntity): File? {
        val rel = game.launchRelPath ?: game.relPath
        return resolveFile(context, game.volumeId, rel)
    }
}

data class ScanResult(
    val volumes: List<String>,
    val added: Int,
    val updated: Int,
    val unavailable: Int,
)

/** Adopt existing GameBEX tree into Room. Files only (dirs skipped;
 *  scummvm directory-games arrive with the import flow). Missing media is
 *  marked unavailable, never deleted. */
object LibraryScanner {
    /** One lock per volume: rescan and ingest serialize DB mutations so a
     *  fresh row can never be marked unavailable by a racing scan. */
    private val volLocks = ConcurrentHashMap<String, Mutex>()
    private fun volLock(vol: String): Mutex = volLocks.getOrPut(vol) { Mutex() }

    private val genGuard = Any()
    private var lastGen = 0L
    /** Strictly monotonic scan id (wall-clock based, process-guarded). */
    private fun nextGen(): Long = synchronized(genGuard) {
        lastGen = maxOf(System.currentTimeMillis(), lastGen + 1)
        lastGen
    }

    /** Seen-stamp chunk: bounded IN-list, no bind-limit risk at any size. */
    private const val SEEN_CHUNK = 500

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Any()
    private var inFlight: Deferred<ScanResult>? = null
    /** Wall-clock ms of the last completed pass; guards sequential re-entry. */
    private var lastRefreshMs = 0L
    /** Storage inputs of the last completed pass (grant + volume set). */
    private var lastFingerprint = ""
    /** Init-path scans skip the SD walk when a pass finished recently. */
    const val REFRESH_FRESH_MS = 60_000L

    /** Inputs whose change invalidates freshness (grant flip, SD swap). */
    private fun fingerprint(context: Context): String =
        (if (hasAllFilesAccess(context)) "g1" else "g0") + "|" +
            RomStore.volumeRoots(context).joinToString(",") { it.first }

    /**
     * Coalesced rescan: concurrent callers (Home + Library + System +
     * volume events) share one pass instead of each walking the tree.
     * Late joiners await the running pass and share its result.
     */
    suspend fun refresh(context: Context, db: LibraryDb): ScanResult {
        val job = synchronized(refreshLock) {
            inFlight?.takeIf { it.isActive }
                ?: refreshScope.async { rescan(context, db) }.also { inFlight = it }
        }
        try {
            val result = job.await()
            synchronized(refreshLock) {
                if (inFlight === job) {
                    lastRefreshMs = System.currentTimeMillis()
                    lastFingerprint = fingerprint(context.applicationContext)
                }
            }
            return result
        } finally {
            synchronized(refreshLock) {
                if (inFlight === job && job.isCompleted) inFlight = null
            }
        }
    }

    /**
     * Init-path entry: skips the walk when a pass completed within
     * [REFRESH_FRESH_MS] with identical storage inputs, so Home/Library/
     * System re-entry does not repeat the same SD walk. A grant flip or
     * volume-set change always rescans. Explicit scans call [refresh].
     */
    suspend fun refreshIfStale(
        context: Context,
        db: LibraryDb,
        maxAgeMs: Long = REFRESH_FRESH_MS,
    ): ScanResult? {
        val app = context.applicationContext
        val fresh = synchronized(refreshLock) {
            System.currentTimeMillis() - lastRefreshMs < maxAgeMs &&
                lastFingerprint == fingerprint(app)
        }
        if (fresh) return null
        return refresh(app, db)
    }

    suspend fun rescan(context: Context, db: LibraryDb): ScanResult {
        var added = 0
        var updated = 0
        var unavailable = 0
        val volumes = mutableListOf<String>()
        for ((vol, root) in RomStore.volumeRoots(context)) {
            volumes += vol
            if (!root.isDirectory) continue
            // Without all-files access the SD listing is untrustworthy
            // (denied dirs read as empty) — skip the vol rather than
            // mass-marking its games unavailable.
            // User folders (dir:) need the same grant for the same reason.
            val shared = vol.startsWith("sd:") || RomStore.isUserVolume(vol)
            if (shared && !hasAllFilesAccess(context)) continue
            val (a, u, un) = scanVolume(db, vol, root, aliasFolders = RomStore.isUserVolume(vol))
            added += a
            updated += u
            unavailable += un
        }
        // New/refreshed rows were queued for art inside the scan; wake the
        // worker, or a drain that ran before the scan leaves covers blank
        // until the next launch (seen after a fresh-DB first run).
        if (added > 0 || updated > 0) com.gamebex.client.net.ArtWorker.kick(context)
        return ScanResult(volumes, added, updated, unavailable)
    }

    /**
     * A removed game folder: its rows go unavailable (never deleted — play
     * history, art and prep survive a re-add under the same path).
     */
    suspend fun forgetVolume(db: LibraryDb, vol: String): Int =
        volLock(vol).withLock { db.games().markVolumeUnavailable(vol) }

    private data class RefreshOp(
        val id: Long,
        val title: String,
        val size: Long,
        val mtime: Long,
        val ss: Long,
        val queueArt: Boolean,
        /** Sidecar provenance applied only when the row has none. */
        val enrich: SourceEnrich? = null,
        /** False for enrich-only ops: prep targets stay valid. */
        val invalidatePrep: Boolean = true,
    )

    private data class SourceEnrich(
        val sourceId: String,
        val itemKey: String,
        val refJson: String,
        val variantJson: String?,
    )

    private val provenanceJson = Json { ignoreUnknownKeys = true }

    private fun encodeRef(ref: SourceRef?): String? =
        ref?.let { provenanceJson.encodeToString(SourceRef.serializer(), it) }

    private fun encodeVariant(variant: DownloadFile?): String? =
        variant?.let { provenanceJson.encodeToString(DownloadFile.serializer(), it) }

    /** One volume's file walk, collected without touching the DB. */
    private class ScanPlan {
        val inserts = mutableListOf<GameEntity>()
        val revives = mutableListOf<Long>()
        val refreshes = mutableListOf<RefreshOp>()
        val seenIds = mutableListOf<Long>()
    }

    /**
     * One volume under its lock: preload rows once (no per-ROM query),
     * match files against the map, then commit inserts, revives,
     * refreshes, seen stamps, and stale marks in a single transaction.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun scanVolume(
        db: LibraryDb,
        vol: String,
        root: File,
        /** User folders: ES-DE/RetroArch dir names resolve via [PlatformAliases]. */
        aliasFolders: Boolean = false,
    ): Triple<Int, Int, Int> =
        volLock(vol).withLock {
            val gen = nextGen()
            val existing = db.games().listByVolume(vol).associateBy { it.relPath }
            val plan = ScanPlan()
            var added = 0
            var updated = 0
            var scanClean = true
            val top = root.listFiles()
                ?: return@withLock Triple(0, 0, 0) // unreadable root: never mark from it
            for (dir in top.filter { it.isDirectory }) {
                val slug = platformFor(dir.name, aliasFolders) ?: continue
                val counts = scanPlatformDir(vol, dir, slug, existing, plan)
                added += counts.added
                updated += counts.updated
                if (!counts.clean) scanClean = false
            }
            // Absent-after-successful-scan marks unavailable; a failed
            // listing marks nothing (can't tell absent from unreadable).
            val now = System.currentTimeMillis()
            var unavailable = 0
            db.withTransaction {
                if (plan.inserts.isNotEmpty()) {
                    val ids = db.games().upsertAll(plan.inserts)
                    plan.seenIds += ids
                    // New files queue for art (worker skips search when
                    // the sidecar already pins the ScreenScraper id).
                    db.enrich().enqueueAll(ids.map { EnrichItem(gameId = it, updatedAtMs = now) })
                }
                plan.revives.chunked(SEEN_CHUNK).forEach { db.games().setAvailableAll(it) }
                for (r in plan.refreshes) {
                    db.games().setScanRefreshed(r.id, r.title, r.size, r.mtime, true, r.ss)
                    if (r.invalidatePrep) {
                        db.games().setPrep(r.id, PrepPipeline.PREP_NONE, null, null)
                    }
                    r.enrich?.let { e ->
                        db.games().setSourceReference(r.id, e.sourceId, e.itemKey, e.refJson, e.variantJson)
                    }
                }
                val artIds = plan.refreshes.filter { it.queueArt }.map { it.id }
                if (artIds.isNotEmpty()) {
                    db.enrich().enqueueAll(artIds.map { EnrichItem(gameId = it, updatedAtMs = now) })
                }
                plan.seenIds.chunked(SEEN_CHUNK).forEach { chunk -> db.games().markSeenIds(chunk, gen) }
                if (scanClean) unavailable = db.games().markStaleUnavailable(vol, gen)
            }
            Triple(added, updated, unavailable)
        }

    /** Platform folder for a top-level dir: exact names, plus aliases on user roots. */
    internal fun platformFor(dirName: String, aliasFolders: Boolean): String? =
        if (aliasFolders) PlatformAliases.resolve(dirName)
        else dirName.takeIf { PlatformRegistry.byFolder.containsKey(it) }

    private data class ScanCounts(var added: Int = 0, var updated: Int = 0, var clean: Boolean = true)

    /**
     * One platform dir plus one deliberate nesting level (per-game
     * subdirs). Prep-owned, staging, and hidden dirs are never sources —
     * extracted outputs must not scan back in as new games.
     */
    private fun scanPlatformDir(
        vol: String,
        dir: File,
        slug: String,
        existing: Map<String, GameEntity>,
        plan: ScanPlan,
    ): ScanCounts {
        val counts = ScanCounts()
        val files = dir.listFiles()
        if (files == null) {
            counts.clean = false
            return counts
        }
        val topSkips = discSkips(slug, files)
        for (f in files) {
            if (f.isDirectory) {
                if (f.name.startsWith(".") || f.name.endsWith(".staging")) continue
                if (File(f, PrepPipeline.STAGE_MARKER).isFile) continue
                val inner = f.listFiles()
                if (inner == null) {
                    counts.clean = false
                    continue
                }
                val innerSkips = discSkips(slug, inner)
                for (g in inner) {
                    if (!g.isFile || g.name in innerSkips) continue
                    scanFile(vol, dir, slug, g, "${dir.name}/${f.name}/${g.name}", counts, existing, plan)
                }
                continue
            }
            if (!f.isFile || f.name in topSkips) continue
            scanFile(vol, dir, slug, f, "${dir.name}/${f.name}", counts, existing, plan)
        }
        return counts
    }

    /** Largest sheet the scanner reads for grouping (real sheets are tiny). */
    private const val MAX_SHEET_BYTES = 256 * 1024L

    /**
     * Loose disc parts that fold into a sibling row ([PrepLogic.scanSkips]).
     * Their old rows go stale-unavailable through the normal seen stamps.
     */
    private fun discSkips(folder: String, listing: Array<File>): Set<String> {
        // Only files this folder lists: an unlisted sheet must not hide its parts.
        val files = listing.filter { it.isFile && PlatformRegistry.accepts(folder, it.extension.lowercase()) }
        val sheets = files.filter {
            it.extension.lowercase() in PrepLogic.SCAN_SHEETS && it.length() <= MAX_SHEET_BYTES
        }
        val names = files.map { it.name }
        if (sheets.isEmpty() && names.none { PrepLogic.discGroupKey(it) != null }) return emptySet()
        val refs = sheets.associate { it.name to PrepPipeline.parseSheetRefs(it) }
        return PrepLogic.scanSkips(folder, names, refs)
    }

    private fun scanFile(
        vol: String,
        platformDir: File,
        slug: String,
        f: File,
        rel: String,
        counts: ScanCounts,
        existing: Map<String, GameEntity>,
        plan: ScanPlan,
    ) {
        val folder = slug
        // A 0-byte file is not a game and is never imported. An existing
        // row for one goes stale-unavailable, like any other missing media.
        if (f.length() == 0L) return
        if (Sidecars.isSidecarName(f.name)) return
        if (f.name.endsWith(".tmp")) return
        val ext = f.extension.lowercase()
        if (!PlatformRegistry.accepts(folder, ext)) return
        if (f.name == PrepPipeline.STAGE_MARKER) return
        val row = existing[rel]
        if (row != null && row.sizeBytes == f.length() &&
            row.mtimeMs == f.lastModified()
        ) {
            plan.seenIds += row.id
            if (!row.available) plan.revives += row.id
            // Unchanged bytes but missing provenance: enrich from the
            // sidecar in the batched transaction, no extra SELECT.
            val sidecar = Sidecars.read(f)
            // Rows still carrying the old file-name title (tags, dots as
            // spaces) get the cleaned one; sidecar/scraped titles differ
            // from that form and are never touched.
            val retitle = if (sidecar?.title == null &&
                row.title == legacyPrettyTitle(f.nameWithoutExtension)
            ) prettyTitle(f.nameWithoutExtension).takeIf { it != row.title } else null
            val enrich = enrichFromSidecar(row, sidecar)
            if (enrich != null || retitle != null) {
                plan.refreshes += RefreshOp(
                    id = row.id, title = retitle ?: row.title, size = row.sizeBytes,
                    mtime = row.mtimeMs, ss = row.ssGameId, queueArt = false,
                    enrich = enrich, invalidatePrep = false,
                )
            }
            // One-time migration: dreamcast rows prepped to a
            // CD-R-numbered cue boot black in flycast; force re-prep so
            // the pipeline generates the .gdi boot instead. Bounded
            // reads, fail-open; one-shot (re-prep points at the .gdi).
            if (folder == "dreamcast" && row.prepState == PrepPipeline.PREP_READY &&
                row.launchRelPath?.lowercase()?.endsWith(".cue") == true &&
                row.launchRelPath?.startsWith(RomStore.PREP_REL_PREFIX) == false
            ) {
                val volRoot = platformDir.parentFile
                val needsGdi = volRoot != null && runCatching {
                    PrepPipeline.dreamcastCueNeedsGdi(File(volRoot, row.launchRelPath))
                }.getOrDefault(false)
                if (needsGdi) {
                    plan.refreshes += RefreshOp(
                        id = row.id, title = row.title, size = row.sizeBytes,
                        mtime = row.mtimeMs, ss = row.ssGameId, queueArt = false,
                    )
                }
            }
            return
        }
        val sidecar = Sidecars.read(f)
        val title = sidecar?.title ?: prettyTitle(f.nameWithoutExtension)
        if (row == null) {
            plan.inserts += GameEntity(
                title = title, folder = folder, volumeId = vol,
                relPath = rel, fileName = f.name, sizeBytes = f.length(),
                mtimeMs = f.lastModified(), ext = ext,
                coverUrl = sidecar?.coverUrl,
                description = sidecar?.description,
                source = sidecar?.source ?: "USER",
                ssGameId = sidecar?.ssGameId ?: 0,
                addedAtMs = System.currentTimeMillis(), available = true,
                sourceId = sidecar?.sourceId,
                sourceItemKey = sidecar?.sourceRef?.itemKey,
                sourceRefJson = encodeRef(sidecar?.sourceRef),
                sourceVariantJson = encodeVariant(sidecar?.sourceVariant),
            )
            counts.added++
        } else {
            // Source fingerprint changed: refresh scan-owned fields and
            // invalidate preparation — the old target came from old bytes.
            plan.refreshes += RefreshOp(
                id = row.id, title = sidecar?.title ?: row.title,
                size = f.length(), mtime = f.lastModified(),
                ss = if (row.ssGameId == 0L) sidecar?.ssGameId ?: 0 else row.ssGameId,
                queueArt = row.artSource == "NONE",
                enrich = enrichFromSidecar(row, sidecar),
            )
            plan.seenIds += row.id
            counts.updated++
        }
    }

    /**
     * Sidecar provenance for a row that lacks it. Rows with resolved
     * provenance are never downgraded from an older sidecar.
     */
    private fun enrichFromSidecar(row: GameEntity, sidecar: Sidecar?): SourceEnrich? {
        if (sidecar == null || row.sourceItemKey != null) return null
        val ref = sidecar.sourceRef ?: return null
        val sourceId = sidecar.sourceId ?: ref.sourceId
        if (row.sourceId != null && row.sourceId != sourceId) return null
        return SourceEnrich(
            sourceId = sourceId,
            itemKey = ref.itemKey,
            refJson = encodeRef(ref) ?: return null,
            variantJson = encodeVariant(sidecar.sourceVariant),
        )
    }

    /**
     * Storage-read grant present: all-files access on R+, read permission
     * below (the manifest requests it through 29).
     */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** Display title from a file name without extension (sidecar titles win upstream). */
    fun prettyTitle(base: String): String = com.gamebex.client.data.TitleText.displayTitle(base)

    /** Pre-cleanup scanner title, recognized so old rows migrate once. */
    internal fun legacyPrettyTitle(base: String): String = base
        .replace('_', ' ').replace('.', ' ')
        .replace(Regex("\\s+"), " ").trim()
        .ifBlank { base }

    /** Called after a store download finishes: sidecar + DB row. Never throws. No network here — the caller queues artwork after attaching. */
    suspend fun ingestDownload(
        context: Context,
        db: LibraryDb,
        file: File,
        title: String,
        platformSlug: String?,
        description: String?,
        coverUrl: String?,
        provenance: DownloadProvenance,
    ): Long? {
        // A 0-byte file is not a game: ingest nothing and leave the
        // download row in its retry state.
        if (file.length() == 0L) return null
        try {
            val ref = provenance.ref
            val variant = provenance.variant
            val storeSource = "SOURCE"
            Sidecars.write(
                file,
                Sidecar(
                    title = title, platformSlug = platformSlug, description = description,
                    coverUrl = coverUrl,
                    source = storeSource,
                    downloadedAtMs = System.currentTimeMillis(),
                    sourceId = ref?.sourceId,
                    sourceRef = ref,
                    sourceVariant = variant,
                ),
            )
            val folder = file.parentFile?.name ?: return null
            val vol = volumeIdFor(context, file) ?: return null
            val rel = "$folder/${file.name}"
            val now = System.currentTimeMillis()
            // No downloads-row insert — the engine owns the queue row.
            // Under the volume lock: a racing rescan can neither miss this
            // row's insert nor mark it stale mid-ingest.
            return volLock(vol).withLock {
                val existing = db.games().findByPath(vol, rel)
                if (existing == null) {
                    db.games().upsert(
                        GameEntity(
                            title = title, folder = folder, volumeId = vol, relPath = rel,
                            fileName = file.name, sizeBytes = file.length(),
                            mtimeMs = file.lastModified(), ext = file.extension.lowercase(),
                            coverPath = null, coverUrl = coverUrl, description = description,
                            source = storeSource,
                            addedAtMs = now, available = true,
                            sourceId = ref?.sourceId,
                            sourceItemKey = ref?.itemKey,
                            sourceRefJson = encodeRef(ref),
                            sourceVariantJson = encodeVariant(variant),
                        ),
                    )
                } else {
                    // Replacement download: new bytes + provenance, old prep targets invalid.
                    db.games().setIngested(
                        existing.id, title, file.length(), file.lastModified(),
                        coverUrl ?: existing.coverUrl, description ?: existing.description,
                        storeSource,
                        ref?.sourceId, ref?.itemKey, encodeRef(ref), encodeVariant(variant),
                    )
                    db.games().setPrep(existing.id, PrepPipeline.PREP_NONE, null, null)
                    existing.id
                }
            }
        } catch (_: Exception) {
            // Ingest must never break the download flow.
            return null
        }
    }

    private fun volumeIdFor(context: Context, file: File): String? {
        val canon = try { file.canonicalPath } catch (_: Exception) { return null }
        // Downloads only ever land in GameBEX-owned roots.
        for ((vol, root) in RomStore.builtInRoots(context)) {
            val rc = try { root.canonicalPath } catch (_: Exception) { continue }
            if (canon.startsWith("$rc${File.separator}")) return vol
        }
        return null
    }
}
