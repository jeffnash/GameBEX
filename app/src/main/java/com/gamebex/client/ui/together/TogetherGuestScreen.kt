package com.gamebex.client.ui.together

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.gamebex.client.ui.console.PrimaryConsoleAction
import com.gamebex.client.ui.console.SecondaryConsoleAction
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherController
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.ControllerPrefs
import com.gamebex.client.ui.console.glyphLabel
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.screenFocus
import com.gamebex.client.ui.game.CoreInstallStatus

@Suppress("UNUSED_PARAMETER")
@Composable
fun TogetherGuestScreen(
    controller: TogetherController,
    rail: RailHost,
    hints: ConsoleHints,
    onBack: () -> Unit,
    onOpenSystem: () -> Unit,
    onOpenPpsspp: () -> Unit,
    onOpenRetroArch: () -> Unit = {},
    /** STREAM: false while the viewer is closed, so "Open stream" shows. */
    streamViewerOpen: Boolean = true,
) {
    val view by controller.guest.collectAsState()
    val v = view
    val g = LocalConsoleGeometry.current
    var selected by remember(v?.choices) { mutableIntStateOf(0) }
    var pickerStart by remember(v?.choices) { mutableIntStateOf(0) }
    var seenLive by remember { mutableStateOf(false) }
    var popped by remember { mutableStateOf(false) }
    fun popOnce() { if (!popped) { popped = true; onBack() } }
    LaunchedEffect(v) {
        val current = v
        if (current != null && current.step != GuestStep.ENDED) seenLive = true
        if (seenLive && (current == null || (current.step == GuestStep.ENDED && current.message == null))) popOnce()
    }
    // A fresh composition (restore, recreation) of a session that is already
    // over or gone pops at once. "Ended with a message" stays only for a session
    // this screen watched end (the effect above).
    LaunchedEffect(Unit) {
        val current = controller.guest.value
        if (current == null || current.step == GuestStep.ENDED) popOnce()
    }
    val picking = v?.step == GuestStep.PICKING_GAME
    val choices = v?.choices.orEmpty()
    val visibleStart = if (picking) togetherWindowStart(selected, choices.size, 6, pickerStart) else pickerStart
    SideEffect { if (picking && pickerStart != visibleStart) pickerStart = visibleStart }
    val coreInstall = v?.coreInstall
    val action = v?.problem?.action
    val streamClosed = v?.step == GuestStep.IN_GAME && v.mode == com.gamebex.client.together.TogetherMode.STREAM && !streamViewerOpen
    val primaryEnabled = coreInstall == null || coreInstall.stalled
    val primaryLabel = if (coreInstall != null) {
        if (coreInstall.stalled) "Retry" else "Installing core…"
    } else when (v?.step) {
        GuestStep.ACTION_NEEDED -> action?.let(TogetherCopy::actionLabel)
        GuestStep.INSTALLING_CORE -> TogetherCopy.recheck
        GuestStep.ENDED -> TogetherCopy.back
        GuestStep.IN_GAME -> if (streamClosed) TogetherCopy.openStream else null
        else -> null
    }
    val showLeave = v != null && v.step != GuestStep.ENDED
    val secondaryLabel = if (coreInstall != null) TogetherCopy.cancel else TogetherCopy.leave
    val cancel: () -> Unit = {
        if (coreInstall != null) controller.act(TogetherAction.CANCEL_CORE_INSTALL) else controller.end()
    }
    val primary: () -> Unit = {
        when {
            coreInstall != null -> controller.act(if (coreInstall.stalled) TogetherAction.RETRY_CORE_INSTALL else TogetherAction.INSTALL_CORE)
            picking -> choices.getOrNull(selected)?.let { controller.pick(it.gameId) }
            v?.step == GuestStep.ACTION_NEEDED -> action?.let {
                when (it) {
                    TogetherAction.OPEN_SYSTEM -> onOpenSystem()
                    TogetherAction.OPEN_PPSSPP -> onOpenPpsspp()
                    TogetherAction.OPEN_RETROARCH -> controller.act(it)
                    else -> controller.act(it)
                }
            }
            v?.step == GuestStep.INSTALLING_CORE -> controller.act(TogetherAction.RECHECK)
            v?.step == GuestStep.ENDED -> onBack()
            streamClosed -> controller.act(TogetherAction.OPEN_STREAM)
        }
    }
    val hintsList = buildList {
        when {
            coreInstall != null -> if (coreInstall.stalled) add(ControllerHint(ControllerGlyph.A, "Retry"))
            picking -> add(ControllerHint(ControllerGlyph.A, TogetherCopy.join))
            v?.step == GuestStep.ACTION_NEEDED -> action?.let { add(ControllerHint(ControllerGlyph.A, TogetherCopy.actionLabel(it))) }
            v?.step == GuestStep.INSTALLING_CORE -> add(ControllerHint(ControllerGlyph.A, TogetherCopy.recheck))
            v?.step == GuestStep.ENDED -> add(ControllerHint(ControllerGlyph.A, TogetherCopy.back))
            streamClosed -> add(ControllerHint(ControllerGlyph.A, TogetherCopy.openStream))
        }
        // B is hidden in ENDED: A is Back there, and B → onBack() still works silently.
        if (showLeave) add(ControllerHint(ControllerGlyph.B, secondaryLabel))
    }
    LaunchedEffect(hintsList) { hints.set(hintsList) }
    Box(
        Modifier.fillMaxSize().screenFocus(rememberScreenFocus()).consoleKeys(
            onLeft = { rail.enterRail() },
            onUp = { if (picking && selected > 0) { selected--; true } else false },
            onDown = { if (picking && selected < choices.lastIndex) { selected++; true } else false },
            onA = { primary(); true },
            onB = { if (v?.step == GuestStep.ENDED) onBack() else cancel(); true },
        ),
    ) {
        TogetherFrame(
            left = { TogetherStack {
                TogetherHeader(
                    v?.gameTitle ?: TogetherCopy.joining,
                    v?.let { view ->
                        TogetherCopy.withHost(view.hostName) + view.mode?.let { " · ${TogetherCopy.modeLabel(it)}" }.orEmpty()
                    },
                )
                Column(Modifier.stackY(188).width(g.x(560)), verticalArrangement = Arrangement.spacedBy(g.x(10))) {
                    TogetherStatus(
                        when (v?.step) {
                            GuestStep.CONNECTING, GuestStep.WAITING_OFFER -> TogetherCopy.connecting(v?.hostName.orEmpty())
                            GuestStep.CHECKING -> TogetherCopy.checkingCopy(v?.gameTitle)
                            GuestStep.ACTION_NEEDED -> TogetherCopy.actionHeadline(v?.phase)
                            GuestStep.DOWNLOADING -> TogetherCopy.downloading(v?.gameTitle)
                            GuestStep.CORE_SYNC -> TogetherCopy.copyingCore
                            GuestStep.INSTALLING_CORE -> TogetherCopy.installingCoreHeadline
                            GuestStep.PICKING_GAME -> TogetherCopy.pickTitle
                            GuestStep.READY -> TogetherCopy.readyWaiting(v?.hostName.orEmpty())
                            GuestStep.LAUNCHING -> TogetherCopy.startingIn(v?.countdown)
                            GuestStep.IN_GAME -> TogetherCopy.playingWith(v?.hostName.orEmpty())
                            GuestStep.ENDED -> TogetherCopy.ended
                            null -> TogetherCopy.joining
                        },
                        if (coreInstall != null) null else when (v?.step) {
                            GuestStep.ACTION_NEEDED -> v?.problem?.text
                            GuestStep.DOWNLOADING, GuestStep.CORE_SYNC -> v?.progress?.let { "$it%" }
                            GuestStep.INSTALLING_CORE -> TogetherCopy.installingCore
                            GuestStep.PICKING_GAME -> TogetherCopy.pickSub
                            GuestStep.IN_GAME -> if (streamClosed) TogetherCopy.streamClosed else v?.message
                            GuestStep.ENDED -> v?.message
                            else -> null
                        },
                        null,
                    )
                    coreInstall?.let { CoreInstallStatus(it) }
                }
                if (v?.progress != null && coreInstall == null) {
                    Box(Modifier.stackY(302).width(g.x(560)).height(g.x(6)).background(ConsoleColors.Steel)) {
                        Box(Modifier.fillMaxWidth((v?.progress ?: 0).coerceIn(0, 100) / 100f).height(g.x(6)).background(ConsoleColors.Accent))
                    }
                }
                if (primaryLabel != null || showLeave) {
                    Row(Modifier.stackY(476), horizontalArrangement = Arrangement.spacedBy(g.x(16))) {
                        primaryLabel?.let { label ->
                            PrimaryConsoleAction(label, true, { primary() }, modifier = Modifier.heightIn(min = g.x(84)), enabled = primaryEnabled)
                        }
                        if (showLeave) {
                            SecondaryConsoleAction(secondaryLabel, false, { cancel() }, Modifier.heightIn(min = g.x(84)))
                        }
                    }
                }
                v?.message?.takeIf { coreInstall == null && v?.step != GuestStep.IN_GAME && v?.step != GuestStep.ENDED && v?.step != GuestStep.PICKING_GAME }?.let { message ->
                    Text(message, color = ConsoleColors.Warning, fontSize = g.t(16), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.stackY(580).width(g.x(560)))
                }
            } },
            right = {
                if (picking) {
                    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { g.x(84 + 8).toPx() }
                    Column(
                        Modifier.offset(y = g.x(48)).width(g.x(640))
                            .dragToStep(rowPx, choices.size) { d -> selected = (selected + d).coerceIn(0, choices.lastIndex) },
                        verticalArrangement = Arrangement.spacedBy(g.x(8)),
                    ) {
                        choices.drop(visibleStart).take(6).forEachIndexed { offset, choice ->
                            val index = visibleStart + offset
                            val focused = index == selected
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = g.x(84))
                                    .background(if (focused) ConsoleColors.HighSurface else Color.Transparent)
                                    .border(if (focused) g.x(ConsoleDesign.FOCUS_STROKE) else g.x(1), if (focused) ConsoleColors.FocusRing else ConsoleColors.Steel, RoundedCornerShape(g.x(8)))
                                    .clickable { selected = index; controller.pick(choice.gameId) }
                                    .padding(horizontal = g.x(16)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(choice.title, color = ConsoleColors.TextPrimary, fontSize = g.t(20), lineHeight = g.t(24), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(togetherPlatformName(choice.folder), color = ConsoleColors.TextSecondary, fontSize = g.t(15), lineHeight = g.t(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                } else if (v?.step == GuestStep.LAUNCHING || v?.step == GuestStep.IN_GAME) {
                    Box(Modifier.offset(y = g.x(48))) {
                        TogetherInstructions(v?.instructions.orEmpty().ifEmpty { TogetherCopy.guestInstructions(v?.mode, v?.hostName.orEmpty(), glyphLabel(ControllerGlyph.Y, ControllerPrefs.layout, swap = false)) })
                    }
                }
            },
        )
    }
}
