package com.gamebex.client.ui.console

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.gamebex.client.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import com.gamebex.client.ui.GameBexIcons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints

enum class ConsoleDestination(
    val label: String,
    val icon: ImageVector,
    val route: String,
) {
    Home("Home", Icons.Filled.Home, "library"),
    Library("Library", Icons.Filled.List, "allgames"),
    Discover("Discover", GameBexIcons.Explore, "browse"),
    Downloads("Downloads", GameBexIcons.Download, "downloads"),
    System("System", Icons.Filled.Settings, "emulators"),
}

/** Handle content receives to yield focus to the rail at the left edge. */
interface RailHost {
    fun enterRail(): Boolean
}

/** Console shell; rail focus never reflows the content. */
@Composable
fun ConsoleScaffold(
    activeDestination: ConsoleDestination,
    onDestinationSelected: (ConsoleDestination) -> Unit,
    focusMemory: ConsoleFocusMemory,
    showRail: Boolean = true,
    /** Rail focus ends when this changes (the route) or the rail hides. */
    railResetKey: Any? = null,
    environment: @Composable BoxScope.() -> Unit = {},
    controllerHints: @Composable () -> Unit = {},
    content: @Composable BoxScope.(RailHost) -> Unit,
) {
    val g = LocalConsoleGeometry.current
    var railFocused by remember { mutableStateOf(false) }
    // Navigation that bypasses the rail (Home key, a notification, an invite)
    // must not leave an invisible rail cursor that eats A and the D-pad.
    LaunchedEffect(showRail, railResetKey) { railFocused = false }
    val showRailState = rememberUpdatedState(showRail)
    val railState = rememberShelfState(
        ConsoleDestination.entries.size,
        initial = activeDestination.ordinal,
    )
    // Keep the rail cursor on the live destination when not navigating it.
    if (!railFocused && railState.selected != activeDestination.ordinal) {
        railState.select(activeDestination.ordinal)
    }
    val host = remember {
        object : RailHost {
            override fun enterRail(): Boolean {
                // No rail on Home: there is nothing to enter.
                if (!showRailState.value) return false
                railFocused = true
                return true
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .consoleKeys(
                onUp = if (railFocused) {
                    { if (railState.selected > 0) railState.left() else false }
                } else null,
                onDown = if (railFocused) {
                    { railState.right() }
                } else null,
                onA = if (railFocused) {
                    {
                        onDestinationSelected(ConsoleDestination.entries[railState.selected])
                        railFocused = false
                        true
                    }
                } else null,
                onB = if (railFocused) {
                    { railFocused = false; true }
                } else null,
                onRight = if (railFocused) {
                    { railFocused = false; true }
                } else null,
                // Swallow Left while the rail owns input so it can't
                // leak into the content screen underneath.
                onLeft = if (railFocused) {
                    { true }
                } else null,
            ),
    ) {
        environment()
        Row(modifier = Modifier.fillMaxSize()) {
            if (showRail) {
                ConsoleRail(
                    active = activeDestination,
                    cursor = railState.selected,
                    railFocused = railFocused,
                    focusMemory = focusMemory,
                    onPick = {
                        onDestinationSelected(it)
                        railFocused = false
                    },
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                content(host)
            }
        }
        // The footer band is shell-owned, never inside a screen's scroll, so
        // rows can't collide with it.
        val hintLeft = if (showRail) ConsoleDesign.CONTENT_LEFT else ConsoleDesign.HINT_LEFT_FULL
        val hintRight = if (showRail) ConsoleDesign.CONTENT_RIGHT else ConsoleDesign.HINT_RIGHT_FULL
        // Hint pills are 48 tall inside an 84-tall hit box. Reserve the hit
        // height, centered on the band, so the 40-tall band doesn't clamp them.
        val hintMidY = (ConsoleDesign.HINT_TOP + ConsoleDesign.HINT_BOTTOM) / 2f
        val hintHitH = g.hitMin(g.x(84))
        Box(
            modifier = Modifier
                .offset(x = g.x(hintLeft), y = g.x(hintMidY) - hintHitH / 2)
                .size(g.x(hintRight - hintLeft), hintHitH),
            contentAlignment = Alignment.Center,
        ) {
            controllerHints()
        }
    }
}

@Composable
private fun ConsoleRail(
    active: ConsoleDestination,
    cursor: Int,
    railFocused: Boolean,
    focusMemory: ConsoleFocusMemory,
    onPick: (ConsoleDestination) -> Unit,
) {
    val g = LocalConsoleGeometry.current
    Column(
        modifier = Modifier
            .width(g.railWidth)
            .fillMaxHeight()
            .background(ConsoleColors.Background),
    ) {
        Box(
            modifier = Modifier
                .padding(start = g.x(20), top = g.x(20))
                .size(g.x(56)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.gamebex_icon_foreground),
                contentDescription = "GameBEX",
                modifier = Modifier.size(g.x(48)),
            )
        }
        Spacer(Modifier.height(g.x(28)))
        // A hit box inside the fixed 96x72 cell gets clamped to x(72) (41 dp
        // on a phone), so each cell is an outer hitMin-sized Box carrying the
        // click with the visual cell centred inside it. The measure policy
        // centres each hit Box on its i * x(72) slot instead of stacking, so
        // visual cells stay on their slots while hit boxes overhang (later
        // siblings win overlapping hit tests).
        val slot = g.x(72)
        val hitW = g.hitMin(g.railWidth)
        val hitH = g.hitMin(slot)
        Layout(
            modifier = Modifier.width(g.railWidth),
            content = {
                ConsoleDestination.entries.forEachIndexed { index, dest ->
                    val isActive = dest == active
                    val isCursor = index == cursor && railFocused
                    val lit = isActive || isCursor
                    Box(
                        modifier = Modifier
                            .size(hitW, hitH)
                            .semantics { selected = isActive }
                            .clickable { onPick(dest) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(g.railWidth, slot)
                                .background(
                                    when {
                                        isCursor && !isActive -> ConsoleColors.HighSurface
                                        isActive -> ConsoleColors.RailActive
                                        else -> Color.Transparent
                                    },
                                )
                                .then(
                                    if (isCursor) {
                                        Modifier.border(g.x(ConsoleDesign.FOCUS_STROKE), ConsoleColors.FocusRing)
                                    } else Modifier,
                                ),
                        ) {
                            Column(
                                modifier = Modifier.size(g.railWidth, slot).padding(top = g.x(10)),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    dest.icon, null,
                                    tint = when {
                                        isActive -> ConsoleColors.Accent
                                        isCursor -> ConsoleColors.TextPrimary
                                        else -> ConsoleColors.TextTertiary
                                    },
                                    modifier = Modifier.size(g.x(24)),
                                )
                                Spacer(Modifier.height(g.x(8)))
                                Text(
                                    dest.label,
                                    color = if (lit) ConsoleColors.TextPrimary else ConsoleColors.TextTertiary,
                                    fontSize = g.tMin(12),
                                    lineHeight = g.tMin(14),
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                )
                            }
                            if (isActive) {
                                Box(
                                    Modifier
                                        .padding(top = g.x(8))
                                        .size(g.x(4), g.x(56))
                                        .background(ConsoleColors.Accent),
                                )
                            }
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val slotPx = slot.roundToPx()
            val railPx = g.railWidth.roundToPx()
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(railPx, slotPx * placeables.size) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(
                        x = railHitInset(railPx, placeable.width),
                        y = index * slotPx + railHitInset(slotPx, placeable.height),
                    )
                }
            }
        }
    }
    focusMemory.store("rail", cursor)
}

/**
 * Top-left inset of a [hitPx] hit box centred on a [slotPx] slot (negative
 * when the hit box overhangs). It is the exact negation of `BiasAlignment`'s
 * `Math.round(diff / 2f)`, so the visual cell centred inside the hit box
 * lands back on the slot origin. [RailCellLayoutTest] pins this.
 */
internal fun railHitInset(slotPx: Int, hitPx: Int): Int = -Math.round((hitPx - slotPx) / 2f)
