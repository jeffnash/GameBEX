package com.gamebex.client.ui.discover

import com.gamebex.client.ui.console.GameCoverStatus
import com.gamebex.client.ui.console.ConsoleDesign
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.gamebex.client.R
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.RomEntry
import com.gamebex.client.data.toStableId
import com.gamebex.client.ui.BrowseViewModel
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleFocusMemory
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.GameCover
import com.gamebex.client.ui.console.GameEnvRegion
import com.gamebex.client.ui.console.GameEnvironment
import com.gamebex.client.ui.console.GameEnvironmentModel
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.PrimaryConsoleAction
import com.gamebex.client.ui.console.QuietConsoleAction
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.nearestIndexByX
import com.gamebex.client.ui.console.rememberArtColor
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.rememberShelfState
import com.gamebex.client.ui.console.screenFocus
import com.gamebex.client.ui.console.AnchoredShelf
import com.gamebex.client.ui.console.rememberShelfScroll
import com.gamebex.client.ui.console.shelfCenterDesignPx
import com.gamebex.client.ui.presentation.CatalogMatch
import com.gamebex.client.ui.presentation.GameAvailability
import com.gamebex.client.ui.presentation.GamePresentation
import com.gamebex.client.ui.presentation.GamePrimaryAction
import com.gamebex.client.ui.presentation.folderForCatalogEntry
import com.gamebex.client.ui.presentation.label
import com.gamebex.client.ui.presentation.matchCatalog
import com.gamebex.client.ui.tintFor
import com.gamebex.client.GameBexApp
import com.gamebex.client.sources.SourceImage
import com.gamebex.client.sources.coverImage
import kotlinx.coroutines.launch

/** Left-key target on Discover: shelf move, header hop, or rail exit. */
enum class DiscoverLeft { SHELF_LEFT, TO_HEADER, RAIL }

/** Rail stays reachable in every content state (loading/error/empty). */
fun resolveDiscoverLeft(zone: Int, filteredEmpty: Boolean, selected: Int): DiscoverLeft =
    when {
        zone == 1 && !filteredEmpty && selected > 0 -> DiscoverLeft.SHELF_LEFT
        zone == 1 && !filteredEmpty -> DiscoverLeft.TO_HEADER
        else -> DiscoverLeft.RAIL
    }

/** Process-death restore branch: latest feed, saved console, or nothing. */
enum class DiscoverRestore { LOAD_LATEST, SELECT_CONSOLE, NONE }

fun resolveDiscoverRestore(hasItems: Boolean, selectedConsole: String?): DiscoverRestore =
    when {
        hasItems -> DiscoverRestore.NONE
        selectedConsole == null -> DiscoverRestore.LOAD_LATEST
        else -> DiscoverRestore.SELECT_CONSOLE
    }

/** Tombstone copy, private to Discover; HomeCopy owns the canonical strings. */
private const val DOWNLOAD_AGAIN = "Download again"
private const val NOT_ON_PHONE = "Not on this phone"

/**
 * Featured game plus one catalog shelf. Categories are the console feeds of
 * [BrowseViewModel]: the system chips pick one, LB/RB cycle them. Owned Only
 * browses the local collection; the catalog shelf paginates the feed.
 * Coordinates are design px local to the content box (absolute − rail).
 */
@Composable
fun DiscoverScreen(
    vm: BrowseViewModel,
    focusMemory: ConsoleFocusMemory,
    rail: RailHost,
    hints: ConsoleHints,
    onOpen: (RomEntry) -> Unit,
    onOpenLocal: (Long) -> Unit,
    onSearch: () -> Unit,
    onManageSources: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val items by vm.items.collectAsState()
    val consoles by vm.consoles.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val sel by vm.selectedConsole.collectAsState()
    val games by vm.games.collectAsState()
    val message by vm.message.collectAsState()
    val pageError by vm.pageError.collectAsState()
    val sourceName by vm.sourceName.collectAsState()
    val selection by vm.selection.collectAsState()

    // Initial + selection-change loads are driven by the ViewModel's
    // selection collector (it also restores the saved console per source).

    var ownedOnly by remember { mutableStateOf(focusMemory.recall("discover:owned", 0) == 1) }
    var zone by remember { mutableIntStateOf(focusMemory.recall("discover:zone", 0)) }
    fun setZone(i: Int) {
        // 0 featured, 1 shelf, 2 header controls (source selector),
        // 3 system chips. Up walks shelf → featured → chips → source.
        zone = i.coerceIn(0, 3)
        focusMemory.store("discover:zone", zone)
    }

    // Per-tab memory: restore the browsed console once feeds arrive. The
    // restore flag is keyed on the source selection so categories can
    // never leak across sources.
    var catRestored by remember(selection) { mutableStateOf(false) }
    LaunchedEffect(consoles, selection) {
        if (!catRestored && consoles.isNotEmpty()) {
            catRestored = true
            val want = focusMemory.recallKey("discover:cat")
            if (want != null && consoles.any { it.id == want } && vm.selectedConsole.value != want) {
                vm.selectConsole(want)
            }
        }
    }
    LaunchedEffect(sel, selection) {
        val s = sel
        if (s == null) focusMemory.clearKey("discover:cat") else focusMemory.storeKey("discover:cat", s)
    }

    val browseFolder = sel?.let { id ->
        consoles.firstOrNull { it.id == id }?.platformId
            ?.takeIf { PlatformRegistry.byFolder.containsKey(it) }
    }
    val matches = remember(items, games, browseFolder) { matchCatalog(items, games, browseFolder) }
    val entriesByStable = remember(items) { items.associateBy { it.key.toStableId() } }
    val app = LocalContext.current.applicationContext as GameBexApp
    val installed by app.sources.list().collectAsState()
    fun revFor(sourceId: String): String? = installed.firstOrNull { it.id == sourceId }?.revision
    val scope = rememberCoroutineScope()
    // Source selector: enabled sources + Manage; select commits the
    // registry selection and Browse/Search react through their flows.
    val enabledSources = remember(installed) { installed.filter { it.enabled && it.available } }
    val hasDisabledOrBad = remember(installed) { installed.any { !it.enabled || !it.available } }
    val currentSourceName = selection?.id?.let { id -> installed.firstOrNull { it.id == id }?.name } ?: sourceName
    var selectorOpen by remember { mutableStateOf(false) }
    val dialogShelf = rememberShelfState(enabledSources.size + 1)
    // Header controls: 0 selector.
    val headerShelf = rememberShelfState(1)
    val noSource = enabledSources.isEmpty()
    // System chips: Latest + every console feed. Chip focus moves with
    // Left/Right; any category change (A, tap, LB/RB) snaps it to the
    // selected chip.
    val chips = remember(consoles) { discoverSystemChips(consoles) }
    val selectedChip = systemChipIndex(chips, sel)
    var chipFocus by remember { mutableIntStateOf(selectedChip) }
    LaunchedEffect(selectedChip) { chipFocus = selectedChip }
    var noticeChoice by remember(hasDisabledOrBad) { mutableIntStateOf(0) }
    fun selectSource(id: String) {
        scope.launch { runCatching { app.sources.select(id) } }
        selectorOpen = false
        setZone(0)
    }
    val catalog: List<GamePresentation> = remember(items, matches, installed) {
        items.map { e ->
            val local = (matches[e.key] as? CatalogMatch.Owned)?.game
            val image = e.coverImage(revFor(e.ref.sourceId))
            GamePresentation(
                stableId = e.key.toStableId(), title = e.title,
                platformLabel = e.consoleName ?: "GameBEX",
                cover = image, hero = image,
                availability = if (local != null) GameAvailability.Local else GameAvailability.Available,
                primaryAction = if (local != null) GamePrimaryAction.Play else GamePrimaryAction.Download,
                folder = folderForCatalogEntry(e),
            )
        }
    }
    // Owned Only reads the local collection, not fetched catalog pages, so
    // the shelf (and its empty state) is never a pagination artifact.
    // Only playable rows count as owned: tombstones and rows whose
    // file is currently absent stay out.
    val ownedGames = remember(games, browseFolder, sel) {
        val list = games.filter { it.available }
            .let { if (sel == null) it else it.filter { g -> g.folder == browseFolder } }
        list.sortedBy { it.title.lowercase() }
    }
    val ownedCatalog: List<GamePresentation> = remember(ownedGames, installed) {
        ownedGames.map { gl ->
            // Remote hero only when the owning source is known (scoped
            // load); otherwise the color wash renders, never a naked URL.
            val remoteHero = gl.coverUrl
                ?.takeIf { gl.artHero == null && gl.sourceId != null }
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?.let { SourceImage(gl.sourceId!!, revFor(gl.sourceId!!) ?: "", it) }
            GamePresentation(
                stableId = "local:${gl.id}", title = gl.title,
                platformLabel = PlatformRegistry.byFolder[gl.folder]?.display ?: gl.folder,
                cover = gl.artBox ?: gl.coverPath, hero = gl.artHero ?: remoteHero,
                availability = GameAvailability.Local,
                primaryAction = if (gl.playCount > 0) GamePrimaryAction.Continue else GamePrimaryAction.Play,
                folder = gl.folder,
            )
        }
    }
    val localByStable = remember(ownedGames) { ownedGames.associateBy { "local:${it.id}" } }
    val filtered = if (!ownedOnly) catalog else ownedCatalog
    val shelfState = rememberShelfState(filtered.size, focusMemory.recall("discover:shelf", 0))
    // Featured tracks focus: first item in the header zone, focused tile in
    // the shelf zone — the header/CTA/hero never go stale mid-traversal.
    val featured = if (zone == 0) filtered.firstOrNull()
        else filtered.getOrNull(shelfState.selected) ?: filtered.firstOrNull()

    val shelfScroll = rememberShelfScroll()
    val shelfList = androidx.compose.foundation.lazy.rememberLazyListState()
    if (filtered.isNotEmpty()) focusMemory.store("discover:shelf", shelfState.selected)

    // New category/filter → back to featured (the chip row keeps focus so
    // systems can be tried in a row); tab return restores memory.
    var prevCat by remember { mutableStateOf<Pair<String?, Boolean>?>(null) }
    LaunchedEffect(sel, ownedOnly) {
        val cur = sel to ownedOnly
        if (prevCat != null && prevCat != cur) {
            shelfState.select(0)
            if (zone != 3) setZone(0)
        }
        prevCat = cur
    }
    // Touch scrolling reaches the end without moving the selection: load the next page then too.
    LaunchedEffect(shelfList, filtered.size, ownedOnly) {
        androidx.compose.runtime.snapshotFlow { shelfList.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last -> if (discoverNeedsMore(last, filtered.size, ownedOnly)) vm.loadMore() }
    }
    LaunchedEffect(shelfState.selected, filtered.size, ownedOnly) {
        if (!ownedOnly && filtered.isNotEmpty() && shelfState.selected == filtered.size - 1) vm.loadMore()
    }

    val density = LocalDensity.current.density * g.scale
    fun shelfCenters(): List<Float> = filtered.indices.map { i ->
        shelfCenterDesignPx(i, 118, 28, 57, shelfScroll.px, density) + ConsoleDesign.RAIL_WIDTH
    }

    fun entryOf(p: GamePresentation): RomEntry? = entriesByStable[p.stableId]

    /**
     * Tombstone tile: the entry's only library rows are deleted, so the CTA
     * is "Download again" and Play never runs on the missing file.
     */
    fun downloadAgain(p: GamePresentation): Boolean =
        !ownedOnly && entryOf(p)?.let { matches[it.key] is CatalogMatch.DownloadAgain } == true

    fun focusedPres(): GamePresentation? =
        if (zone == 0) featured else filtered.getOrNull(shelfState.selected)

    // Owned tiles open local detail (full actions); catalog tiles open the
    // unified detail on its catalog face. A tombstone match opens the
    // catalog detail: its local row is deleted and can't play.
    fun openPresentation(p: GamePresentation): Boolean {
        if (ownedOnly) {
            val local = localByStable[p.stableId] ?: return false
            onOpenLocal(local.id)
            return true
        }
        val e = entryOf(p) ?: return false
        val local = (matches[e.key] as? CatalogMatch.Owned)?.game
        if (local != null) onOpenLocal(local.id) else onOpen(e)
        return true
    }

    fun openFocused(): Boolean {
        val p = focusedPres() ?: return false
        return openPresentation(p)
    }

    fun getOrPlay(): Boolean {
        val p = focusedPres() ?: return false
        if (ownedOnly) {
            val local = localByStable[p.stableId] ?: return false
            vm.play(local)
            return true
        }
        val e = entryOf(p) ?: return false
        val local = (matches[e.key] as? CatalogMatch.Owned)?.game
        // Catalog tile: Start queues the download Detail's Download would;
        // a version choice or unmapped system opens Detail instead. A
        // tombstone match downloads again and never plays the missing file.
        if (local != null) vm.play(local) else vm.quickDownload(e) { onOpen(e) }
        return true
    }

    fun cycleCategory(dir: Int): Boolean {
        if (consoles.isEmpty()) return false
        val order = listOf<String?>(null) + consoles.map { it.id }
        val i = order.indexOf(sel).takeIf { it >= 0 } ?: 0
        vm.selectConsole(order[(i + dir + order.size) % order.size])
        return true
    }

    /** Loads chip [i]'s feed as LB/RB would; the selected chip retries a failed load. */
    fun pickChip(i: Int): Boolean {
        val chip = chips.getOrNull(i) ?: return false
        chipFocus = i
        if (chip.id != sel || error != null) vm.selectConsole(chip.id)
        return true
    }

    fun toggleOwned(): Boolean {
        ownedOnly = !ownedOnly
        focusMemory.store("discover:owned", if (ownedOnly) 1 else 0)
        return true
    }

    // No-source notice (catalog mode only; owned mode is local).
    val showNoSource = noSource && !ownedOnly
    // The chip row needs the source's feeds; without them zone 3 falls back.
    val showChips = consoles.isNotEmpty() && !showNoSource
    LaunchedEffect(showChips) { if (!showChips && zone == 3) setZone(0) }
    fun toChips(): Boolean {
        chipFocus = selectedChip
        setZone(3)
        return true
    }

    // Hints name what each key does for the current focus target and
    // content state (the heading already prints LB / RB).
    val hintList = discoverHints(
        selectorOpen = selectorOpen,
        noSource = showNoSource,
        zone = zone,
        empty = filtered.isEmpty(),
        error = error != null,
        ownedOnly = ownedOnly,
        startLabel = focusedPres()?.let { if (downloadAgain(it)) DOWNLOAD_AGAIN else it.primaryAction.label() },
    )
    LaunchedEffect(hintList) { hints.set(hintList) }

    val screenFocus = rememberScreenFocus()
    fun activateNotice(): Boolean {
        onManageSources()
        return true
    }
    fun dialogActivate(): Boolean {
        val i = dialogShelf.selected
        return if (i < enabledSources.size) {
            selectSource(enabledSources[i].id); true
        } else {
            selectorOpen = false
            onManageSources()
            true
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .screenFocus(screenFocus)
            .consoleKeys(
                onLeft = {
                    if (selectorOpen) false
                    else if (showNoSource) {
                        noticeChoice = 0; true
                    } else if (zone == 2) {
                        rail.enterRail()
                    } else if (zone == 3) {
                        if (chipFocus > 0) {
                            chipFocus--; true
                        } else rail.enterRail()
                    } else when (resolveDiscoverLeft(zone, filtered.isEmpty(), shelfState.selected)) {
                        DiscoverLeft.SHELF_LEFT -> shelfState.left()
                        DiscoverLeft.TO_HEADER -> {
                            setZone(0); true
                        }
                        DiscoverLeft.RAIL -> {
                            if (zone != 0) setZone(0)
                            rail.enterRail()
                        }
                    }
                },
                onRight = {
                    if (selectorOpen) false
                    else if (showNoSource) {
                        if (hasDisabledOrBad) noticeChoice = 1
                        true
                    } else if (zone == 2) {
                        headerShelf.right()
                    } else if (zone == 3) {
                        if (chipFocus < chips.size - 1) {
                            chipFocus++; true
                        } else false
                    } else when {
                        filtered.isEmpty() -> false
                        zone == 0 -> {
                            setZone(1); true
                        }
                        else -> shelfState.right()
                    }
                },
                onUp = {
                    if (selectorOpen) dialogShelf.left()
                    else if (showNoSource) false
                    else if (zone == 1 && filtered.isNotEmpty()) {
                        setZone(0); true
                    } else if (zone == 0) {
                        if (showChips) toChips() else {
                            setZone(2); true
                        }
                    } else if (zone == 3) {
                        setZone(2); true
                    } else false
                },
                onDown = {
                    if (selectorOpen) dialogShelf.right()
                    else if (showNoSource) false
                    else if (zone == 2) {
                        if (showChips) toChips() else {
                            setZone(0); true
                        }
                    } else if (zone == 3) {
                        setZone(0); true
                    } else if (zone == 0 && filtered.isNotEmpty()) {
                        // Featured cover center ≈ x297; drop to nearest tile.
                        shelfState.select(nearestIndexByX(shelfCenters(), 297f))
                        setZone(1)
                        true
                    } else false
                },
                onA = {
                    if (selectorOpen) dialogActivate()
                    else if (showNoSource) activateNotice()
                    else if (zone == 2) {
                        selectorOpen = true
                        true
                    } else if (zone == 3) {
                        pickChip(chipFocus)
                    } else if (filtered.isEmpty()) {
                        if (error != null) {
                            if (sel == null) vm.load() else vm.selectConsole(sel)
                            true
                        } else false
                    } else openFocused()
                },
                onB = {
                    if (selectorOpen) {
                        selectorOpen = false; true
                    } else false
                },
                onStart = { if (filtered.isEmpty() || selectorOpen) false else getOrPlay() },
                onX = { if (selectorOpen) false else { onSearch(); true } },
                onY = { if (selectorOpen) false else toggleOwned() },
                onLB = { if (selectorOpen) false else cycleCategory(-1) },
                onRB = { if (selectorOpen) false else cycleCategory(1) },
            ),
    ) {
        val focusedPres = if (zone == 0) featured else filtered.getOrNull(shelfState.selected)
        val heroImage = focusedPres?.hero as? SourceImage
        val tint = focusedPres?.folder?.let { tintFor(it) } ?: ConsoleColors.AccentDim
        GameEnvironment(
            model = GameEnvironmentModel(null, rememberArtColor(null, tint), heroImage = heroImage),
            region = GameEnvRegion(630, 20, 890, 340),
        )

        Text(
            "DISCOVER",
            color = ConsoleColors.TextTertiary, fontSize = g.t(15),
            lineHeight = g.t(18), fontWeight = FontWeight.Bold,
            modifier = Modifier.offset(x = g.x(48), y = g.x(34)),
        )
        if (showChips) {
            SystemChipRow(
                chips = chips,
                selected = selectedChip,
                focused = chipFocus.takeIf { zone == 3 },
                onPick = { i ->
                    setZone(3)
                    pickChip(i)
                },
                modifier = Modifier.offset(x = g.x(48), y = g.x(60)).width(g.x(1000)),
            )
        }
        featured?.let { f ->
            val ftint = f.folder?.let { tintFor(it) } ?: ConsoleColors.AccentDim
            GameCover(
                title = f.title, cover = f.cover, focused = zone == 0, tint = ftint,
                systemFolder = f.folder,
                ring = false, // the GET/PLAY CTA beside it already rings zone focus
                modifier = Modifier.offset(x = g.x(134), y = g.x(106)).width(g.x(166)).height(g.x(236)),
            )
            Column(
                modifier = Modifier.offset(x = g.x(342), y = g.x(126)).width(g.x(380)),
            ) {
                var titleSize by remember(f.title) { mutableIntStateOf(44) }
                Text(
                    f.title.uppercase(),
                    color = ConsoleColors.TextPrimary, fontSize = g.t(titleSize),
                    fontWeight = FontWeight.Black, lineHeight = g.t(titleSize + 2),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (it.hasVisualOverflow && titleSize > 26) titleSize -= 2 },
                    modifier = Modifier.height(g.x(92)),
                )
                Text(
                    f.platformLabel.uppercase(),
                    color = ConsoleColors.TextSecondary, fontSize = g.t(17),
                    lineHeight = g.t(20), fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = g.x(4)),
                )
                Text(
                    when {
                        f.availability == GameAvailability.Local -> "On this device"
                        downloadAgain(f) -> NOT_ON_PHONE
                        else -> "Available to download"
                    },
                    color = ConsoleColors.TextSecondary, fontSize = g.t(16),
                    lineHeight = g.t(18),
                    modifier = Modifier.padding(top = g.x(2)),
                )
                PrimaryConsoleAction(
                    label = if (downloadAgain(f)) DOWNLOAD_AGAIN else f.primaryAction.label(),
                    focused = zone == 0,
                    onClick = { getOrPlay() },
                    controllerGlyph = ControllerGlyph.Start,
                    modifier = Modifier.width(g.x(200)),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.offset(x = g.x(48), y = g.x(362)).width(g.x(1000)),
        ) {
            Text(
                categoryHeading(sel, consoles),
                color = ConsoleColors.TextPrimary, fontSize = g.t(24),
                lineHeight = g.t(28), fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ShelfSwitch(
                onPrev = { cycleCategory(-1) },
                onNext = { cycleCategory(1) },
                modifier = Modifier.padding(start = g.x(8)),
            )
            if (ownedOnly) {
                Text(
                    "OWNED ONLY",
                    color = ConsoleColors.Accent, fontSize = g.t(15),
                    lineHeight = g.t(18), fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = g.x(12)),
                )
            }
            // Source identity stays explicit even with a single source.
            val selFocused = zone == 2 && headerShelf.selected == 0
            Text(
                "SOURCE: ${(currentSourceName ?: "none").uppercase()}",
                color = if (selFocused) ConsoleColors.TextPrimary else ConsoleColors.TextTertiary,
                fontSize = g.t(15), lineHeight = g.t(18), fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = g.x(16))
                    .clickable { selectorOpen = true },
            )
        }

        if (showNoSource) {
            // No enabled source: scaffold stays, grid goes away.
            Column(
                modifier = Modifier.offset(x = g.x(48), y = g.x(402)).width(g.x(900)),
            ) {
                Text(
                    "Import a ROM source to discover games.",
                    color = ConsoleColors.TextPrimary, fontSize = g.t(24),
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(g.x(16)),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = g.x(16)),
                ) {
                    PrimaryConsoleAction(
                        "Import source", noticeChoice == 0,
                        onClick = { activateNotice() },
                    )
                    if (hasDisabledOrBad) {
                        QuietConsoleAction(
                            "Sources", noticeChoice == 1,
                            onClick = { activateNotice() },
                        )
                    }
                }
            }
        } else if (filtered.isEmpty()) {
            Text(
                when {
                    loading -> "Browsing ${sourceName ?: "catalog"}…"
                    error != null -> "$error — press A to retry."
                    ownedOnly -> if (sel == null) "No owned games yet."
                    else "No owned games in this category."
                    else -> "Nothing here yet."
                },
                color = ConsoleColors.TextTertiary, fontSize = g.t(17),
                lineHeight = g.t(20),
                modifier = Modifier.offset(x = g.x(48), y = g.x(402)),
            )
        } else {
            AnchoredShelf(
                items = filtered,
                selected = shelfState.selected,
                onSelect = { shelfState.select(it); setZone(1) },
                onActivate = { openPresentation(filtered[it]) },
                itemWidth = g.x(118),
                itemHeight = g.x(168),
                gap = g.x(28),
                padStart = g.x(57),
                scroll = shelfScroll,
                listState = shelfList,
                modifier = Modifier.offset(y = g.x(402)).height(g.x(210)),
                renderItem = { item, focused ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GameCover(
                            title = item.title, cover = item.cover,
                            status = if (item.availability == GameAvailability.Local) GameCoverStatus.Owned else null,
                            focused = focused && zone == 1,
                            tint = item.folder?.let { tintFor(it) } ?: ConsoleColors.AccentDim,
                            systemFolder = item.folder,
                            modifier = Modifier.width(g.x(118)).height(g.x(168)),
                        )
                        Text(
                            item.title.uppercase(),
                            color = if (focused && zone == 1) ConsoleColors.TextPrimary
                            else ConsoleColors.TextSecondary,
                            fontSize = g.t(13), lineHeight = g.t(15),
                            fontWeight = FontWeight.Bold,
                            // Ownership shows as the cover's badge, so the title gets two lines.
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(g.x(118)).padding(top = g.x(5)),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                },
            )
        }

        // Pagination failure is non-blocking: items stay, the banner
        // persists until reaching the shelf end retries the page.
        val pageErr = pageError
        if (pageErr != null && filtered.isNotEmpty()) {
            Text(
                stringResource(R.string.catalog_page_scroll_retry, pageErr),
                color = ConsoleColors.Warning, fontSize = g.t(16),
                lineHeight = g.t(18),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = g.x(80)),
            )
        }
        LaunchedEffect(message) {
            if (message != null) {
                kotlinx.coroutines.delay(5000)
                vm.clearMessage()
            }
        }
        message?.let { msg ->
            Text(
                msg,
                color = ConsoleColors.Warning, fontSize = g.t(16),
                lineHeight = g.t(18),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = g.x(52)),
            )
        }
        if (selectorOpen) {
            SourceSelectorDialog(
                enabled = enabledSources,
                selectedId = selection?.id,
                dialogShelf = dialogShelf.selected,
                onPick = { i ->
                    dialogShelf.select(i)
                    dialogActivate()
                },
                onClose = { selectorOpen = false },
            )
        }
    }
}

@Composable
private fun SourceSelectorDialog(
    enabled: List<com.gamebex.client.sources.InstalledSource>,
    selectedId: String?,
    dialogShelf: Int,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize()
            .background(ConsoleColors.Background.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(g.x(560))
                .clip(RoundedCornerShape(g.x(16)))
                .background(ConsoleColors.Elevated)
                .border(g.x(1.5f), ConsoleColors.FocusRing, RoundedCornerShape(g.x(16)))
                .padding(horizontal = g.x(28), vertical = g.x(24)),
            verticalArrangement = Arrangement.spacedBy(g.x(8)),
        ) {
            Text(
                "Browse source", color = ConsoleColors.TextPrimary,
                fontSize = g.t(24), fontWeight = FontWeight.Bold,
            )
            enabled.forEachIndexed { i, s ->
                val focused = i == dialogShelf
                Text(
                    (if (s.id == selectedId) "● " else "○ ") + s.name.ifBlank { s.id },
                    color = if (focused) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary,
                    fontSize = g.t(19), fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(g.x(8)))
                        .background(if (focused) ConsoleColors.HighSurface else ConsoleColors.Elevated)
                        .clickable { onPick(i) }
                        .padding(horizontal = g.x(12), vertical = g.x(8)),
                )
            }
            val manageFocused = enabled.size == dialogShelf
            QuietConsoleAction(
                "Manage sources…", manageFocused,
                onClick = { onPick(enabled.size) },
            )
            QuietConsoleAction("Close", false, onClick = onClose)
        }
    }
}

/**
 * Discover hint row (≤4), state-aware: A names what A does (Open, Retry,
 * Import source, Change source, Select), never "Open" over an error.
 * Zone 3 is the system chip row (A selects the focused system).
 * [startLabel] is the focused game's primary action (Start runs it).
 */
internal fun discoverHints(
    selectorOpen: Boolean,
    noSource: Boolean,
    zone: Int,
    empty: Boolean,
    error: Boolean,
    ownedOnly: Boolean,
    startLabel: String?,
): List<ControllerHint> {
    if (selectorOpen) {
        return listOf(
            ControllerHint(ControllerGlyph.A, "Select"),
            ControllerHint(ControllerGlyph.B, "Close"),
        )
    }
    val search = ControllerHint(ControllerGlyph.X, "Search")
    val owned = ControllerHint(ControllerGlyph.Y, if (ownedOnly) "All Games" else "Owned Only")
    val a = when {
        noSource -> "Import source"
        zone == 2 -> "Change source"
        zone == 3 -> "Select"
        empty -> if (error) "Retry" else null
        else -> "Open"
    }
    return listOfNotNull(
        a?.let { ControllerHint(ControllerGlyph.A, it) },
        search,
        owned,
        startLabel?.takeIf { !noSource && !empty }?.let { ControllerHint(ControllerGlyph.Start, it) },
    )
}

private fun categoryHeading(sel: String?, consoles: List<com.gamebex.client.data.ConsoleItem>): String =
    if (sel == null) "LATEST ADDITIONS"
    else consoles.firstOrNull { it.id == sel }?.name?.uppercase() ?: "BROWSE"

/** True when the shelf's last visible tile is within one of the end of a catalog feed. */
internal fun discoverNeedsMore(lastVisible: Int?, count: Int, ownedOnly: Boolean): Boolean =
    !ownedOnly && count > 0 && lastVisible != null && lastVisible >= count - 2

/** "LB / RB" as two tap targets, so the shelf's category switches by touch too. */
@Composable
internal fun ShelfSwitch(onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    @Composable
    fun label(text: String, action: String?, onClick: (() -> Unit)?) = Text(
        text,
        color = ConsoleColors.TextTertiary, fontSize = g.t(15),
        lineHeight = g.t(18), fontWeight = FontWeight.Bold,
        modifier = if (onClick == null) Modifier else Modifier
            .clickable(onClickLabel = action, onClick = onClick)
            .padding(horizontal = g.x(8), vertical = g.x(12)),
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        label("LB", "Previous category", onPrev)
        label("/", null, null)
        label("RB", "Next category", onNext)
    }
}
