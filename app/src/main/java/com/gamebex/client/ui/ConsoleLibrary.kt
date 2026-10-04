package com.gamebex.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamebex.client.R
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleFocusMemory
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ConsoleSearchField
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.ControllerHintBar
import com.gamebex.client.ui.console.LaunchStatusPanel
import com.gamebex.client.ui.console.SecondaryConsoleAction
import com.gamebex.client.ui.console.FollowSelected
import com.gamebex.client.ui.console.GameCover
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.PrimaryConsoleAction
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.fadeTrailing
import com.gamebex.client.ui.console.rememberArtColor
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.rememberShelfState
import com.gamebex.client.ui.console.screenFocus
import com.gamebex.client.ui.presentation.relativeDayLabel
import com.gamebex.client.ui.presentation.GamePrimaryAction
import com.gamebex.client.ui.presentation.LaunchAction
import com.gamebex.client.ui.presentation.LaunchStatus
import com.gamebex.client.ui.presentation.LocalLauncher
import com.gamebex.client.ui.presentation.Tone
import com.gamebex.client.ui.presentation.ctaEnabled
import com.gamebex.client.ui.presentation.ctaLabel
import com.gamebex.client.ui.presentation.label

/** Design canvas x → content-box-local design px (content starts after the rail). */
private fun cx(canvasX: Int): Int = canvasX - ConsoleDesign.RAIL_WIDTH.toInt()

private const val ZONE_TOP = 0 // search (col 0) + sort (col 1)
private const val ZONE_CHIPS = 1
private const val ZONE_LIST = 2
private const val ZONE_PREVIEW = 3

internal data class LibrarySearchGeometry(
    val chipTop: Float,
    val listTop: Float,
    val listHeight: Float,
)

internal fun librarySearchGeometry(scale: Float, searchHeight: Float): LibrarySearchGeometry {
    val chipTop = maxOf(164f * scale, 92f * scale + searchHeight + 8f * scale)
    val listTop = maxOf(220f * scale, chipTop + 36f * scale + 16f * scale)
    return LibrarySearchGeometry(chipTop, listTop, 648f * scale - listTop)
}

/**
 * Library split view: search, sort and filters over a list, with a preview
 * pane that follows list focus. D-pad: Up/Down moves
 * search/sort → chips → list; Right enters the preview's Play / Start
 * fresh; A opens (search: types; sort: cycles; chip: toggles); Start plays;
 * Y favorites; X rescans; Select (or long-press) deletes after a confirm.
 * While typing, Left/Right reach the text cursor and Down/B/IME-Search
 * return to the list. Quick-play recovery takes A/X for its actions and B
 * to dismiss, or cancel an in-flight core installation.
 */
@Composable
fun ConsoleLibraryScreen(
    vm: LibraryViewModel,
    railHost: RailHost?,
    focusMemory: ConsoleFocusMemory,
    hints: ConsoleHints,
    onOpen: (Long) -> Unit,
    onBrowse: () -> Unit,
    onInstallEmu: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val searchHeight = g.hitMin(g.x(56))
    val searchGeometry = librarySearchGeometry(g.scale, searchHeight.value)
    val games by vm.games.collectAsState()
    val query by vm.query.collectAsState()
    val folder by vm.folder.collectAsState()
    val favOnly by vm.favOnly.collectAsState()
    val sort by vm.sort.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val counts by vm.counts.collectAsState()
    val preview by vm.preview.collectAsState()
    val notice by vm.notice.collectAsState()
    var pendingDelete by remember { mutableStateOf<GameEntity?>(null) }
    pendingDelete?.let { victim ->
        com.gamebex.client.ui.console.DeleteGameDialog(
            victim,
            fromSource = victim.sourceRefJson != null,
            onConfirm = {
                pendingDelete = null
                vm.deleteGame(victim)
            },
            onDismiss = { pendingDelete = null },
        )
    }
    val allStatus by vm.status.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val focus = rememberScreenFocus()
    val shelf = rememberShelfState(maxOf(games.size, 1), initial = focusMemory.recall("library"))
    focusMemory.store("library", shelf.selected)
    val selected = games.getOrNull(shelf.selected)
    LaunchedEffect(selected?.id) { vm.select(selected?.id) }
    val listState = rememberLazyListState()
    FollowSelected(listState, shelf.selected, games.size)

    val filtered = query.isNotBlank() || folder != null || favOnly
    val libraryEmpty = games.isEmpty() && !filtered && !scanning

    var zone by remember { mutableIntStateOf(ZONE_LIST) }
    var topCol by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun startEditing() {
        zone = ZONE_TOP; topCol = 0; editing = true
        runCatching { searchFocus.requestFocus() }
        keyboard?.show()
    }
    fun stopEditing(toList: Boolean) {
        editing = false
        keyboard?.hide()
        runCatching { focus.requestFocus() }
        if (toList) zone = ZONE_LIST
    }
    var previewBtn by remember { mutableIntStateOf(0) } // 0 primary, 1 start fresh
    val canFresh = preview?.takeIf { it.game.id == selected?.id }?.canStartFresh == true
    if (!canFresh) previewBtn = 0
    if (zone == ZONE_PREVIEW && (preview == null || selected == null)) zone = ZONE_LIST
    var chipCol by remember { mutableIntStateOf(focusMemory.recall("library:chip", 0)) }
    focusMemory.store("library:chip", chipCol)
    data class LibChip(val label: String, val selected: Boolean, val run: () -> Unit)
    val chips = remember(folder, favOnly, counts) {
        buildList {
            add(LibChip("All", folder == null && !favOnly) { vm.onFolder(null); vm.onFavOnly(false) })
            add(LibChip("Favorites", favOnly) { vm.onFavOnly(!favOnly) })
            counts.sortedBy { it.folder }.forEach { c ->
                val label = PlatformRegistry.byFolder[c.folder]?.display ?: c.folder
                add(LibChip("$label · ${c.n}", folder == c.folder) {
                    vm.onFolder(if (folder == c.folder) null else c.folder)
                })
            }
        }
    }
    chipCol = chipCol.coerceIn(0, maxOf(chips.size - 1, 0))
    val chipRowState = rememberLazyListState()
    FollowSelected(chipRowState, chipCol, chips.size)
    fun cycleSort() {
        val all = LibSort.entries
        vm.onSort(all[(all.indexOf(sort) + 1) % all.size])
    }
    fun emptyAction() = if (filtered) vm.clearFilters() else onBrowse()
    fun primary(p: LibraryPreview) {
        // Unsupported system: the CTA is disabled, nothing to run.
        if (!p.presentation.ctaEnabled) return
        when (p.presentation.primaryAction) {
            GamePrimaryAction.Play, GamePrimaryAction.Continue -> vm.play(p.game.id)
            else -> onOpen(p.game.id)
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(5000)
            vm.clearNotice()
        }
    }
    fun startFresh(p: LibraryPreview) {
        if (p.canStartFresh) vm.play(p.game.id, fresh = true)
    }

    // In-flight work stays visible; settled recovery follows selection.
    val status = allStatus?.takeIf { it.busy || it.gameId == selected?.id }
    val recovery = status?.takeIf { !it.busy || it.canCancel }
    LaunchedEffect(selected?.id) {
        if (allStatus?.let { !it.busy && it.gameId != selected?.id } == true) vm.clearStatus()
    }
    fun runStatusAction(secondary: Boolean = false): Boolean {
        val st = recovery ?: return false
        val a = (if (secondary) st.secondaryAction else st.action) ?: return false
        when (a) {
            LaunchAction.OpenDetails -> {
                vm.clearStatus()
                onOpen(st.gameId)
            }
            is LaunchAction.Install -> {
                LocalLauncher.beginInstall(context, a.emu)
                vm.clearStatus()
                onInstallEmu()
            }
            LaunchAction.Retry -> vm.retry(st)
            LaunchAction.OpenRetroArch -> vm.openRetroArch()
        }
        return true
    }
    // Start runs the preview's primary action; hints name it, never a
    // hardcoded verb, and drop keys that do nothing in this state.
    val startLabel = preview?.takeIf { it.presentation.ctaEnabled }?.let { actionLabel(it) }
    val previewA = preview?.let {
        if (previewBtn == 1) "Start fresh" else startLabel
    }
    val hintList = if (!editing && recovery != null) listOfNotNull(
        recovery.action?.let { ControllerHint(ControllerGlyph.A, it.label()) },
        recovery.secondaryAction?.let { ControllerHint(ControllerGlyph.X, it.label()) },
        ControllerHint(ControllerGlyph.B, if (recovery.canCancel) "Cancel" else "Dismiss"),
    ) else libraryHints(
        zone = zone, topCol = topCol, editing = editing,
        settled = false, settledAction = null,
        previewA = previewA, startLabel = startLabel,
        favorite = selected?.favorite,
        emptyA = if (games.isEmpty()) (if (filtered) "Clear filters" else "Browse games") else null,
    )
    LaunchedEffect(hintList) { hints.set(hintList) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .screenFocus(focus)
            .consoleKeys(
                onUp = {
                    when {
                        editing -> true
                        zone == ZONE_PREVIEW -> { previewBtn = 0; true }
                        zone == ZONE_LIST -> if (shelf.selected > 0 && games.isNotEmpty()) shelf.left()
                        else { zone = ZONE_CHIPS; true }
                        zone == ZONE_CHIPS -> { zone = ZONE_TOP; true }
                        else -> false
                    }
                },
                onDown = {
                    when {
                        editing -> { stopEditing(toList = true); true }
                        zone == ZONE_PREVIEW -> { if (canFresh) previewBtn = 1; true }
                        zone == ZONE_TOP -> { zone = ZONE_CHIPS; true }
                        zone == ZONE_CHIPS -> { zone = ZONE_LIST; true }
                        else -> games.isNotEmpty() && shelf.right()
                    }
                },
                // While typing, Left/Right fall through to the text cursor.
                onLeft = {
                    when {
                        editing -> false
                        zone == ZONE_PREVIEW -> { zone = ZONE_LIST; true }
                        zone == ZONE_TOP && topCol > 0 -> { topCol--; true }
                        zone == ZONE_CHIPS && chipCol > 0 -> { chipCol--; true }
                        else -> railHost?.enterRail() ?: false
                    }
                },
                onRight = {
                    when {
                        editing -> false
                        zone == ZONE_TOP && topCol == 0 -> { topCol = 1; true }
                        zone == ZONE_CHIPS && chipCol < chips.size - 1 -> { chipCol++; true }
                        zone == ZONE_LIST && preview != null && selected != null -> {
                            zone = ZONE_PREVIEW; previewBtn = 0; true
                        }
                        else -> false
                    }
                },
                onA = {
                    if (!editing && recovery?.action != null) runStatusAction()
                    else when (zone) {
                        ZONE_TOP -> {
                            if (editing) stopEditing(toList = true)
                            else if (topCol == 0) startEditing() else cycleSort()
                            true
                        }
                        ZONE_PREVIEW -> preview?.let {
                            if (previewBtn == 1) startFresh(it) else primary(it)
                            true
                        } ?: false
                        ZONE_CHIPS -> { chips.getOrNull(chipCol)?.run?.invoke(); true }
                        else -> if (games.isEmpty()) { emptyAction(); true }
                        else selected?.let { onOpen(it.id); true } ?: false
                    }
                },
                onB = {
                    when {
                        editing -> { stopEditing(toList = false); true }
                        recovery != null -> { vm.clearStatus(); true }
                        zone == ZONE_PREVIEW -> { zone = ZONE_LIST; true }
                        else -> false
                    }
                },
                onStart = {
                    preview?.takeIf { it.presentation.ctaEnabled }?.let { primary(it); true } ?: false
                },
                onY = { selected?.let { vm.toggleFav(it); true } ?: false },
                onX = {
                    if (!editing && recovery?.secondaryAction != null) runStatusAction(secondary = true)
                    else { vm.rescan(); true }
                },
                onSelect = {
                    if (zone == ZONE_LIST && selected != null) { pendingDelete = selected; true } else false
                },
            ),
    ) {
        Row(
            modifier = Modifier.offset(x = g.x(cx(120)), y = g.x(36)),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "Library", color = ConsoleColors.TextPrimary,
                fontSize = g.t(34), lineHeight = g.t(38), fontWeight = FontWeight.Bold,
            )
            Text(
                notice ?: if (scanning) "Scanning…" else if (games.size == 1) "1 game" else "${games.size} games",
                color = ConsoleColors.TextTertiary, fontSize = g.t(17), lineHeight = g.t(23),
                modifier = Modifier.padding(start = g.x(20), bottom = g.x(4)),
            )
        }

        // X Rescan, right-aligned over Sort. Before Sort so Sort wins
        // where the two hit boxes overlap.
        Box(
            modifier = Modifier.offset(x = g.x(cx(120)), y = g.x(14))
                .size(g.x(760), g.x(84)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            ControllerHintBar(listOf(ControllerHint(ControllerGlyph.X, "Rescan")))
        }

        ConsoleSearchField(
            query = query, onQuery = vm::onQuery,
            placeholder = "Search your games",
            focused = zone == ZONE_TOP && topCol == 0,
            focusRequester = searchFocus,
            onTap = { startEditing() },
            onSubmit = { stopEditing(toList = true) },
            modifier = Modifier.offset(x = g.x(cx(120)), y = g.x(92))
                .size(g.x(540), searchHeight),
        )
        SortControl(
            sort = sort, focused = zone == ZONE_TOP && topCol == 1 && !editing,
            onClick = { zone = ZONE_TOP; topCol = 1; cycleSort() },
            modifier = Modifier.offset(x = g.x(cx(680)), y = g.x(92)).size(g.x(200), g.x(56)),
        )

        LazyRow(
            state = chipRowState,
            horizontalArrangement = Arrangement.spacedBy(g.x(8)),
            modifier = Modifier
                .offset(x = g.x(cx(120)), y = searchGeometry.chipTop.dp)
                .width(g.x(760))
                .fadeTrailing(g.x(48), enabled = chips.size > 4),
        ) {
            itemsIndexed(chips, key = { _, c -> c.label }) { i, chip ->
                FilterChip(chip.label, chip.selected, focused = zone == ZONE_CHIPS && i == chipCol) {
                    zone = ZONE_CHIPS; chipCol = i
                    chip.run()
                }
            }
        }

        Box(
            modifier = Modifier
                .offset(x = g.x(cx(120)), y = searchGeometry.listTop.dp)
                .size(g.x(760), searchGeometry.listHeight.dp),
        ) {
            when {
                games.isNotEmpty() -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(games, key = { _, it -> it.id }) { i, game ->
                        LibraryRow(
                            game = game,
                            focused = zone == ZONE_LIST && i == shelf.selected,
                            selected = i == shelf.selected,
                            onClick = {
                                zone = ZONE_LIST
                                if (shelf.selected == i) onOpen(game.id) else shelf.select(i)
                            },
                            onLongClick = {
                                zone = ZONE_LIST
                                shelf.select(i)
                                pendingDelete = game
                            },
                        )
                    }
                }
                scanning -> ListNote("SCANNING…", "Looking for games on your storage.", null, false) {}
                filtered -> ListNote(
                    "NO MATCHES", "Nothing fits these filters.", "Clear filters",
                    focused = zone == ZONE_LIST,
                ) { vm.clearFilters() }
            }
        }

        // Controls stay in the list column (≤ x880) so the sort box never
        // overlaps the pane, which rises to the search row (y92).
        Box(
            modifier = Modifier
                .offset(x = g.x(cx(936)), y = g.x(92))
                .size(g.x(500), g.x(620 - 92)),
        ) {
            val p = preview
            when {
                libraryEmpty -> EmptyLibrary(focused = zone == ZONE_LIST, onBrowse = onBrowse)
                p != null && selected != null -> PreviewPane(
                    p = p,
                    primaryFocused = zone == ZONE_PREVIEW && previewBtn == 0,
                    freshFocused = zone == ZONE_PREVIEW && previewBtn == 1,
                    onAction = { primary(p) },
                    onFresh = { startFresh(p) },
                    status = status?.takeUnless { it.busy },
                    onStatusAction = { runStatusAction() },
                    onDismissStatus = { vm.clearStatus() },
                    onSecondaryStatusAction = { runStatusAction(secondary = true) },
                )
            }
        }
        // A core job must remain visible even if filtering removes the preview.
        status?.takeIf { it.busy }?.let { st ->
            LaunchStatusPanel(
                status = st,
                onAction = { runStatusAction() },
                onDismiss = { vm.clearStatus() },
                onSecondaryAction = { runStatusAction(secondary = true) },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = g.x(24), bottom = g.x(52)),
            )
        }
    }
}

@Composable
private fun SortControl(sort: LibSort, focused: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD))
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (focused) ConsoleColors.HighSurface else ConsoleColors.Surface)
            .border(
                if (focused) g.x(ConsoleDesign.FOCUS_STROKE) else g.x(1),
                if (focused) ConsoleColors.FocusRing else ConsoleColors.Steel,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = g.x(16)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SORT", color = ConsoleColors.TextTertiary, fontFamily = Silkscreen,
            fontSize = g.tMin(12), lineHeight = g.tMin(14),
        )
        Text(
            sortLabel(sort), color = ConsoleColors.TextPrimary,
            fontSize = g.t(17), lineHeight = g.t(20), fontWeight = FontWeight.SemiBold,
            maxLines = 1, modifier = Modifier.padding(start = g.x(12)),
        )
    }
}

private fun sortLabel(s: LibSort): String = when (s) {
    LibSort.Recent -> "Newest"
    LibSort.Title -> "Title"
    LibSort.System -> "System"
    LibSort.Size -> "Size"
    LibSort.Played -> "Last played"
}

@Composable
private fun FilterChip(text: String, selected: Boolean, focused: Boolean, onClick: () -> Unit) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CHIP))
    Box(
        modifier = Modifier
            .height(g.x(36))
            .clip(shape)
            .background(if (selected) ConsoleColors.AccentDim else Color.Transparent)
            .border(
                when {
                    focused -> g.x(ConsoleDesign.FOCUS_STROKE)
                    selected -> g.x(2)
                    else -> g.x(1)
                },
                if (focused || selected) ConsoleColors.FocusRing else ConsoleColors.Steel,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = g.x(12)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected || focused) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary,
            fontSize = g.t(15), lineHeight = g.t(18), maxLines = 1,
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun LibraryRow(
    game: GameEntity,
    focused: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
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
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = g.x(20)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameCover(
            title = game.title,
            cover = game.artBox ?: game.coverPath,
            focused = false,
            ring = false,
            tint = tintFor(game.folder),
            systemFolder = game.folder,
            modifier = Modifier.size(g.x(48), g.x(68)),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = g.x(16))) {
            Text(
                game.title, color = ConsoleColors.TextPrimary,
                fontSize = g.t(18), lineHeight = g.t(22),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                rowMeta(game), color = ConsoleColors.TextTertiary,
                fontSize = g.t(15), lineHeight = g.t(18),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // Stable trailing marker column so labels never jitter.
        Row(
            modifier = Modifier.width(g.x(56)),
            horizontalArrangement = Arrangement.spacedBy(g.x(8), Alignment.End),
        ) {
            if (game.prepState in SETUP_STATES || !game.available) {
                Icon(
                    Icons.Filled.Warning,
                    if (game.available) "Needs setup" else "Storage missing",
                    tint = if (game.available) ConsoleColors.Warning else ConsoleColors.Error,
                    modifier = Modifier.size(g.x(22)),
                )
            }
            if (game.favorite) {
                Icon(
                    Icons.Filled.Favorite, "Favorite", tint = ConsoleColors.Accent,
                    modifier = Modifier.size(g.x(20)),
                )
            }
        }
    }
}

private val SETUP_STATES = setOf("NEEDS_BIOS", "NEEDS_MANUAL", "FAILED")

private fun systemName(folder: String): String = PlatformRegistry.byFolder[folder]?.display ?: folder

private fun formatOf(game: GameEntity): String =
    game.relPath.substringAfterLast('/').substringAfterLast('.', "").uppercase()

private fun rowMeta(game: GameEntity): String =
    listOf(systemName(game.folder), formatSize(game.sizeBytes), formatOf(game))
        .filter { it.isNotBlank() }
        .joinToString(" · ")

@Composable
private fun PreviewPane(
    p: LibraryPreview,
    primaryFocused: Boolean,
    freshFocused: Boolean,
    onAction: () -> Unit,
    onFresh: () -> Unit,
    status: LaunchStatus?,
    onStatusAction: () -> Unit,
    onDismissStatus: () -> Unit,
    onSecondaryStatusAction: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val game = p.game
    // Faint wash from the selected art; readable without it.
    val wash = rememberArtColor(game.artBox ?: game.coverPath, tintFor(game.folder))
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(wash.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset.Zero,
                        radius = size.width,
                    ),
                )
            },
    ) {
        Column {
            Row {
                GameCover(
                    title = game.title,
                    cover = game.artBox ?: game.coverPath,
                    focused = false,
                    ring = false,
                    tint = tintFor(game.folder),
                    systemFolder = game.folder,
                    modifier = Modifier.size(g.x(170), g.x(240)),
                )
                Column(modifier = Modifier.padding(start = g.x(24)).fillMaxWidth()) {
                    Text(
                        p.presentation.platformLabel.uppercase(),
                        color = ConsoleColors.TextTertiary, fontFamily = Silkscreen,
                        fontSize = g.tMin(13), lineHeight = g.tMin(16), maxLines = 1,
                    )
                    Spacer(Modifier.height(g.x(12)))
                    Text(
                        game.title, color = ConsoleColors.TextPrimary,
                        fontSize = g.t(28), lineHeight = g.t(32), fontWeight = FontWeight.Bold,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(g.x(24)))
                    PrimaryConsoleAction(
                        label = actionLabel(p),
                        focused = primaryFocused,
                        onClick = onAction,
                        controllerGlyph = ControllerGlyph.Start,
                        enabled = p.presentation.ctaEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (p.canStartFresh) {
                        SecondaryConsoleAction(
                            label = "Start fresh",
                            focused = freshFocused,
                            onClick = onFresh,
                            modifier = Modifier.padding(top = g.x(10)).fillMaxWidth(),
                        )
                    }
                    status?.let {
                        LaunchStatusPanel(
                            status = it,
                            onAction = onStatusAction,
                            onDismiss = onDismissStatus,
                            onSecondaryAction = onSecondaryStatusAction,
                            modifier = Modifier.padding(top = g.x(10)).fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(g.x(24)))
            FactGrid(p)
        }
    }
}

private fun actionLabel(p: LibraryPreview): String = p.presentation.ctaLabel()

/**
 * Library hint row (≤4), state-aware: every key named does that thing
 * here. [startLabel] null = Start does nothing; [favorite] null = no
 * selected game; [emptyA] non-null when the list is empty (A then browses
 * or clears filters). X Rescan isn't here: it has its own pill in the
 * header, visible (and tappable) in every zone.
 */
internal fun libraryHints(
    zone: Int,
    topCol: Int,
    editing: Boolean,
    settled: Boolean,
    settledAction: String?,
    previewA: String?,
    startLabel: String?,
    favorite: Boolean?,
    emptyA: String?,
): List<ControllerHint> {
    val start = startLabel?.let { ControllerHint(ControllerGlyph.Start, it) }
    val fav = favorite?.let { ControllerHint(ControllerGlyph.Y, if (it) "Unfavorite" else "Favorite") }
    return when {
        editing -> listOf(
            ControllerHint(ControllerGlyph.A, "Done"),
            ControllerHint(ControllerGlyph.B, "Close"),
        )
        // A settled panel with an action owns A/B; an action-less one
        // leaves A on the focus target.
        settled && settledAction != null -> listOfNotNull(
            ControllerHint(ControllerGlyph.A, settledAction),
            ControllerHint(ControllerGlyph.B, "Dismiss"),
            start, fav,
        )
        zone == ZONE_PREVIEW -> listOfNotNull(
            previewA?.let { ControllerHint(ControllerGlyph.A, it) },
            ControllerHint(ControllerGlyph.B, "Back"),
            start, fav,
        )
        zone == ZONE_TOP || zone == ZONE_CHIPS -> listOfNotNull(
            ControllerHint(
                ControllerGlyph.A,
                when {
                    zone == ZONE_CHIPS -> "Filter"
                    topCol == 0 -> "Search"
                    else -> "Sort"
                },
            ),
            start, fav,
        )
        emptyA != null -> listOf(ControllerHint(ControllerGlyph.A, emptyA))
        else -> listOfNotNull(
            ControllerHint(ControllerGlyph.A, "Open"),
            start, fav,
            ControllerHint(ControllerGlyph.Back, "Delete"),
        )
    }
}

@Composable
private fun FactGrid(p: LibraryPreview) {
    val g = LocalConsoleGeometry.current
    val game = p.game
    val file = listOf(formatSize(game.sizeBytes), formatOf(game)).filter { it.isNotBlank() }.joinToString(" · ")
    val facts = listOf(
        Triple("STATUS", p.readiness.label, toneColor(p.readiness.tone)),
        Triple(
            "LAST PLAYED",
            if (game.lastPlayedMs > 0) relativeDayLabel(game.lastPlayedMs) else "Never",
            ConsoleColors.TextSecondary,
        ),
        Triple("PLAYS", game.playCount.toString(), ConsoleColors.TextSecondary),
        Triple("FILE", file, ConsoleColors.TextSecondary),
        Triple("EMULATOR", p.emulator, ConsoleColors.TextSecondary),
    )
    Column(verticalArrangement = Arrangement.spacedBy(g.x(14))) {
        facts.chunked(2).forEach { pair ->
            Row {
                pair.forEach { (label, value, color) ->
                    Column(modifier = Modifier.width(g.x(250))) {
                        Text(
                            label, color = ConsoleColors.TextTertiary, fontFamily = Silkscreen,
                            fontSize = g.tMin(12), lineHeight = g.tMin(14),
                        )
                        Text(
                            value, color = color,
                            fontSize = g.t(17), lineHeight = g.t(21), fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = g.x(4)),
                        )
                    }
                }
            }
        }
    }
}

private fun toneColor(t: Tone): Color = when (t) {
    Tone.Ready -> ConsoleColors.Success
    Tone.Attention -> ConsoleColors.Warning
    Tone.Blocked -> ConsoleColors.Error
    Tone.Neutral -> ConsoleColors.TextSecondary
}

/** Empty-list note inside the list region when filters hide every game. */
@Composable
private fun BoxScope.ListNote(
    heading: String,
    body: String,
    action: String?,
    focused: Boolean,
    onAction: () -> Unit,
) {
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
        if (action != null) {
            PrimaryConsoleAction(
                label = action, focused = focused, onClick = onAction, controllerGlyph = null,
                modifier = Modifier.padding(top = g.x(20)).width(g.x(250)),
            )
        }
    }
}

/** Empty library: the preview region becomes a branded vignette. */
@Composable
private fun EmptyLibrary(focused: Boolean, onBrowse: () -> Unit) {
    val g = LocalConsoleGeometry.current
    Column(modifier = Modifier.padding(top = g.x(40))) {
        Image(
            painterResource(R.drawable.gamebex_icon_foreground), null,
            modifier = Modifier.size(g.x(80)),
        )
        Text(
            "NO GAMES YET", color = ConsoleColors.TextPrimary,
            fontSize = g.t(24), lineHeight = g.t(28), fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = g.x(20)),
        )
        Text(
            "Add ROMs to the GameBEX folder,\nor get games from Discover.",
            color = ConsoleColors.TextSecondary, fontSize = g.t(17), lineHeight = g.t(23),
            modifier = Modifier.padding(top = g.x(8)),
        )
        PrimaryConsoleAction(
            label = "Browse games", focused = focused, onClick = onBrowse, controllerGlyph = null,
            modifier = Modifier.padding(top = g.x(24)).width(g.x(250)),
        )
    }
}
