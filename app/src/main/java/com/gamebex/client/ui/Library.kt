package com.gamebex.client.ui

import com.gamebex.client.ui.presentation.GamePresentation
import com.gamebex.client.ui.presentation.GamePrimaryAction
import com.gamebex.client.ui.presentation.LaunchStatus
import com.gamebex.client.ui.presentation.LocalGamePresenter
import com.gamebex.client.ui.presentation.LocalLauncher
import com.gamebex.client.ui.presentation.LocalLaunchJob
import com.gamebex.client.ui.presentation.Readiness
import com.gamebex.client.ui.presentation.emulatorLabel
import com.gamebex.client.ui.presentation.readinessOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamebex.client.data.local.FolderCount
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.LibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Tints = listOf(
    0xFF7C6FF0, 0xFF00A3CC, 0xFF00D4AA, 0xFF8FD14F, 0xFFF4BE62, 0xFFF08A5D,
    0xFFFF8293, 0xFFC792EA, 0xFF64D8CB, 0xFF7CBFFF, 0xFFD4A373, 0xFF9AA5B1,
).map { Color(it) }

fun tintFor(folder: String): Color = Tints[(folder.hashCode() and 0x7fffffff) % Tints.size]

enum class LibSort { Recent, Title, System, Size, Played }

/**
 * Pure list projection behind LibraryViewModel.games (filter + sort).
 * Top-level so the perf harness can time it over 10k rows on the JVM.
 */
fun filterSortGames(
    all: List<GameEntity>,
    query: String,
    folder: String?,
    favOnly: Boolean,
    sort: LibSort,
): List<GameEntity> {
    var list = all.filter { it.available }
    if (folder != null) list = list.filter { it.folder == folder }
    if (favOnly) list = list.filter { it.favorite }
    if (query.isNotBlank()) list = list.filter { it.title.contains(query, true) }
    return when (sort) {
        LibSort.Recent -> list.sortedByDescending { it.addedAtMs }
        LibSort.Title -> list.sortedBy { it.title.lowercase() }
        LibSort.System -> list.sortedWith(compareBy({ it.folder }, { it.title.lowercase() }))
        LibSort.Size -> list.sortedByDescending { it.sizeBytes }
        LibSort.Played -> list.sortedByDescending { it.lastPlayedMs }
    }
}

class LibraryViewModel(private val appContext: android.content.Context) : ViewModel() {
    private val db = LibraryDb.get(appContext)
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val _folder = MutableStateFlow<String?>(null)
    val folder: StateFlow<String?> = _folder
    private val _favOnly = MutableStateFlow(false)
    val favOnly: StateFlow<Boolean> = _favOnly
    private val _sort = MutableStateFlow(LibSort.Recent)
    val sort: StateFlow<LibSort> = _sort
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _counts = MutableStateFlow<List<FolderCount>>(emptyList())
    val counts: StateFlow<List<FolderCount>> = _counts

    val games: StateFlow<List<GameEntity>> = combine(
        db.games().observeAll(), _query, _folder, _favOnly, _sort,
    ) { all, q, folder, favOnly, sort ->
        filterSortGames(all, q, folder, favOnly, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { db.games().observeCountsByFolder().collect { _counts.value = it } }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LibraryScanner.refreshIfStale(appContext, db) }
        }
    }

    fun rescan() {
        viewModelScope.launch {
            _scanning.value = true
            try {
                withContext(Dispatchers.IO) { LibraryScanner.refresh(appContext, db) }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun onQuery(q: String) { _query.value = q }
    fun onFolder(f: String?) { _folder.value = f }
    fun onFavOnly(v: Boolean) { _favOnly.value = v }
    fun onSort(s: LibSort) { _sort.value = s }
    fun toggleFav(game: GameEntity) {
        viewModelScope.launch(Dispatchers.IO) { db.games().setFavorite(game.id, !game.favorite) }
    }

    fun clearFilters() {
        _query.value = ""
        _folder.value = null
        _favOnly.value = false
    }

    private val _selectedId = MutableStateFlow<Long?>(null)
    fun select(id: Long?) { _selectedId.value = id }

    /** Preview for the focused row; presenter touches disk, so it runs on IO. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val preview: StateFlow<LibraryPreview?> = combine(_selectedId, games) { id, list ->
        list.firstOrNull { it.id == id }
    }
        .distinctUntilChanged()
        .mapLatest { g -> g?.let { withContext(Dispatchers.IO) { buildPreview(it) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun buildPreview(g: GameEntity): LibraryPreview {
        val lp = LocalGamePresenter.present(appContext, g, continued = g.playCount > 0, metaLine = null)
        return LibraryPreview(
            game = g,
            presentation = lp.presentation,
            readiness = readinessOf(lp.playable.status),
            emulator = emulatorLabel(g.folder, g.emulatorOverride, g.coreOverride),
            // Only a played game has a resume point worth skipping.
            canStartFresh = lp.presentation.primaryAction == GamePrimaryAction.Continue &&
                com.gamebex.client.launch.EmuRouter.canStartFresh(g.folder, g.emulatorOverride),
        )
    }

    /** Quick-play status panel for the last Play; null hides it. */
    private val _status = MutableStateFlow<LaunchStatus?>(null)
    val status: StateFlow<LaunchStatus?> = _status
    private val launchJob = LocalLaunchJob(
        scope = { viewModelScope },
        launcher = { id, fresh, report -> LocalLauncher.play(appContext, id, fresh, report) },
        onStatus = { _status.value = it },
    )
    fun clearStatus() = launchJob.dismiss()

    fun play(id: Long, fresh: Boolean = false) = launchJob.play(id, fresh)

    fun openRetroArch() {
        LocalLauncher.openRetroArch(appContext)?.let { _notice.value = it }
    }

    /** Delete to free space (after the confirm dialog); the row drops from the list. */
    /** One-line result of a non-launch action (delete); UI clears it after a beat. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }

    fun deleteGame(game: GameEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            _notice.value = when (val out = com.gamebex.client.data.local.GameDeleter.delete(appContext, db, game.id)) {
                is com.gamebex.client.data.local.GameDeleter.Outcome.Deleted -> {
                    com.gamebex.client.net.ReadyNotifier.cancel(appContext, game.id)
                    "Deleted ${game.title} · freed ${formatSize(out.bytesFreed)}."
                }
                is com.gamebex.client.data.local.GameDeleter.Outcome.Failed -> out.reason
            }
        }
    }

    /** Status-panel Retry: replays the same game and launch mode. */
    fun retry(status: LaunchStatus) {
        if (_status.value == status) launchJob.retry()
    }
}

data class LibraryPreview(
    val game: GameEntity,
    val presentation: GamePresentation,
    val readiness: Readiness,
    val emulator: String,
    val canStartFresh: Boolean = false,
)

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
