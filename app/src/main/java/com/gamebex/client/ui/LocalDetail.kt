package com.gamebex.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamebex.client.data.DownloadFile
import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.downloadFileNameFor
import com.gamebex.client.data.shouldPromptForChoice
import com.gamebex.client.data.PlayStates
import com.gamebex.client.data.RomEntry
import com.gamebex.client.data.Route
import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import com.gamebex.source.SourceKey
import com.gamebex.source.SourceRef
import com.gamebex.client.data.BiosCheck
import com.gamebex.client.data.BiosTable
import com.gamebex.client.data.local.ArtStore
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.PrepPipeline
import com.gamebex.client.data.local.RomStore
import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.net.BiosFetcher
import com.gamebex.client.net.ScreenScraper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.gamebex.client.data.local.AutoPrep
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class LocalDetailViewModel(
    private val appContext: android.content.Context? = null,
    database: LibraryDb? = null,
    sourceAccess: com.gamebex.client.sources.SourceAccess? = null,
    engine: com.gamebex.client.net.DownloadController? = null,
    private val launchDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val coreStallMs: Long = 8_000L,
) : ViewModel() {
    private val db: LibraryDb by lazy { database ?: LibraryDb.get(appContext!!) }
    private val _game = MutableStateFlow<GameEntity?>(null)
    val game: StateFlow<GameEntity?> = _game
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _missingEmu = MutableStateFlow<Emu?>(null)
    val missingEmu: StateFlow<Emu?> = _missingEmu

    /** Visible from Play through download, installation and confirmed launch. */
    private val _coreFetch = MutableStateFlow<CoreInstallProgress?>(null)
    val coreFetch: StateFlow<CoreInstallProgress?> = _coreFetch
    private val _needsRetroArchSetup = MutableStateFlow(false)
    val needsRetroArchSetup: StateFlow<Boolean> = _needsRetroArchSetup
    private val _coreRetryAvailable = MutableStateFlow(false)
    val coreRetryAvailable: StateFlow<Boolean> = _coreRetryAvailable
    private val launchLock = Any()
    private var launchJob: Job? = null
    private var cancelledLaunch: Job? = null
    private var retryLaunch: (() -> Unit)? = null
    /** Core probe / emu launch in flight. */
    private val _launching = MutableStateFlow(false)
    val launching: StateFlow<Boolean> = _launching

    fun load(id: Long) {
        viewModelScope.launch {
            db.games().observeById(id).collect {
                _game.value = it
                refreshBios(it)
            }
        }
    }

    private val appEngine: com.gamebex.client.net.DownloadController by lazy { engine ?: app.engine }
    private val appSources: com.gamebex.client.sources.SourceAccess by lazy { sourceAccess ?: app.sources }
    private val catalogJson = Json { ignoreUnknownKeys = true }

    private val _catalog = MutableStateFlow<RomEntry?>(null)
    val catalog: StateFlow<RomEntry?> = _catalog
    private val _catalogDl = MutableStateFlow<PlayStates.Dl?>(null)
    val catalogDl: StateFlow<PlayStates.Dl?> = _catalogDl
    private val _catalogDesc = MutableStateFlow<String?>(null)
    val catalogDesc: StateFlow<String?> = _catalogDesc
    private val _catalogFiles = MutableStateFlow<List<DownloadFile>>(emptyList())
    val catalogFiles: StateFlow<List<DownloadFile>> = _catalogFiles
    private val _selectedFile = MutableStateFlow<DownloadFile?>(null)
    val selectedFile: StateFlow<DownloadFile?> = _selectedFile
    /**
     * Non-null while the catalog face is blocked on a missing source:
     * local Play stays, source-only actions show this copy.
     */
    private val _catalogBlocked = MutableStateFlow<String?>(null)
    val catalogBlocked: StateFlow<String?> = _catalogBlocked

    fun selectFile(f: DownloadFile) {
        _selectedFile.value = f
    }
    /** True when Download must ask which file (multi-file, none picked). */
    private val _needsChoice = MutableStateFlow(false)
    val needsChoice: StateFlow<Boolean> = _needsChoice
    fun dismissChoice() {
        _needsChoice.value = false
    }
    fun chooseFile(f: DownloadFile) {
        _needsChoice.value = false
        downloadCatalog(f)
    }
    /** Every queued version for the owned game, newest first. */
    private val _versions = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val versions: StateFlow<List<DownloadRecord>> = _versions
    fun loadVersions() {
        viewModelScope.launch(Dispatchers.IO) {
            val key = _catalog.value?.key ?: resolveGameKey() ?: run {
                _versions.value = emptyList()
                return@launch
            }
            _versions.value = db.downloads().findBySourceItem(key.sourceId, key.itemKey)
        }
    }
    fun cancelCatalog() {
        val id = catalogJobId ?: return
        catalogJobId = null
        _catalogDl.value = null
        appEngine.cancel(id)
        _message.value = "Download cancelled."
        loadVersions()
    }
    fun deleteVersion(jobId: Long) {
        if (catalogJobId == jobId) {
            catalogJobId = null
            _catalogDl.value = null
        }
        appEngine.delete(jobId)
        _message.value = "Version deleted."
        loadVersions()
    }
    /**
     * Opens the stored SourceRef's catalog face in place ("more versions").
     * The stored variant stays selected so re-download keeps its file.
     */
    fun loadCatalogForGame() {
        val g = _game.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ref = try {
                storedRef(g)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = sourceBlockedCopy(g.sourceId, e)
                return@launch
            }
            g.sourceVariantJson?.let { raw ->
                runCatching { catalogJson.decodeFromString(DownloadFile.serializer(), raw) }
                    .getOrNull()?.let { _selectedFile.value = it }
            }
            loadCatalog(
                RomEntry(
                    ref = ref, title = g.title,
                    platformId = g.folder.takeIf { PlatformRegistry.byFolder.containsKey(it) },
                    coverUrl = g.coverUrl,
                ),
                clearGame = false,
            )
            _needsChoice.value = true
        }
    }
    /** Job id when the catalog job needs WebView assist. */
    private val _assist = MutableStateFlow<Long?>(null)
    val assist: StateFlow<Long?> = _assist
    private var catalogJobId: Long? = null
    private var catalogFolder: String? = null
    private var catalogRevision: String? = null
    private var catalogGen = 0
    private var catalogLoadJob: Job? = null
    private var catalogJobsJob: Job? = null
    private var catalogWatchJob: Job? = null

    fun loadCatalog(entry: RomEntry, clearGame: Boolean = true) {
        // Same key + unchanged revision: reuse loaded data, keep watchers.
        val currentRev = appSources.installedRevision(entry.ref.sourceId)
        if (_catalog.value?.key == entry.key && catalogRevision != null && catalogRevision == currentRev) {
            return
        }
        val gen = ++catalogGen
        catalogLoadJob?.cancel()
        catalogJobsJob?.cancel()
        catalogWatchJob?.cancel()
        _catalog.value = entry
        catalogRevision = null
        catalogFolder = entry.platformId?.takeIf { PlatformRegistry.byFolder.containsKey(it) }
        _catalogFiles.value = emptyList()
        _selectedFile.value = null
        _versions.value = emptyList()
        _catalogDesc.value = null
        _catalogDl.value = null
        _catalogBlocked.value = null
        _assist.value = null
        catalogJobId = null
        if (clearGame) _game.value = null
        launchSourceItemWatch(entry.key)
        catalogLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val source = try {
                appSources.open(entry.ref.sourceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != catalogGen) return@launch
                _catalogBlocked.value = if (e is SourceException && e.failure == SourceFailure.UNAVAILABLE) {
                    UserErrors.sourceNeededForCatalog(sourceNameOf(entry.ref.sourceId))
                } else {
                    sourceBlockedCopy(entry.ref.sourceId, e)
                }
                return@launch
            }
            if (gen != catalogGen) return@launch
            catalogRevision = source.revision
            val live = db.downloads().findLiveBySourceItem(entry.ref.sourceId, entry.ref.itemKey)
            if (live != null && gen == catalogGen) {
                catalogJobId = live.id
                _catalogDl.value = PlayStates.Dl(live.status, live.bytes, live.totalBytes, sourceId = live.sourceId)
            }
            runCatching { source.variants(entry.ref) }.onSuccess {
                if (gen != catalogGen) return@launch
                _catalogFiles.value = it
                if (_needsChoice.value && it.size <= 1) {
                    _needsChoice.value = false
                    _message.value = "Only one version available."
                }
            }
            runCatching { source.detail(entry.ref).description }.onSuccess {
                if (gen == catalogGen) _catalogDesc.value = it
            }
            if (gen == catalogGen) loadVersions()
        }
        catalogJobsJob = viewModelScope.launch {
            appEngine.jobs.collect { map ->
                val snap = catalogJobId?.let(map::get)
                _catalogDl.value = snap?.let {
                    // DONE but row not ingested yet: hold at 100% until the
                    // source-item observer flips to local mode (never show
                    // Download again for a finished file).
                    if (it.status == com.gamebex.client.net.DownloadEngine.ST_DONE)
                        PlayStates.Dl(
                            com.gamebex.client.net.DownloadEngine.ST_ACTIVE,
                            it.total, it.total, sourceId = it.sourceId,
                        )
                    else PlayStates.Dl(it.status, it.read, it.total, sourceId = it.sourceId)
                }
                _assist.value =
                    if (snap?.status == com.gamebex.client.net.DownloadEngine.ST_NEEDS_ASSIST)
                        snap.id else null
            }
        }
    }

    /** Owned already (or row lands mid-screen): local mode takes over. */
    private fun launchSourceItemWatch(key: SourceKey) {
        catalogWatchJob?.cancel()
        catalogWatchJob = viewModelScope.launch {
            db.games().observeBySourceItem(key.sourceId, key.itemKey).collect {
                if (it != null) {
                    _game.value = it
                    refreshBios(it)
                }
            }
        }
    }

    /** Catalog Download: best file first, double-tap safe via engine dedup. */
    fun downloadCatalog(file: DownloadFile? = null) {
        val entry = _catalog.value ?: return
        if (_game.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            var files = _catalogFiles.value
            if (files.isEmpty()) {
                val source = try {
                    appSources.open(entry.ref.sourceId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _message.value = sourceBlockedCopy(entry.ref.sourceId, e)
                    return@launch
                }
                files = runCatching { source.variants(entry.ref) }.getOrElse { emptyList() }
                _catalogFiles.value = files
            }
            val picked = file?.takeIf { it in files }
                ?: _selectedFile.value?.takeIf { it in files }
            if (shouldPromptForChoice(files.size, picked != null)) {
                // Multi-file release, nothing chosen: prompt instead of
                // silently taking the first (often the wrong region).
                _needsChoice.value = true
                return@launch
            }
            val first = picked ?: files.firstOrNull()
            if (first == null) {
                _message.value = "Couldn't load the download files."
                return@launch
            }
            if (catalogFolder == null) {
                _message.value = "No system mapped for this game yet."
                return@launch
            }
            try {
                val id = appEngine.enqueueSource(
                    entry, first, _catalogDesc.value,
                    targetFileName = downloadFileNameFor(first.name),
                )
                catalogJobId = id
                appEngine.jobs.value[id]?.let {
                    _catalogDl.value = PlayStates.Dl(it.status, it.read, it.total, sourceId = it.sourceId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.gamebex.client.net.DestinationBusyException) {
                catalogJobId = e.jobId
                _message.value = "Already downloading to that file."
            } catch (e: Exception) {
                _message.value = sourceBlockedCopy(entry.ref.sourceId, e)
            }
        }
    }

    fun resumeCatalog() {
        val id = catalogJobId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val rec = db.downloads().getById(id)
            val sourceId = rec?.sourceId
            if (sourceId != null) {
                try {
                    appSources.open(sourceId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _message.value = sourceBlockedCopy(sourceId, e)
                    return@launch
                }
            }
            appEngine.resume(id)
        }
    }

    private fun sourceBlockedCopy(sourceId: String?, e: Exception): String =
        if (e is SourceException && e.failure == SourceFailure.UNAVAILABLE) {
            UserErrors.sourceMissing(sourceNameOf(sourceId))
        } else {
            UserErrors.forCatalog(e, sourceNameOf(sourceId))
        }

    private fun sourceNameOf(sourceId: String?): String =
        sourceId ?: "This source"

    /** The loaded game's source key, or null for imported files. */
    private fun resolveGameKey(): SourceKey? {
        val g = _game.value ?: return _catalog.value?.key
        val sourceId = g.sourceId ?: return null
        val itemKey = g.sourceItemKey ?: return null
        return SourceKey(sourceId, itemKey)
    }

    /** The row's stored [SourceRef]; INCOMPATIBLE when absent or mismatched. */
    private fun storedRef(g: GameEntity): SourceRef {
        val ref = g.sourceRefJson?.let { raw ->
            runCatching { catalogJson.decodeFromString(SourceRef.serializer(), raw) }.getOrNull()
        }
        if (ref == null || ref.sourceId != g.sourceId ||
            (g.sourceItemKey != null && ref.itemKey != g.sourceItemKey)
        ) {
            throw SourceException(SourceFailure.INCOMPATIBLE, g.sourceId, "detail")
        }
        return ref
    }

    /** Whole-file Replace asks first: it re-downloads gigabytes. */
    private val _confirmReplace = MutableStateFlow(false)
    val confirmReplace: StateFlow<Boolean> = _confirmReplace
    fun requestRedownload() {
        _confirmReplace.value = true
    }
    fun dismissRedownload() {
        _confirmReplace.value = false
    }

    /**
     * Re-fetches the owned file (corrupt archive, failed prep) via staged publish
     * onto the same path, so ingest refreshes this row. Disabled while a live job owns the file.
     */
    fun redownload() {
        _confirmReplace.value = false
        val g = _game.value ?: return
        if (g.sourceId == null) {
            _message.value = "Only downloads can be re-downloaded."
            return
        }
        if (catalogJobId != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val ref = try {
                storedRef(g)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = sourceBlockedCopy(g.sourceId, e)
                return@launch
            }
            val variant = g.sourceVariantJson?.let { raw ->
                runCatching { catalogJson.decodeFromString(DownloadFile.serializer(), raw) }.getOrNull()
            } ?: run {
                _message.value = "No stored file to re-download."
                return@launch
            }
            if (db.downloads().findLiveBySourceItem(ref.sourceId, ref.itemKey) != null) return@launch
            val entry = RomEntry(
                ref = ref, title = g.title,
                platformId = g.folder.takeIf { PlatformRegistry.byFolder.containsKey(it) },
                coverUrl = g.coverUrl,
            )
            try {
                val id = appEngine.enqueueSource(
                    entry, variant, g.description, targetFileName = g.fileName,
                )
                catalogJobId = id
                _message.value = "Re-downloading ${g.fileName}."
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.gamebex.client.net.DestinationBusyException) {
                catalogJobId = e.jobId
                _message.value = "Already downloading to that file."
            } catch (e: Exception) {
                _message.value = sourceBlockedCopy(ref.sourceId, e)
            }
        }
    }

    fun toggleFav() {
        val g = _game.value ?: return
        viewModelScope.launch(Dispatchers.IO) { db.games().setFavorite(g.id, !g.favorite) }
    }

    /**
     * [fresh] = "Start fresh": RetroArch boots without auto-loading the
     * resume point for this launch only (see EmuRouter.canStartFresh).
     */
    fun play(context: android.content.Context, fresh: Boolean = false) {
        val g = _game.value ?: return
        launchGame(g, fresh, launch = { game, startFresh, progress ->
            EmuRouter.play(context, game, startFresh, onCoreFetch = progress)
        }, onLaunched = { game, out ->
            // Counted on confirmed boot at next resume, not here.
            app.noteLaunch(game.id, out.emuPkg)
        })
    }

    /** The launch boundary is injectable so lifecycle tests exercise the real state machine. */
    internal fun launchGame(
        g: GameEntity,
        fresh: Boolean = false,
        launch: suspend (GameEntity, Boolean, (Long, Long) -> Unit) -> EmuRouter.Outcome,
        onLaunched: (GameEntity, EmuRouter.Outcome.Launched) -> Unit = { _, _ -> },
    ) {
        synchronized(launchLock) {
            if (launchJob != null) return
            val route = PlatformRegistry.byFolder[g.folder]?.route as? Route.RetroCore
            val core = route?.takeIf {
                g.emulatorOverride == null || g.emulatorOverride == Emu.RETROARCH.pkg
            }?.let { g.coreOverride ?: it.core }
            _message.value = null
            _missingEmu.value = null
            _needsRetroArchSetup.value = false
            _coreRetryAvailable.value = false
            _launching.value = true
            _coreFetch.value = core?.let { CoreInstallProgress(it) }
            retryLaunch = { launchGame(g, fresh, launch, onLaunched) }
            val predecessor = cancelledLaunch
            lateinit var job: Job
            job = viewModelScope.launch(launchDispatcher, start = CoroutineStart.LAZY) {
                var watchdog: Job? = null
                var progressVersion = 0L
                fun armWatchdog() {
                    watchdog?.cancel()
                    val version = ++progressVersion
                    watchdog = this.launch {
                        delay(coreStallMs)
                        synchronized(launchLock) {
                            if (launchJob === job && progressVersion == version) {
                                _coreFetch.value = _coreFetch.value?.copy(stalled = true)
                            }
                        }
                    }
                }
                try {
                    synchronized(launchLock) { if (launchJob === job && core != null) armWatchdog() }
                    // Let cancellation release EmuRouter's in-flight gate before retrying.
                    predecessor?.join()
                    ensureActive()
                    val out = launch(g, fresh) { read, total ->
                        synchronized(launchLock) {
                            if (launchJob === job && job.isActive && core != null) {
                                val old = _coreFetch.value ?: CoreInstallProgress(core)
                                val next = if (read < 0 && total < 0) old.copy(installing = true)
                                    else old.copy(read = read.coerceAtLeast(0), total = total.coerceAtLeast(0), installing = false)
                                // Identical callbacks are not evidence of forward progress.
                                if (next.copy(stalled = false) != old.copy(stalled = false)) {
                                    _coreFetch.value = next.copy(stalled = false)
                                    armWatchdog()
                                }
                            }
                        }
                    }
                    ensureActive()
                    synchronized(launchLock) {
                        if (launchJob === job) when (out) {
                            is EmuRouter.Outcome.Launched -> {
                                onLaunched(g, out)
                                _message.value = if (fresh) {
                                    "Started fresh in ${out.emuLabel}. Quitting saves a new resume point."
                                } else "Playing in ${out.emuLabel}."
                            }
                            is EmuRouter.Outcome.MissingEmu -> _missingEmu.value = out.emu
                            is EmuRouter.Outcome.Blocked -> _message.value = out.reason
                            is EmuRouter.Outcome.NeedsSetup -> {
                                _message.value = out.text
                                _needsRetroArchSetup.value = core != null
                            }
                            is EmuRouter.Outcome.Failed -> _message.value = out.msg
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logW("GameBEXPlay", "launch failed", e)
                    synchronized(launchLock) {
                        if (launchJob === job) _message.value = UserErrors.launchFailed()
                    }
                } finally {
                    watchdog?.cancel()
                    synchronized(launchLock) {
                        // A cancelled launch must never clear its replacement's progress.
                        if (launchJob === job) {
                            launchJob = null
                            _launching.value = false
                            _coreFetch.value = null
                        }
                    }
                }
            }
            launchJob = job
            job.start()
        }
    }

    fun cancelCoreInstall() {
        synchronized(launchLock) {
            val job = launchJob ?: return
            if (_coreFetch.value == null) return
            launchJob = null
            // A second retry can be cancelled while it is still waiting for the
            // original backend's cleanup; keep that original gate until it finishes.
            cancelledLaunch = cancelledLaunch?.takeUnless { it.isCompleted } ?: job
            job.cancel()
            _launching.value = false
            _coreFetch.value = null
            _needsRetroArchSetup.value = false
            _coreRetryAvailable.value = true
            _message.value = "Core installation cancelled. Retry when you're ready."
        }
    }

    fun retryCoreInstall() {
        synchronized(launchLock) {
            if (_coreFetch.value?.stalled != true && !_coreRetryAvailable.value && !_needsRetroArchSetup.value) return
            cancelCoreInstall()
            retryLaunch?.invoke()
        }
    }

    /** Open the installed runtime's launcher, never the APK installer. */
    fun openRetroArch(context: android.content.Context) {
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(Emu.RETROARCH.pkg)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: run {
                _message.value = "RetroArch isn't available to open."
                return
            }
            context.startActivity(intent)
        }.onFailure { _message.value = "RetroArch refused to open." }
    }

    /** Guided runtimes (Vita3K): open the app so the user imports inside it. */
    fun openRuntime() {
        val g = _game.value ?: return
        val emu = (PlatformRegistry.byFolder[g.folder]?.route as? Route.GuidedInstall)?.emu ?: return
        runCatching {
            val intent = appContext!!.packageManager.getLaunchIntentForPackage(emu.pkg)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: run {
                _message.value = "${emu.label} is installed but has no launcher."
                return
            }
            appContext!!.startActivity(intent)
            _message.value = "Opened ${emu.label} — import inside it."
        }.onFailure { _message.value = "${emu.label} refused to open." }
    }

    /** Cycle Default -> alt1 -> ... -> Default for platforms with verified alts. */
    fun cycleEmu() {
        val g = _game.value ?: return
        val alts = PlatformRegistry.byFolder[g.folder]?.altEmus ?: return
        if (alts.isEmpty()) return
        val options: List<String?> = listOf(null) + alts.map { it.pkg }
        val next = options[(options.indexOf(g.emulatorOverride) + 1) % options.size]
        viewModelScope.launch(Dispatchers.IO) { db.games().setEmulatorOverride(g.id, next) }
    }

    fun consumeMissingEmu(): Emu? {
        val v = _missingEmu.value
        _missingEmu.value = null
        return v
    }
    fun clearMessage() { _message.value = null }

    private val app get() = appContext!!.applicationContext as com.gamebex.client.GameBexApp
    val artStatus: StateFlow<String?> get() = app.art.status

    private val _ssCreds by lazy { MutableStateFlow(ScreenScraper.loadCreds(appContext!!)) }
    val ssCreds: StateFlow<ScreenScraper.Companion.Creds> by lazy { _ssCreds }

    fun refreshArt() {
        val g = _game.value ?: return
        app.art.refreshNow(g.id)
    }

    /** Copy a user-picked image into a slot; marks the row MIXED/USER. */
    fun importArt(uri: android.net.Uri, slot: String) {
        val g = _game.value ?: return
        if (slot !in ArtStore.SLOTS) return
        viewModelScope.launch(Dispatchers.IO) {
            val file = ArtStore.importFromUri(appContext!!, g.id, slot, uri)
            if (file == null) {
                _message.value = "That file isn't a usable image (JPEG/PNG/WebP, under 12 MB)."
                return@launch
            }
            val cur = db.games().findById(g.id) ?: return@launch
            val hasSs = listOf(cur.artBox, cur.artHero, cur.artLogo, cur.artShot)
                .any { it != null } && cur.artSource != "USER"
            db.games().upsert(
                cur.copy(
                    artBox = if (slot == ArtStore.SLOT_BOX) file.absolutePath else cur.artBox,
                    artHero = if (slot == ArtStore.SLOT_HERO) file.absolutePath else cur.artHero,
                    artLogo = if (slot == ArtStore.SLOT_LOGO) file.absolutePath else cur.artLogo,
                    artShot = if (slot == ArtStore.SLOT_SHOT) file.absolutePath else cur.artShot,
                    artSource = if (hasSs) "MIXED" else "USER",
                ),
            )
            _message.value = "Artwork updated."
        }
    }

    fun saveSsCreds(devId: String, devPw: String, login: String, loginPw: String) {
        val c = ScreenScraper.Companion.Creds(devId.trim(), devPw.trim(), login.trim(), loginPw.trim())
        ScreenScraper.saveCreds(appContext!!, c)
        _ssCreds.value = c
        _message.value = if (c.complete()) "ScreenScraper login saved." else "Saved — add the dev keys to fetch art."
        app.art.kick()
    }

    private val _bios = MutableStateFlow<BiosCheck.FolderReport?>(null)
    val bios: StateFlow<BiosCheck.FolderReport?> = _bios
    private val _preparing = MutableStateFlow(false)
    /** Manual Prepare/Finish Setup here, or a background auto-prep after download. */
    val preparing: StateFlow<Boolean> = combine(_preparing, AutoPrep.active, _game) { local, auto, g ->
        local || (g != null && g.id in auto)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private fun refreshBios(g: GameEntity?) {
        // Test instances have no Context; BIOS state is unread there.
        if (appContext == null) return
        viewModelScope.launch(Dispatchers.IO) {
            if (g == null) {
                _bios.value = null
                return@launch
            }
            val rom = RomStore.resolveFile(appContext!!, g.volumeId, g.relPath)
            val dirs = PrepPipeline.biosEmuDirs(appContext!!, g.folder, g.emulatorOverride) +
                listOfNotNull(rom?.parentFile)
            _bios.value = BiosCheck.checkFolder(g.folder, dirs, PrepPipeline.biosPrivateDirs(appContext!!))
        }
    }

    /**
     * Finish Setup / Run: fetch missing BIOS first (one press does
     * everything), then run the prep pipeline.
     */
    fun prepare() {
        runPrepare(explicitBios = false)
    }

    /** Manual BIOS retry from the Advanced row (same path + summary). */
    fun downloadBios() {
        runPrepare(explicitBios = true)
    }

    /**
     * Detail's Finish Setup: same as [prepare], then straight into Play
     * when setup ends READY — no second press.
     */
    fun finishSetupAndPlay(context: android.content.Context) {
        runPrepare(explicitBios = false, playWhenReady = context)
    }

    private fun runPrepare(explicitBios: Boolean, playWhenReady: android.content.Context? = null) {
        val g = _game.value ?: return
        // A background auto-prep already holds this game; a second run would only queue behind it.
        if (_preparing.value || g.id in AutoPrep.active.value) return
        _preparing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var fetch: BiosFetcher.FetchReport? = null
                if (BiosTable.TABLE[g.folder]?.isNotEmpty() == true) {
                    val rom = RomStore.resolveFile(appContext!!, g.volumeId, g.relPath)
                    val scanDirs = PrepPipeline.biosEmuDirs(appContext!!, g.folder, g.emulatorOverride) +
                        listOfNotNull(rom?.parentFile)
                    if (!BiosCheck.checkFolder(g.folder, scanDirs).ready) {
                        _message.value = "Fetching BIOS…"
                        fetch = BiosFetcher.fetchMissing(appContext!!, g.folder, RomStore.writableRomDir(g, rom), scanDirs)
                    }
                }
                val report = PrepPipeline.prepare(appContext!!, db, g.id)
                _bios.value = report.bios ?: _bios.value
                _message.value = when {
                    report.state == PrepPipeline.PREP_READY && fetch?.installed?.isNotEmpty() == true ->
                        "${fetch.summary()} — ready to play."
                    report.state == PrepPipeline.PREP_READY -> "Ready: ${report.note}"
                    fetch?.indexError != null -> fetch.summary()
                    fetch?.installed?.isNotEmpty() == true -> "${fetch.summary()}. ${report.note}"
                    explicitBios && fetch == null -> "BIOS already present."
                    else -> report.note
                }
                if (playWhenReady != null && report.state == PrepPipeline.PREP_READY) {
                    // Launch from the fresh row: the observed one may still
                    // carry the pre-prep state (no launch target yet).
                    db.games().findById(g.id)?.let { _game.value = it }
                    _preparing.value = false
                    play(playWhenReady)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("GameBEXPrep", "prepare failed", e)
                _message.value = UserErrors.prepFailed()
            } finally {
                _preparing.value = false
            }
        }
    }

    private val _confirmDelete = MutableStateFlow(false)
    val confirmDelete: StateFlow<Boolean> = _confirmDelete
    /** True once the row is gone: the screen pops back. */
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted
    private var deleting = false

    fun requestDelete() {
        if (_game.value != null) _confirmDelete.value = true
    }

    fun dismissDelete() {
        _confirmDelete.value = false
    }

    fun deleteGame() {
        val g = _game.value ?: return
        _confirmDelete.value = false
        if (deleting) return
        deleting = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val out = com.gamebex.client.data.local.GameDeleter.delete(appContext!!, db, g.id)) {
                    is com.gamebex.client.data.local.GameDeleter.Outcome.Deleted -> {
                        com.gamebex.client.net.ReadyNotifier.cancel(appContext!!, g.id)
                        _message.value = "Deleted ${g.title} · freed ${formatSize(out.bytesFreed)}."
                        _deleted.value = true
                    }
                    is com.gamebex.client.data.local.GameDeleter.Outcome.Failed -> _message.value = out.reason
                }
            } finally {
                deleting = false
            }
        }
    }
}
