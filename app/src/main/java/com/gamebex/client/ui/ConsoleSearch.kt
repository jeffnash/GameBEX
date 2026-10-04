package com.gamebex.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamebex.client.GameBexApp
import com.gamebex.client.R
import com.gamebex.client.data.RomEntry
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.sources.coverImage
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ConsoleSearchField
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerGlyphBadge
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.FollowSelected
import com.gamebex.client.ui.console.GameCover
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.PrimaryConsoleAction
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.rememberShelfState
import com.gamebex.client.ui.console.screenFocus
import com.gamebex.client.ui.discover.SystemChipRow
import com.gamebex.client.ui.discover.searchSystemChips
import com.gamebex.client.ui.discover.systemChipIndex
import com.gamebex.client.ui.presentation.CatalogMatch
import com.gamebex.client.ui.presentation.folderForCatalogEntry
import com.gamebex.client.ui.presentation.matchCatalog
import com.gamebex.source.SourceKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val CONSOLE_RECENT_MAX = 6

fun consoleRecent(recent: List<String>): List<String> = recent.take(CONSOLE_RECENT_MAX)

/** Longer than the VM's 350ms query debounce. */
const val QUERY_SETTLE_MS = 450L

/**
 * EMPTY before the query settles is only the debounce gap: show the loading
 * silhouettes instead of flashing NO GAMES FOUND while typing.
 */
fun consoleSearchState(state: SearchState, queryStable: Boolean): SearchState =
    if (state == SearchState.EMPTY && !queryStable) SearchState.LOADING else state

/** Focus zones of the console Search screen; CHIPS (system chips) sits between field and list. */
enum class SearchZone { FIELD, LIST, PREVIEW, CHIPS }

/** What B does in the current focus context. */
enum class SearchBack { STOP_EDITING, TO_FIELD, LEAVE }

/**
 * B from the list/preview returns to the field first; on the field, B first
 * closes an edit that changed the query, then leaves Search once the field
 * is focused and unchanged.
 */
fun resolveSearchBack(zone: SearchZone, editing: Boolean, fieldChanged: Boolean): SearchBack = when {
    zone != SearchZone.FIELD -> SearchBack.TO_FIELD
    editing && fieldChanged -> SearchBack.STOP_EDITING
    else -> SearchBack.LEAVE
}

/** Focusable rows in the list region for a state (failure = one Retry). */
fun searchListCount(state: SearchState, results: Int, recent: Int): Int = when (state) {
    SearchState.IDLE -> minOf(recent, CONSOLE_RECENT_MAX)
    SearchState.RESULTS -> results
    SearchState.FAILURE -> 1
    SearchState.LOADING, SearchState.EMPTY -> 0
}

/** Catalog result availability/download state shown in rows and preview. */
enum class SearchAvailability { IN_LIBRARY, DOWNLOADING, QUEUED, PAUSED, NEEDS_ATTENTION, FAILED, DELETED, AVAILABLE }

fun latestDownloadFor(records: List<DownloadRecord>, key: SourceKey): DownloadRecord? =
    records.filter { it.sourceId == key.sourceId && it.sourceItemKey == key.itemKey }
        .maxByOrNull { it.updatedAtMs }

/**
 * Library ownership wins; otherwise the newest download's status. A tombstone
 * whose download is settled reads as "Download again", never OWNED.
 */
fun searchAvailability(match: CatalogMatch?, downloadStatus: String?): SearchAvailability = when {
    match is CatalogMatch.Owned -> SearchAvailability.IN_LIBRARY
    downloadStatus == DownloadEngine.ST_ACTIVE -> SearchAvailability.DOWNLOADING
    downloadStatus == DownloadEngine.ST_QUEUED -> SearchAvailability.QUEUED
    downloadStatus == DownloadEngine.ST_PAUSED ||
        downloadStatus == DownloadEngine.ST_PARKED_SD ||
        downloadStatus == DownloadEngine.ST_SOURCE_UNAVAILABLE -> SearchAvailability.PAUSED
    downloadStatus == DownloadEngine.ST_NEEDS_ASSIST -> SearchAvailability.NEEDS_ATTENTION
    downloadStatus == DownloadEngine.ST_FAILED -> SearchAvailability.FAILED
    match is CatalogMatch.DownloadAgain -> SearchAvailability.DELETED
    else -> SearchAvailability.AVAILABLE
}

/** Short trailing label for a row; null for plain "available". */
fun searchAvailabilityTag(a: SearchAvailability): String? = when (a) {
    SearchAvailability.IN_LIBRARY -> "OWNED"
    SearchAvailability.DOWNLOADING -> "GETTING"
    SearchAvailability.QUEUED -> "QUEUED"
    SearchAvailability.PAUSED -> "PAUSED"
    SearchAvailability.NEEDS_ATTENTION -> "CHECK"
    SearchAvailability.FAILED -> "FAILED"
    SearchAvailability.DELETED -> "REMOVED"
    SearchAvailability.AVAILABLE -> null
}

/** Preview status sentence; percent only when determinate. */
fun searchAvailabilityLabel(a: SearchAvailability, read: Long = 0, total: Long = 0): String = when (a) {
    SearchAvailability.IN_LIBRARY -> "In your library"
    SearchAvailability.DOWNLOADING ->
        if (total > 0) "Downloading ${(read * 100 / total).coerceIn(0, 100)}%" else "Downloading"
    SearchAvailability.QUEUED -> "Queued to download"
    SearchAvailability.PAUSED -> "Download paused"
    SearchAvailability.NEEDS_ATTENTION -> "Download needs attention"
    SearchAvailability.FAILED -> "Download failed"
    SearchAvailability.DELETED -> "Download again"
    SearchAvailability.AVAILABLE -> "Available to download"
}

private fun availabilityColor(a: SearchAvailability): Color = when (a) {
    SearchAvailability.IN_LIBRARY -> ConsoleColors.Success
    SearchAvailability.DOWNLOADING, SearchAvailability.QUEUED -> ConsoleColors.Accent
    SearchAvailability.PAUSED, SearchAvailability.NEEDS_ATTENTION -> ConsoleColors.Warning
    SearchAvailability.FAILED -> ConsoleColors.Error
    SearchAvailability.DELETED -> ConsoleColors.Accent
    SearchAvailability.AVAILABLE -> ConsoleColors.TextSecondary
}

/** Design canvas x → content-box-local design px (content starts after the rail). */
private fun cx(canvasX: Int): Int = canvasX - ConsoleDesign.RAIL_WIDTH.toInt()

/**
 * Controller: entry focuses the field and opens text input; submit moves to
 * the first result; Down walks field → system chips → list; on the chips
 * Left/Right move and A narrows the search to that system; Right from the
 * list enters the preview; A opens the catalog detail; X removes a focused
 * recent search; B walks back list/chips → field → leave (see [resolveSearchBack]).
 */
@Composable
fun ConsoleSearchScreen(
    vm: SearchViewModel,
    railHost: RailHost?,
    hints: ConsoleHints,
    onBack: () -> Unit,
    onOpen: (RomEntry) -> Unit,
    onSources: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val searchHeight = g.hitMin(g.x(56))
    val chipGeometry = librarySearchGeometry(g.scale, searchHeight.value)
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val error by vm.error.collectAsState()
    val unavailable by vm.sourceUnavailable.collectAsState()
    val sourceName by vm.sourceName.collectAsState()
    val recentAll by vm.recent.collectAsState()
    val recent = consoleRecent(recentAll)
    val consoles by vm.consoles.collectAsState()
    val system by vm.system.collectAsState()
    val chips = remember(consoles) { searchSystemChips(consoles) }
    val showChips = chips.size > 1
    val selectedChip = systemChipIndex(chips, system)
    // Typing settles for the VM's debounce before "no results" is claimed.
    var queryStable by remember(query) { mutableStateOf(false) }
    LaunchedEffect(query) {
        delay(QUERY_SETTLE_MS)
        queryStable = true
    }
    val state = consoleSearchState(resolveSearchState(query, searching, results.size, error), queryStable)

    // Availability: library ownership + newest download per catalog entry.
    val app = LocalContext.current.applicationContext as GameBexApp
    val games by remember { LibraryDb.get(app).games().observeAll() }
        .collectAsState(initial = emptyList<GameEntity>())
    val downloads by remember { app.engine.history(200) }
        .collectAsState(initial = emptyList<DownloadRecord>())
    val installed by app.sources.list().collectAsState()
    fun revFor(sourceId: String): String? = installed.firstOrNull { it.id == sourceId }?.revision
    val matches = remember(results, games) { matchCatalog(results, games, null) }
    fun availabilityOf(e: RomEntry): Pair<SearchAvailability, DownloadRecord?> {
        val rec = latestDownloadFor(downloads, e.key)
        return searchAvailability(matches[e.key], rec?.status) to rec
    }
    val resultKeys = remember(results) { searchResultKeys(results) }

    val focus = rememberScreenFocus()
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var zoneIdx by rememberSaveable { mutableIntStateOf(SearchZone.FIELD.ordinal) }
    val zone = SearchZone.entries[zoneIdx]
    var editing by remember { mutableStateOf(false) }
    // Query when the field last took focus: "unchanged" for B-to-leave.
    var fieldBaseline by rememberSaveable { mutableStateOf(query) }
    var entered by rememberSaveable { mutableStateOf(false) }
    var savedSel by rememberSaveable { mutableIntStateOf(0) }
    var listIdentity by rememberSaveable { mutableStateOf("") }
    // Chip under D-pad focus; snaps to the selected system when it changes.
    var chipFocus by rememberSaveable { mutableIntStateOf(selectedChip) }
    LaunchedEffect(selectedChip) { chipFocus = selectedChip }

    val listCount = searchListCount(state, results.size, recent.size)
    val shelf = rememberShelfState(maxOf(listCount, 1), initial = savedSel)
    savedSel = shelf.selected
    // New list content (not a nav return) starts at the top.
    val identity = state.name + ":" + (if (state == SearchState.RESULTS) resultKeys.hashCode() else recent.hashCode())
    LaunchedEffect(identity) {
        if (listIdentity.isNotEmpty() && listIdentity != identity) shelf.select(0)
        listIdentity = identity
    }
    // The chips stay focusable while a re-run search is loading or empty.
    if (listCount == 0 && (zone == SearchZone.LIST || zone == SearchZone.PREVIEW)) zoneIdx = SearchZone.FIELD.ordinal
    if (zone == SearchZone.CHIPS && !showChips) zoneIdx = SearchZone.FIELD.ordinal
    if (zone == SearchZone.PREVIEW && state != SearchState.RESULTS) zoneIdx = SearchZone.LIST.ordinal
    val listState = rememberLazyListState()
    FollowSelected(listState, shelf.selected, listCount)
    val selectedEntry = if (state == SearchState.RESULTS) results.getOrNull(shelf.selected) else null

    fun setZone(z: SearchZone) { zoneIdx = z.ordinal }
    fun startEditing() {
        setZone(SearchZone.FIELD)
        editing = true
        fieldBaseline = query
        runCatching { searchFocus.requestFocus() }
        keyboard?.show()
    }
    fun stopEditing() {
        editing = false
        keyboard?.hide()
        runCatching { focus.requestFocus() }
    }
    fun toField() {
        setZone(SearchZone.FIELD)
        fieldBaseline = query
    }
    fun toList() {
        if (listCount == 0) return
        if (editing) stopEditing()
        setZone(SearchZone.LIST)
        shelf.select(0)
    }
    fun toChips() {
        if (editing) stopEditing()
        chipFocus = selectedChip
        setZone(SearchZone.CHIPS)
    }
    /** Narrows to chip [i]'s system; the VM re-runs the current query. */
    fun pickChip(i: Int) {
        val chip = chips.getOrNull(i) ?: return
        chipFocus = i
        vm.selectSystem(chip.id)
    }
    var submitJob by remember { mutableStateOf<Job?>(null) }
    fun submit() {
        vm.commitRecent(query)
        stopEditing()
        fieldBaseline = query
        if (query.isBlank()) return
        // Wait past the VM's 350ms debounce, then for the fetch to settle.
        submitJob?.cancel()
        submitJob = scope.launch {
            delay(400)
            snapshotFlow { vm.searching.value }.first { !it }
            if (vm.results.value.isNotEmpty() && vm.error.value == null) {
                setZone(SearchZone.LIST)
                shelf.select(0)
            }
        }
    }
    fun open(e: RomEntry) {
        vm.commitRecent(query)
        onOpen(e)
    }
    fun pickRecent(q: String) {
        vm.onQuery(q)
        vm.commitRecent(q)
        fieldBaseline = q
        submitJob?.cancel()
        setZone(SearchZone.FIELD)
        submit()
    }
    fun failureAction() = if (unavailable) onSources() else vm.retry()

    // Entry: focus the field and open text input once the root focus grant
    // (two frames in rememberScreenFocus) has settled. Nav returns restore.
    LaunchedEffect(Unit) {
        if (!entered) {
            entered = true
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            startEditing()
        }
    }

    val primaryLabel = "View details"
    LaunchedEffect(zone, editing, state, unavailable) {
        val back = ControllerHint(ControllerGlyph.B, "Back")
        hints.set(
            when {
                zone == SearchZone.FIELD && editing -> listOf(
                    ControllerHint(ControllerGlyph.A, "Search"), back,
                )
                zone == SearchZone.FIELD -> listOf(ControllerHint(ControllerGlyph.A, "Type"), back)
                zone == SearchZone.CHIPS -> listOf(ControllerHint(ControllerGlyph.A, "Filter"), back)
                zone == SearchZone.PREVIEW -> listOf(
                    ControllerHint(ControllerGlyph.A, primaryLabel), back,
                )
                state == SearchState.IDLE -> listOf(
                    ControllerHint(ControllerGlyph.A, "Search"),
                    ControllerHint(ControllerGlyph.X, "Remove"),
                    back,
                )
                state == SearchState.FAILURE -> listOf(
                    ControllerHint(ControllerGlyph.A, if (unavailable) "Sources" else "Retry"), back,
                )
                else -> listOf(ControllerHint(ControllerGlyph.A, "Open"), back)
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .screenFocus(focus)
            .consoleKeys(
                onUp = {
                    when (zone) {
                        SearchZone.LIST -> if (shelf.selected > 0) shelf.left()
                        else { if (showChips) toChips() else toField(); true }
                        SearchZone.CHIPS -> { toField(); true }
                        else -> true
                    }
                },
                onDown = {
                    when (zone) {
                        SearchZone.FIELD -> { if (showChips) toChips() else toList(); true }
                        SearchZone.CHIPS -> { toList(); true }
                        SearchZone.LIST -> shelf.right()
                        SearchZone.PREVIEW -> true
                    }
                },
                // While typing, Left/Right fall through to the text cursor.
                onLeft = {
                    when {
                        editing -> false
                        zone == SearchZone.PREVIEW -> { setZone(SearchZone.LIST); true }
                        zone == SearchZone.CHIPS && chipFocus > 0 -> { chipFocus--; true }
                        else -> railHost?.enterRail() ?: false
                    }
                },
                onRight = {
                    when {
                        editing -> false
                        zone == SearchZone.CHIPS && chipFocus < chips.size - 1 -> { chipFocus++; true }
                        zone == SearchZone.LIST && selectedEntry != null -> { setZone(SearchZone.PREVIEW); true }
                        else -> false
                    }
                },
                onA = {
                    when (zone) {
                        SearchZone.FIELD -> if (editing) submit() else startEditing()
                        SearchZone.CHIPS -> pickChip(chipFocus)
                        SearchZone.PREVIEW -> selectedEntry?.let { open(it) }
                        SearchZone.LIST -> when (state) {
                            SearchState.IDLE -> recent.getOrNull(shelf.selected)?.let { pickRecent(it) }
                            SearchState.RESULTS -> selectedEntry?.let { open(it) }
                            SearchState.FAILURE -> failureAction()
                            else -> Unit
                        }
                    }
                    true
                },
                onB = {
                    when (resolveSearchBack(zone, editing, query != fieldBaseline)) {
                        SearchBack.TO_FIELD -> toField()
                        SearchBack.STOP_EDITING -> { stopEditing(); fieldBaseline = query }
                        SearchBack.LEAVE -> { if (editing) stopEditing(); onBack() }
                    }
                    true
                },
                onStart = { selectedEntry?.let { open(it); true } ?: false },
                onX = {
                    if (!editing && zone == SearchZone.LIST && state == SearchState.IDLE) {
                        recent.getOrNull(shelf.selected)?.let { vm.removeRecent(it) }
                        true
                    } else false
                },
            ),
    ) {
        Row(
            modifier = Modifier.offset(x = g.x(cx(120)), y = g.x(36)),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "Search", color = ConsoleColors.TextPrimary,
                fontSize = g.t(34), lineHeight = g.t(38), fontWeight = FontWeight.Bold,
            )
            sourceName?.let {
                Text(
                    it, color = ConsoleColors.TextTertiary, fontSize = g.t(17), lineHeight = g.t(23),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = g.x(20), bottom = g.x(4)).widthIn(max = g.x(500)),
                )
            }
        }

        ConsoleSearchField(
            query = query, onQuery = vm::onQuery,
            placeholder = "Search the catalog",
            focused = zone == SearchZone.FIELD,
            focusRequester = searchFocus,
            onTap = { startEditing() },
            onSubmit = { submit() },
            modifier = Modifier.offset(x = g.x(cx(120)), y = g.x(92))
                .size(g.x(680), searchHeight),
        )

        // System chips share Library's search geometry (chips y164, list y220).
        if (showChips) {
            SystemChipRow(
                chips = chips,
                selected = selectedChip,
                focused = chipFocus.takeIf { zone == SearchZone.CHIPS },
                onPick = { i ->
                    if (editing) stopEditing()
                    setZone(SearchZone.CHIPS)
                    pickChip(i)
                },
                modifier = Modifier.offset(x = g.x(cx(120)), y = chipGeometry.chipTop.dp).width(g.x(760)),
            )
        }

        val listTop = if (showChips) chipGeometry.listTop.dp else g.x(176)
        Box(
            modifier = Modifier
                .offset(x = g.x(cx(120)), y = listTop)
                .size(g.x(760), g.x(648) - listTop),
        ) {
            when (state) {
                SearchState.RESULTS -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(results.size, key = { resultKeys[it] }) { i ->
                        val e = results[i]
                        val (avail, _) = availabilityOf(e)
                        ResultRow(
                            entry = e,
                            cover = e.coverImage(revFor(e.ref.sourceId)),
                            availability = avail,
                            focused = zone == SearchZone.LIST && i == shelf.selected,
                            selected = i == shelf.selected,
                            onClick = {
                                setZone(SearchZone.LIST)
                                if (shelf.selected == i) open(e) else shelf.select(i)
                            },
                        )
                    }
                }
                SearchState.IDLE -> if (recent.isNotEmpty()) {
                    Column {
                        Text(
                            "RECENT", color = ConsoleColors.TextTertiary, fontFamily = Silkscreen,
                            fontSize = g.tMin(12), lineHeight = g.tMin(14),
                            modifier = Modifier.padding(start = g.x(20), bottom = g.x(8)),
                        )
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(recent.size, key = { "recent:" + recent[it] }) { i ->
                                val q = recent[i]
                                RecentRow(
                                    query = q,
                                    focused = zone == SearchZone.LIST && i == shelf.selected,
                                    onClick = { setZone(SearchZone.LIST); shelf.select(i); pickRecent(q) },
                                    onRemove = { vm.removeRecent(q) },
                                )
                            }
                        }
                    }
                } else {
                    IdleVignette()
                }
                SearchState.LOADING -> Column { repeat(3) { RowSilhouette() } }
                SearchState.EMPTY -> ListNote("NO GAMES FOUND", "Try a shorter title or another spelling.")
                SearchState.FAILURE -> Column(modifier = Modifier.padding(top = g.x(32), start = g.x(20))) {
                    Text(
                        "SEARCH FAILED", color = ConsoleColors.Error, fontFamily = Silkscreen,
                        fontSize = g.tMin(13), lineHeight = g.tMin(16),
                    )
                    Text(
                        error ?: "Search failed.", color = ConsoleColors.TextPrimary,
                        fontSize = g.t(17), lineHeight = g.t(23),
                        modifier = Modifier.padding(top = g.x(8)).widthIn(max = g.x(520)),
                    )
                    PrimaryConsoleAction(
                        label = if (unavailable) "Sources" else "Retry",
                        focused = zone == SearchZone.LIST,
                        onClick = { setZone(SearchZone.LIST); failureAction() },
                        controllerGlyph = null,
                        modifier = Modifier.padding(top = g.x(20)).width(g.x(250)),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .offset(x = g.x(cx(936)), y = g.x(152))
                .size(g.x(500), g.x(648 - 152)),
        ) {
            when {
                state == SearchState.LOADING -> PreviewSilhouette()
                selectedEntry != null -> {
                    val (avail, rec) = availabilityOf(selectedEntry)
                    SearchPreview(
                        entry = selectedEntry,
                        cover = selectedEntry.coverImage(revFor(selectedEntry.ref.sourceId)),
                        availability = avail,
                        record = rec,
                        actionLabel = primaryLabel,
                        actionFocused = zone == SearchZone.PREVIEW,
                        onAction = { open(selectedEntry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    entry: RomEntry,
    cover: Any?,
    availability: SearchAvailability,
    focused: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD))
    val dividerStart = g.x(20 + 48 + 16)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(g.x(82))
            .drawBehind {
                if (!selected) {
                    val y = size.height - 1f
                    drawLine(
                        ConsoleColors.Steel.copy(alpha = 0.55f),
                        Offset(dividerStart.toPx(), y), Offset(size.width, y), strokeWidth = 1f,
                    )
                }
            }
            .clip(shape)
            .background(if (selected) ConsoleColors.Surface else Color.Transparent)
            .then(
                if (focused) Modifier.border(g.x(ConsoleDesign.FOCUS_STROKE), ConsoleColors.FocusRing, shape)
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = g.x(20)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameCover(
            title = entry.title,
            cover = cover,
            focused = false,
            ring = false,
            tint = folderForCatalogEntry(entry)?.let { tintFor(it) } ?: ConsoleColors.Accent,
            systemFolder = folderForCatalogEntry(entry),
            modifier = Modifier.size(g.x(48), g.x(68)),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = g.x(16))) {
            Text(
                entry.title, color = ConsoleColors.TextPrimary,
                fontSize = g.t(18), lineHeight = g.t(22),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.consoleName.orEmpty(), color = ConsoleColors.TextTertiary,
                fontSize = g.t(15), lineHeight = g.t(18),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // Fixed trailing status column (min width) so labels never jitter.
        Box(modifier = Modifier.widthIn(min = g.x(80)), contentAlignment = Alignment.CenterEnd) {
            searchAvailabilityTag(availability)?.let {
                Text(
                    it, color = availabilityColor(availability), fontFamily = Silkscreen,
                    fontSize = g.tMin(12), lineHeight = g.tMin(14), maxLines = 1,
                )
            }
        }
    }
}

/**
 * Compact recent row (72). Focused, it shows X Remove; otherwise a quiet ✕,
 * so a touch user can remove a row without first picking it (a tap on the
 * row searches it).
 */
@Composable
internal fun RecentRow(query: String, focused: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(g.x(72))
            .drawBehind {
                if (!focused) {
                    val y = size.height - 1f
                    drawLine(
                        ConsoleColors.Steel.copy(alpha = 0.55f),
                        Offset(g.x(20).toPx(), y), Offset(size.width, y), strokeWidth = 1f,
                    )
                }
            }
            .clip(shape)
            .background(if (focused) ConsoleColors.Surface else Color.Transparent)
            .then(
                if (focused) Modifier.border(g.x(ConsoleDesign.FOCUS_STROKE), ConsoleColors.FocusRing, shape)
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = g.x(20)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            query, color = if (focused) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary,
            fontSize = g.t(18), lineHeight = g.t(22),
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (focused) {
            Row(
                modifier = Modifier.clickable(onClick = onRemove).padding(start = g.x(16)),
                horizontalArrangement = Arrangement.spacedBy(g.x(8)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControllerGlyphBadge(ControllerGlyph.X)
                Text(
                    "Remove", color = ConsoleColors.TextSecondary,
                    fontSize = g.t(15), lineHeight = g.t(18), maxLines = 1,
                )
            }
        } else {
            val hit = g.hitMin(g.x(56))
            Box(
                modifier = Modifier.sizeIn(minWidth = hit, minHeight = hit).clickable(onClick = onRemove),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Close, "Remove $query", tint = ConsoleColors.TextTertiary,
                    modifier = Modifier.size(g.x(20)),
                )
            }
        }
    }
}

@Composable
private fun SearchPreview(
    entry: RomEntry,
    cover: Any?,
    availability: SearchAvailability,
    record: DownloadRecord?,
    actionLabel: String,
    actionFocused: Boolean,
    onAction: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    Row {
        GameCover(
            title = entry.title,
            cover = cover,
            focused = false,
            ring = false,
            tint = folderForCatalogEntry(entry)?.let { tintFor(it) } ?: ConsoleColors.Accent,
            systemFolder = folderForCatalogEntry(entry),
            modifier = Modifier.size(g.x(170), g.x(240)),
        )
        Column(modifier = Modifier.padding(start = g.x(24)).fillMaxWidth()) {
            Text(
                (entry.consoleName ?: "Catalog").uppercase(),
                color = ConsoleColors.TextTertiary, fontFamily = Silkscreen,
                fontSize = g.tMin(13), lineHeight = g.tMin(16), maxLines = 2,
            )
            Spacer(Modifier.height(g.x(12)))
            Text(
                entry.title, color = ConsoleColors.TextPrimary,
                fontSize = g.t(28), lineHeight = g.t(32), fontWeight = FontWeight.Bold,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(g.x(14)))
            Text(
                "STATUS", color = ConsoleColors.TextTertiary, fontFamily = Silkscreen,
                fontSize = g.tMin(12), lineHeight = g.tMin(14),
            )
            Text(
                searchAvailabilityLabel(
                    availability,
                    read = record?.bytes ?: 0,
                    total = record?.totalBytes ?: 0,
                ),
                color = availabilityColor(availability),
                fontSize = g.t(17), lineHeight = g.t(21), fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = g.x(4)),
            )
            Spacer(Modifier.height(g.x(24)))
            PrimaryConsoleAction(
                label = actionLabel,
                focused = actionFocused,
                onClick = onAction,
                controllerGlyph = ControllerGlyph.A,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Shown when idle with no search history. */
@Composable
private fun IdleVignette() {
    val g = LocalConsoleGeometry.current
    Column(modifier = Modifier.padding(top = g.x(32), start = g.x(20))) {
        Image(
            painterResource(R.drawable.gamebex_icon_foreground), null,
            modifier = Modifier.size(g.x(64)),
        )
        Text(
            "SEARCH THE CATALOG", color = ConsoleColors.TextPrimary,
            fontSize = g.t(24), lineHeight = g.t(28), fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = g.x(20)),
        )
        Text(
            "Type a game title, then press Search.",
            color = ConsoleColors.TextSecondary, fontSize = g.t(17), lineHeight = g.t(23),
            modifier = Modifier.padding(top = g.x(8)),
        )
    }
}

@Composable
private fun ListNote(heading: String, body: String) {
    val g = LocalConsoleGeometry.current
    Column(modifier = Modifier.padding(top = g.x(32), start = g.x(20))) {
        Text(
            heading, color = ConsoleColors.TextPrimary,
            fontSize = g.t(24), lineHeight = g.t(28), fontWeight = FontWeight.Bold,
        )
        Text(
            body, color = ConsoleColors.TextSecondary, fontSize = g.t(17), lineHeight = g.t(23),
            modifier = Modifier.padding(top = g.x(8)),
        )
    }
}

/** Static loading row in the final row geometry, instead of a lone spinner. */
@Composable
private fun RowSilhouette() {
    val g = LocalConsoleGeometry.current
    val r = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_BADGE))
    Row(
        modifier = Modifier.fillMaxWidth().height(g.x(82)).padding(horizontal = g.x(20)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(g.x(48), g.x(68))
                .clip(RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD)))
                .background(ConsoleColors.Surface),
        )
        Column(modifier = Modifier.padding(start = g.x(16))) {
            Box(Modifier.size(g.x(320), g.x(16)).clip(r).background(ConsoleColors.Surface))
            Spacer(Modifier.height(g.x(10)))
            Box(Modifier.size(g.x(160), g.x(12)).clip(r).background(ConsoleColors.Surface))
        }
    }
}

@Composable
private fun PreviewSilhouette() {
    val g = LocalConsoleGeometry.current
    val r = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_BADGE))
    Row {
        Box(
            Modifier.size(g.x(170), g.x(240))
                .clip(RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD)))
                .background(ConsoleColors.Surface),
        )
        Column(modifier = Modifier.padding(start = g.x(24))) {
            Box(Modifier.size(g.x(120), g.x(12)).clip(r).background(ConsoleColors.Surface))
            Spacer(Modifier.height(g.x(16)))
            Box(Modifier.size(g.x(260), g.x(24)).clip(r).background(ConsoleColors.Surface))
            Spacer(Modifier.height(g.x(12)))
            Box(Modifier.size(g.x(200), g.x(24)).clip(r).background(ConsoleColors.Surface))
        }
    }
}
