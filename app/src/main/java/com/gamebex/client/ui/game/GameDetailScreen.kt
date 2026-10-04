package com.gamebex.client.ui.game

import com.gamebex.client.ui.console.ConsoleDesign
import androidx.compose.ui.graphics.Brush
import com.gamebex.client.ui.console.ConsoleDialog
import com.gamebex.client.ui.console.ConsoleDialogButton
import com.gamebex.client.ui.console.DialogTone
import com.gamebex.client.ui.presentation.emulatorLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamebex.client.data.BiosCheck
import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.PlayStates
import com.gamebex.client.data.Route
import com.gamebex.client.data.DownloadFile
import com.gamebex.client.data.local.ArtStore
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.ui.AssistActivity
import com.gamebex.client.ui.LocalDetailViewModel
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherSupport
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.GameCover
import com.gamebex.client.ui.console.GameEnvRegion
import com.gamebex.client.ui.console.GameEnvironment
import com.gamebex.client.ui.console.GameEnvironmentModel
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.PrimaryConsoleAction
import com.gamebex.client.ui.console.SecondaryConsoleAction
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.rememberArtColor
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.screenFocus
import com.gamebex.client.ui.downloadStateLabel
import com.gamebex.client.ui.formatSize
import com.gamebex.client.ui.presentation.relativeDay
import com.gamebex.client.ui.presentation.GamePrimaryAction
import com.gamebex.client.ui.presentation.LocalGamePresenter
import com.gamebex.client.ui.presentation.LocalLauncher
import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.ui.UserErrors
import com.gamebex.client.ui.presentation.LocalPresentation
import com.gamebex.client.ui.presentation.label
import com.gamebex.client.ui.tintFor
import com.gamebex.client.GameBexApp
import com.gamebex.client.sources.SourceImage
import com.gamebex.client.sources.coverImage

/** True for http(s) art refs (catalog covers); local art is a file path. */
fun isRemoteArtRef(s: String?): Boolean =
    s != null && (s.startsWith("http://") || s.startsWith("https://"))

/**
 * Splits hero candidates into (localFile, remoteUrl). The first local file wins;
 * otherwise the first remote ref, so catalog heroes still render.
 */
fun resolveHeroArt(
    candidates: List<String?>,
    isLocalFile: (String) -> Boolean,
): Pair<String?, String?> {
    var remote: String? = null
    for (c in candidates) {
        if (c.isNullOrBlank()) continue
        if (isLocalFile(c)) return c to null
        if (remote == null && isRemoteArtRef(c)) remote = c
    }
    return null to remote
}

/**
 * Game detail page. All behavior delegates to [LocalDetailViewModel].
 * Coordinates are design px local to the content box (absolute − rail).
 */
@Composable
fun GameDetailScreen(
    vm: LocalDetailViewModel,
    rail: RailHost,
    hints: ConsoleHints,
    onBack: () -> Unit,
    onInstallEmu: () -> Unit,
    onManageSources: () -> Unit,
    onFreeSpace: () -> Unit = {},
    onPlayTogether: (Long) -> Unit = {},
    onOpenRetroArch: (() -> Unit)? = null,
) {
    val g = LocalConsoleGeometry.current
    val ctx = LocalContext.current
    val game by vm.game.collectAsState()
    val message by vm.message.collectAsState()
    val missingEmu by vm.missingEmu.collectAsState()
    val bios by vm.bios.collectAsState()
    val preparing by vm.preparing.collectAsState()
    val prepBusy = preparing
    val artStatus by vm.artStatus.collectAsState()
    val catalog by vm.catalog.collectAsState()
    val catalogDl by vm.catalogDl.collectAsState()
    val catalogDesc by vm.catalogDesc.collectAsState()
    val catalogFiles by vm.catalogFiles.collectAsState()
    val selectedFile by vm.selectedFile.collectAsState()
    val needsChoice by vm.needsChoice.collectAsState()
    val versions by vm.versions.collectAsState()
    val confirmReplace by vm.confirmReplace.collectAsState()
    LaunchedEffect(game?.id) { vm.loadVersions() }
    val assist by vm.assist.collectAsState()
    val coreFetch by vm.coreFetch.collectAsState()
    val launching by vm.launching.collectAsState()
    val needsRetroArchSetup by vm.needsRetroArchSetup.collectAsState()
    val coreRetryAvailable by vm.coreRetryAvailable.collectAsState()
    val ssCreds by vm.ssCreds.collectAsState()

    // Keyed per game so recomposition never resets focus. Focus starts on the
    // primary action with the Overview tab.
    var tab by remember(game?.id, catalog?.key) { mutableIntStateOf(0) }
    var zone by remember(game?.id, catalog?.key) { mutableIntStateOf(0) } // 0 CTA, 1 tabs, 2 adv rows
    var ctaBtn by remember(game?.id, catalog?.key) { mutableIntStateOf(0) } // zone 0: 0 primary, 1 start fresh
    var advRow by remember(game?.id, catalog?.key) { mutableIntStateOf(0) }
    var showSsLogin by remember { mutableStateOf(false) }
    var pendingSlot by remember(game?.id) { mutableStateOf<String?>(null) }
    val artPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri != null && slot != null) vm.importArt(uri, slot)
    }
    // Missing-row vs still-loading: Room answers fast; only a sustained null
    // is not-found.
    var loadWaited by remember(game?.id, catalog?.key) { mutableStateOf(false) }
    LaunchedEffect(game, catalog) {
        loadWaited = false
        if (game == null && catalog == null) {
            kotlinx.coroutines.delay(1500)
            loadWaited = true
        }
    }

    val ent = game
    val catalogFolder = catalog?.let {
        com.gamebex.client.ui.presentation.folderForCatalogEntry(it)
    }
    val lp = ent?.let {
        LocalGamePresenter.present(
            ctx.applicationContext, it,
            continued = it.playCount > 0, metaLine = null,
            biosMissing = missingBiosNames(bios),
            preparing = prepBusy,
        )
    } ?: catalog?.let {
        LocalGamePresenter.presentCatalog(it, catalogFolder, catalogDl)
    }
    val pres = lp?.presentation
    val fetchLabel = coreFetch?.text
    val coreStalled = coreFetch?.stalled == true
    val hasCoreCancel = coreFetch != null
    val hasCoreOpen = needsRetroArchSetup
    val hasCoreRetry = coreStalled || coreRetryAvailable || needsRetroArchSetup
    // Blocked route: no resolving operation exists, so the CTA parks.
    val unsupported = lp?.playable?.status is PlayStates.Status.Blocked
    val ctaLabel = coreInstallActionLabel(coreFetch, launching, needsRetroArchSetup, coreRetryAvailable,
        if (unsupported) "Unavailable" else {
            val a = pres?.primaryAction
            // A delete tombstone's CTA is "Download again".
            if (a == GamePrimaryAction.Download && ent != null) NOT_ON_PHONE_CTA else a?.label() ?: "Play"
        })

    // Start fresh: RetroArch-routed games with a resume point only (the
    // standalones expose no per-launch auto-load switch). Hidden mid-launch.
    val canFresh = ent != null && pres?.primaryAction == GamePrimaryAction.Continue &&
        !launching && fetchLabel == null && !needsRetroArchSetup && !coreRetryAvailable &&
        EmuRouter.canStartFresh(ent.folder, ent.emulatorOverride)
    val togetherMode = remember(ent) { ent?.let { TogetherSupport.modeFor(it) } }
    val canTogether = ent != null && togetherMode != null && !launching && fetchLabel == null &&
        !needsRetroArchSetup && !coreRetryAvailable
    if (!canFresh && !hasCoreCancel && !hasCoreOpen && ctaBtn == 1) ctaBtn = 0
    if (!canTogether && ctaBtn == 2) ctaBtn = 0
    val freshFocused = zone == 0 && ctaBtn == 1

    val catalogBlocked by vm.catalogBlocked.collectAsState()
    val rows = ent?.let {
        advRows(
            it, bios, preparing, ssCreds.complete(), vm,
            onImportArt = { slot ->
                pendingSlot = slot
                artPicker.launch("image/*")
            },
            onSsLogin = { showSsLogin = true },
            versions = versions,
            catalogBlocked = catalogBlocked,
        )
    }
        ?: buildList {
            add(
                AdvRowData(
                    label = "Emulator",
                    value = catalogEmuLabel(catalogFolder),
                    actionLabel = null,
                    run = null,
                ),
            )
            // Multi-file releases (regions/discs): pick the variant here;
            // the Download CTA uses the checked one.
            if (catalogFiles.size > 1) {
                val current = selectedFile?.takeIf { it in catalogFiles } ?: catalogFiles.first()
                catalogFiles.forEachIndexed { i, f ->
                    val bits = listOf(f.kind, f.size).filter { it.isNotBlank() }
                    val picked = f == current
                    add(
                        AdvRowData(
                            label = "File ${i + 1}",
                            value = f.name + (if (bits.isEmpty()) "" else " (${bits.joinToString(" · ")})") +
                                if (picked) " ✓" else "",
                            actionLabel = if (picked) null else "Use",
                            run = if (picked) null else ({ vm.selectFile(f) }),
                        ),
                    )
                }
            }
        }

    val downloading = pres?.primaryAction is GamePrimaryAction.Downloading
    val prepRunning = pres?.primaryAction == GamePrimaryAction.Preparing
    val ctaEnabled = hasCoreRetry || needsRetroArchSetup ||
        (!launching && fetchLabel == null && !downloading && !prepRunning && !unsupported)
    // Hints follow the focused zone: A names what A does there.
    val hintList = if (lp == null || pres == null) {
        listOf(ControllerHint(ControllerGlyph.B, "Back"))
    } else {
        detailHints(
            zone = zone,
            ctaA = if (zone == 0 && ctaBtn == 1 && hasCoreCancel) "Cancel"
                else if (zone == 0 && ctaBtn == 1 && hasCoreOpen) "Open RetroArch"
                else if (zone == 0 && hasCoreRetry) ctaLabel
                else ctaHint(ctaBtn, zone, ctaLabel, ctaEnabled),
            advAction = rows.getOrNull(advRow)?.takeIf { it.run != null }?.actionLabel,
            tab = tab,
            downloading = downloading,
            favorite = ent?.favorite,
            backLabel = if (hasCoreCancel) "Cancel" else "Back",
        )
    }
    LaunchedEffect(hintList) { hints.set(hintList) }

    if (missingEmu != null) {
        val emu = missingEmu!!
        val emuFocus = remember { FocusRequester() }
        ConsoleDialog(
            title = "${emu.label} isn't installed",
            onDismiss = { vm.consumeMissingEmu() },
            initialFocus = emuFocus,
            body = {
                Text(
                    "Install ${emu.label} to play this game. GameBEX fetches a reviewed build " +
                        "and Android asks you to confirm; progress shows in System.",
                )
            },
            buttons = {
                ConsoleDialogButton("Cancel", { vm.consumeMissingEmu() })
                ConsoleDialogButton(
                    "Install",
                    {
                        vm.consumeMissingEmu()
                        // Same path as the Home/Library quick-play prompt.
                        LocalLauncher.beginInstall(ctx, emu)
                        onInstallEmu()
                    },
                    Modifier.focusRequester(emuFocus),
                    tone = DialogTone.Primary,
                )
            },
        )
    }

    if (showSsLogin) {
        com.gamebex.client.ui.ScreenScraperCredentialsDialog(
            creds = ssCreds,
            onSave = { a, b, c, d ->
                vm.saveSsCreds(a, b, c, d)
                showSsLogin = false
            },
            onDismiss = { showSsLogin = false },
            focusSave = true,
        )
    }

    val confirmDelete by vm.confirmDelete.collectAsState()
    val deleted by vm.deleted.collectAsState()
    LaunchedEffect(deleted) { if (deleted) onBack() }
    val delTarget = game
    if (confirmDelete && delTarget != null) {
        com.gamebex.client.ui.console.DeleteGameDialog(
            delTarget,
            fromSource = delTarget.sourceRefJson != null,
            onConfirm = { vm.deleteGame() }, onDismiss = { vm.dismissDelete() },
        )
    }

    if (confirmReplace) {
        val ent = game
        val replFocus = remember { FocusRequester() }
        ConsoleDialog(
            title = "Replace this file?",
            onDismiss = { vm.dismissRedownload() },
            initialFocus = replFocus,
            body = {
                Text(
                    "Re-downloads the whole game" +
                        (if (ent != null) " (${ent.fileName})" else "") +
                        ". The current file is kept until the new bytes verify.",
                )
            },
            buttons = {
                // Safe default: initial focus parks on Cancel, so a stray
                // A press backs out instead of re-downloading gigabytes.
                ConsoleDialogButton("Cancel", { vm.dismissRedownload() }, Modifier.focusRequester(replFocus))
                ConsoleDialogButton("Re-download", { vm.redownload() }, tone = DialogTone.Primary)
            },
        )
    }

    if (needsChoice && catalogFiles.size > 1) {
        // D-pad native: TextButtons are focusable/clickable, first row takes
        // initial focus; Cancel is a real row since gamepad B can't reach
        // dialog windows (system BACK still dismisses).
        val firstFocus = remember(catalogFiles) { FocusRequester() }
        ConsoleDialog(
            title = "Which version?",
            onDismiss = { vm.dismissChoice() },
            initialFocus = firstFocus,
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    catalogFiles.forEachIndexed { i, f ->
                        val bits = listOf(f.kind, f.size).filter { it.isNotBlank() }
                        ConsoleDialogButton(
                            f.name + (if (bits.isEmpty()) "" else " (${bits.joinToString(" · ")})"),
                            { vm.chooseFile(f) },
                            if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            fill = true,
                        )
                    }
                }
            },
            buttons = { ConsoleDialogButton("Cancel", { vm.dismissChoice() }) },
        )
    }

    val screenFocus = rememberScreenFocus()
    // The entry grant lands on the loading box; when the loaded page
    // replaces it, focus dies with it and every button (and hint tap)
    // goes nowhere. Re-grant once the real page is composed.
    val loaded = lp != null && pres != null
    LaunchedEffect(loaded) {
        if (loaded) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { screenFocus.requestFocus() }
        }
    }
    if (lp == null || pres == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .screenFocus(screenFocus)
                .consoleKeys(onB = { onBack(); true }),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (loadWaited) "Game not found." else "Loading…",
                color = ConsoleColors.TextTertiary,
                fontSize = g.t(17), lineHeight = g.t(20),
            )
        }
        return
    }

    fun startFresh(): Boolean {
        if (!canFresh) return false
        vm.play(ctx, fresh = true)
        return true
    }

    fun setTab(i: Int) {
        tab = i.coerceIn(0, 1)
        advRow = advRow.coerceIn(0, maxOf(rows.size - 1, 0))
        if (tab == 0 && zone == 2) zone = 1
    }

    fun openRetroArch() {
        if (onOpenRetroArch != null) onOpenRetroArch() else vm.openRetroArch(ctx)
    }

    fun primary(): Boolean {
        if (hasCoreRetry) { vm.retryCoreInstall(); return true }
        // Repeated A is consumed while the visible install stays in the foreground.
        if (launching || fetchLabel != null) return true
        if (unsupported) return false
        return when (pres.primaryAction) {
            GamePrimaryAction.Play, GamePrimaryAction.Continue -> {
                vm.play(ctx); true
            }
            GamePrimaryAction.Install -> {
                vm.play(ctx); true
            }
            is GamePrimaryAction.OpenApp -> {
                vm.openRuntime(); true
            }
            GamePrimaryAction.FinishSetup -> when (lp.playable.status) {
                // Missing runtimes resolve in the System tab; every other
                // setup state runs the prep pipeline, which explains itself.
                is PlayStates.Status.NeedsRuntime,
                is PlayStates.Status.NeedsCore,
                -> {
                    onInstallEmu(); true
                }
                // Low space: the Library, biggest games first (Select deletes).
                is PlayStates.Status.NeedsSpace -> {
                    onFreeSpace(); true
                }
                // Setup that ends READY continues straight into Play.
                else -> {
                    vm.finishSetupAndPlay(ctx); true
                }
            }
            GamePrimaryAction.Download -> {
                // Tombstone: re-fetch the owned file onto the same path so
                // ingest revives the row. Catalog face downloads.
                if (ent != null) vm.redownload() else vm.downloadCatalog()
                true
            }
            is GamePrimaryAction.Downloading, GamePrimaryAction.Preparing -> false
            GamePrimaryAction.Resume, GamePrimaryAction.Retry -> {
                vm.resumeCatalog(); true
            }
            GamePrimaryAction.Verify -> {
                assist?.let { jobId ->
                    ctx.startActivity(AssistActivity.intent(ctx, jobId))
                }
                true
            }
            GamePrimaryAction.ManageSources -> {
                onManageSources(); true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .screenFocus(screenFocus)
            .consoleKeys(
                onLeft = {
                    if (zone == 0) when {
                        ctaBtn == 2 -> { ctaBtn = if (canFresh) 1 else 0; true }
                        ctaBtn == 1 -> { ctaBtn = 0; true }
                        else -> rail.enterRail()
                    } else if (zone == 1 && tab > 0) {
                        setTab(tab - 1); true
                    } else rail.enterRail()
                },
                onRight = {
                    if (zone == 0) when {
                        ctaBtn == 0 && (canFresh || hasCoreCancel || hasCoreOpen) -> { ctaBtn = 1; true }
                        ctaBtn == 0 && canTogether -> { ctaBtn = 2; true }
                        ctaBtn == 1 && canTogether -> { ctaBtn = 2; true }
                        else -> false
                    } else if (zone == 1 && tab < 1) {
                        setTab(tab + 1); true
                    } else false
                },
                onUp = {
                    when {
                        zone == 2 && advRow > 0 -> {
                            advRow--; true
                        }
                        zone == 2 -> {
                            zone = 1; true
                        }
                        zone == 1 -> {
                            zone = 0; true
                        }
                        else -> false
                    }
                },
                onDown = {
                    when {
                        zone == 0 -> {
                            zone = 1; true
                        }
                        zone == 1 && tab == 1 -> {
                            zone = 2
                            advRow = advRow.coerceIn(0, maxOf(rows.size - 1, 0))
                            true
                        }
                        zone == 2 && advRow < rows.size - 1 -> {
                            advRow++; true
                        }
                        else -> false
                    }
                },
                onA = {
                    when (zone) {
                        0 -> when (ctaBtn) {
                            1 -> when {
                                hasCoreCancel -> { vm.cancelCoreInstall(); true }
                                hasCoreOpen -> { openRetroArch(); true }
                                else -> startFresh()
                            }
                            2 -> if (canTogether) { onPlayTogether(ent!!.id); true } else false
                            else -> primary()
                        }
                        2 -> {
                            rows.getOrNull(advRow)?.run?.invoke(); true
                        }
                        else -> true
                    }
                },
                onB = { if (hasCoreCancel) vm.cancelCoreInstall() else onBack(); true },
                onX = { setTab(1 - tab); zone = 1; true },
                onY = {
                    if (downloading) {
                        vm.cancelCatalog(); true
                    } else if (ent != null) {
                        vm.toggleFav(); true
                    } else false // catalog face: nothing to favorite yet
                },
                onStart = { primary() },
                onLB = { setTab(0); zone = 1; true },
                onRB = { setTab(1); zone = 1; true },
            ),
    ) {
        val folder = ent?.folder ?: catalogFolder ?: "?"
        val tint = tintFor(folder)
        val app = ctx.applicationContext as GameBexApp
        val installed by app.sources.list().collectAsState()
        fun revFor(sourceId: String): String? = installed.firstOrNull { it.id == sourceId }?.revision
        // Catalog art arrives as SourceImage (scoped loader); local rows
        // keep their file paths. Naked remote URLs never load.
        val catalogImage = catalog?.coverImage(catalog?.ref?.sourceId?.let { revFor(it) })
        val heroRaw: String? = ent?.artHero ?: (pres.hero as? String)
        val boxRaw: String? = ent?.artBox ?: (pres.cover as? String)
        // No fanart is the common case: fall back to the cover so
        // the right side renders a tinted backdrop instead of void.
        val (heroLocal, heroRemote) = remember(heroRaw, boxRaw) {
            resolveHeroArt(listOf(heroRaw, boxRaw)) { File(it).isFile }
        }
        val heroImage = heroRemote?.takeIf { catalog != null }?.let {
            SourceImage(catalog!!.ref.sourceId, revFor(catalog!!.ref.sourceId) ?: "", it)
        }
        val glow = rememberArtColor(heroLocal, tint)
        GameEnvironment(
            model = GameEnvironmentModel(heroLocal, glow, heroImage = heroImage),
            region = GameEnvRegion(720, 0, 800, 620),
            continuousScrim = true,
        )
        // Full scrim under the info column (ends x882 local), easing to a 55%
        // dim on the right so the art stays visible behind the copy.
        run {
            val w = ConsoleDesign.WIDTH - ConsoleDesign.RAIL_WIDTH
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            700f / w to ConsoleColors.Scrim90,
                            900f / w to ConsoleColors.Background.copy(alpha = 0.78f),
                            1f to ConsoleColors.Background.copy(alpha = 0.55f),
                        ),
                    ),
            )
        }

        GameCover(
            title = pres.title, cover = catalogImage ?: pres.cover,
            focused = false, tint = tint,
            systemFolder = folder,
            modifier = Modifier.offset(x = g.x(84), y = g.x(136)).width(g.x(270)).height(g.x(382)),
        )

        Column(
            modifier = Modifier.offset(x = g.x(412), y = g.x(126)).width(g.x(470)),
        ) {
            // Shrinks until layout reports no overflow; char-count buckets
            // cannot fit real titles ("SUPER MARIO 64" at 56sp overflows 430px).
            var titleSize by remember(pres.title) { mutableIntStateOf(56) }
            Text(
                pres.title.uppercase(),
                color = ConsoleColors.TextPrimary, fontSize = g.t(titleSize),
                fontWeight = FontWeight.Black, lineHeight = g.t(titleSize + 2),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow && titleSize > 28) titleSize -= 2 },
                modifier = Modifier.height(g.x(116)),
            )
            Text(
                pres.platformLabel.uppercase(),
                color = ConsoleColors.TextSecondary, fontSize = g.t(17),
                lineHeight = g.t(20), fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = g.x(8)),
            )
            // Preparing with no op running means never-prepared, not busy.
            val (stateText, stateColor) = ent?.let {
                if (lp.playable.status is PlayStates.Status.Preparing && !prepBusy)
                    "Not prepared yet." to ConsoleColors.TextSecondary
                else stateLine(lp, it)
            } ?: catalogStateLine(lp.playable.status, pres.platformLabel)
            if (coreFetch != null) {
                CoreInstallStatus(coreFetch!!, Modifier.padding(top = g.x(6)))
            } else {
                Text(
                    if (needsRetroArchSetup) message.orEmpty() else stateText,
                    color = if (needsRetroArchSetup) ConsoleColors.Warning else stateColor,
                    fontSize = g.t(16), lineHeight = g.t(18),
                    maxLines = if (needsRetroArchSetup) 3 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = g.x(6)),
                )
            }
            // Secondary buttons take the width their labels need; the primary gets
            // the rest and ellipsizes. Fixed caps would cut "START FRESH" to "START".
            Row(
                modifier = Modifier.padding(top = g.x(36)).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(g.x(16)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryConsoleAction(
                    label = ctaLabel,
                    focused = zone == 0 && ctaBtn == 0,
                    onClick = { primary() },
                    controllerGlyph = ControllerGlyph.A,
                    enabled = ctaEnabled,
                    modifier = Modifier.weight(1f, fill = false)
                        .widthIn(min = g.x(if (canFresh || canTogether || hasCoreCancel || hasCoreOpen) 150 else 250)),
                )
                if (hasCoreOpen) {
                    SecondaryConsoleAction(
                        label = "Open RetroArch",
                        focused = zone == 0 && ctaBtn == 1,
                        onClick = { openRetroArch() },
                    )
                }
                if (hasCoreCancel) {
                    SecondaryConsoleAction(
                        label = "Cancel",
                        focused = zone == 0 && ctaBtn == 1,
                        onClick = { vm.cancelCoreInstall() },
                    )
                }
                if (canFresh) {
                    SecondaryConsoleAction(
                        label = "Start fresh",
                        focused = freshFocused,
                        onClick = { zone = 0; ctaBtn = 1; startFresh() },
                    )
                }
                if (canTogether) {
                    SecondaryConsoleAction(
                        label = TogetherCopy.twoP,
                        focused = zone == 0 && ctaBtn == 2,
                        onClick = { zone = 0; ctaBtn = 2; onPlayTogether(ent!!.id) },
                        modifier = Modifier.widthIn(min = g.x(84)).heightIn(min = g.x(54))
                            .semantics { contentDescription = TogetherCopy.twoPHint },
                    )
                }
            }
            Text(
                ent?.let { metaLine(it) } ?: catalogMetaLine(catalogFiles, selectedFile),
                color = ConsoleColors.TextSecondary, fontSize = g.t(16),
                lineHeight = g.t(18),
                modifier = Modifier.padding(top = g.x(44)),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(g.x(36)),
            modifier = Modifier.offset(x = g.x(412), y = g.x(494)),
        ) {
            DetailTab("Overview", selected = tab == 0, zoneFocused = zone == 1 && tab == 0) { setTab(0) }
            DetailTab("Advanced", selected = tab == 1, zoneFocused = zone == 1 && tab == 1) { setTab(1) }
        }

        Box(
            modifier = Modifier.offset(x = g.x(412), y = g.x(544)).width(g.x(560)),
        ) {
            if (tab == 0) {
                val desc = ent?.description ?: catalogDesc
                Text(
                    desc?.takeIf { it.isNotBlank() } ?: "No description.",
                    color = if (desc.isNullOrBlank()) ConsoleColors.TextTertiary
                    else ConsoleColors.TextSecondary,
                    fontSize = g.t(17), lineHeight = g.t(22),
                    // Whole lines only, ending above the footer band (y648)
                    // at any text size.
                    maxLines = (104f / (22f * g.textScale)).toInt().coerceIn(1, 4),
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                // Rows scroll inside the tab box (y544–652), clear of the hint bar.
                // Rows are fixed-height, so focus scrolls by index math (this
                // Compose version has no bring-into-view API).
                val advScroll = rememberScrollState()
                val advDensity = androidx.compose.ui.platform.LocalDensity.current
                LaunchedEffect(advRow, zone) {
                    if (zone == 2) with(advDensity) {
                        val rowPx = g.x(36).toPx()
                        val top = advRow * rowPx
                        val bottom = top + g.x(30).toPx()
                        val viewTop = advScroll.value.toFloat()
                        val viewBottom = viewTop + g.x(108).toPx()
                        when {
                            top < viewTop -> advScroll.animateScrollTo(top.toInt())
                            bottom > viewBottom -> advScroll.animateScrollTo((bottom - g.x(108).toPx()).toInt())
                        }
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(g.x(6)),
                    modifier = Modifier.heightIn(max = g.x(108)).verticalScroll(advScroll),
                ) {
                    rows.forEachIndexed { i, row ->
                        AdvRow(row, focused = zone == 2 && i == advRow) { row.run?.invoke() }
                    }
                }
            }
        }

        // Transient status line: clears itself so it never parks on content.
        LaunchedEffect(message, needsRetroArchSetup, coreRetryAvailable) {
            if (message != null && !needsRetroArchSetup && !coreRetryAvailable) {
                kotlinx.coroutines.delay(5000)
                vm.clearMessage()
            }
        }
        message?.takeIf { !needsRetroArchSetup }?.let { msg ->
            Text(
                msg,
                color = ConsoleColors.Warning, fontSize = g.t(16),
                lineHeight = g.t(18),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = g.x(52)),
            )
        }
    }
}

internal const val CORE_INSTALL_SPINNER_TAG = "core-install-spinner"
internal const val CORE_INSTALL_MESSAGE_TAG = "core-install-message"
internal const val CORE_INSTALL_PROGRESS_TAG = "core-install-progress"

internal fun coreInstallActionLabel(
    progress: CoreInstallProgress?,
    launching: Boolean,
    needsSetup: Boolean,
    retryAvailable: Boolean,
    fallback: String,
): String = when {
    progress?.stalled == true || retryAvailable -> "Retry"
    progress != null -> "Installing core…"
    needsSetup -> "Retry"
    launching -> "Launching…"
    else -> fallback
}

/** Same palette and compact progress geometry on the solo and together launch surfaces. */
@Composable
internal fun CoreInstallStatus(progress: CoreInstallProgress, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(g.x(6))) {
        Row(horizontalArrangement = Arrangement.spacedBy(g.x(10)), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(g.x(20)).testTag(CORE_INSTALL_SPINNER_TAG),
                color = ConsoleColors.Accent,
                strokeWidth = g.x(2),
            )
            Text(
                progress.text, color = if (progress.stalled) ConsoleColors.Warning else ConsoleColors.TextPrimary,
                fontSize = g.t(16), lineHeight = g.t(20), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(CORE_INSTALL_MESSAGE_TAG),
            )
        }
        progress.fraction?.let { fraction ->
            Box(Modifier.fillMaxWidth().height(g.x(6)).background(ConsoleColors.Steel)
                .testTag(CORE_INSTALL_PROGRESS_TAG)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
                if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).height(g.x(6)).background(ConsoleColors.Accent))
            }
        }
    }
}

internal fun ctaHint(ctaBtn: Int, zone: Int, ctaLabel: String, ctaEnabled: Boolean): String? = when {
    zone == 0 && ctaBtn == 1 -> "Start fresh"
    zone == 0 && ctaBtn == 2 -> TogetherCopy.twoPHint
    else -> ctaLabel.takeIf { ctaEnabled }
}

/**
 * Detail hint row for the focused zone (0 CTA, 1 tabs, 2 Advanced rows).
 * A shows only where it acts: enabled CTA, nothing on tabs, focused row action.
 * Y shows Cancel mid-download and Favorite for a local game.
 */
internal fun detailHints(
    zone: Int,
    ctaA: String?,
    advAction: String?,
    tab: Int,
    downloading: Boolean,
    favorite: Boolean?,
    backLabel: String = "Back",
): List<ControllerHint> = buildList {
    when (zone) {
        0 -> ctaA
        2 -> advAction
        else -> null
    }?.let { add(ControllerHint(ControllerGlyph.A, it)) }
    add(ControllerHint(ControllerGlyph.B, backLabel))
    add(ControllerHint(ControllerGlyph.X, if (tab == 0) "Advanced" else "Overview"))
    when {
        downloading -> add(ControllerHint(ControllerGlyph.Y, "Cancel"))
        favorite != null -> add(ControllerHint(ControllerGlyph.Y, if (favorite) "Unfavorite" else "Favorite"))
    }
}

/**
 * Versions row summary: only unfinished jobs count as queued (a finished
 * or failed download is a version, not a queue entry).
 */
internal fun versionsSummary(statuses: List<String>): String {
    if (statuses.isEmpty()) return "This file only"
    val n = statuses.size
    val queued = statuses.count {
        it != com.gamebex.client.net.DownloadEngine.ST_DONE &&
            it != com.gamebex.client.net.DownloadEngine.ST_FAILED
    }
    val versions = if (n == 1) "1 version" else "$n versions"
    return when (queued) {
        0 -> versions
        n -> "$queued queued"
        else -> "$versions · $queued queued"
    }
}

private data class AdvRowData(
    val label: String,
    val value: String,
    val actionLabel: String?,
    val run: (() -> Unit)?,
    val maxLines: Int = 1,
    /** Destructive action: label renders in the error color. */
    val destructive: Boolean = false,
)

private fun advRows(
    ent: GameEntity,
    bios: BiosCheck.FolderReport?,
    preparing: Boolean,
    ssComplete: Boolean,
    vm: LocalDetailViewModel,
    onImportArt: (String) -> Unit,
    onSsLogin: () -> Unit,
    versions: List<DownloadRecord>,
    catalogBlocked: String? = null,
): List<AdvRowData> {
    val platform = PlatformRegistry.byFolder[ent.folder]
    val rows = mutableListOf<AdvRowData>()
    rows += AdvRowData(
        label = "Emulator",
        value = emuLabelFor(ent),
        actionLabel = if (platform?.altEmus.isNullOrEmpty()) null else "Change",
        run = if (platform?.altEmus.isNullOrEmpty()) null else vm::cycleEmu,
    )
    val prepHuman = when (ent.prepState) {
        "READY" -> "Ready"
        "NEEDS_BIOS" -> "Needs BIOS"
        "NEEDS_MANUAL" -> "Needs manual fix"
        "FAILED" -> "Failed"
        else -> "Not run"
    }
    val biosTail = bios?.let {
        val missing = missingBiosNames(it)
        when {
            it.needs.isEmpty() -> "no BIOS needed"
            missing.isEmpty() -> "BIOS present"
            else -> "missing ${missing.take(2).joinToString(", ")}"
        }
    }
    // Cartridge systems never need prep: hide the row unless this game
    // actually hit a prep problem (FDS/64DD firmware, unbootable format).
    val prepProblem = ent.prepState == "NEEDS_BIOS" || ent.prepState == "NEEDS_MANUAL" ||
        ent.prepState == "FAILED"
    if (com.gamebex.client.data.local.PrepLogic.hasPrepStep(ent.folder) || prepProblem) rows += AdvRowData(
        label = "Preparation",
        value = listOfNotNull(
            prepHuman,
            ent.prepNote?.takeIf { it.isNotBlank() },
            biosTail,
        ).joinToString(" · "),
        actionLabel = if (preparing) "…" else "Run",
        run = if (preparing) null else vm::prepare,
    )
    if (ent.prepState == "NEEDS_BIOS") {
        val miss = missingBiosNames(bios)
        rows += AdvRowData(
            label = "BIOS",
            value = if (miss.isEmpty()) "See placement in Preparation"
            else "Get ${miss.take(2).joinToString(", ")} (MD5-verified)",
            actionLabel = if (preparing) "…" else "Download",
            run = if (preparing) null else vm::downloadBios,
        )
    }
    if (ent.sourceId != null) {
        rows += AdvRowData(
            label = "File",
            value = ent.fileName,
            actionLabel = "Replace",
            run = vm::requestRedownload,
        )
        rows += AdvRowData(
            label = "Versions",
            value = catalogBlocked ?: versionsSummary(versions.map { it.status }),
            actionLabel = "More",
            run = vm::loadCatalogForGame,
        )
        versions.forEachIndexed { i, v ->
            rows += AdvRowData(
                label = "Version ${i + 1}",
                value = "${v.fileName} · ${downloadStateLabel(v.status)}",
                actionLabel = "Delete",
                run = { vm.deleteVersion(v.id) },
            )
        }
    }
    rows += AdvRowData(
        label = "Artwork",
        value = when (ent.artSource) {
            "SS" -> "ScreenScraper"
            "USER" -> "Your images"
            "MIXED" -> "Mixed"
            else -> "None yet"
        },
        actionLabel = "Refresh",
        run = vm::refreshArt,
    )
    for (slot in ArtStore.SLOTS) {
        val path = when (slot) {
            ArtStore.SLOT_BOX -> ent.artBox
            ArtStore.SLOT_HERO -> ent.artHero
            ArtStore.SLOT_LOGO -> ent.artLogo
            else -> ent.artShot
        }
        rows += AdvRowData(
            label = "Art $slot",
            value = if (path.isNullOrBlank()) "Empty" else "Set",
            actionLabel = "Import",
            run = { onImportArt(slot) },
        )
    }
    rows += AdvRowData(
        label = "ScreenScraper",
        value = if (ssComplete) "Login saved" else "Login missing",
        actionLabel = "Edit",
        run = onSsLogin,
    )
    if (platform?.route is Route.RetroCore) {
        rows += AdvRowData(
            label = "Suspend",
            value = "Select+Start in-game, resumes on next Play.",
            actionLabel = null,
            run = null,
        )
    }
    // Last, far from Play: frees the game's files after a confirm dialog.
    rows += AdvRowData(
        label = "Storage",
        value = "Delete game · frees ${formatSize(ent.sizeBytes)}",
        actionLabel = "Delete",
        run = vm::requestDelete,
        destructive = true,
    )
    return rows
}

@Composable
private fun DetailTab(
    label: String,
    selected: Boolean,
    zoneFocused: Boolean,
    onClick: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(8))
    Column(
        modifier = Modifier
            .clip(shape)
            .then(
                if (zoneFocused) Modifier.border(g.x(2), ConsoleColors.FocusRing, shape)
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = g.x(10), vertical = g.x(6)),
    ) {
        Text(
            label,
            color = if (selected) ConsoleColors.TextPrimary else ConsoleColors.TextTertiary,
            fontSize = g.t(18), lineHeight = g.t(22),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(top = g.x(4))
                .width(g.x(28))
                .height(g.x(2))
                .background(if (selected) ConsoleColors.Accent else Color.Transparent),
        )
    }
}

@Composable
private fun AdvRow(row: AdvRowData, focused: Boolean, onClick: () -> Unit) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(10))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(g.x(560))
            .height(g.x(30))
            .clip(shape)
            .then(
                if (focused) Modifier.border(g.x(2), ConsoleColors.FocusRing, shape)
                else Modifier.border(g.x(1), ConsoleColors.Steel, shape),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = g.x(14), vertical = g.x(5)),
    ) {
        Text(
            row.label.uppercase(),
            color = ConsoleColors.TextTertiary, fontSize = g.t(14),
            lineHeight = g.t(16), fontWeight = FontWeight.Bold,
            modifier = Modifier.width(g.x(130)),
        )
        Text(
            row.value,
            color = ConsoleColors.TextPrimary, fontSize = g.t(16),
            lineHeight = g.t(18),
            maxLines = row.maxLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.actionLabel != null) {
            Text(
                row.actionLabel,
                color = if (row.destructive) ConsoleColors.Error else ConsoleColors.Accent, fontSize = g.t(15),
                lineHeight = g.t(18), fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = g.x(12)),
            )
        }
    }
}

/** Tombstone (deleted game) copy. */
private const val NOT_ON_PHONE_LINE = "Not on this phone"
private const val NOT_ON_PHONE_CTA = "Download again"

/** Human state line for the ready-state page. No package IDs, .so names, or paths. */
private fun stateLine(lp: LocalPresentation, ent: GameEntity): Pair<String, Color> = when (val s = lp.playable.status) {
    // A tombstone reads as a download again, never "Storage volume is missing".
    is PlayStates.Status.Available -> NOT_ON_PHONE_LINE to ConsoleColors.Accent
    is PlayStates.Status.Ready,
    is PlayStates.Status.Playing,
    -> if (ent.lastPlayedMs > 0) {
        "Last played ${relativeDay(ent.lastPlayedMs)}" to ConsoleColors.TextSecondary
    } else {
        "Never played" to ConsoleColors.TextSecondary
    }
    is PlayStates.Status.NeedsRuntime -> "${s.emu.label} is not installed" to ConsoleColors.Warning
    is PlayStates.Status.NeedsCore -> "RetroArch core is not installed" to ConsoleColors.Warning
    is PlayStates.Status.NeedsBios -> (
        if (s.names.isEmpty()) "BIOS files are missing"
        else "Missing BIOS: ${s.names.take(2).joinToString(", ")}${if (s.names.size > 2) "…" else ""}"
        ) to ConsoleColors.Warning
    is PlayStates.Status.NeedsManual -> s.note.ifBlank { "Needs a manual fix" } to ConsoleColors.Warning
    is PlayStates.Status.Preparing -> "Preparing…" to ConsoleColors.TextSecondary
    is PlayStates.Status.NeedsVolume -> "Storage volume is missing" to ConsoleColors.Error
    is PlayStates.Status.NeedsSpace -> "Not enough free space" to ConsoleColors.Warning
    // Route reasons are internal notes; show plain copy instead.
    is PlayStates.Status.Blocked -> UserErrors.unsupportedSystem(lp.presentation.platformLabel) to ConsoleColors.Error
    else -> "" to ConsoleColors.TextSecondary
}

/** Catalog state line: download progress, never package IDs or paths. */
private fun catalogStateLine(s: PlayStates.Status, platform: String): Pair<String, Color> = when (s) {
    is PlayStates.Status.Available -> "Available to download" to ConsoleColors.TextSecondary
    is PlayStates.Status.Downloading -> when {
        s.assist -> "Needs a quick verification check." to ConsoleColors.Warning
        s.paused && s.total > 0 ->
            "Paused at ${(s.read * 100 / s.total).toInt()}%." to ConsoleColors.TextSecondary
        s.paused -> "Paused." to ConsoleColors.TextSecondary
        s.total > 0 -> "Downloading… ${(s.read * 100 / s.total).toInt()}%" to ConsoleColors.TextSecondary
        else -> "Downloading…" to ConsoleColors.TextSecondary
    }
    is PlayStates.Status.DownloadFailed ->
        (s.error ?: "Download failed.") to ConsoleColors.Warning
    is PlayStates.Status.Blocked -> UserErrors.unsupportedSystem(platform) to ConsoleColors.Error
    else -> "" to ConsoleColors.TextSecondary
}

private fun catalogMetaLine(files: List<DownloadFile>, selected: DownloadFile?): String {
    if (files.isEmpty()) return "Finding download files…"
    val cur = selected?.takeIf { it in files } ?: files.first()
    return buildString {
        append(files.size.toString() + if (files.size == 1) " file" else " files")
        if (files.size > 1) append(" · using ${cur.name}")
        if (cur.size.isNotBlank()) append(" · ${cur.size}")
    }
}

private fun catalogEmuLabel(folder: String?): String = emulatorLabel(folder)

private fun metaLine(ent: GameEntity): String = buildString {
    append(formatSize(ent.sizeBytes))
    append(" · ")
    append(ent.ext.uppercase())
    if (ent.favorite) append(" · ♥")
    if (ent.playCount > 0) append(" · Played ${ent.playCount}×")
}

private fun emuLabelFor(ent: GameEntity): String {
    ent.emulatorOverride?.let { pkg ->
        Emu.entries.firstOrNull { it.pkg == pkg }?.let { return it.label }
    }
    return when (val route = PlatformRegistry.byFolder[ent.folder]?.route) {
        is Route.RetroCore -> "RetroArch · ${com.gamebex.client.data.CoreNames.display(ent.coreOverride ?: route.core)}"
        is Route.Standalone -> route.emu.label
        is Route.GuidedInstall -> "${route.emu.label} (guided)"
        is Route.SideloadApp -> "Install as app"
        is Route.Blocked -> "Blocked"
        null -> "?"
    }
}

private fun missingBiosNames(report: BiosCheck.FolderReport?): List<String> =
    report?.needs?.flatMap { it.files }
        ?.filter { it.state == BiosCheck.FileState.MISSING }
        ?.map { it.want.name } ?: emptyList()
