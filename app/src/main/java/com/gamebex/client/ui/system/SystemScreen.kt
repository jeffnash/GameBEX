package com.gamebex.client.ui.system

import com.gamebex.client.ui.presentation.SystemReadinessItem
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.presentation.attentionIndices
import com.gamebex.client.ui.presentation.prepSummaries
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.gamebex.client.R
import com.gamebex.client.data.EmuCatalog
import com.gamebex.client.data.Route
import com.gamebex.client.data.local.GameFolders
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.LibraryScanner
import com.gamebex.client.install.InstallState
import com.gamebex.client.data.CoreNames
import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.launch.ControllerProbe
import com.gamebex.client.launch.DeviceProfile
import com.gamebex.client.launch.PerfPlan
import com.gamebex.client.launch.PerfRecommendations
import com.gamebex.client.launch.PerfTuner
import com.gamebex.client.launch.RecKind
import com.gamebex.client.launch.RetroArchTuner
import com.gamebex.client.launch.RetroCoreProbe
import com.gamebex.client.ui.EmulatorsViewModel
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.openAllFilesSettings
import com.gamebex.client.ui.console.ConsoleFocusMemory
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.PrimaryConsoleAction
import com.gamebex.client.ui.console.QuietConsoleAction
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.consoleSelected
import com.gamebex.client.ui.console.isSystemReducedMotion
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.rememberShelfState
import com.gamebex.client.ui.console.screenFocus
import com.gamebex.client.ui.guidedLinksFor
import com.gamebex.client.ui.presentation.PrepSummary
import com.gamebex.client.ui.presentation.SystemAction
import com.gamebex.client.ui.presentation.SystemReadinessState
import com.gamebex.client.ui.presentation.EmuSnap
import com.gamebex.client.ui.presentation.systemReadinessItems
import com.gamebex.client.ui.presentation.systemsSubtitle
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tier-1 System: readiness first, emulator identity second.
 * One row per ROM folder (shared folders merge); every state derives from live probes
 * (package snapshots, RetroArch core query, prep counts) — never hardcoded.
 * Coordinates are design px local to the content box (absolute − rail).
 */
@Composable
fun SystemScreen(
    vm: EmulatorsViewModel,
    focusMemory: ConsoleFocusMemory,
    rail: RailHost,
    onSources: () -> Unit = {},
    onTogether: () -> Unit = {},
    hints: ConsoleHints? = null,
) {
    val g = LocalConsoleGeometry.current
    val context = LocalContext.current
    val snaps by vm.snaps.collectAsState()
    val installStates by vm.installer.states.collectAsState()
    val reducedMotion = isSystemReducedMotion()

    var cores by remember { mutableStateOf<Set<String>?>(null) }
    var coresProbed by remember { mutableStateOf(false) }
    var controllers by remember { mutableStateOf<List<ControllerProbe.Controller>?>(null) }
    var prepMap by remember { mutableStateOf<Map<String, PrepSummary>>(emptyMap()) }
    var prepLoaded by remember { mutableStateOf(false) }
    var sdPresent by remember { mutableStateOf(false) }
    var grantOk by remember { mutableStateOf(true) }
    var missingSys by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** Progress/result line for a system-file download (FetchSystemFiles). */
    var sysFetch by remember { mutableStateOf<String?>(null) }
    var sysFetching by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    suspend fun rescan(forceRom: Boolean = false) {
        vm.refresh()
        sdPresent = com.gamebex.client.cocoon.CocoonDirs.usingSd(context)
        grantOk = LibraryScanner.hasAllFilesAccess(context)
        // ROM files too (not just emulator readiness): after an SD swap or
        // a fresh all-files grant, this re-marks newly visible games.
        // Entry/resume paths skip when fresh; the X button forces.
        withContext(Dispatchers.IO) {
            val db = LibraryDb.get(context)
            if (forceRom) LibraryScanner.refresh(context, db)
            else LibraryScanner.refreshIfStale(context, db)
        }
        cores = RetroCoreProbe.queryInstalledCores(context)
        coresProbed = true
        missingSys = withContext(Dispatchers.IO) {
            com.gamebex.client.launch.CoreSystemFiles.missing(com.gamebex.client.launch.CoreSystemFiles.PACKS.keys)
        }
        controllers = ControllerProbe.connected()
        // Silent idempotent tune: shared dirs + managed CONFIGFILE
        // refresh (RA's own cfg is cross-app unreadable on Android 13+).
        runCatching { RetroArchTuner.tune() }
        val counts = withContext(Dispatchers.IO) {
            LibraryDb.get(context).games().prepCounts()
        }
        prepMap = prepSummaries(counts)
        prepLoaded = true
    }
    LaunchedEffect(Unit) { rescan() }
    // Device tier for "Recommended for this phone" (sysfs + RAM, read once).
    var device by remember { mutableStateOf<DeviceProfile?>(null) }
    LaunchedEffect(Unit) { device = withContext(Dispatchers.IO) { DeviceProfile.read(context) } }
    // Game folders modal: add/remove user library roots, then rescan so
    // new games appear (removed roots' rows go unavailable, never deleted).
    val folders = remember {
        GameFoldersState(context) { _, removed ->
            scope.launch {
                if (removed != null) {
                    withContext(Dispatchers.IO) {
                        LibraryScanner.forgetVolume(
                            LibraryDb.get(context), GameFolders.volumeIdFor(removed),
                        )
                    }
                }
                rescan(forceRom = true)
            }
        }
    }
    // Resume hook: refresh after the user returns from system settings
    // (permission-gated installs) or swaps SD — mirrors Emulators screen.
    // vm.refresh() also forwards installer.onResumed().
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch { rescan() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val items = remember(snaps, cores, coresProbed, prepMap, missingSys) {
        // Shared with DeviceStatus.systemWarnings: the header ⚠ count and
        // this screen can never disagree.
        systemReadinessItems(
            cores = if (coresProbed) cores else null,
            prepMap = prepMap,
            missingSystemFiles = missingSys,
        ) { pkg ->
            snaps[pkg]?.let { EmuSnap(it.installed, it.versionName, it.versionCode) }
                ?: EmuSnap(false, null, 0)
        }
            // Systems you have games for first; registry order otherwise.
            .sortedByDescending { (prepMap[it.folder]?.total ?: 0) > 0 }
    }
    fun gamesIn(item: SystemReadinessItem): Int = prepMap[item.folder]?.total ?: 0
    // Unconfigured systems with no games read as quiet "Not set up".
    fun rowLabel(item: SystemReadinessItem): String =
        if (gamesIn(item) == 0 && item.state != SystemReadinessState.Ready &&
            item.state != SystemReadinessState.LimitedSupport
        ) "Not set up" else stateLabel(item.state)
    fun rowColor(item: SystemReadinessItem): Color =
        if (rowLabel(item) == "Not set up") ConsoleColors.TextTertiary else stateColor(item.state)
    val attention = remember(items, prepMap) { attentionIndices(items, prepMap) }
    val subtitleCounts = remember(items, prepMap) {
        val withGames = items.filter { gamesIn(it) > 0 }
        Triple(
            withGames.size,
            withGames.count { it.state == SystemReadinessState.Ready },
            withGames.count { it.state == SystemReadinessState.LimitedSupport },
        )
    }

    val rows = rememberShelfState(items.size, focusMemory.recall("system:row", 0))
    var zone by remember { mutableIntStateOf(1) } // 0 = header buttons, 1 = list
    // 0 = fix, 1 = set-as-home, 2 = system launcher, 3 = sources,
    // 4 = game folders, 5 = controls, 6 = file access (SD only).
    // Header: 0 = next issue (only while something needs attention),
    // 1 = device menu, 2 = storage grant (only while SD access is actually
    // missing; otherwise it lives in Device). Indices stay fixed; a hidden
    // button just raises the lower bound.
    var zone0Btn by remember { mutableIntStateOf(0) }
    val grantButton = sdPresent && !grantOk
    val maxBtn = if (grantButton) 2 else 1
    val minBtn = if (attention.isEmpty()) 1 else 0
    var showControls by remember { mutableStateOf(false) }
    var showDevice by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(maxBtn, minBtn) {
        zone0Btn = zone0Btn.coerceIn(minBtn, maxBtn)
    }
    // Recommended settings for the focused system on this phone's tier.
    val plans: List<PerfPlan> = remember(items, rows.selected, device) {
        val p = items.getOrNull(rows.selected)?.let { PlatformRegistry.bySlug[it.platformId] }
        val d = device
        if (p == null || d == null) emptyList() else PerfRecommendations.forPlatform(p, d.tier)
    }
    val hasRecs = plans.isNotEmpty()
    // Y = next issue only while there is one: the hint hides with the button.
    val hasIssues = attention.isNotEmpty()
    val foldersPicking = folders.mode == GameFoldersState.Mode.PICK
    val foldersInFolder = foldersPicking && folders.pickDir != null
    LaunchedEffect(hints, hasIssues, hasRecs, folders.open, foldersPicking, foldersInFolder) {
        hints?.set(systemHints(folders.open, foldersPicking, foldersInFolder, hasIssues, hasRecs))
    }
    // Setup guides share the panel via START toggle (status and guides
    // never fit the 414px budget together); resets per focused row.
    var showGuides by remember(rows.selected) { mutableStateOf(false) }
    // SELECT: "Recommended for this phone" in the same panel; A applies there.
    var showRecs by remember(rows.selected) { mutableStateOf(false) }
    var recApplied by remember(rows.selected) { mutableStateOf<Map<Emu, Set<String>?>>(emptyMap()) }
    var recLines by remember(rows.selected) { mutableStateOf<List<String>>(emptyList()) }
    var recBusy by remember { mutableStateOf(false) }
    LaunchedEffect(showRecs, plans) {
        if (!showRecs) return@LaunchedEffect
        recApplied = withContext(Dispatchers.IO) {
            plans.filter { it.applicable }.associate { it.emu to PerfTuner.inspect(context, it.emu, it.tier) }
        }
    }
    fun applyRecs(): Boolean {
        val targets = plans.filter { it.applicable }
        if (recBusy || targets.isEmpty()) return true
        recBusy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                targets.map { PerfTuner.apply(context, it.emu, it.tier) } to
                    targets.associate { it.emu to PerfTuner.inspect(context, it.emu, it.tier) }
            }
            recLines = out.first.map { it.line }
            recApplied = out.second
            recBusy = false
        }
        return true
    }
    var guidedPkg by remember { mutableStateOf<String?>(null) }
    val guidedFocus = remember { FocusRequester() }
    LaunchedEffect(guidedPkg) {
        if (guidedPkg != null) runCatching { guidedFocus.requestFocus() }
    }
    // Signer conflict: the installed copy is signed by another key. The
    // user picks Uninstall (OS dialog) or keeps the existing install.
    var conflictPkg by remember { mutableStateOf<String?>(null) }
    val conflictFocus = remember { FocusRequester() }
    LaunchedEffect(conflictPkg) {
        if (conflictPkg != null) runCatching { conflictFocus.requestFocus() }
    }
    val guidesAvailable = items.getOrNull(rows.selected)?.let {
        it.biosGuide != null || it.overlayGuide != null || it.graphicsGuide != null ||
            it.controllerGuide != null
    } == true
    if (items.isNotEmpty()) {
        focusMemory.store("system:row", rows.selected)
        items.getOrNull(rows.selected)?.let { focusMemory.storeKey("system:row:id", it.platformId) }
    }
    // Rebind by stable id when the list recomputes (rescan), so insertions
    // can't shift selection onto the wrong system.
    val ids = remember(items) { items.map { it.platformId } }
    LaunchedEffect(ids) {
        focusMemory.recallKey("system:row:id")?.let { rows.selectStableId(it, ids) }
    }

    // Real lazy viewport: the controller selection drives the scroll position
    // and the window only moves when selection crosses its boundary.
    val listState = rememberLazyListState()
    // Keyed on ids too: a reorder (games-first sort landing after the
    // prep query) must re-scroll, or the list keeps the old top row.
    LaunchedEffect(rows.selected, ids) {
        if (items.isEmpty()) return@LaunchedEffect
        val i = rows.selected.coerceIn(0, items.size - 1)
        if (reducedMotion) listState.scrollToItem(i) else listState.animateScrollToItem(i)
    }

    fun jumpToAttention(dir: Int): Boolean {
        if (attention.isEmpty()) return false
        val target = if (dir > 0) {
            attention.firstOrNull { it > rows.selected } ?: attention.first()
        } else {
            attention.lastOrNull { it < rows.selected } ?: attention.last()
        }
        rows.select(target)
        zone = 1
        return true
    }

    fun runPrimary(): Boolean {
        val item = items.getOrNull(rows.selected) ?: return false
        when (val a = item.primaryAction) {
            is SystemAction.Install -> {
                // Guided origins (Dolphin) have no direct channel: installer
                // is a no-op for them, so route to the Play Store/site flow.
                if (guidedLinksFor(a.pkg) != null) {
                    guidedPkg = a.pkg
                } else if (vm.installer.stateOf(a.pkg) is InstallState.SignerConflict) {
                    conflictPkg = a.pkg
                } else if (vm.installer.stateOf(a.pkg) is InstallState.NeedsUnknownSources) {
                    vm.openUnknownSources()
                } else {
                    vm.installer.start(a.pkg)
                }
            }
            is SystemAction.Open -> vm.openApp(a.pkg)
            SystemAction.OpenCoreUpdater -> vm.openApp(RetroCoreProbe.RA_PKG)
            is SystemAction.FetchSystemFiles -> {
                if (sysFetching) return true
                sysFetching = true
                val zip = com.gamebex.client.launch.CoreSystemFiles.PACKS[a.core]?.zip ?: a.core
                sysFetch = "Downloading $zip…"
                scope.launch {
                    val r = com.gamebex.client.launch.CoreSystemFiles.ensure(
                        context, a.core,
                        onProgress = { read, total ->
                            sysFetch = if (total > 0) "Downloading $zip… ${read * 100 / total}%" else "Downloading $zip…"
                        },
                        onUnpack = { done, total -> sysFetch = "Installing $done of $total files…" },
                    )
                    sysFetch = when (r) {
                        is com.gamebex.client.launch.CoreSystemFiles.Result.Failed -> r.reason
                        else -> null
                    }
                    sysFetching = false
                    rescan()
                }
            }
            null -> return false
        }
        return true
    }

    val screenFocus = rememberScreenFocus()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .screenFocus(screenFocus)
            .consoleKeys(
                onLeft = {
                    if (folders.open) true
                    else if (zone == 0 && zone0Btn > minBtn) {
                        zone0Btn -= 1; true
                    } else rail.enterRail()
                },
                onRight = {
                    if (folders.open) true
                    else if (zone == 0 && zone0Btn < maxBtn) {
                        zone0Btn += 1; true
                    } else false
                },
                onUp = {
                    when {
                        folders.open -> folders.up()
                        zone == 1 && rows.selected > 0 -> rows.left()
                        zone == 1 -> {
                            zone = 0; zone0Btn = minBtn; true
                        }
                        else -> false
                    }
                },
                onDown = {
                    if (folders.open) folders.down()
                    else if (zone == 0) {
                        zone = 1; true
                    } else rows.right()
                },
                onA = {
                    if (folders.open) folders.a()
                    else if (guidedPkg != null || conflictPkg != null) true
                    else if (zone == 1 && showRecs) applyRecs()
                    else if (zone == 0) {
                        when (zone0Btn) {
                            0 -> jumpToAttention(1)
                            1 -> {
                                showDevice = true; true
                            }
                            else -> {
                                context.openAllFilesSettings(); true
                            }
                        }
                    } else runPrimary()
                },
                onB = {
                    if (folders.open) folders.b()
                    else if (guidedPkg != null) {
                        guidedPkg = null; true
                    } else if (conflictPkg != null) {
                        conflictPkg = null; true
                    } else if (showRecs) {
                        showRecs = false; true
                    } else false
                },
                onY = { if (folders.open) folders.y() else jumpToAttention(1) },
                onStart = {
                    if (folders.open) true
                    else if (guidesAvailable) {
                        showGuides = !showGuides; showRecs = false; true
                    } else false
                },
                onSelect = {
                    if (folders.open) true
                    else if (hasRecs) {
                        showRecs = !showRecs; showGuides = false; true
                    } else false
                },
                onX = {
                    if (!folders.open) scope.launch { rescan(forceRom = true) }
                    true
                },
                onLB = { if (folders.open) true else jumpToAttention(-1) },
                onRB = { if (folders.open) true else jumpToAttention(1) },
            ),
    ) {
        // Header stack (eyebrow → headline → status → actions): flows, so
        // larger text sizes push down instead of colliding.
        Column(modifier = Modifier.offset(x = g.x(48), y = g.x(30)).width(g.x(1300))) {
            Text(
                "SYSTEM",
                color = ConsoleColors.TextTertiary, fontSize = g.tMin(15),
                lineHeight = g.tMin(18), fontWeight = FontWeight.Bold,
            )
            Text(
                when {
                    // Attention counts need game counts: never claim
                    // "ready" before they load.
                    !prepLoaded || !coresProbed -> "CHECKING SYSTEMS…"
                    attention.isEmpty() -> "ALL SYSTEMS READY"
                    attention.size == 1 -> "1 THING NEEDS ATTENTION"
                    else -> "${attention.size} THINGS NEED ATTENTION"
                },
                color = ConsoleColors.TextPrimary, fontSize = g.t(36),
                lineHeight = g.t(40), fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = g.x(8)),
            )
            Text(
                if (!coresProbed) "Checking cores…"
                else systemsSubtitle(
                    subtitleCounts.first, subtitleCounts.second,
                    ControllerProbe.statusLine(controllers),
                    limitedWithGames = subtitleCounts.third,
                ),
                color = ConsoleColors.TextSecondary, fontSize = g.t(17),
                lineHeight = g.t(20), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = g.x(4)),
            )
            Row(
                modifier = Modifier.padding(top = g.x(14)),
                horizontalArrangement = Arrangement.spacedBy(g.x(16)),
            ) {
                if (hasIssues) {
                    PrimaryConsoleAction(
                        label = "NEXT ISSUE",
                        focused = zone == 0 && zone0Btn == 0,
                        onClick = { jumpToAttention(1) },
                        controllerGlyph = ControllerGlyph.Y,
                        modifier = Modifier.widthIn(min = g.x(210)),
                    )
                }
                PrimaryConsoleAction(
                    label = "DEVICE",
                    focused = zone == 0 && zone0Btn == 1,
                    onClick = { zone = 0; zone0Btn = 1; showDevice = true },
                    controllerGlyph = null,
                    modifier = Modifier.widthIn(min = g.x(170)),
                )
                // Missing SD access hides every SD game: keep the fix one
                // press away until granted.
                if (grantButton) {
                    PrimaryConsoleAction(
                        label = stringResource(R.string.storage_files_grant),
                        focused = zone == 0 && zone0Btn == 2,
                        onClick = { context.openAllFilesSettings() },
                        controllerGlyph = null,
                        modifier = Modifier.widthIn(min = g.x(210)),
                    )
                }
            }
        }
        if (showControls) ControllerSettingsDialog(onDismiss = { showControls = false })
        if (showDevice) {
            DeviceDialog(
                sdPresent = sdPresent,
                grantOk = grantOk,
                onDismiss = { showDevice = false },
                onSetHome = { vm.openHomeChooser() },
                onLauncher = { vm.openSystemLauncher() },
                onSources = onSources,
                onTogether = onTogether,
                onFolders = { folders.show() },
                onControls = { showControls = true },
                onFiles = { context.openAllFilesSettings() },
            )
        }

        // Selectable scrolling list in the y258–648 box. Rows keep the 72u
        // visual with a 48dp touch floor; minimum (not fixed) height lets
        // scaled fonts expand instead of clipping.
        LazyColumn(
            state = listState,
            modifier = Modifier.offset(x = g.x(48), y = g.x(258)).width(g.x(540)).height(g.x(390)),
        ) {
            itemsIndexed(items, key = { _, item -> item.platformId }) { i, item ->
                val focused = zone == 1 && i == rows.selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .width(g.x(540))
                        .heightIn(min = g.hitMin(g.x(72)))
                        .clip(RoundedCornerShape(g.x(12)))
                        .consoleSelected(focused, "${item.displayName}, ${rowLabel(item)}")
                        .then(
                            if (focused) Modifier.border(
                                g.x(2), ConsoleColors.FocusRing, RoundedCornerShape(g.x(12)),
                            ) else Modifier,
                        )
                        .clickable {
                            zone = 1
                            if (i == rows.selected) runPrimary() else rows.select(i)
                        }
                        .padding(horizontal = g.x(16)),
                ) {
                    Box(
                        modifier = Modifier
                            .width(g.x(20)).height(g.x(20))
                            .clip(CircleShape)
                            .background(rowColor(item)),
                    )
                    Text(
                        item.displayName,
                        color = if (focused) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary,
                        fontSize = g.t(19), lineHeight = g.t(22),
                        fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = g.x(14)),
                    )
                    Text(
                        rowLabel(item),
                        color = rowColor(item),
                        fontSize = g.tMin(15), lineHeight = g.tMin(18),
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (i < items.size - 1) {
                    Box(
                        modifier = Modifier
                            .width(g.x(540)).height(g.x(1))
                            .background(ConsoleColors.Surface),
                    )
                    Box(modifier = Modifier.height(g.x(5)))
                }
            }
        }

        // Context panel for the focused row: bounded + scrollable so full
        // guide text and failure reasons stay reachable instead of clipped.
        val panelScroll = remember(rows.selected, showGuides, showRecs) { ScrollState(0) }
        items.getOrNull(rows.selected)?.let { item ->
            Column(
                modifier = Modifier.offset(x = g.x(640), y = g.x(238)).width(g.x(560))
                    .heightIn(max = g.x(400))
                    .verticalScroll(panelScroll),
            ) {
                Text(
                    item.displayName.uppercase(),
                    color = ConsoleColors.TextPrimary, fontSize = g.t(32),
                    lineHeight = g.t(36), fontWeight = FontWeight.Black,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    rowLabel(item),
                    color = rowColor(item),
                    fontSize = g.t(18), lineHeight = g.t(22),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = g.x(6)),
                )
                if (showRecs) {
                    RecommendedPanel(
                        device = device,
                        plans = plans,
                        applied = recApplied,
                        lines = recLines,
                        busy = recBusy,
                        focused = zone == 1,
                        onApply = { zone = 1; applyRecs() },
                        onBack = { showRecs = false },
                    )
                } else if (!showGuides) {
                item.detail?.let {
                    Text(
                        it, color = ConsoleColors.TextSecondary,
                        fontSize = g.t(17), lineHeight = g.t(22),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = g.x(8)),
                    )
                }
                item.gamesLine?.let {
                    Text(
                        it, color = ConsoleColors.TextSecondary,
                        fontSize = g.t(16), lineHeight = g.t(20),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = g.x(4)),
                    )
                }
                val action = item.primaryAction
                if (action != null) {
                    val pkg = (action as? SystemAction.Install)?.pkg
                    val istate = pkg?.let { installStates[it] }
                    val guided = pkg?.let { guidedLinksFor(it) } != null
                    PrimaryConsoleAction(
                        label = when {
                            istate is InstallState.Failed -> "RETRY"
                            istate is InstallState.SignerConflict -> "RESOLVE"
                            guided && item.state == SystemReadinessState.MissingRuntime -> "GET"
                            item.state == SystemReadinessState.MissingRuntime -> "INSTALL"
                            item.state == SystemReadinessState.UpdateAvailable -> "UPDATE"
                            action is SystemAction.FetchSystemFiles -> if (sysFetching) "DOWNLOADING" else "DOWNLOAD"
                            item.state == SystemReadinessState.NeedsSetup -> "FINISH SETUP"
                            else -> "OPEN"
                        },
                        focused = zone == 1,
                        onClick = { runPrimary() },
                        modifier = Modifier.width(g.x(260)).padding(top = g.x(14)),
                    )
                    // Actionable failures persist in the installer flow and
                    // render in full here; they never collapse to one line.
                    sysFetch?.takeIf { action is SystemAction.FetchSystemFiles }?.let {
                        Text(
                            it, color = ConsoleColors.Warning,
                            fontSize = g.tMin(15), lineHeight = g.tMin(18),
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = g.x(6)),
                        )
                    }
                    installLine(istate)?.let {
                        Text(
                            it,
                            color = if (istate is InstallState.Failed ||
                                istate is InstallState.SignerConflict
                            ) ConsoleColors.Error else ConsoleColors.Warning,
                            fontSize = g.tMin(15), lineHeight = g.tMin(18),
                            maxLines = 4, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = g.x(6)),
                        )
                    }
                }
                item.runtimeLabel?.let {
                    Text(
                        "Runtime", color = ConsoleColors.TextTertiary,
                        fontSize = g.tMin(15), lineHeight = g.tMin(18),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = g.x(16)),
                    )
                    Text(
                        it, color = ConsoleColors.TextSecondary,
                        fontSize = g.t(16), lineHeight = g.t(20),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (guidesAvailable) {
                    QuietConsoleAction(
                        "VIEW SETUP GUIDES",
                        focused = false,
                        onClick = { showGuides = true },
                        modifier = Modifier.padding(top = g.x(8)),
                    )
                    Text(
                        "START · SETUP GUIDES",
                        color = ConsoleColors.TextTertiary,
                        fontSize = g.tMin(14), lineHeight = g.tMin(16),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = g.x(4)),
                    )
                }
                if (hasRecs) {
                    QuietConsoleAction(
                        "RECOMMENDED FOR THIS PHONE",
                        focused = false,
                        onClick = { showRecs = true; showGuides = false },
                        modifier = Modifier.padding(top = g.x(8)),
                    )
                    Text(
                        "SELECT · ${device?.tier?.label?.uppercase() ?: "TUNING"}",
                        color = ConsoleColors.TextTertiary,
                        fontSize = g.tMin(14), lineHeight = g.tMin(16),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = g.x(4)),
                    )
                }
                } else {
                item.biosGuide?.let {
                    Text(
                        it, color = ConsoleColors.TextSecondary,
                        fontSize = g.tMin(15), lineHeight = g.tMin(20),
                        modifier = Modifier.padding(top = g.x(8)),
                    )
                }
                item.overlayGuide?.let {
                    Text(
                        "Touch overlay", color = ConsoleColors.TextTertiary,
                        fontSize = g.tMin(15), lineHeight = g.tMin(18),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = g.x(16)),
                    )
                    Text(
                        it, color = ConsoleColors.TextSecondary,
                        fontSize = g.tMin(15), lineHeight = g.tMin(20),
                    )
                }
                item.graphicsGuide?.let {
                    Text(
                        "Graphics", color = ConsoleColors.TextTertiary,
                        fontSize = g.tMin(15), lineHeight = g.tMin(18),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = g.x(16)),
                    )
                    Text(
                        it, color = ConsoleColors.TextSecondary,
                        fontSize = g.tMin(15), lineHeight = g.tMin(20),
                    )
                }
                item.controllerGuide?.let {
                    Text(
                        "Controller", color = ConsoleColors.TextTertiary,
                        fontSize = g.tMin(15), lineHeight = g.tMin(18),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = g.x(16)),
                    )
                    Text(
                        it, color = ConsoleColors.TextSecondary,
                        fontSize = g.tMin(15), lineHeight = g.tMin(20),
                    )
                }
                QuietConsoleAction(
                    "BACK TO STATUS",
                    focused = false,
                    onClick = { showGuides = false },
                    modifier = Modifier.padding(top = g.x(12)),
                )
                }
            }
        }

        GameFoldersPanel(folders)

        // Guided install flow (no direct channel): Play Store or official site.
        guidedPkg?.let { pkg ->
            val links = guidedLinksFor(pkg)
            val emuLabel = EmuCatalog.byPkg[pkg]?.emu?.label ?: "emulator"
            if (links != null) {
                AlertDialog(
                    onDismissRequest = { guidedPkg = null },
                    title = { Text("Get $emuLabel") },
                    text = {
                        Text(
                            "No direct download channel. Install from the Play Store " +
                                "listing or the official site, then come back — System " +
                                "detects it automatically.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { guidedPkg = null; vm.openGuided(pkg, true) },
                            modifier = Modifier.focusRequester(guidedFocus),
                        ) {
                            Text("Play Store")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { guidedPkg = null; vm.openGuided(pkg, false) }) {
                            Text("Official site")
                        }
                    },
                )
            }
        }

        conflictPkg?.let { pkg ->
            val entry = EmuCatalog.byPkg[pkg]
            val st = installStates[pkg] as? InstallState.SignerConflict
            AlertDialog(
                onDismissRequest = { conflictPkg = null },
                title = { Text("Installed from another source") },
                text = {
                    Text(
                        "${entry?.emu?.label ?: "This emulator"} is installed" +
                            "${st?.installedVersion?.let { " ($it)" } ?: ""} but signed by a " +
                            "different key than our reviewed build" +
                            "${entry?.pinnedVersionName?.let { " ($it)" } ?: ""}.\n\n" +
                            "Installed: ${st?.installedSigner ?: snaps[pkg]?.signerSha256?.take(16) ?: "?"}\n" +
                            "Reviewed:  ${st?.pinnedSigner?.trimEnd('…') ?: entry?.pinnedSignerSha256?.take(16) ?: "?"}…\n\n" +
                            "Pick one — we never uninstall anything for you.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { conflictPkg = null; vm.useExisting(pkg) },
                        modifier = Modifier.focusRequester(conflictFocus),
                    ) {
                        Text("Use existing install")
                    }
                },
                dismissButton = {
                    // OS-owned uninstall dialog; the user confirms there.
                    TextButton(onClick = { conflictPkg = null; vm.requestUninstall(pkg) }) {
                        Text("Uninstall…")
                    }
                },
            )
        }
    }
}

/**
 * "Recommended for this phone": tier line, then per emulator up to five
 * rows — ✓ applied/managed, ☐ set it in the emulator (or GameBEX can
 * apply), ? only if a game runs slow, • expectation — and APPLY when any
 * row is writable (A in this mode).
 */
@Composable
private fun RecommendedPanel(
    device: DeviceProfile?,
    plans: List<PerfPlan>,
    applied: Map<Emu, Set<String>?>,
    lines: List<String>,
    busy: Boolean,
    focused: Boolean,
    onApply: () -> Unit,
    onBack: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    Text(
        "Recommended for this phone", color = ConsoleColors.TextTertiary,
        fontSize = g.tMin(15), lineHeight = g.tMin(18), fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = g.x(10)),
    )
    device?.let {
        Text(
            "${it.tier.label} · ${it.summary}", color = ConsoleColors.TextSecondary,
            fontSize = g.tMin(15), lineHeight = g.tMin(19), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
    plans.forEach { plan ->
        Text(
            plan.emu.label + (plan.core?.let { " · ${CoreNames.display(it)}" } ?: ""),
            color = ConsoleColors.TextTertiary, fontSize = g.tMin(15), lineHeight = g.tMin(18),
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = g.x(12)),
        )
        plan.recs.take(5).forEach { rec ->
            val done = when (rec.kind) {
                RecKind.MANAGED -> true
                RecKind.AUTO -> applied[plan.emu]?.contains(rec.key) == true
                else -> false
            }
            val mark = when {
                done -> "✓"
                rec.kind == RecKind.TRY_IF_SLOW -> "?"
                rec.kind == RecKind.NOTE -> "•"
                else -> "☐"
            }
            val how = when {
                rec.kind == RecKind.MANAGED -> "Managed by GameBEX."
                done -> "Applied."
                rec.kind == RecKind.AUTO -> "GameBEX can apply this."
                rec.kind == RecKind.MANUAL -> "Set in ${plan.emu.label}."
                rec.kind == RecKind.TRY_IF_SLOW -> "Only if a game runs slow — in ${plan.emu.label}."
                else -> ""
            }
            Text(
                "$mark ${rec.setting}: ${rec.value}",
                color = if (done) ConsoleColors.Success else ConsoleColors.TextPrimary,
                fontSize = g.tMin(15), lineHeight = g.tMin(19), fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = g.x(6)),
            )
            Text(
                listOf(rec.why, how).filter { it.isNotEmpty() }.joinToString(" "),
                color = ConsoleColors.TextSecondary, fontSize = g.tMin(14), lineHeight = g.tMin(18),
            )
        }
    }
    if (plans.any { it.applicable }) {
        PrimaryConsoleAction(
            label = if (busy) "APPLYING…" else "APPLY RECOMMENDED",
            focused = focused,
            onClick = onApply,
            modifier = Modifier.width(g.x(300)).padding(top = g.x(14)),
        )
        Text(
            "Close the emulator first: it may save over these on exit.",
            color = ConsoleColors.TextTertiary, fontSize = g.tMin(14), lineHeight = g.tMin(18),
            modifier = Modifier.padding(top = g.x(4)),
        )
    }
    lines.forEach {
        Text(
            it, color = ConsoleColors.TextSecondary, fontSize = g.tMin(15), lineHeight = g.tMin(19),
            modifier = Modifier.padding(top = g.x(6)),
        )
    }
    QuietConsoleAction(
        "BACK TO STATUS",
        focused = false,
        onClick = onBack,
        modifier = Modifier.padding(top = g.x(12)),
    )
}

private fun stateColor(state: SystemReadinessState): Color = when (state) {
    SystemReadinessState.Ready -> ConsoleColors.Success // green = ready; pink is focus only
    SystemReadinessState.NeedsSetup, SystemReadinessState.UpdateAvailable -> ConsoleColors.Warning
    SystemReadinessState.MissingRuntime -> ConsoleColors.Error
    SystemReadinessState.LimitedSupport -> ConsoleColors.TextTertiary
}

private fun stateLabel(state: SystemReadinessState): String = when (state) {
    SystemReadinessState.Ready -> "Ready"
    SystemReadinessState.NeedsSetup -> "Needs setup"
    SystemReadinessState.MissingRuntime -> "Not installed"
    SystemReadinessState.UpdateAvailable -> "Update available"
    SystemReadinessState.LimitedSupport -> "Limited"
}

private fun installLine(state: InstallState?): String? = when (state) {
    null, InstallState.Idle -> null
    is InstallState.Downloading -> {
        val pct = state.total?.takeIf { it > 0 }?.let { (state.read * 100 / it).toInt() }
        if (pct != null) "Downloading… $pct%" else "Downloading…"
    }
    InstallState.Validating -> "Validating build…"
    InstallState.NeedsUnknownSources -> "Allow unknown sources, then press A."
    InstallState.AwaitingApproval -> "Confirm in the system installer…"
    InstallState.Installing -> "Installing…"
    is InstallState.Done -> "Installed ${state.versionName ?: ""}".trim()
    is InstallState.Failed -> state.reason
    is InstallState.SignerConflict -> "Signer conflict — kept installed copy. Press A to resolve."
}

/** Device menu: the single header entry for device-level settings. */
@Composable
private fun DeviceDialog(
    sdPresent: Boolean,
    grantOk: Boolean,
    onDismiss: () -> Unit,
    onSetHome: () -> Unit,
    onLauncher: () -> Unit,
    onSources: () -> Unit,
    onTogether: () -> Unit,
    onFolders: () -> Unit,
    onControls: () -> Unit,
    onFiles: () -> Unit,
) {
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    fun go(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    com.gamebex.client.ui.console.ConsoleDialog(
        title = "Device",
        onDismiss = onDismiss,
        initialFocus = first,
        body = {
            val options = buildList {
                add("Set as home screen" to onSetHome)
                add("System launcher" to onLauncher)
                add("Game sources" to onSources)
                add("Game folders" to onFolders)
                add("Controls & text size" to onControls)
                add(TogetherCopy.deviceMenuOption to onTogether)
                if (sdPresent) add((if (grantOk) "Storage: granted" else "Storage: grant") to onFiles)
            }
            // Two columns keep all options on screen at large text sizes.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.chunked(2).forEachIndexed { r, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEachIndexed { c, (label, action) ->
                            com.gamebex.client.ui.console.ConsoleDialogButton(
                                label, go(action),
                                modifier = Modifier.weight(1f)
                                    .then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        buttons = { com.gamebex.client.ui.console.ConsoleDialogButton("Close", onDismiss) },
    )
}

/**
 * System's hint strip: the Game folders panel's own actions while it's open, else
 * System's. Every action carries a glyph, so its pill can be tapped. Pure.
 */
internal fun systemHints(
    foldersOpen: Boolean,
    picking: Boolean,
    inFolder: Boolean,
    hasIssues: Boolean,
    hasRecs: Boolean,
): List<ControllerHint> =
    if (foldersOpen) {
        foldersHints(picking, inFolder)
    } else {
        // At most four. LB/RB (previous/next issue) stay unhinted: Y and the
        // NEXT ISSUE button cover next, and a tap on a row reaches any system.
        listOfNotNull(
            ControllerHint(ControllerGlyph.A, "Select"),
            ControllerHint(ControllerGlyph.X, "Rescan"),
            if (hasIssues) ControllerHint(ControllerGlyph.Y, "Next issue") else null,
            if (hasRecs) ControllerHint(ControllerGlyph.Back, "Tuning") else null,
        )
    }
