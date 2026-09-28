package com.gamebex.client.ui.together

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.TogetherController
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleDialog
import com.gamebex.client.ui.console.ConsoleDialogButton
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

@Composable
fun TogetherHubScreen(
    controller: TogetherController,
    rail: RailHost,
    hints: ConsoleHints,
    onBack: () -> Unit,
    onJoined: () -> Unit,
) {
    val nearby by controller.nearby.collectAsState()
    val address by controller.myAddress.collectAsState()
    var zone by remember(nearby.isNotEmpty()) { mutableIntStateOf(if (nearby.isEmpty()) 1 else 0) }
    var selected by remember { mutableIntStateOf(0) }
    var hubStart by remember { mutableIntStateOf(0) }
    var showAddress by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    val myName by controller.myName.collectAsState()
    val customName by controller.customName.collectAsState()
    DisposableEffect(Unit) { controller.startBrowsing(); onDispose { controller.stopBrowsing() } }
    val g = LocalConsoleGeometry.current
    val message by TogetherUi.hubMessage.collectAsState()
    LaunchedEffect(nearby.size) { selected = selected.coerceIn(0, (nearby.lastIndex).coerceAtLeast(0)) }
    LaunchedEffect(zone) {
        hints.set(
            listOf(
                ControllerHint(ControllerGlyph.A, if (zone == 0) TogetherCopy.join else TogetherCopy.joinByAddress),
                ControllerHint(ControllerGlyph.B, TogetherCopy.back),
                ControllerHint(ControllerGlyph.Y, TogetherCopy.rename),
            ),
        )
    }
    fun join(target: NearbyHost) { TogetherUi.hubMessage.value = null; controller.join(target); onJoined() }
    Box(Modifier.fillMaxSize().screenFocus(rememberScreenFocus()).consoleKeys(
        onLeft = { if (zone == 1 && nearby.isNotEmpty()) { zone = 0; true } else rail.enterRail() },
        onRight = { if (zone == 0) { zone = 1; true } else false },
        onUp = { if (zone == 0 && selected > 0) { selected--; true } else false },
        onDown = { if (zone == 0 && selected < nearby.lastIndex) { selected++; true } else false },
        onA = { if (zone == 0) nearby.getOrNull(selected)?.let(::join) else showAddress = true; true },
        onB = { onBack(); true },
        onY = { showRename = true; true },
    )) {
        TogetherFrame(
            left = { TogetherStack {
                val visibleStart = togetherWindowStart(selected, nearby.size, 5, hubStart)
                SideEffect { if (hubStart != visibleStart) hubStart = visibleStart }
                TogetherEyebrow(Modifier.stackY(0))
                Text(TogetherCopy.nearby, color = ConsoleColors.TextPrimary, fontSize = g.t(34), lineHeight = g.t(40), fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.stackY(28))
                Box(Modifier.stackY(92).width(g.x(640))) {
                    if (nearby.isEmpty()) Text(TogetherCopy.hubEmpty, color = ConsoleColors.TextSecondary, fontSize = g.t(18), lineHeight = g.t(24), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    else Column(
                        Modifier.dragToStep(
                            with(androidx.compose.ui.platform.LocalDensity.current) { g.x(84 + 8).toPx() },
                            nearby.size,
                        ) { d -> zone = 0; selected = (selected + d).coerceIn(0, nearby.lastIndex) },
                        verticalArrangement = Arrangement.spacedBy(g.x(8)),
                    ) {
                        nearby.drop(visibleStart).take(5).forEachIndexed { offset, host ->
                            val index = visibleStart + offset
                            val focused = zone == 0 && index == selected
                            // 84 tall at Default; a row grows rather than clip its second line.
                            Row(
                                Modifier.width(g.x(640)).heightIn(min = g.x(84))
                                    .background(if (focused) ConsoleColors.HighSurface else Color.Transparent)
                                    .border(if (focused) g.x(ConsoleDesign.FOCUS_STROKE) else g.x(1), if (focused) ConsoleColors.FocusRing else ConsoleColors.Steel, RoundedCornerShape(g.x(8)))
                                    .clickable { selected = index; join(host) }
                                    .padding(horizontal = g.x(16)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(host.name, color = ConsoleColors.TextPrimary, fontSize = g.t(20), lineHeight = g.t(24), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${host.gameTitle} · ${togetherPlatformName(host.folder)}", color = ConsoleColors.TextSecondary, fontSize = g.t(15), lineHeight = g.t(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            } },
            right = {
                TogetherStack(Modifier.offset(x = g.x(40)).width(g.x(560))) {
                    Text(TogetherCopy.thisPhoneLabel, color = ConsoleColors.TextTertiary, fontSize = g.t(15), lineHeight = g.t(18), fontWeight = FontWeight.Bold, modifier = Modifier.stackY(92))
                    Text(address ?: TogetherCopy.noWifiShort, color = ConsoleColors.TextPrimary, fontSize = g.t(28), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.stackY(120))
                    myName.takeIf { it.isNotBlank() }?.let { name ->
                        Text(
                            TogetherCopy.shownAs(name), color = ConsoleColors.TextSecondary, fontSize = g.t(17), lineHeight = g.t(22),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.stackY(166),
                        )
                    }
                    // Rename is Y (hint) or a tap, like the other screens' B/Y buttons; D-pad focus stays on Join.
                    Row(Modifier.stackY(208), horizontalArrangement = Arrangement.spacedBy(g.x(16))) {
                        SecondaryConsoleAction(TogetherCopy.joinByAddress, zone == 1, { showAddress = true }, Modifier.heightIn(min = g.x(84)))
                        SecondaryConsoleAction(TogetherCopy.rename, false, { showRename = true }, Modifier.heightIn(min = g.x(84)))
                    }
                    message?.let { Text(it, color = ConsoleColors.Error, fontSize = g.t(16), modifier = Modifier.stackY(308)) }
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

