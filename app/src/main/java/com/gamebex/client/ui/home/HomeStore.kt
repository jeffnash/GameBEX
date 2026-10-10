package com.gamebex.client.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamebex.client.data.BiosCheck
import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlayStates
import com.gamebex.client.data.local.AutoPrep
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.LibraryScanner
import com.gamebex.client.data.local.PrepPipeline
import com.gamebex.client.data.local.PrepProgress
import com.gamebex.client.data.local.PrepProgressBus
import com.gamebex.client.data.local.RomStore
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.ui.UserErrors
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.DownloadStart
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.HomeEffect
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.HomeIntent
import com.gamebex.client.ui.home.model.HomeMode
import com.gamebex.client.ui.home.model.HomeScreenModel
import com.gamebex.client.ui.home.model.DeviceStatus
import com.gamebex.client.ui.home.model.HeaderModel
import com.gamebex.client.ui.home.model.HomeDownloads
import com.gamebex.client.ui.home.model.SaveIndex
import com.gamebex.client.ui.home.model.SaveInfo
import com.gamebex.client.ui.home.model.SiteSearch
import com.gamebex.client.ui.home.model.SiteResult
import com.gamebex.client.ui.home.model.SizeInfo
import com.gamebex.client.ui.logW
import com.gamebex.client.ui.presentation.LaunchAction
import com.gamebex.client.ui.presentation.LaunchStatus
import com.gamebex.client.ui.presentation.LocalLaunchJob
import com.gamebex.client.ui.presentation.LocalGamePresenter
import com.gamebex.client.ui.presentation.LocalLauncher
import com.gamebex.source.RomEntry
import com.gamebex.source.SourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * The Home view model. Folds `observeAll()`, download jobs, saves, site
 * search and device status into one [HomeScreenModel], and turns
 * [HomeIntent]s into service calls plus one-shot [HomeEffect]s. File IO,
 * save lookup and readiness run on [Dispatchers.IO]; model rebuilds are
 * debounced at 100 ms. Readiness is only evaluated for the cards that are
 * shown.
 *
 * Rebuild cost: saves and readiness are cached per game
 * until `saves.invalidate()` / `Entered` / a DB row change, and download
 * byte progress never triggers a rebuild at all — it patches the job
 * cards in place, so a running download re-stats no files, re-probes no
 * readiness and touches no StatFs. The pipeline itself is guarded: an
 * upstream failure logs and keeps the last good model.
 */
class HomeStore internal constructor(
    private val saves: SaveIndex,
    private val site: SiteSearch,
    private val downloads: HomeDownloads,
    private val device: DeviceStatus,
    private val db: LibraryDb,
    private val readiness: (GameEntity) -> PlayStates.Status,
    private val launcher: suspend (gameId: Long, fresh: Boolean, onStatus: (LaunchStatus?) -> Unit) -> Unit,
    private val rescan: suspend (refreshIfStale: Boolean) -> Unit,
    private val scope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Test seam: counts the library's title normalizations. */
    private val searchKeys: SearchKeysCache = SearchKeysCache(),
    private val beginInstall: (Emu) -> Unit = {},
    private val openRetroArch: () -> String? = { "RetroArch isn't available to open." },
) : ViewModel() {

    /** Production wiring over the services. */
    constructor(
        appContext: Context,
        saves: SaveIndex,
        site: SiteSearch,
        downloads: HomeDownloads,
        device: DeviceStatus,
        db: LibraryDb,
    ) : this(
        saves, site, downloads, device, db,
        readiness = { g -> productionStatus(appContext, g) },
        launcher = { gameId, fresh, onStatus -> LocalLauncher.play(appContext, gameId, fresh, onStatus) },
        beginInstall = { LocalLauncher.beginInstall(appContext, it) },
        openRetroArch = { LocalLauncher.openRetroArch(appContext) },
        rescan = { stale ->
            withContext(Dispatchers.IO) {
                if (stale) LibraryScanner.refreshIfStale(appContext, db)
                else LibraryScanner.refresh(appContext, db)
            }
        },
    )

    /** Test seam: VM tests run everything on the virtual-time scheduler. */
    private val launchTarget: CoroutineScope
        get() = scope ?: viewModelScope

    private val saveCache = CachingSaveIndex(saves)
    private val readinessProbe = ReadinessCache(readiness)
    private val factory = HomeCardFactory(readinessProbe)
    private val eta = DownloadEta()
    private val json = Json { ignoreUnknownKeys = true }

    private val _model = MutableStateFlow(emptyModel())
    val model: StateFlow<HomeScreenModel> = _model

    private val _effects = Channel<HomeEffect>(Channel.BUFFERED)
    val effects: Flow<HomeEffect> = _effects.receiveAsFlow()

    private val queryFlow = MutableStateFlow("")

    /** Search-field binding; updated synchronously in onIntent. */
    val query: StateFlow<String>
        get() = queryFlow

    private val surpriseId = MutableStateFlow<Long?>(null)
    private val statusFlow = MutableStateFlow<LaunchStatus?>(null)
    private val launchJob = LocalLaunchJob({ launchTarget }, launcher) { status ->
        statusFlow.value = status
        // A press and byte ticks must not wait for the library rebuild debounce.
        _model.update { it.copy(status = status) }
    }
    private val tickFlow = MutableStateFlow(0)

    /** Query the site state belongs to, so stale results never leak. */
    private val sitePair: MutableStateFlow<Pair<String, SiteQuery>> =
        MutableStateFlow("" to SiteQuery.None)
    private val sizes = MutableStateFlow<Map<String, SizeInfo?>>(emptyMap())
    private var sizeFetch: Job? = null
    private var lastSurpriseId: Long? = null
    private var knownJobIds: Set<Long> = emptySet()
    /** Consecutive patchPrep ticks each Preparing game spent off the bus. */
    private val stalePrepSeen = mutableMapOf<Long, Int>()

    /** Bumped on every roll and mode reset; see [HomeScreenModel.selectionToken]. */
    private val selectionTick = java.util.concurrent.atomic.AtomicLong(0)

    /** Mode of the last rebuilt model; a change is a selection reset. */
    private var lastMode: HomeMode? = null

    /** Inputs of the last completed rebuild, for the progress patch path. */
    private var lastData: DataInput? = null

    private data class DataInput(
        val games: List<GameEntity>,
        val records: List<DownloadRecord>,
        val jobs: Map<Long, DownloadEngine.DlSnapshot>,
        val armed: Set<Long>,
        val online: Boolean,
    )

    private data class SiteInput(
        val name: String?,
        val query: String,
        val pair: Pair<String, SiteQuery>,
        val sizes: Map<String, SizeInfo?>,
    )

    private data class UiInput(
        val site: SiteInput,
        val surpriseId: Long?,
        val status: LaunchStatus?,
        /** Monotonic re-entry marker; part of the input so Entered always rebuilds. */
        val tick: Int,
        val warnings: Int,
    )

    init {
        startSiteSearch()
        launchTarget.launch {
            combine(dataFlow(), uiFlow()) { data, ui -> data to ui }
                .debounce(REBUILD_DEBOUNCE_MS)
                // A long-lived pipeline never ends with a
                // terminal .catch. Per-value recovery (warningsOrLast) keeps
                // one bad value from getting here; anything else restarts
                // collection instead of freezing Home forever.
                .retryWhen { e, _ ->
                    if (e is CancellationException) throw e
                    logW(TAG, "model pipeline failed; restarting", e)
                    delay(PIPELINE_RESTART_MS)
                    true
                }
                .collect { (data, ui) ->
                    try {
                        rebuild(data, ui)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logW(TAG, "model rebuild failed; keeping the last good model", e)
                    }
                }
        }
        launchTarget.launch {
            // One bad patch must not end progress updates.
            downloads.jobs.collect { jobs ->
                try {
                    patchProgress(jobs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logW(TAG, "progress patch failed", e)
                }
            }
        }
        launchTarget.launch {
            // Live prep bytes patch Preparing cards in place.
            // The 5s tick also flips a quiet prep to "Still working…"
            // without waiting for its next byte report.
            combine(PrepProgressBus.byGame, prepTick()) { states, _ -> states }
                .collect { states ->
                    try {
                        patchPrep(states)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logW(TAG, "prep patch failed", e)
                    }
                }
        }
    }

    private fun prepTick(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(PREP_TICK_MS)
        }
    }

    private fun dataFlow(): Flow<DataInput> = combine(
        db.games().observeAll(),
        db.downloads().observeRecent(),
        downloads.jobs.map { it.progressless() }.distinctUntilChanged(),
        downloads.armed,
        device.online,
    ) { games, records, jobs, armed, online ->
        DataInput(games, records, jobs, armed, online)
    }

    /**
     * Job shape without the per-tick byte counter: 4–8 progress updates a
     * second must never rebuild the model. Structural changes — ids, status,
     * totals, errors — still rebuild.
     */
    private fun Map<Long, DownloadEngine.DlSnapshot>.progressless(): Map<Long, DownloadEngine.DlSnapshot> =
        mapValues { (_, snap) -> snap.copy(read = 0L) }

    private fun uiFlow(): Flow<UiInput> = combine(
        combine(site.pluginName, queryFlow, sitePair, sizes) { name, query, pair, sizes ->
            SiteInput(name, query, pair, sizes)
        },
        surpriseId,
        statusFlow,
        tickFlow.map { tick -> tick to warningsOrLast() },
    ) { site, surprise, status, tickAndWarnings ->
        UiInput(site, surprise, status, tickAndWarnings.first, tickAndWarnings.second)
    }

    /** Last good system-warning count. */
    private var lastWarnings = 0

    /**
     * `systemWarnings()` is device IO and can fail. One
     * failure must never end the model pipeline — it keeps the last good
     * count and the flow goes on emitting.
     */
    private suspend fun warningsOrLast(): Int = try {
        device.systemWarnings().also { lastWarnings = it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logW(TAG, "system warnings failed; keeping the last count", e)
        lastWarnings
    }

    private suspend fun rebuild(data: DataInput, ui: UiInput) = withContext(io) {
        val jobs = downloads.jobs.value
        // One ETA/progress observation per job per rebuild;
        // the snapshots handed to grouping carry the monotonic read.
        val patches = HashMap<Long, DownloadEta.Patch>(jobs.size)
        val clampedJobs = HashMap<Long, DownloadEngine.DlSnapshot>(jobs.size)
        for ((id, snap) in jobs) {
            val p = eta.patch(id, snap.read, snap.total, snap.status)
            patches[id] = p
            clampedJobs[id] = snap.copy(read = p.read)
        }
        lastData = data.copy(jobs = clampedJobs)
        val query = ui.site.query
        val saveMap = data.games.mapNotNull { g -> saveCache.lookup(g)?.let { g.id to it } }.toMap()
        val surprise = ui.surpriseId?.let { id -> data.games.firstOrNull { it.id == id } }
        jobs.keys.let { current ->
            (knownJobIds - current).forEach { eta.remove(it) }
            knownJobIds = current
        }
        // StatFs once per folder per rebuild, never per card.
        val freeCache = HashMap<String?, Long?>()
        val result = HomeGrouping.build(
            games = data.games,
            saves = saveMap,
            jobs = clampedJobs,
            records = data.records,
            armed = data.armed,
            online = data.online,
            freeBytes = { folder ->
                if (freeCache.containsKey(folder)) freeCache[folder]
                else runCatching { device.freeBytes(folder) }.getOrNull().also { freeCache[folder] = it }
            },
            siteName = ui.site.name,
            query = query,
            site = siteQueryFor(query, ui.site),
            surprise = surprise,
            now = System.currentTimeMillis(),
            cards = factory,
            etaSec = { snap -> patches[snap.id]?.etaSec },
            // CHECK_SYSTEMS invite reads the live warning count.
            systemWarnings = ui.warnings,
            // Normalized titles, cached per game-list version.
            searchKeys = searchKeys.keysFor(data.games),
        )
        // A mode change resets the selection to defaultKey.
        if (result.mode != lastMode) {
            selectionTick.incrementAndGet()
            lastMode = result.mode
        }
        val next = HomeScreenModel(
            mode = result.mode,
            header = headerFor(data, ui),
            sections = result.sections,
            query = query,
            defaultKey = result.defaultKey,
            selectionToken = selectionTick.get(),
        )
        _model.update { next.copy(status = statusFlow.value) }
    }

    /**
     * Download byte ticks patch the job cards in
     * place — no saves lookup, no readiness probe, no StatFs, no grouping.
     * Structure changes belong to the debounced rebuild; [HomeCardFactory]
     * rebuilds the strings so the percent and ETA text stay live.
     */
    private suspend fun patchProgress(jobs: Map<Long, DownloadEngine.DlSnapshot>) {
        val data = lastData ?: return
        if (jobs.keys != data.jobs.keys) return
        val oldCards = _model.value.cards.filter { it.downloadId != null }
        if (oldCards.isEmpty()) return
        val patched = HashMap<CardKey, HomeCard>(oldCards.size)
        withContext(io) {
            for (card in oldCards) {
                val id = card.downloadId ?: continue
                val snap = jobs[id] ?: continue
                // Never step progress backwards.
                val p = eta.patch(id, snap.read, snap.total, snap.status)
                patched[card.key] = factory.jobCard(
                    snapshot = snap.copy(read = p.read),
                    record = data.records.firstOrNull { it.id == id },
                    gameId = card.gameId,
                    armed = snap.id in data.armed,
                    etaSec = p.etaSec,
                    siteName = site.pluginName.value,
                    nowMs = System.currentTimeMillis(),
                )
            }
        }
        if (patched.isEmpty()) return
        _model.update { cur ->
            cur.copy(sections = cur.sections.map { section ->
                if (section.group != HomeGroup.DOWNLOADING) return@map section
                section.copy(cards = section.cards.map { card ->
                    // position is assigned by HomeGrouping at assembly (TV channel = position + 1).
                    patched[card.key]?.copy(position = card.position) ?: card
                })
            })
        }
    }

    /**
     * Live prep snapshots patch Preparing cards in place, same
     * as the download byte-tick patch. Cards whose game is on the bus
     * get byte progress; cards still Preparing with no live run for
     * [STALE_PREP_TICKS] ticks (never started, or died with the process)
     * flip to NeedsFix so the stall is visible with a working button. A
     * finished or failed prep is re-derived by the debounced rebuild
     * (FAILED -> NeedsFix w/ Retry).
     */
    private suspend fun patchPrep(states: Map<Long, PrepProgress>) {
        val preparing = _model.value.cards.filter { card ->
            card.state is CardState.Preparing && card.gameId != null
        }
        if (preparing.isEmpty()) {
            stalePrepSeen.clear()
            return
        }
        val nowMs = System.currentTimeMillis()
        // Auto-prep can work off the bus for a while first (BIOS fetch, listing a
        // compressed archive); only a game nothing is working on counts as quiet.
        val autoActive = AutoPrep.active.value
        val quiet = preparing.mapNotNull { it.gameId }.filter { it !in states && it !in autoActive }.toSet()
        val newlyStale = stalePrepTick(quiet, stalePrepSeen)
        val patched = withContext(io) {
            buildMap {
                for (card in preparing) {
                    val snap = states[card.gameId]
                    if (snap != null) put(card.key, factory.prepPatch(card, snap, nowMs))
                    else if (card.gameId in newlyStale) {
                        put(card.key, factory.prepStale(card, HomeCopy.prepStalled, nowMs))
                    }
                }
            }
        }
        if (patched.isEmpty()) return
        _model.update { cur ->
            cur.copy(sections = cur.sections.map { section ->
                section.copy(cards = section.cards.map { card ->
                    patched[card.key]?.copy(position = card.position) ?: card
                })
            })
        }
    }

    private fun headerFor(data: DataInput, ui: UiInput) = HeaderModel(
        searchPlaceholder = HomeCopy.headerSearchPlaceholder(ui.site.name),
        browseLabel = HomeCopy.headerBrowseLabel(ui.site.name),
        downloadsBadge = HomeCopy.headerDownloadsBadge(
            active = HomeGrouping.downloadQueue(data.jobs).size,
            online = data.online,
        ),
        systemWarnings = ui.warnings,
    )

    /**
     * Site results are merged with the sizes that have landed so far.
     * The pair is keyed on the trimmed query, so compare trimmed ("sonic "
     * stayed SEARCHING forever).
     */
    private fun siteQueryFor(query: String, site: SiteInput): SiteQuery {
        if (site.pair.first != query.trim()) return SiteQuery.Searching
        val result = site.pair.second
        if (result !is SiteQuery.Found) return result
        return SiteQuery.Found(result.entries.map { hit ->
            hit.copy(size = site.sizes[siteKey(hit.entry)] ?: hit.size)
        })
    }

    private data class SearchInput(val query: String, val online: Boolean, val plugin: String?)

    /**
     * Local results on every keystroke, site search debounced 400 ms. A
     * change to `online` or `pluginName` while the query is non-blank
     * re-runs the search; identical inputs are collapsed so trailing spaces
     * never cost a request.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun startSiteSearch() {
        launchTarget.launch {
            combine(queryFlow, device.online, site.pluginName) { q, online, plugin ->
                SearchInput(q.trim(), online, plugin)
            }
                .distinctUntilChanged()
                .debounce { input -> if (input.query.isEmpty()) 0L else SITE_DEBOUNCE_MS }
                .collectLatest { input -> runSiteSearch(input.query) }
        }
    }

    private suspend fun runSiteSearch(q: String) {
        // A new query owns the size lookups; the old ones must stop
        // hitting the site even when the new result has nothing to size.
        sizeFetch?.cancel()
        if (q.isEmpty()) {
            sizes.value = emptyMap()
            sitePair.value = q to SiteQuery.None
            return
        }
        sizes.value = emptyMap()
        sitePair.value = q to SiteQuery.Searching
        val result = try {
            site.search(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failure must reach grouping as SiteQuery.Failed,
            // never as empty results and never as an uncaught exception.
            logW(TAG, "site search failed", e)
            SiteResult.Failed(UserErrors.forCatalog(e, site.pluginName.value))
        }
        when (result) {
            is SiteResult.Found -> {
                sitePair.value = q to SiteQuery.Found(result.entries.map { SiteHit(it, null) })
                fetchSizes(q, result.entries.take(SIZE_LOOKUPS))
            }
            SiteResult.NoPlugin -> sitePair.value = q to SiteQuery.NoPlugin
            SiteResult.Offline -> sitePair.value = q to SiteQuery.Offline
            is SiteResult.Failed -> sitePair.value = q to SiteQuery.Failed(result.message)
        }
    }

    /** Sizes for the first 6 results, at most 2 in flight. */
    private fun fetchSizes(q: String, entries: List<RomEntry>) {
        sizeFetch?.cancel()
        sizeFetch = launchTarget.launch {
            val gate = Semaphore(2)
            entries.map { entry ->
                launch {
                    gate.withPermit {
                        val size = try {
                            site.size(entry)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logW(TAG, "size lookup failed", e)
                            null
                        }
                        if (queryFlow.value.trim() == q) {
                            sizes.update { it + (siteKey(entry) to size) }
                        }
                    }
                }
            }.forEach { it.join() }
        }
    }

    private fun siteKey(entry: RomEntry): String = "${entry.ref.sourceId}:${entry.ref.itemKey}"

    fun onIntent(i: HomeIntent) {
        when (i) {
            is HomeIntent.Activate -> {
                if (launchBusy()) return
                model.value.card(i.key)?.let { card -> dispatch(card.primary, card) }
            }
            is HomeIntent.Secondary ->
                model.value.card(i.key)?.let { card -> card.secondary?.let { dispatch(it, card) } }
            is HomeIntent.Query -> queryFlow.value = i.text
            HomeIntent.ClearSearch -> queryFlow.value = ""
            HomeIntent.Surprise -> rollSurprise()
            HomeIntent.ExitSurprise -> surpriseId.value = null
            is HomeIntent.Nav -> emit(HomeEffect.Navigate(i.dest))
            HomeIntent.Entered -> {
                saveCache.invalidate()
                readinessProbe.markStale()
                tickFlow.update { it + 1 }
                launchTarget.launch {
                    try {
                        rescan(true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logW(TAG, "library rescan failed", e)
                    }
                }
            }
            HomeIntent.DismissStatus -> launchJob.dismiss()
            HomeIntent.RetryStatus -> statusFlow.value?.action?.let { runStatusAction(it) }
            HomeIntent.SecondaryStatus -> statusFlow.value?.secondaryAction?.let { runStatusAction(it) }
        }
    }

    /** A does nothing while a launch is in progress. */
    private fun launchBusy(): Boolean = statusFlow.value?.busy == true

    private fun dispatch(action: HomeAction, card: HomeCard) {
        when (action) {
            is HomeAction.Play -> play(action.gameId, fresh = false)
            is HomeAction.DownloadAgain -> launchTarget.launch { downloadAgain(action.gameId, card) }
            is HomeAction.DownloadFromSite -> launchTarget.launch { downloadFromSite(action.entry, card) }
            is HomeAction.TogglePlayWhenReady -> downloads.togglePlayWhenReady(action.downloadId)
            is HomeAction.PauseDownload -> downloads.pause(action.downloadId)
            is HomeAction.ResumeDownload -> downloads.resume(action.downloadId)
            is HomeAction.Go -> emit(HomeEffect.Navigate(action.dest))
            HomeAction.RescanPhone -> launchTarget.launch {
                try {
                    rescan(false)
                    tickFlow.update { it + 1 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logW(TAG, "phone rescan failed", e)
                }
            }
            HomeAction.RollAgain -> rollSurprise()
            HomeAction.None -> Unit
        }
    }

    private fun emit(effect: HomeEffect) {
        launchTarget.launch { _effects.send(effect) }
    }

    private fun play(gameId: Long, fresh: Boolean) = launchJob.play(gameId, fresh)

    private fun runStatusAction(action: LaunchAction) {
        val current = statusFlow.value ?: return
        when (action) {
            LaunchAction.Retry -> launchJob.retry()
            LaunchAction.OpenDetails -> {
                launchJob.dismiss()
                emit(HomeEffect.Navigate(HomeDestination.Game(current.gameId)))
            }
            is LaunchAction.Install -> {
                beginInstall(action.emu)
                launchJob.dismiss()
                emit(HomeEffect.Navigate(HomeDestination.System))
            }
            LaunchAction.OpenRetroArch -> openRetroArch()?.let { emit(HomeEffect.Toast(it)) }
        }
    }

    /** DownloadAgain + the NeedsChoice rule; toast on Started/Failed. */
    private suspend fun downloadAgain(gameId: Long, card: HomeCard) {
        val start = try {
            downloads.downloadAgain(gameId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logW(TAG, "download again failed", e)
            DownloadStart.Failed(DOWNLOAD_FAILED)
        }
        when (start) {
            is DownloadStart.Started ->
                _effects.send(HomeEffect.Toast(HomeCopy.downloadingToast(card.title, offPhoneSize(card))))
            DownloadStart.NeedsChoice -> {
                val g = db.games().findById(gameId) ?: return
                val entry = romEntryOf(g) ?: return
                _effects.send(HomeEffect.Navigate(HomeDestination.SiteEntry(entry)))
            }
            is DownloadStart.Failed -> _effects.send(HomeEffect.Toast(start.message))
        }
    }

    /** DownloadFromSite: 1 file → Started + toast, else the entry page. */
    private suspend fun downloadFromSite(entry: RomEntry, card: HomeCard) {
        val start = try {
            downloads.downloadFromSite(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logW(TAG, "download from site failed", e)
            DownloadStart.Failed(DOWNLOAD_FAILED)
        }
        when (start) {
            is DownloadStart.Started ->
                _effects.send(HomeEffect.Toast(HomeCopy.downloadingToast(card.title, onSiteSize(card))))
            DownloadStart.NeedsChoice ->
                _effects.send(HomeEffect.Navigate(HomeDestination.SiteEntry(entry)))
            is DownloadStart.Failed -> _effects.send(HomeEffect.Toast(start.message))
        }
    }

    private fun offPhoneSize(card: HomeCard): SizeInfo? = (card.state as? CardState.OffPhone)?.size

    private fun onSiteSize(card: HomeCard): SizeInfo? = (card.state as? CardState.OnSite)?.size

    private fun romEntryOf(g: GameEntity): RomEntry? {
        val refJson = g.sourceRefJson ?: return null
        val ref = runCatching { json.decodeFromString<SourceRef>(refJson) }.getOrNull() ?: return null
        return RomEntry(ref = ref, title = g.title, platformId = g.folder, coverUrl = g.coverUrl)
    }

    /** SURPRISE / Roll again; nothing qualifies → HOME + toast. */
    private fun rollSurprise() {
        launchTarget.launch {
            try {
                val games = withContext(io) { db.games().observeAll().first() }
                val pick = withContext(io) {
                    HomeGrouping.pickSurprise(
                        games = games,
                        isReady = { g -> readinessProbe(g) == PlayStates.Status.Ready },
                        excludeId = lastSurpriseId,
                    )
                }
                if (pick == null) {
                    surpriseId.value = null
                    _effects.send(HomeEffect.Toast(HomeCopy.surpriseNothing))
                } else {
                    lastSurpriseId = pick.id
                    // Roll again sends the cursor and the TV
                    // to the new pick, even when the mode stays SURPRISE.
                    selectionTick.incrementAndGet()
                    surpriseId.value = pick.id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW(TAG, "surprise roll failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "GameBexHomeStore"
        private const val REBUILD_DEBOUNCE_MS = 100L

        /** Backoff before restarting a failed pipeline. */
        private const val PIPELINE_RESTART_MS = 500L
        /** Re-evaluate live preps this often for "Still working…". */
        private const val PREP_TICK_MS = 5_000L
        /**
         * Consecutive off-bus ticks before a Preparing card flips
         * to NeedsFix (15s — a fresh kick reaches begin() in milliseconds,
         * so only a dead or never-started prep trips it).
         */
        internal const val STALE_PREP_TICKS = 3
        private const val SITE_DEBOUNCE_MS = 400L
        private const val SIZE_LOOKUPS = 6

        /** Toast when a download action throws. */
        private const val DOWNLOAD_FAILED = "The download couldn't start."

        private fun emptyModel() = HomeScreenModel(
            mode = HomeMode.HOME,
            header = HeaderModel(
                searchPlaceholder = HomeCopy.headerSearchPlaceholder(null),
                browseLabel = HomeCopy.headerBrowseLabel(null),
                downloadsBadge = null,
                systemWarnings = 0,
            ),
            sections = emptyList(),
        )

        /** Production readiness, with BIOS names when prep says so. */
        private fun productionStatus(ctx: Context, g: GameEntity): PlayStates.Status =
            LocalGamePresenter.present(
                ctx, g,
                continued = g.playCount > 0,
                metaLine = null,
                biosMissing = missingBios(ctx, g),
            ).playable.status

        /**
         * Missing required BIOS file names for a shown card (NeedsBios facts
         * line). Only probed for rows that prep flagged; the whole probe is
         * inside the guard — a bad volume or prep config must not escape
         * into viewModelScope.
         */
        private fun missingBios(ctx: Context, g: GameEntity): List<String> {
            if (g.prepState != "NEEDS_BIOS") return emptyList()
            return runCatching {
                val romDir = RomStore.resolveFile(ctx, g.volumeId, g.relPath)?.parentFile
                val dirs = PrepPipeline.biosEmuDirs(ctx, g.folder, g.emulatorOverride) +
                    listOfNotNull(romDir)
                BiosCheck.checkFolder(g.folder, dirs, PrepPipeline.biosPrivateDirs(ctx))
                    .missingRequired.map { it.name }
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * One patchPrep tick of the stale-prep detector. [quietIds] are
 * Preparing games with no live bus entry this tick; [seen] counts their
 * consecutive quiet ticks (ids that went live or left Preparing are
 * forgotten). Returns the ids that JUST crossed [threshold] so the caller
 * patches each card once.
 */
internal fun stalePrepTick(
    quietIds: Set<Long>,
    seen: MutableMap<Long, Int>,
    threshold: Int = HomeStore.STALE_PREP_TICKS,
): Set<Long> {
    seen.keys.retainAll(quietIds)
    if (quietIds.isEmpty()) return emptySet()
    val out = mutableSetOf<Long>()
    for (id in quietIds) {
        val n = (seen[id] ?: 0) + 1
        seen[id] = n
        if (n == threshold) out += id
    }
    return out
}

/**
 * Normalized search keys per game-list version. Grouping
 * and search normalize every title twice per call — with a 6,000-title
 * library that lands on every keystroke. The store builds the map once per
 * games emission (the list instance is the version) and passes it to
 * [HomeGrouping.build]; a game the map doesn't know is normalized on the
 * spot inside the grouping, so a stale map can never miss a result.
 * Invalidation: rebuilt whenever the games list instance changes (each
 * Room emission). Confined to the model-rebuild collector.
 */
internal class SearchKeysCache(
    private val normalize: (String) -> String = HomeGrouping::normalize,
) {
    private var forList: List<GameEntity>? = null
    private var keys: Map<Long, SearchKeys> = emptyMap()

    fun keysFor(games: List<GameEntity>): Map<Long, SearchKeys> {
        if (games !== forList) {
            forList = games
            keys = games.associate { g ->
                g.id to SearchKeys(normalize(HomeCopy.displayTitle(g.title)), normalize(g.title))
            }
        }
        return keys
    }
}

/**
 * `SaveInfo` per game until [SaveIndex.invalidate]:
 * the save index re-stats every state file per lookup while the model
 * rebuilds several times a second. A failing lookup caches "no saves"
 * like a miss; `invalidate()` (Home re-entry, after an emulator exits) is
 * the refresh point.
 */
internal class CachingSaveIndex(private val inner: SaveIndex) : SaveIndex {

    private val cache = ConcurrentHashMap<Long, Pair<GameEntity, SaveInfo?>>()

    override fun lookup(game: GameEntity): SaveInfo? {
        cache[game.id]?.let { (row, info) -> if (row == game) return info }
        val info = try {
            inner.lookup(game)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logW(TAG, "save lookup failed", e)
            null
        }
        cache[game.id] = game to info
        return info
    }

    override fun invalidate() {
        cache.clear()
        inner.invalidate()
    }

    private companion object {
        const val TAG = "GameBexHomeStore"
    }
}

/**
 * Readiness per shown game: entries live until the game's
 * DB row changes or [markStale] flags them. Home re-entry marks entries
 * stale — it never clears the whole library's cache — and each entry is
 * re-checked lazily when a card is actually built for it, so only the
 * shown cards pay a probe. A failing probe falls back to the game's last
 * known status, else a fixable "couldn't check" state — one broken card
 * never takes the model down.
 */
internal class ReadinessCache(
    private val inner: (GameEntity) -> PlayStates.Status,
) : (GameEntity) -> PlayStates.Status {

    private class Entry(val row: GameEntity, val status: PlayStates.Status) {
        @Volatile var stale: Boolean = false
    }

    private val cache = ConcurrentHashMap<Long, Entry>()

    override fun invoke(game: GameEntity): PlayStates.Status {
        val last = cache[game.id]
        if (last != null && last.row == game && !last.stale) return last.status
        return try {
            inner(game).also { status -> cache[game.id] = Entry(game, status) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logW(TAG, "readiness probe failed", e)
            // The entry stays stale so the next look retries.
            last?.status ?: PlayStates.Status.NeedsManual(UNCHECKED_NOTE)
        }
    }

    /**
     * Home re-entry: every entry may be out of date (an emulator, core or
     * BIOS can appear while Home is away), so they are re-checked when
     * next shown — and only then.
     */
    fun markStale() {
        cache.values.forEach { it.stale = true }
    }

    private companion object {
        const val TAG = "GameBexHomeStore"

        /** Facts line of the fallback card. */
        const val UNCHECKED_NOTE = "GameBEX couldn't check this game."
    }
}
