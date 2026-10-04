package com.gamebex.client.ui.together

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.TogetherController
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.SecondaryConsoleAction
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.screenFocus

/**
 * The Play together hub: host a game from the library (left) or join one
 * (right: nearby hosts, Join by address, this phone's address and Rename).
 * The D-pad moves between the host list, the Nearby list and the address
 * button ([TogetherHubFocus]); every row and button also takes a tap.
 */
@Composable
fun TogetherHubScreen(
    controller: TogetherController,
    rail: RailHost,
    hints: ConsoleHints,
    onBack: () -> Unit,
    onJoined: () -> Unit,
    /** The "Host a game" rows ([hostableGames]); null while the library loads. */
    hostable: List<HostableGame>? = emptyList(),
    /** Hosts the game with this id, as the game page's 2P button does. */
    onHost: (Long) -> Unit = {},
) {
    val nearby by controller.nearby.collectAsState()
    val address by controller.myAddress.collectAsState()
    val games = hostable.orEmpty()
    var focus by remember { mutableStateOf(HubFocus()) }
    var hostStart by remember { mutableIntStateOf(0) }
    var nearbyStart by remember { mutableIntStateOf(0) }
    var showAddress by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    val myName by controller.myName.collectAsState()
    val customName by controller.customName.collectAsState()
    DisposableEffect(Unit) { controller.startBrowsing(); onDispose { controller.stopBrowsing() } }
    val g = LocalConsoleGeometry.current
    val message by TogetherUi.hubMessage.collectAsState()
    // The zone the cursor can really sit in: an empty list hands it on (TogetherHubFocus.resolve).
    fun current() = TogetherHubFocus.resolve(focus, games.size, nearby.size)
    val at = current()
    LaunchedEffect(at.zone) {
        hints.set(
            listOf(
                ControllerHint(ControllerGlyph.A, TogetherHubFocus.aLabel(at.zone)),
                ControllerHint(ControllerGlyph.B, TogetherCopy.back),
                ControllerHint(ControllerGlyph.Y, TogetherCopy.rename),
            ),
        )
    }
    fun join(target: NearbyHost) { TogetherUi.hubMessage.value = null; controller.join(target); onJoined() }
    fun host(game: HostableGame) { TogetherUi.hubMessage.value = null; onHost(game.id) }
    /** Moves the cursor; true when it really moved (an edge press stays unconsumed). */
    fun moveTo(next: HubFocus): Boolean { val moved = next != current(); focus = next; return moved }
    Box(Modifier.fillMaxSize().screenFocus(rememberScreenFocus()).consoleKeys(
        onLeft = { TogetherHubFocus.left(focus, games.size, nearby.size)?.let(::moveTo) ?: rail.enterRail() },
        onRight = { moveTo(TogetherHubFocus.right(focus, games.size, nearby.size)) },
        onUp = { moveTo(TogetherHubFocus.up(focus, games.size, nearby.size)) },
        onDown = { moveTo(TogetherHubFocus.down(focus, games.size, nearby.size)) },
        onA = {
            val now = current()
            when (now.zone) {
                HubZone.HOST -> games.getOrNull(now.host)?.let(::host)
                HubZone.NEARBY -> nearby.getOrNull(now.nearby)?.let(::join)
                HubZone.ADDRESS -> showAddress = true
            }
            true
        },
        onB = { onBack(); true },
        onY = { showRename = true; true },
    )) {
        val rowPx = with(LocalDensity.current) { g.x(84 + 8).toPx() }
        TogetherFrame(
            left = { TogetherStack {
                val visibleStart = togetherWindowStart(at.host, games.size, TogetherHubFocus.HOST_ROWS, hostStart)
                SideEffect { if (hostStart != visibleStart) hostStart = visibleStart }
                TogetherEyebrow(Modifier.stackY(0))
                Text(TogetherCopy.hostTitle, color = ConsoleColors.TextPrimary, fontSize = g.t(34), lineHeight = g.t(40), fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.stackY(28))
                Box(Modifier.stackY(92).width(g.x(640))) {
                    // Nothing while the library loads; the empty line only once it has answered.
                    if (hostable != null && games.isEmpty()) {
                        Text(TogetherCopy.hostEmpty, color = ConsoleColors.TextSecondary, fontSize = g.t(18), lineHeight = g.t(24), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    } else if (games.isNotEmpty()) Column(
                        Modifier.dragToStep(rowPx, games) { d ->
                            val row = focus.host.coerceIn(0, games.lastIndex)
                            focus = focus.copy(zone = HubZone.HOST, host = (row + d).coerceIn(0, games.lastIndex))
                        },
                        verticalArrangement = Arrangement.spacedBy(g.x(8)),
                    ) {
                        games.drop(visibleStart).take(TogetherHubFocus.HOST_ROWS).forEachIndexed { offset, game ->
                            val index = visibleStart + offset
                            HubRow(game.title, hostRowLine(game), at.zone == HubZone.HOST && index == at.host, 640) {
                                focus = focus.copy(zone = HubZone.HOST, host = index)
                                host(game)
                            }
                        }
                    }
                }
            } },
            right = {
                TogetherStack(Modifier.offset(x = g.x(40)).width(g.x(560))) {
                    val visibleStart = togetherWindowStart(at.nearby, nearby.size, TogetherHubFocus.NEARBY_ROWS, nearbyStart)
                    SideEffect { if (nearbyStart != visibleStart) nearbyStart = visibleStart }
                    Text(TogetherCopy.nearby, color = ConsoleColors.TextPrimary, fontSize = g.t(34), lineHeight = g.t(40), fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.stackY(28))
                    Box(Modifier.stackY(92).width(g.x(560))) {
                        if (nearby.isEmpty()) Text(TogetherCopy.hubEmpty, color = ConsoleColors.TextSecondary, fontSize = g.t(18), lineHeight = g.t(24), maxLines = 4, overflow = TextOverflow.Ellipsis)
                        else Column(
                            Modifier.dragToStep(rowPx, nearby.size) { d ->
                                val row = focus.nearby.coerceIn(0, nearby.lastIndex)
                                focus = focus.copy(zone = HubZone.NEARBY, nearby = (row + d).coerceIn(0, nearby.lastIndex))
                            },
                            verticalArrangement = Arrangement.spacedBy(g.x(8)),
                        ) {
                            nearby.drop(visibleStart).take(TogetherHubFocus.NEARBY_ROWS).forEachIndexed { offset, target ->
                                val index = visibleStart + offset
                                HubRow(target.name, "${target.gameTitle} · ${togetherPlatformName(target.folder)}", at.zone == HubZone.NEARBY && index == at.nearby, 560) {
                                    focus = focus.copy(zone = HubZone.NEARBY, nearby = index)
                                    join(target)
                                }
                            }
                        }
                    }
                    Text(TogetherCopy.thisPhoneLabel, color = ConsoleColors.TextTertiary, fontSize = g.t(15), lineHeight = g.t(18), fontWeight = FontWeight.Bold, modifier = Modifier.stackY(376))
                    Text(address ?: TogetherCopy.noWifiShort, color = ConsoleColors.TextPrimary, fontSize = g.t(28), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.stackY(400))
                    myName.takeIf { it.isNotBlank() }?.let { name ->
                        Text(
                            TogetherCopy.shownAs(name), color = ConsoleColors.TextSecondary, fontSize = g.t(17), lineHeight = g.t(22),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.stackY(446),
                        )
                    }
                    // Rename is Y (hint) or a tap, like the other screens' B/Y buttons; D-pad focus stays on Join by address.
                    Row(Modifier.stackY(484), horizontalArrangement = Arrangement.spacedBy(g.x(16))) {
                        SecondaryConsoleAction(TogetherCopy.joinByAddress, at.zone == HubZone.ADDRESS, { showAddress = true }, Modifier.heightIn(min = g.x(84)))
                        SecondaryConsoleAction(TogetherCopy.rename, false, { showRename = true }, Modifier.heightIn(min = g.x(84)))
                    }
                    message?.let { Text(it, color = ConsoleColors.Error, fontSize = g.t(16), modifier = Modifier.stackY(584)) }
                }
            },
        )
        if (showRename) {
            ConsoleNameDialog(
                customName = customName,
                onDismiss = { showRename = false },
                onSave = { name -> controller.rename(name); showRename = false },
            )
        }
        if (showAddress) {
            JoinAddressDialog(
                onDismiss = { showAddress = false },
                onJoin = { text ->
                    TogetherUi.hubMessage.value = null
                    val result = controller.joinAddress(text)
                    if (result == null) { showAddress = false; onJoined() } else TogetherUi.hubMessage.value = result
                    result
                },
            )
        }
    }
}

/** A hub list row: bold first line, secondary second line. 84 tall at Default; it grows rather than clip its second line. */
@Composable
private fun HubRow(title: String, line: String, focused: Boolean, width: Int, onClick: () -> Unit) {
    val g = LocalConsoleGeometry.current
    Row(
        Modifier.width(g.x(width)).heightIn(min = g.x(84))
            .background(if (focused) ConsoleColors.HighSurface else Color.Transparent)
            .border(if (focused) g.x(ConsoleDesign.FOCUS_STROKE) else g.x(1), if (focused) ConsoleColors.FocusRing else ConsoleColors.Steel, RoundedCornerShape(g.x(8)))
            .clickable(onClick = onClick)
            .padding(horizontal = g.x(16)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, color = ConsoleColors.TextPrimary, fontSize = g.t(20), lineHeight = g.t(24), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, color = ConsoleColors.TextSecondary, fontSize = g.t(15), lineHeight = g.t(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
