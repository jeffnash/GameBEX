package com.gamebex.client.ui

import android.content.SharedPreferences
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamebex.client.R
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleFocusMemory
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.FollowSelected
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.PrimaryConsoleAction
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.SecondaryConsoleAction
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.rememberShelfState
import com.gamebex.client.ui.console.screenFocus
import com.gamebex.client.ui.presentation.relativeDayLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where downloads land, for the context pane. */
data class DownloadStorage(val label: String, val path: String, val freeBytes: Long)

/** Removable volumes live under /storage/XXXX-XXXX; everything else is internal. */
fun downloadVolumeLabel(path: String): String =
    if (path.startsWith("/storage/") && !path.startsWith("/storage/emulated")) "SD card"
    else "Internal storage"

/**
 * Completed rows the user removed from the Downloads list. Persisted in
 * prefs; the download records themselves are kept.
 */
class DownloadDismissals(private val prefs: SharedPreferences?) {
    private val _ids = MutableStateFlow(load())
    val ids: StateFlow<Set<Long>> = _ids

    private fun load(): Set<Long> =
        prefs?.getStringSet(KEY, emptySet())?.mapNotNullTo(HashSet()) { it.toLongOrNull() } ?: emptySet()

    fun dismiss(add: Collection<Long>, existing: Set<Long>) {
        val next = pruneDismissed(_ids.value, add, existing)
        _ids.value = next
        prefs?.edit()?.putStringSet(KEY, next.mapTo(HashSet()) { it.toString() })?.apply()
    }

    companion object {
        const val PREFS = "downloads_ui"
        private const val KEY = "dismissed_completed"
    }
}

/**
 * Add [add] and drop ids no longer in history so the set stays bounded.
 * An empty [existing] (history not loaded yet) never prunes.
 */
fun pruneDismissed(current: Set<Long>, add: Collection<Long>, existing: Set<Long>): Set<Long> {
    val merged = current + add
    if (existing.isEmpty()) return merged
    return merged.filterTo(HashSet()) { it in existing || it in add }
}

/** Active work (anything unfinished) vs quiet completed history. */
data class DownloadSections(
    val active: List<DownloadRow>,
    val completed: List<DownloadRow>,
) {
    /** Focus order: active rows, then completed rows. */
    val all: List<DownloadRow> = active + completed
    val runningCount: Int = active.count {
        it.status == DownloadEngine.ST_ACTIVE || it.status == DownloadEngine.ST_INGEST
    }
    val queuedCount: Int = active.count { it.status == DownloadEngine.ST_QUEUED }
    val attentionCount: Int = active.count { it.status in ATTENTION }
    val isEmpty: Boolean get() = all.isEmpty()
}

private val ATTENTION = setOf(
    DownloadEngine.ST_FAILED, DownloadEngine.ST_NEEDS_ASSIST,
    DownloadEngine.ST_SOURCE_UNAVAILABLE, DownloadEngine.ST_PARKED_SD,
)

/** Running first, then needs-attention, paused, queued; stable by id (creation order). */
private fun activeRank(status: String): Int = when (status) {
    DownloadEngine.ST_ACTIVE, DownloadEngine.ST_INGEST -> 0
    in ATTENTION -> 1
    DownloadEngine.ST_PAUSED -> 2
    DownloadEngine.ST_QUEUED -> 3
    else -> 4
}

/**
 * Split merged rows into sections. Active order is by state rank then id,
 * so progress persistence (which bumps updatedAtMs) never reshuffles rows;
 * completed keeps history order (most recent first) minus [dismissed].
 */
fun sectionDownloadRows(rows: List<DownloadRow>, dismissed: Set<Long> = emptySet()): DownloadSections =
    DownloadSections(
        active = rows.filter { it.status != DownloadEngine.ST_DONE }
            .sortedWith(compareBy<DownloadRow> { activeRank(it.status) }.thenBy { it.id }),
        completed = rows.filter { it.status == DownloadEngine.ST_DONE && it.id !in dismissed },
    )

enum class DownloadAction(val label: String) {
    Details("Details"), Pause("Pause"), Resume("Resume"), Retry("Retry"),
    Verify("Verify"), Open("Open"), Cancel("Cancel"), Remove("Remove"),
    ClearCompleted("Clear completed"),
}

/** A on a list row. */
fun downloadPrimaryAction(status: String?): DownloadAction? = when (status) {
    null -> null
    DownloadEngine.ST_PAUSED, DownloadEngine.ST_PARKED_SD,
    DownloadEngine.ST_SOURCE_UNAVAILABLE -> DownloadAction.Resume
    DownloadEngine.ST_FAILED -> DownloadAction.Retry
    DownloadEngine.ST_NEEDS_ASSIST -> DownloadAction.Verify
    DownloadEngine.ST_DONE -> DownloadAction.Open
    else -> DownloadAction.Details // active, queued, finishing
}

/** X on a list row: finished/failed rows are removed, never "cancelled". */
fun downloadSecondaryAction(status: String?): DownloadAction? = when (status) {
    null -> null
    DownloadEngine.ST_DONE, DownloadEngine.ST_FAILED -> DownloadAction.Remove
    else -> DownloadAction.Cancel
}

/** Job actions offered in the context pane (Details target). */
fun downloadJobActions(status: String?): List<DownloadAction> {
    val main = when (status) {
        null, DownloadEngine.ST_INGEST -> null
        DownloadEngine.ST_ACTIVE, DownloadEngine.ST_QUEUED -> DownloadAction.Pause
        else -> downloadPrimaryAction(status)
    }
    return listOfNotNull(main, downloadSecondaryAction(status))
}

/** Pane focus order top→bottom: Clear completed, then the selected job's actions. */
fun downloadPaneActions(status: String?, canClearCompleted: Boolean): List<DownloadAction> =
    listOfNotNull(DownloadAction.ClearCompleted.takeIf { canClearCompleted }) + downloadJobActions(status)

/** Footer hints for the focused item (max four). */
fun downloadHints(
    row: DownloadRow?,
    empty: Boolean,
    paneAction: DownloadAction? = null,
): List<ControllerHint> {
    val back = ControllerHint(ControllerGlyph.B, "Back")
    return when {
        empty -> listOf(ControllerHint(ControllerGlyph.A, "Browse games"), back)
        paneAction != null -> listOf(ControllerHint(ControllerGlyph.A, paneAction.label), back)
        else -> listOfNotNull(
            downloadPrimaryAction(row?.status)?.let { ControllerHint(ControllerGlyph.A, it.label) },
            downloadSecondaryAction(row?.status)?.let { ControllerHint(ControllerGlyph.X, it.label) },
            back,
        )
    }
}

/** Whole percent, only when the total is known. */
fun downloadPercent(read: Long, total: Long): Int? =
    if (total > 0) ((read.coerceAtLeast(0) * 100) / total).coerceIn(0, 100).toInt() else null

private fun platformName(folder: String?): String? =
    folder?.let { PlatformRegistry.byFolder[it]?.display ?: it }

/** "Platform · file.zip · from Source": distinguishes duplicate titles. */
fun downloadRowMeta(row: DownloadRow, platform: String? = platformName(row.platformFolder)): String =
    listOfNotNull(
        platform,
        row.fileName.takeIf { it.isNotBlank() },
        row.sourceLabel?.let { "from $it" },
    ).joinToString(" · ")

private fun stateColor(status: String): Color = when (status) {
    DownloadEngine.ST_FAILED -> ConsoleColors.Error
    DownloadEngine.ST_NEEDS_ASSIST, DownloadEngine.ST_PARKED_SD,
    DownloadEngine.ST_SOURCE_UNAVAILABLE -> ConsoleColors.Warning
    DownloadEngine.ST_DONE -> ConsoleColors.Success
    else -> ConsoleColors.TextSecondary
}

// ---------- landscape screen ----------

/** Design canvas x → content-box-local design px (rail is 96). */
private fun cx(canvasX: Int): Int = canvasX - ConsoleDesign.RAIL_WIDTH.toInt()

private const val ZONE_LIST = 0
private const val ZONE_PANE = 1

@Composable
internal fun ConsoleDownloadsScreen(
    vm: DownloadsViewModel,
    onBack: () -> Unit,
    railHost: RailHost?,
    focusMemory: ConsoleFocusMemory,
    onOpen: (Long) -> Unit,
    sourceNames: Map<String, String>,
    hints: ConsoleHints,
    onBrowse: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val context = LocalContext.current
    val history by vm.history.collectAsStateWithLifecycle()
    val live by vm.engine.jobs.collectAsStateWithLifecycle()
    val dismissed by vm.dismissals.ids.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    // Progress ticks re-emit `live`; merge/section only when inputs change.
    val rows = remember(history, live, sourceNames) { mergeDownloadRows(history, live, sourceNames) }
    val sections = remember(rows, dismissed) { sectionDownloadRows(rows, dismissed) }
    val all = sections.all
    val ids = remember(all) { all.map { it.id } }

    val focus = rememberScreenFocus()
    val shelf = rememberShelfState(all.size, initial = focusMemory.recall("downloads"))
    focusMemory.store("downloads", shelf.selected)
    // Rebind by id: a job finishing moves from Active to Completed.
    val lastId = remember { arrayOf<Long?>(null) }
    LaunchedEffect(ids) { shelf.selectStableId(lastId[0], ids) }
    fun noteSelection() { lastId[0] = ids.getOrNull(shelf.selected) }

    val selected = all.getOrNull(shelf.selected).takeIf { !sections.isEmpty }
    var zone by remember { mutableIntStateOf(ZONE_LIST) }
    var paneIdx by remember { mutableIntStateOf(0) }
    val canClear = vm.canClearCompleted && sections.completed.isNotEmpty()
    val paneActions = downloadPaneActions(selected?.status, canClear)
    val inPane = zone == ZONE_PANE && paneActions.isNotEmpty()
    val paneSel = paneIdx.coerceIn(0, maxOf(paneActions.size - 1, 0))
    fun paneEntry(): Int = if (canClear && paneActions.size > 1) 1 else 0

    LaunchedEffect(sections.completed.size) { vm.refreshStorage(context) }

    fun run(action: DownloadAction, row: DownloadRow?) {
        when (action) {
            DownloadAction.Details -> { zone = ZONE_PANE; paneIdx = paneEntry() }
            DownloadAction.ClearCompleted -> {
                vm.removeCompleted(sections.completed.map { it.id })
                zone = ZONE_LIST
            }
            else -> {
                row ?: return
                when (action) {
                    DownloadAction.Pause -> vm.pause(row.id)
                    DownloadAction.Resume, DownloadAction.Retry -> vm.resume(row.id)
                    DownloadAction.Verify -> context.startActivity(AssistActivity.intent(context, row.id))
                    DownloadAction.Open -> vm.openGame(row.id) { it?.let(onOpen) }
                    DownloadAction.Cancel -> { vm.cancel(row.id); zone = ZONE_LIST }
                    DownloadAction.Remove -> {
                        if (row.status == DownloadEngine.ST_DONE) vm.removeCompleted(row.id) else vm.cancel(row.id)
                        zone = ZONE_LIST
                    }
                    else -> Unit
                }
            }
        }
    }

    val footer = downloadHints(selected, sections.isEmpty, paneActions.getOrNull(paneSel)?.takeIf { inPane })
    LaunchedEffect(footer) { hints.set(footer) }

    val listState = rememberLazyListState()
    // Lazy indices include section headers.
    val lazyIndex = when {
        sections.active.isNotEmpty() && shelf.selected < sections.active.size -> 1 + shelf.selected
        sections.active.isNotEmpty() -> 2 + shelf.selected
        else -> 1 + shelf.selected
    }
    FollowSelected(listState, lazyIndex, all.size + 2)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .screenFocus(focus)
            .consoleKeys(
                onUp = {
                    if (inPane) { if (paneSel > 0) paneIdx = paneSel - 1; true }
                    else shelf.left().also { noteSelection() }
                },
                onDown = {
                    if (inPane) { if (paneSel < paneActions.size - 1) paneIdx = paneSel + 1; true }
                    else shelf.right().also { noteSelection() }
                },
                onLeft = {
                    if (inPane) { zone = ZONE_LIST; true } else railHost?.enterRail() ?: false
                },
                onRight = {
                    if (!inPane && paneActions.isNotEmpty() && !sections.isEmpty) {
                        zone = ZONE_PANE; paneIdx = paneEntry(); true
                    } else false
                },
                onA = {
                    when {
                        sections.isEmpty -> { onBrowse(); true }
                        inPane -> { run(paneActions[paneSel], selected); true }
                        else -> downloadPrimaryAction(selected?.status)?.let { run(it, selected); true } ?: false
                    }
                },
                onX = {
                    if (inPane || sections.isEmpty) false
                    else downloadSecondaryAction(selected?.status)?.let { run(it, selected); true } ?: false
                },
                onB = {
                    if (inPane) zone = ZONE_LIST else onBack()
                    true
                },
            ),
    ) {
        Row(
            modifier = Modifier.offset(x = g.x(cx(120)), y = g.x(36)),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "Downloads", color = ConsoleColors.TextPrimary, fontFamily = SpaceGrotesk,
                fontSize = g.t(34), lineHeight = g.t(38), fontWeight = FontWeight.Bold,
            )
            if (!sections.isEmpty) {
                Text(
                    listOfNotNull(
                        "${sections.active.size} active".takeIf { sections.active.isNotEmpty() },
                        "${sections.completed.size} completed".takeIf { sections.completed.isNotEmpty() },
                    ).joinToString(" · "),
                    color = ConsoleColors.TextTertiary, fontSize = g.t(17), lineHeight = g.t(23),
                    modifier = Modifier.padding(start = g.x(20), bottom = g.x(4)),
                )
            }
        }

        Box(
            modifier = Modifier
                .offset(x = g.x(cx(120)), y = g.x(104))
                .size(g.x(820), g.x(648 - 104)),
        ) {
            if (sections.isEmpty) {
                EmptyDownloads(focused = true, onBrowse = onBrowse)
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    if (sections.active.isNotEmpty()) {
                        item(key = "h-active") { SectionHeader("Active", sections.active.size, first = true) }
                        itemsIndexed(sections.active, key = { _, r -> r.id }) { i, r ->
                            ActiveDownloadRow(
                                r,
                                focused = !inPane && shelf.selected == i,
                                selected = shelf.selected == i,
                                onClick = {
                                    zone = ZONE_LIST
                                    if (shelf.selected == i) downloadPrimaryAction(r.status)?.let { run(it, r) }
                                    else { shelf.select(i); noteSelection() }
                                },
                            )
                        }
                    }
                    if (sections.completed.isNotEmpty()) {
                        item(key = "h-done") {
                            SectionHeader("Completed", sections.completed.size, first = sections.active.isEmpty())
                        }
                        val base = sections.active.size
                        itemsIndexed(sections.completed, key = { _, r -> r.id }) { j, r ->
                            val i = base + j
                            CompletedDownloadRow(
                                r,
                                focused = !inPane && shelf.selected == i,
                                selected = shelf.selected == i,
                                onClick = {
                                    zone = ZONE_LIST
                                    if (shelf.selected == i) run(DownloadAction.Open, r)
                                    else { shelf.select(i); noteSelection() }
                                },
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .offset(x = g.x(cx(984)), y = g.x(112))
                .size(g.x(440), g.x(648 - 112)),
        ) {
            ContextPane(
                sections = sections,
                storage = storage,
                selected = selected,
                actions = paneActions,
                focusedIndex = if (inPane) paneSel else -1,
                onAction = { i ->
                    zone = ZONE_PANE; paneIdx = i
                    run(paneActions[i], selected)
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int, first: Boolean) {
    val g = LocalConsoleGeometry.current
    Text(
        "${label.uppercase()} · $count",
        color = ConsoleColors.TextTertiary, fontSize = g.tMin(13), lineHeight = g.t(18),
        fontWeight = FontWeight.Bold, letterSpacing = g.t(13) * 0.08f,
        modifier = Modifier.padding(start = g.x(20), top = g.x(if (first) 0 else 28), bottom = g.x(8)),
    )
}

private fun Modifier.downloadRowChrome(
    focused: Boolean,
    selected: Boolean,
    dividerStart: androidx.compose.ui.unit.Dp,
    focusStroke: androidx.compose.ui.unit.Dp,
    radius: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .fillMaxWidth()
        .drawBehind {
            if (!focused && !selected) {
                val y = size.height - 1f
                drawLine(
                    ConsoleColors.Steel.copy(alpha = 0.6f),
                    Offset(dividerStart.toPx(), y), Offset(size.width, y), strokeWidth = 1f,
                )
            }
        }
        .clip(shape)
        .background(if (focused || selected) ConsoleColors.Surface else Color.Transparent)
        .then(if (focused) Modifier.border(focusStroke, ConsoleColors.FocusRing, shape) else Modifier)
        .clickable(onClick = onClick)
}

@Composable
private fun ActiveDownloadRow(row: DownloadRow, focused: Boolean, selected: Boolean, onClick: () -> Unit) {
    val g = LocalConsoleGeometry.current
    val pct = downloadPercent(row.read, row.total)
    val fraction = if (row.total > 0) (row.read.toFloat() / row.total).coerceIn(0f, 1f) else 0f
    val complete = row.status == DownloadEngine.ST_INGEST || (row.total > 0 && row.read >= row.total)
    Column(
        modifier = Modifier
            .heightIn(min = g.x(96))
            .downloadRowChrome(focused, selected, g.x(20), g.x(ConsoleDesign.FOCUS_STROKE), g.x(ConsoleDesign.RADIUS_CARD), onClick)
            .padding(start = g.x(20), end = g.x(20), top = g.x(16), bottom = g.x(14)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.title, color = ConsoleColors.TextPrimary,
                    fontSize = g.t(20), lineHeight = g.t(24),
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val meta = downloadRowMeta(row)
                if (meta.isNotEmpty()) {
                    Text(
                        meta, color = ConsoleColors.TextTertiary,
                        fontSize = g.tMin(15), lineHeight = g.t(19),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Fixed trailing column: state + tabular percent never jitter.
            Column(
                modifier = Modifier.width(g.x(190)).padding(start = g.x(16)),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    downloadStateLabel(row.status), color = stateColor(row.status),
                    fontSize = g.tMin(15), lineHeight = g.t(19),
                    fontWeight = if (row.status in ATTENTION) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                )
                if (pct != null) {
                    Text(
                        "$pct%", color = ConsoleColors.TextPrimary,
                        fontSize = g.t(20), lineHeight = g.t(24), fontWeight = FontWeight.Bold,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"), maxLines = 1, textAlign = TextAlign.End,
                    )
                }
            }
        }
        Spacer(Modifier.height(g.x(12)))
        ProgressTrack(
            fraction = if (complete) 1f else fraction,
            fill = when {
                complete -> ConsoleColors.Success
                row.status == DownloadEngine.ST_FAILED -> ConsoleColors.Error
                row.status == DownloadEngine.ST_ACTIVE -> ConsoleColors.Accent
                else -> ConsoleColors.AccentShade
            },
        )
    }
}

@Composable
private fun ProgressTrack(fraction: Float, fill: Color) {
    val g = LocalConsoleGeometry.current
    val animated by animateFloatAsState(
        fraction, animationSpec = tween(180, easing = LinearEasing), label = "dl-progress",
    )
    Box(
        modifier = Modifier.fillMaxWidth().height(g.x(6)).background(ConsoleColors.Steel),
    ) {
        if (animated > 0f) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(animated).background(fill))
        }
    }
}

@Composable
private fun CompletedDownloadRow(row: DownloadRow, focused: Boolean, selected: Boolean, onClick: () -> Unit) {
    val g = LocalConsoleGeometry.current
    val size = if (row.total > 0) row.total else row.read
    Row(
        modifier = Modifier
            .heightIn(min = g.x(64))
            .downloadRowChrome(
                focused, selected, g.x(20 + 20 + 16), g.x(ConsoleDesign.FOCUS_STROKE),
                g.x(ConsoleDesign.RADIUS_CARD), onClick,
            )
            .padding(horizontal = g.x(20), vertical = g.x(10)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Check, contentDescription = "Completed",
            tint = ConsoleColors.Success, modifier = Modifier.size(g.x(20)),
        )
        Column(modifier = Modifier.weight(1f).padding(start = g.x(16))) {
            Text(
                row.title,
                color = if (focused) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary,
                fontSize = g.t(18), lineHeight = g.t(22),
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val meta = downloadRowMeta(row)
            if (meta.isNotEmpty()) {
                Text(
                    meta, color = ConsoleColors.TextTertiary,
                    fontSize = g.tMin(14), lineHeight = g.t(18),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            modifier = Modifier.width(g.x(150)).padding(start = g.x(16)),
            horizontalAlignment = Alignment.End,
        ) {
            if (size > 0) {
                Text(
                    formatSize(size), color = ConsoleColors.TextSecondary,
                    fontSize = g.tMin(15), lineHeight = g.t(19), style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1, textAlign = TextAlign.End,
                )
            }
            if (row.updatedAtMs > 0) {
                Text(
                    relativeDayLabel(row.updatedAtMs), color = ConsoleColors.TextTertiary,
                    fontSize = g.tMin(14), lineHeight = g.t(18),
                    maxLines = 1, textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun EmptyDownloads(focused: Boolean, onBrowse: () -> Unit) {
    val g = LocalConsoleGeometry.current
    Column(modifier = Modifier.padding(start = g.x(20), top = g.x(24))) {
        Image(
            painterResource(R.drawable.gamebex_icon_foreground), null,
            modifier = Modifier.size(g.x(80)),
        )
        Text(
            "NO DOWNLOADS YET", color = ConsoleColors.TextPrimary,
            fontSize = g.t(24), lineHeight = g.t(28), fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = g.x(20)),
        )
        Text(
            "Games you get from Discover\nshow up here while they download.",
            color = ConsoleColors.TextSecondary, fontSize = g.t(17), lineHeight = g.t(23),
            modifier = Modifier.padding(top = g.x(8)),
        )
        PrimaryConsoleAction(
            label = "Browse games", focused = focused, onClick = onBrowse, controllerGlyph = null,
            modifier = Modifier.padding(top = g.x(24)).width(g.x(250)),
        )
    }
}

@Composable
private fun ContextPane(
    sections: DownloadSections,
    storage: DownloadStorage?,
    selected: DownloadRow?,
    actions: List<DownloadAction>,
    focusedIndex: Int,
    onAction: (Int) -> Unit,
) {
    val g = LocalConsoleGeometry.current
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Eyebrow("Queue")
        Row(
            modifier = Modifier.padding(top = g.x(12)),
            horizontalArrangement = Arrangement.spacedBy(g.x(32)),
        ) {
            Stat(sections.runningCount, "Active")
            Stat(sections.queuedCount, "Queued")
            if (sections.attentionCount > 0) Stat(sections.attentionCount, "Need attention", ConsoleColors.Warning)
        }
        Text(
            "Saving to", color = ConsoleColors.TextTertiary, fontSize = g.tMin(15), lineHeight = g.t(19),
            modifier = Modifier.padding(top = g.x(20)),
        )
        Text(
            storage?.let { "${it.label} · ${formatSize(it.freeBytes)} free" } ?: "Checking storage…",
            color = ConsoleColors.TextSecondary, fontSize = g.t(17), lineHeight = g.t(22),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        storage?.let {
            Text(
                it.path, color = ConsoleColors.TextTertiary, fontSize = g.tMin(13), lineHeight = g.t(17),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        actions.forEachIndexed { i, a ->
            if (a == DownloadAction.ClearCompleted) {
                QuietPaneAction(
                    a.label, focused = focusedIndex == i, onClick = { onAction(i) },
                    modifier = Modifier.padding(top = g.x(12)),
                )
            }
        }
        Box(
            Modifier.padding(vertical = g.x(20)).fillMaxWidth().height(g.x(1))
                .background(ConsoleColors.Steel),
        )
        if (selected != null) {
            SelectedJob(selected)
            actions.forEachIndexed { i, a ->
                if (a == DownloadAction.ClearCompleted) return@forEachIndexed
                val primary = a != DownloadAction.Cancel && a != DownloadAction.Remove
                val mod = Modifier.padding(top = g.x(12)).fillMaxWidth()
                if (primary) {
                    PrimaryConsoleAction(a.label, focused = focusedIndex == i, onClick = { onAction(i) }, modifier = mod, controllerGlyph = null)
                } else {
                    SecondaryConsoleAction(a.label, focused = focusedIndex == i, onClick = { onAction(i) }, modifier = mod)
                }
            }
        }
    }
}

@Composable
private fun SelectedJob(row: DownloadRow) {
    val g = LocalConsoleGeometry.current
    Eyebrow(if (row.status == DownloadEngine.ST_DONE) "Completed" else "Selected job")
    Text(
        row.title, color = ConsoleColors.TextPrimary, fontFamily = SpaceGrotesk,
        fontSize = g.t(22), lineHeight = g.t(27), fontWeight = FontWeight.Bold,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = g.x(12)),
    )
    val pct = downloadPercent(row.read, row.total)
    val progress = when {
        row.status == DownloadEngine.ST_DONE -> row.total.takeIf { it > 0 }?.let { formatSize(it) }
        row.total > 0 -> "${formatSize(row.read)} of ${formatSize(row.total)}" + (pct?.let { " · $it%" } ?: "")
        row.read > 0 -> formatSize(row.read)
        else -> null
    }
    Text(
        listOfNotNull(downloadStateLabel(row.status), progress).joinToString(" · "),
        color = stateColor(row.status), fontSize = g.t(17), lineHeight = g.t(22),
        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        modifier = Modifier.padding(top = g.x(8)),
    )
    DetailLine("Platform", platformName(row.platformFolder))
    DetailLine("File", row.fileName.takeIf { it.isNotBlank() })
    DetailLine("Source", row.sourceLabel)
    if (row.status == DownloadEngine.ST_DONE && row.updatedAtMs > 0) {
        DetailLine("Finished", relativeDayLabel(row.updatedAtMs))
    }
    if (row.status != DownloadEngine.ST_DONE) {
        row.error?.takeIf { it.isNotBlank() }?.let {
            Text(
                it, color = ConsoleColors.TextSecondary, fontSize = g.tMin(15), lineHeight = g.t(20),
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = g.x(8)),
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val g = LocalConsoleGeometry.current
    Row(modifier = Modifier.padding(top = g.x(6))) {
        Text(
            label, color = ConsoleColors.TextTertiary, fontSize = g.tMin(15), lineHeight = g.t(20),
            modifier = Modifier.width(g.x(96)), maxLines = 1,
        )
        Text(
            value, color = ConsoleColors.TextSecondary, fontSize = g.tMin(15), lineHeight = g.t(20),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Eyebrow(text: String) {
    val g = LocalConsoleGeometry.current
    Text(
        text.uppercase(), color = ConsoleColors.TextTertiary, fontSize = g.tMin(13), lineHeight = g.t(18),
        fontWeight = FontWeight.Bold, letterSpacing = g.t(13) * 0.08f,
    )
}

@Composable
private fun Stat(value: Int, label: String, tint: Color = ConsoleColors.TextPrimary) {
    val g = LocalConsoleGeometry.current
    Column {
        Text(
            "$value", color = tint, fontFamily = SpaceGrotesk,
            fontSize = g.t(28), lineHeight = g.t(32), fontWeight = FontWeight.Bold,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        )
        Text(label, color = ConsoleColors.TextTertiary, fontSize = g.tMin(15), lineHeight = g.t(19), maxLines = 1)
    }
}

/** Quiet secondary action: text only until focused (then ring + surface). */
@Composable
private fun QuietPaneAction(label: String, focused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD))
    Text(
        label,
        color = if (focused) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary,
        fontSize = g.t(16), lineHeight = g.t(20),
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier
            .clip(shape)
            .background(if (focused) ConsoleColors.HighSurface else Color.Transparent)
            .border(
                if (focused) g.x(ConsoleDesign.FOCUS_STROKE) else g.x(1),
                if (focused) ConsoleColors.FocusRing else ConsoleColors.Steel,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = g.x(14), vertical = g.x(8)),
    )
}
