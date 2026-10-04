package com.gamebex.client.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamebex.client.data.ConsoleItem
import com.gamebex.client.data.RomEntry
import com.gamebex.client.sources.SourceAccess
import com.gamebex.client.sources.SourceSelection
import com.gamebex.source.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "GameBEXBrowse"

/** Append-page merge keyed by stable SourceKey identity; first copy wins. */
fun mergeCatalogPages(existing: List<RomEntry>, next: List<RomEntry>): List<RomEntry> {
    val seen = HashSet<SourceKey>()
    return (existing + next).filter { seen.add(it.key) }
}

/** Discover Start on a catalog tile: queue now, or let detail ask/explain. */
enum class QuickDownloadPlan { ENQUEUE, OPEN_DETAIL }

/**
 * Only an unambiguous download queues from the shelf: a mapped system and
 * exactly one file. Multi-file releases need Detail's version choice; an
 * unmapped system or no files need Detail's explanation.
 */
fun quickDownloadPlan(folderMapped: Boolean, fileCount: Int): QuickDownloadPlan =
    if (folderMapped && fileCount == 1) QuickDownloadPlan.ENQUEUE else QuickDownloadPlan.OPEN_DETAIL

class BrowseViewModel(
    private val sources: SourceAccess,
    private val appContext: android.content.Context? = null,
    private val saved: SavedStateHandle = SavedStateHandle(),
    library: kotlinx.coroutines.flow.Flow<List<com.gamebex.client.data.local.GameEntity>>? = null,
    private val downloads: com.gamebex.client.net.DownloadController? = null,
) : ViewModel() {
    private val db by lazy { com.gamebex.client.data.local.LibraryDb.get(appContext!!) }
    private val engine: com.gamebex.client.net.DownloadController by lazy {
        downloads ?: (appContext!!.applicationContext as com.gamebex.client.GameBexApp).engine
    }
    private var quickJob: Job? = null
    private val _consoles = MutableStateFlow<List<ConsoleItem>>(emptyList())
    val consoles: StateFlow<List<ConsoleItem>> = _consoles
    private val _items = MutableStateFlow<List<RomEntry>>(emptyList())
    val items: StateFlow<List<RomEntry>> = _items
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _selectedConsole = MutableStateFlow<String?>(null)
    val selectedConsole: StateFlow<String?> = _selectedConsole
    private var cursor: String? = null
    private val _endReached = MutableStateFlow(false)
    private var requestGen = 0
    private var requestJob: Job? = null
    private var pageJob: Job? = null
    private var activeSelection: SourceSelection? = null
    private val _selection = MutableStateFlow<SourceSelection?>(null)
    val selection: StateFlow<SourceSelection?> = _selection
    private val _loadingMore = MutableStateFlow(false)
    /** Non-blocking pagination failure: items stay visible, footer offers retry. */
    private val _pageError = MutableStateFlow<String?>(null)
    val pageError: StateFlow<String?> = _pageError
    private val _sourceName = MutableStateFlow<String?>(null)
    val sourceName: StateFlow<String?> = _sourceName
    private val _sourceUnavailable = MutableStateFlow(false)
    val sourceUnavailable: StateFlow<Boolean> = _sourceUnavailable
    // Local library for Tier-1 owned (Play) vs available (Get) status.
    private val _games = MutableStateFlow<List<com.gamebex.client.data.local.GameEntity>>(emptyList())
    val games: StateFlow<List<com.gamebex.client.data.local.GameEntity>> = _games
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        viewModelScope.launch {
            (library ?: db.games().observeAll()).collect { _games.value = it }
        }
        viewModelScope.launch {
            combine(sources.ready, sources.selected) { ready, sel -> ready to sel }
                .collectLatest { (ready, sel) ->
                    if (!ready) {
                        _loading.value = true
                        return@collectLatest
                    }
                    startSelection(sel)
                }
        }
    }

    /** New initialized selection: reset everything, start the first page. */
    private fun startSelection(sel: SourceSelection?) {
        val gen = ++requestGen
        requestJob?.cancel()
        pageJob?.cancel()
        activeSelection = sel
        _selection.value = sel
        _items.value = emptyList()
        _consoles.value = emptyList()
        _error.value = null
        _pageError.value = null
        _sourceName.value = null
        cursor = null
        _selectedConsole.value = null
        _loadingMore.value = false
        _endReached.value = false
        if (sel == null) {
            _loading.value = false
            _sourceUnavailable.value = true
            _error.value = UserErrors.noSource()
            return
        }
        _sourceUnavailable.value = false
        _loading.value = true
        requestJob = viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) { sources.open(sel.id, sel.revision) }
                if (gen != requestGen) return@launch
                _sourceName.value = source.name
                // Every non-empty feed: Discover's system chips scroll.
                val consoles = withContext(Dispatchers.IO) { source.consoles() }
                    .filter { it.count > 0 }
                if (gen != requestGen) return@launch
                _consoles.value = consoles
                // Restore this source's saved console; drop it when the
                // taxonomy no longer carries the id.
                val want = saved.get<String>("console:${sel.id}")
                    ?.takeIf { id -> consoles.any { it.id == id } }
                if (saved.get<String>("console:${sel.id}") != null && want == null) {
                    saved.remove<String>("console:${sel.id}")
                }
                _selectedConsole.value = want
                val page = withContext(Dispatchers.IO) {
                    if (want == null) source.latest(null) else source.browse(want, null, null)
                }
                if (gen != requestGen) return@launch
                _items.value = page.items
                cursor = page.next
                _endReached.value = page.next == null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != requestGen) return@launch
                logW(TAG, "catalog load failed", e)
                _sourceUnavailable.value = e is com.gamebex.source.SourceException &&
                    e.failure == com.gamebex.source.SourceFailure.UNAVAILABLE
                _error.value = UserErrors.forCatalog(e, _sourceName.value)
            }
            if (gen == requestGen) _loading.value = false
        }
    }

    /** Reload the current selection's first page (retry path). */
    fun load() {
        startSelection(activeSelection ?: sources.selected.value)
    }

    fun selectConsole(id: String?) {
        val sel = activeSelection ?: return
        val gen = ++requestGen
        requestJob?.cancel()
        pageJob?.cancel()
        _selectedConsole.value = id
        if (id == null) saved.remove<String>("console:${sel.id}") else saved["console:${sel.id}"] = id
        _pageError.value = null
        _error.value = null
        _loading.value = true
        requestJob = viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) { sources.open(sel.id, sel.revision) }
                if (gen != requestGen || activeSelection != sel) return@launch
                if (_consoles.value.isEmpty()) {
                    val consoles = withContext(Dispatchers.IO) { source.consoles() }
                        .filter { it.count > 0 }
                    if (gen != requestGen || activeSelection != sel) return@launch
                    _consoles.value = consoles
                }
                val page = withContext(Dispatchers.IO) {
                    if (id == null) source.latest(null) else source.browse(id, null, null)
                }
                if (gen != requestGen || activeSelection != sel) return@launch
                cursor = page.next
                _endReached.value = page.next == null
                _items.value = page.items
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != requestGen || activeSelection != sel) return@launch
                logW(TAG, "console browse failed", e)
                _error.value = UserErrors.forCatalog(e, _sourceName.value)
            }
            if (gen == requestGen && activeSelection == sel) _loading.value = false
        }
    }

    fun loadMore() {
        if (_loading.value || _loadingMore.value || _endReached.value) return
        val sel = activeSelection ?: return
        val gen = requestGen
        val at = cursor ?: return
        _pageError.value = null
        _loadingMore.value = true
        pageJob?.cancel()
        pageJob = viewModelScope.launch {
            try {
                val id = _selectedConsole.value
                val source = withContext(Dispatchers.IO) { sources.open(sel.id, sel.revision) }
                if (gen != requestGen || activeSelection != sel) return@launch
                val page = withContext(Dispatchers.IO) {
                    if (id == null) source.latest(at) else source.browse(id, null, at)
                }
                if (gen != requestGen || activeSelection != sel) return@launch
                _items.value = mergeCatalogPages(_items.value, page.items)
                cursor = page.next
                _endReached.value = page.next == null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != requestGen || activeSelection != sel) return@launch
                logW(TAG, "catalog page failed", e)
                _pageError.value = UserErrors.forCatalog(e, _sourceName.value)
            } finally {
                if (gen == requestGen && activeSelection == sel) _loadingMore.value = false
            }
        }
    }

    /** Tier-1 Play on an owned match. Same outcomes as Home play. */
    fun play(local: com.gamebex.client.data.local.GameEntity) {
        _message.value = "Launching…"
        viewModelScope.launch(Dispatchers.IO) {
            val g = db.games().findById(local.id) ?: return@launch
            var announced = false
            when (val out = com.gamebex.client.launch.EmuRouter.play(appContext!!, g) { _, _ ->
                if (!announced) {
                    announced = true
                    _message.value = "Fetching missing core…"
                }
            }) {
                is com.gamebex.client.launch.EmuRouter.Outcome.Launched -> {
                    // Counted on confirmed boot at next resume, not here.
                    (appContext!!.applicationContext as com.gamebex.client.GameBexApp)
                        .noteLaunch(g.id, out.emuPkg)
                    _message.value = null
                }
                is com.gamebex.client.launch.EmuRouter.Outcome.MissingEmu ->
                    _message.value = "${out.emu.label} is not installed."
                is com.gamebex.client.launch.EmuRouter.Outcome.Blocked -> _message.value = out.reason
                is com.gamebex.client.launch.EmuRouter.Outcome.NeedsSetup -> _message.value = out.text
                is com.gamebex.client.launch.EmuRouter.Outcome.Failed -> _message.value = out.msg
            }
        }
    }

    /**
     * Discover Start on a catalog tile: the same enqueue Detail's Download
     * runs (single file, engine dedup) when [quickDownloadPlan] allows;
     * otherwise [openDetail] runs (main thread) so Detail can ask which
     * version or explain why not.
     */
    fun quickDownload(entry: RomEntry, openDetail: () -> Unit) {
        if (quickJob?.isActive == true) return
        val mapped = entry.platformId?.let { com.gamebex.client.data.PlatformRegistry.byFolder.containsKey(it) } == true
        if (!mapped) {
            openDetail()
            return
        }
        quickJob = viewModelScope.launch {
            try {
                val (files, desc) = withContext(Dispatchers.IO) {
                    val source = sources.open(entry.ref.sourceId)
                    val files = runCatching { source.variants(entry.ref) }.getOrElse { emptyList() }
                    val desc = if (files.size == 1) {
                        runCatching { source.detail(entry.ref).description }.getOrNull()
                    } else null
                    files to desc
                }
                when (quickDownloadPlan(mapped, files.size)) {
                    QuickDownloadPlan.OPEN_DETAIL -> openDetail()
                    QuickDownloadPlan.ENQUEUE -> {
                        val file = files.first()
                        withContext(Dispatchers.IO) {
                            engine.enqueueSource(
                                entry, file, desc,
                                targetFileName = com.gamebex.client.data.downloadFileNameFor(file.name),
                            )
                        }
                        _message.value = "Downloading ${entry.title}. Progress is in Downloads."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.gamebex.client.net.DestinationBusyException) {
                _message.value = "Already downloading ${entry.title}."
            } catch (e: Exception) {
                logW(TAG, "quick download failed", e)
                _message.value = UserErrors.forCatalog(e, _sourceName.value)
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
