package com.gamebex.client.ui.together

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.HostView
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherController
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.PrimaryConsoleAction
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.SecondaryConsoleAction
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.screenFocus
import com.gamebex.client.ui.game.CoreInstallStatus

/** Test tag on the host's primary (A) button. */
internal const val HOST_PRIMARY_TAG = "together-host-primary"

/** Test tag on the host's "Use <mode> instead" button. */
internal const val HOST_ALT_MODE_TAG = "together-host-alt-mode"

internal fun togetherPlatformName(folder: String): String = PlatformRegistry.byFolder[folder]?.display ?: folder

internal fun togetherSubtitle(folder: String, mode: TogetherMode?): String? {
    val platform = togetherPlatformName(folder).uppercase()
    val suffix = mode?.let { " · ${TogetherCopy.modeLabel(it)}" }.orEmpty()
    return (platform + suffix).ifBlank { null }
}

/**
 * Touch scrolling for a D-pad window list: a vertical drag steps the selection one row
 * per [rowPx] dragged (finger up = later rows) and the window follows the selection.
 * [key] restarts the detector when the list changes, so [onStep] never sees a stale size.
 */
internal fun Modifier.dragToStep(rowPx: Float, key: Any?, onStep: (Int) -> Unit): Modifier =
    pointerInput(rowPx, key) {
        var dragged = 0f
        detectVerticalDragGestures(onDragStart = { dragged = 0f }) { change, dy ->
            dragged += dy
            while (dragged <= -rowPx) { onStep(1); dragged += rowPx }
            while (dragged >= rowPx) { onStep(-1); dragged -= rowPx }
            change.consume()
        }
    }

internal fun togetherWindowStart(selected: Int, count: Int, visible: Int, currentStart: Int = 0): Int {
    if (count <= visible || visible <= 0) return 0
    val maxStart = count - visible
    val start = when {
        selected < currentStart -> selected
        selected >= currentStart + visible -> selected - visible + 1
        else -> currentStart
    }
    return start.coerceIn(0, maxStart)
}

internal fun togetherPhaseColor(phase: GuestPhase?): Color = when (phase) {
    GuestPhase.READY, GuestPhase.PLAYING -> ConsoleColors.Success
    GuestPhase.NEEDS_EMULATOR, GuestPhase.NEEDS_SETUP, GuestPhase.NEEDS_CORE,
    GuestPhase.NEEDS_GAME, GuestPhase.MISMATCH -> ConsoleColors.Warning
    else -> ConsoleColors.TextSecondary
}

@Composable
internal fun TogetherFrame(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    Box(modifier.fillMaxSize()) {
        Box(Modifier.offset(x = g.x(84), y = g.x(48)).width(g.x(640))) { left() }
        Box(Modifier.offset(x = g.x(760), y = g.x(48)).width(g.x(640))) { right() }
    }
}

private data class StackY(val y: Int)

/** A [TogetherStack] child's y, in canvas units from the stack's top. */
internal fun Modifier.stackY(y: Int): Modifier = layoutId(StackY(y))

/**
 * Stacks children top to bottom. Each sits at its [stackY] or, when the
 * child above runs past that, right below it. At Default text sizes every
 * child keeps its designed position; larger text (DisplayPrefs or the system
 * font scale) pushes later children down instead of overlapping them.
 */
@Composable
internal fun TogetherStack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val g = LocalConsoleGeometry.current
    Layout(content, modifier) { measurables, constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        var bottom = 0
        var width = 0
        val placed = measurables.map { measurable ->
            val placeable = measurable.measure(childConstraints)
            val specY = (measurable.layoutId as? StackY)?.let { g.x(it.y).roundToPx() } ?: bottom
            val top = maxOf(specY, bottom)
            bottom = top + placeable.height
            width = maxOf(width, placeable.width)
            placeable to top
        }
        layout(
            width.coerceIn(constraints.minWidth, constraints.maxWidth),
            bottom.coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            placed.forEach { (placeable, top) -> placeable.place(0, top) }
        }
    }
}

/** The "Play together" eyebrow. Its canvas-scaled line height keeps it inside y 28 at Default. */
@Composable
internal fun TogetherEyebrow(modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    Text(TogetherCopy.eyebrow, color = ConsoleColors.Accent, fontSize = g.t(15), lineHeight = g.t(18), fontWeight = FontWeight.Bold, modifier = modifier)
}

/** Eyebrow (y 0), title (y 28) and subtitle (y 142) as [TogetherStack] children. */
@Composable
internal fun TogetherHeader(title: String, subtitle: String? = null) {
    val g = LocalConsoleGeometry.current
    var titleSize by remember(title) { mutableIntStateOf(44) }
    TogetherEyebrow(Modifier.stackY(0))
    Text(
        title.uppercase(),
        color = ConsoleColors.TextPrimary,
        fontSize = g.t(titleSize),
        lineHeight = g.t(titleSize + 2),
        fontWeight = FontWeight.Black,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow && titleSize > 28) titleSize -= 2 },
        modifier = Modifier.stackY(28).width(g.x(560)),
    )
    subtitle?.let {
        Text(
            it, color = ConsoleColors.TextSecondary, fontSize = g.t(17), fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.stackY(142),
        )
    }
}

@Composable
internal fun TogetherStatus(headline: String, detail: String?, message: String?) {
    val g = LocalConsoleGeometry.current
    Column(verticalArrangement = Arrangement.spacedBy(g.x(4))) {
        Text(headline, color = ConsoleColors.TextPrimary, fontSize = g.t(24), lineHeight = g.t(28), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, color = ConsoleColors.TextSecondary, fontSize = g.t(17), lineHeight = g.t(22), maxLines = 3, overflow = TextOverflow.Ellipsis) }
        message?.let { Text(it, color = ConsoleColors.Warning, fontSize = g.t(16), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Items wrap fully: at Larger the longest guest step needs a 4th line. */
@Composable
internal fun TogetherInstructions(items: List<String>) {
    val g = LocalConsoleGeometry.current
    Column(verticalArrangement = Arrangement.spacedBy(g.x(14))) {
        Text(TogetherCopy.inTheGame, color = ConsoleColors.TextTertiary, fontSize = g.t(15), fontWeight = FontWeight.Bold)
        items.forEachIndexed { i, item -> Text("${i + 1}. $item", color = ConsoleColors.TextPrimary, fontSize = g.t(20), lineHeight = g.t(24)) }
    }
}

@Composable
fun TogetherHostScreen(
    controller: TogetherController,
    gameId: Long,
    rail: RailHost,
    hints: ConsoleHints,
    onBack: () -> Unit,
    onOpenSystem: () -> Unit,
    onOpenPpsspp: () -> Unit,
    onOpenRetroArch: () -> Unit = {},
) {
    val view by controller.host.collectAsState()
    val g = LocalConsoleGeometry.current
    var seenLive by remember { mutableStateOf(false) }
    var popped by remember { mutableStateOf(false) }
    fun popOnce() { if (!popped) { popped = true; onBack() } }
    // Only a fresh entry (the 2P button) starts a session. A restored entry
    // (back stack restore, recreation, process death) remembers it already started,
    // and pops when that session is over or gone instead of hosting again.
    var started by rememberSaveable(gameId) { mutableStateOf(false) }
    LaunchedEffect(gameId) {
        val current = controller.host.value
        if (!started) {
            started = true
            if (current?.gameId != gameId || current.step == HostStep.ENDED) controller.hostGame(gameId)
        } else if (current == null || current.step == HostStep.ENDED) {
            popOnce()
        }
    }
    LaunchedEffect(view) {
        val current = view
        if (current != null && current.step != HostStep.ENDED) seenLive = true
        if (seenLive && (current == null || current.step == HostStep.ENDED)) popOnce()
    }
    val v = view
    val coreInstall = v?.coreInstall
    val problemAction = v?.problem?.action
    val showPrimary = coreInstall != null || when (v?.step) {
        HostStep.NOT_READY -> problemAction != null
        HostStep.IN_GAME -> v?.linkLost != true
        HostStep.CHECKING, HostStep.WAITING, HostStep.JOINED, HostStep.LAUNCHING -> true
        else -> false
    }
    val primaryEnabled = if (coreInstall != null) coreInstall.stalled else when (v?.step) {
        HostStep.NOT_READY -> problemAction != null
        HostStep.JOINED -> v?.canStart == true
        HostStep.IN_GAME -> v?.linkLost != true
        else -> false
    }
    val primaryLabel = if (coreInstall != null) {
        if (coreInstall.stalled) "Retry" else "Installing core…"
    } else when (v?.step) {
        HostStep.CHECKING -> TogetherCopy.checking
        HostStep.NOT_READY -> problemAction?.let(TogetherCopy::actionLabel).orEmpty()
        HostStep.WAITING -> TogetherCopy.waitingForPlayer2
        HostStep.JOINED -> TogetherCopy.start
        HostStep.LAUNCHING -> TogetherCopy.starting
        HostStep.IN_GAME -> if (v?.linkLost == true) "" else TogetherCopy.playAgain
        else -> ""
    }
    // Before a guest joins, X (or a tap) hosts the game in its next mode instead.
    val altMode = v?.altModes?.firstOrNull()?.takeIf {
        coreInstall == null && (v.step == HostStep.WAITING || v.step == HostStep.NOT_READY)
    }
    val secondaryLabel = if (coreInstall != null) TogetherCopy.cancel else when (v?.step) {
        HostStep.JOINED, HostStep.LAUNCHING, HostStep.IN_GAME -> TogetherCopy.end
        else -> TogetherCopy.cancel
    }
    val cancel: () -> Unit = {
        if (coreInstall != null) controller.act(TogetherAction.CANCEL_CORE_INSTALL) else controller.end()
    }
    val primary: () -> Unit = {
        if (coreInstall != null) {
            controller.act(if (coreInstall.stalled) TogetherAction.RETRY_CORE_INSTALL else TogetherAction.INSTALL_CORE)
            // Repeated A asks the engine to re-show cached progress, never starts another fetch.
        } else when (v?.step) {
            HostStep.JOINED -> if (primaryEnabled) controller.hostStart()
            HostStep.NOT_READY -> when (problemAction) {
                TogetherAction.OPEN_SYSTEM -> onOpenSystem()
                TogetherAction.OPEN_PPSSPP -> onOpenPpsspp()
                TogetherAction.OPEN_RETROARCH -> controller.act(TogetherAction.OPEN_RETROARCH)
                null -> Unit
                else -> controller.act(problemAction)
            }
            HostStep.IN_GAME -> if (v?.linkLost != true) controller.hostPlayAgain()
            else -> Unit
        }
    }
    val hintList = buildList {
        if (showPrimary && primaryEnabled && primaryLabel.isNotBlank()) add(ControllerHint(ControllerGlyph.A, primaryLabel))
        add(ControllerHint(ControllerGlyph.B, secondaryLabel))
        if (v?.step == HostStep.JOINED) add(ControllerHint(ControllerGlyph.Y, TogetherCopy.removeHint))
        altMode?.let { add(ControllerHint(ControllerGlyph.X, TogetherCopy.useModeInstead(it))) }
    }
    LaunchedEffect(hintList) { hints.set(hintList) }
    Box(
        Modifier.fillMaxSize().screenFocus(rememberScreenFocus()).consoleKeys(
            onLeft = { rail.enterRail() },
            onA = { if (primaryEnabled || coreInstall != null) primary(); true },
            onB = { cancel(); true },
            onY = { if (v?.step == HostStep.JOINED) { controller.hostRemoveGuest(); true } else false },
            onX = { altMode?.let { controller.hostSwitchMode(it); true } ?: false },
            onStart = { if (primaryEnabled || coreInstall != null) primary(); true },
        ),
    ) {
        TogetherFrame(
            left = { TogetherStack {
                TogetherHeader(v?.gameTitle?.takeIf { it.isNotBlank() } ?: "", v?.let { togetherSubtitle(it.folder, it.mode) })
                Column(Modifier.stackY(188).width(g.x(560)), verticalArrangement = Arrangement.spacedBy(g.x(10))) {
                    TogetherStatus(
                        if (coreInstall != null) "Installing core" else when (v?.step) {
                            HostStep.CHECKING -> TogetherCopy.checkingPhone
                            HostStep.NOT_READY -> TogetherCopy.notReady
                            HostStep.WAITING -> TogetherCopy.hostWaitingTitle
                            HostStep.JOINED -> TogetherCopy.guestJoined(v?.guestName ?: TogetherCopy.otherPhone)
                            HostStep.LAUNCHING -> TogetherCopy.starting
                            HostStep.IN_GAME -> if (v?.linkLost == true) v?.message ?: "" else TogetherCopy.playingWith(v?.guestName ?: TogetherCopy.otherPhone)
                            HostStep.ENDED, null -> ""
                        },
                        if (coreInstall != null) null else when (v?.step) {
                            HostStep.NOT_READY -> v?.problem?.text
                            HostStep.WAITING -> TogetherCopy.hostWaitingSub
                            // "Streaming to Sam · 60 fps · 7.9 Mbps".
                            HostStep.IN_GAME -> v?.streamLine
                            else -> null
                        }, null,
                    )
                    coreInstall?.let { CoreInstallStatus(it) }
                }
                if (v?.guestName != null && v?.step in setOf(HostStep.JOINED, HostStep.LAUNCHING, HostStep.IN_GAME)) {
                    Surface(
                        modifier = Modifier.stackY(332).width(g.x(560)).heightIn(min = g.x(116)),
                        shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_PANEL)),
                        color = ConsoleColors.Surface,
                        border = androidx.compose.foundation.BorderStroke(g.x(1), ConsoleColors.Steel),
                    ) {
                        Column(Modifier.padding(g.x(20))) {
                            Text(v?.guestName.orEmpty(), color = ConsoleColors.TextPrimary, fontSize = g.t(22), lineHeight = g.t(26), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(TogetherCopy.phaseLine(v?.guestPhase, v?.guestDetail, v?.guestProgress), color = togetherPhaseColor(v?.guestPhase), fontSize = g.t(17), lineHeight = g.t(22), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            v?.guestProgress?.let { progress ->
                                Spacer(Modifier.height(g.x(10)))
                                Box(Modifier.width(g.x(520)).height(g.x(6)).background(ConsoleColors.Steel)) {
                                    Box(Modifier.fillMaxWidth(progress.coerceIn(0, 100) / 100f).height(g.x(6)).background(ConsoleColors.Accent))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.stackY(476), horizontalArrangement = Arrangement.spacedBy(g.x(16))) {
                    if (showPrimary) {
                        PrimaryConsoleAction(primaryLabel, primaryEnabled, { if (primaryEnabled) primary() }, enabled = primaryEnabled, modifier = Modifier.heightIn(min = g.x(84)).testTag(HOST_PRIMARY_TAG))
                    }
                    SecondaryConsoleAction(secondaryLabel, false, cancel, Modifier.heightIn(min = g.x(84)))
                    altMode?.let { mode ->
                        SecondaryConsoleAction(
                            TogetherCopy.useModeInstead(mode),
                            false,
                            { controller.hostSwitchMode(mode) },
                            Modifier.heightIn(min = g.x(84)).testTag(HOST_ALT_MODE_TAG),
                        )
                    }
                }
                v?.message?.takeIf { coreInstall == null }?.let {
                    Text(it, color = ConsoleColors.Warning, fontSize = g.t(16), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.stackY(580).width(g.x(560)))
                }
            } },
            right = {
                if (v?.step == HostStep.WAITING && v?.qrText != null) TogetherStack {
                    QrCode(v?.qrText.orEmpty(), Modifier.stackY(48), g.x(300))
                    Text(TogetherCopy.qrCaption, color = ConsoleColors.TextSecondary, fontSize = g.t(16), lineHeight = g.t(20), modifier = Modifier.stackY(364))
                    Text(v?.myAddress?.let(TogetherCopy::thisPhone) ?: TogetherCopy.noWifiShort, color = ConsoleColors.TextPrimary, fontSize = g.t(22), fontWeight = FontWeight.Bold, modifier = Modifier.stackY(396))
                } else if (v?.step == HostStep.JOINED || v?.step == HostStep.LAUNCHING || v?.step == HostStep.IN_GAME) {
                    Box(Modifier.offset(y = g.x(48))) {
                        TogetherInstructions(v?.instructions.orEmpty().ifEmpty { TogetherCopy.hostInstructions(v?.mode) })
                    }
                }
            },
        )

    }
}

private fun platformName(folder: String): String = PlatformRegistry.byFolder[folder]?.display ?: folder
