package com.gamebex.client.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.gamebex.client.stream.StreamFlagDef
import com.gamebex.client.stream.StreamFlagGroup
import com.gamebex.client.stream.StreamFlagPrefs
import com.gamebex.client.stream.StreamFlagStore
import com.gamebex.client.stream.StreamFlagType
import com.gamebex.client.stream.StreamFlags
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleFocusMemory
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.PrimaryConsoleAction
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.SecondaryConsoleAction
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.isSystemReducedMotion
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.rememberShelfState
import com.gamebex.client.ui.console.screenFocus

/** Effective raw value for a row: the stored override, else the built-in default. */
fun flagEffective(def: StreamFlagDef, stored: String?): String =
    stored?.takeIf { it.isNotEmpty() } ?: def.default

/** Display label for an effective raw value. */
fun flagValueLabel(def: StreamFlagDef, effective: String): String = when (val t = def.type) {
    is StreamFlagType.Toggle -> if (effective == "1") "On" else "Off"
    is StreamFlagType.Choice -> {
        val i = t.values.indexOf(effective)
        if (i >= 0) t.labels[i] else effective
    }
}

/** Next raw value when cycling: toggles flip, choices wrap around. */
fun flagCycle(def: StreamFlagDef, effective: String, dir: Int): String = when (val t = def.type) {
    is StreamFlagType.Toggle -> if (effective == "1") "0" else "1"
    is StreamFlagType.Choice -> {
        val i = t.values.indexOf(effective)
            .takeIf { it >= 0 } ?: t.values.indexOf(def.default).coerceAtLeast(0)
        t.values[(i + dir).mod(t.values.size)]
    }
}

/**
 * Stream flags (Settings/System → Device → Advanced → Stream flags): every
 * Stream improvement behind a per-phone switch, no adb needed. D-pad + touch;
 * changes apply to the next stream.
 */
@Composable
fun StreamFlagsScreen(
    onBack: () -> Unit,
    rail: RailHost,
    focusMemory: ConsoleFocusMemory,
    hints: ConsoleHints? = null,
    store: StreamFlagStore? = null,
) {
    val g = LocalConsoleGeometry.current
    val context = LocalContext.current
    val prefs = remember(store) { store ?: StreamFlagPrefs(context) }
    val reducedMotion = isSystemReducedMotion()

    var values by remember { mutableStateOf(readFlagValues(prefs)) }
    fun refresh() {
        values = readFlagValues(prefs)
    }

    val flags = remember { StreamFlags.ALL }
    LaunchedEffect(Unit) {
        hints?.set(
            listOf(
                ControllerHint(ControllerGlyph.A, "Change"),
                ControllerHint(ControllerGlyph.LB, "Previous value"),
                ControllerHint(ControllerGlyph.RB, "Next value"),
                ControllerHint(ControllerGlyph.B, "Back"),
            ),
        )
    }

    val rows = rememberShelfState(flags.size, focusMemory.recall("stream-flags:row", 0))
    if (flags.isNotEmpty()) focusMemory.store("stream-flags:row", rows.selected)
    var zone by remember { mutableIntStateOf(1) } // 0 = presets, 1 = list
    var presetIdx by remember { mutableIntStateOf(0) } // 0 = defaults, 1 = promising

    fun changeSelected(dir: Int): Boolean {
        val def = flags.getOrNull(rows.selected) ?: return false
        prefs.set(def.key, flagCycle(def, flagEffective(def, values[def.key]), dir))
        refresh()
        return true
    }

    fun runPreset(i: Int): Boolean {
        if (i == 0) prefs.resetAll() else prefs.setPromising()
        refresh()
        return true
    }

    val listState = rememberLazyListState()
    // Lazy indices interleave group headers; map the flag index to its item.
    val lazyIndexOf = remember(flags) {
        val map = HashMap<Int, Int>()
        var lazy = 0
        var lastGroup: StreamFlagGroup? = null
        flags.forEachIndexed { i, def ->
            if (def.group != lastGroup) {
                lastGroup = def.group
                lazy++
            }
            map[i] = lazy
            lazy++
        }
        map
    }
    LaunchedEffect(rows.selected) {
        val i = lazyIndexOf[rows.selected] ?: return@LaunchedEffect
        if (reducedMotion) listState.scrollToItem(i) else listState.animateScrollToItem(i)
    }

    val screenFocus = rememberScreenFocus()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("stream-flags-screen")
            .screenFocus(screenFocus)
            .consoleKeys(
                onLeft = {
                    if (zone == 0 && presetIdx > 0) {
                        presetIdx -= 1; true
                    } else if (zone == 0) {
                        rail.enterRail()
                    } else {
                        rail.enterRail()
                    }
                },
                onRight = {
                    if (zone == 0 && presetIdx < 1) {
                        presetIdx += 1; true
                    } else false
                },
                onUp = {
                    if (zone == 1 && rows.selected > 0) rows.left()
                    else if (zone == 1) {
                        zone = 0; true
                    } else false
                },
                onDown = {
                    if (zone == 0) {
                        zone = 1; true
                    } else rows.right()
                },
                onA = {
                    if (zone == 0) runPreset(presetIdx) else changeSelected(1)
                },
                onB = { onBack(); true },
                onLB = { if (zone == 1) changeSelected(-1) else false },
                onRB = { if (zone == 1) changeSelected(1) else false },
            ),
    ) {
        Column(modifier = Modifier.offset(x = g.x(48), y = g.x(30)).width(g.x(1300))) {
            Text(
                "ADVANCED",
                color = ConsoleColors.TextTertiary, fontSize = g.tMin(15),
                lineHeight = g.tMin(18), fontWeight = FontWeight.Bold,
            )
            Text(
                "STREAM FLAGS",
                color = ConsoleColors.TextPrimary, fontSize = g.t(36),
                lineHeight = g.t(40), fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = g.x(8)),
            )
            Text(
                "Set on this phone · changes apply to the next stream",
                color = ConsoleColors.TextSecondary, fontSize = g.t(17),
                lineHeight = g.t(20), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = g.x(4)),
            )
            Row(
                modifier = Modifier.padding(top = g.x(14)),
                horizontalArrangement = Arrangement.spacedBy(g.x(16)),
            ) {
                SecondaryConsoleAction(
                    label = "Defaults",
                    focused = zone == 0 && presetIdx == 0,
                    onClick = { runPreset(0) },
                    modifier = Modifier.testTag("flags-defaults"),
                )
                PrimaryConsoleAction(
                    label = "Everything promising ON",
                    focused = zone == 0 && presetIdx == 1,
                    onClick = { runPreset(1) },
                    controllerGlyph = null,
                    modifier = Modifier.testTag("flags-promising"),
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.offset(x = g.x(48), y = g.x(258)).width(g.x(1300)).height(g.x(390))
                .testTag("stream-flags-list"),
        ) {
            var lastGroup: StreamFlagGroup? = null
            flags.forEachIndexed { i, def ->
                if (def.group != lastGroup) {
                    lastGroup = def.group
                    item(key = "group:${def.group}") {
                        Text(
                            def.group.title.uppercase(),
                            color = ConsoleColors.TextTertiary, fontSize = g.tMin(14),
                            lineHeight = g.tMin(18), fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = g.x(if (i == 0) 0 else 14), bottom = g.x(6)),
                        )
                    }
                }
                item(key = "flag:${def.key}") {
                    val effective = flagEffective(def, values[def.key])
                    val focused = zone == 1 && rows.selected == i
                    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD))
                    Row(
                        modifier = Modifier
                            .heightIn(min = g.x(72))
                            .clip(shape)
                            .background(if (focused) ConsoleColors.HighSurface else ConsoleColors.Elevated)
                            .then(
                                if (focused) {
                                    Modifier.border(
                                        g.x(ConsoleDesign.FOCUS_STROKE), ConsoleColors.FocusRing, shape,
                                    )
                                } else Modifier,
                            )
                            .clickable {
                                rows.select(i)
                                zone = 1
                                prefs.set(def.key, flagCycle(def, effective, 1))
                                refresh()
                            }
                            .testTag("flag-row-${def.key}")
                            .padding(horizontal = g.x(16), vertical = g.x(10)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                def.label,
                                color = ConsoleColors.TextPrimary, fontSize = g.t(17),
                                lineHeight = g.t(22), fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${def.side.label} · ${def.description}",
                                color = ConsoleColors.TextSecondary, fontSize = g.tMin(14),
                                lineHeight = g.tMin(18), maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            flagValueLabel(def, effective),
                            color = if (effective != def.default) ConsoleColors.Accent else ConsoleColors.TextTertiary,
                            fontSize = g.t(17),
                            lineHeight = g.t(22), fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = g.x(16))
                                .testTag("flag-value-${def.key}"),
                        )
                    }
                }
            }
        }
    }
}

private fun readFlagValues(store: StreamFlagStore): Map<String, String?> =
    StreamFlags.ALL.associate { it.key to store.get(it.key) }
