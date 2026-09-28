package com.gamebex.client.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.gamebex.client.sources.SourceDetail
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.ControllerHintBar
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.PrimaryConsoleAction
import com.gamebex.client.ui.console.QuietConsoleAction
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.rememberShelfState
import com.gamebex.client.ui.console.screenFocus

private data class DetailAction(val label: String, val primary: Boolean, val run: () -> Unit)

/**
 * Installed-source detail: unverified author metadata, version, source
 * ID, declared origins by purpose, capabilities, and actions. First
 * focus is the primary action; Back returns to the invoking row.
 */
@Composable
fun SourceDetailView(
    vm: SourcesViewModel,
    railHost: RailHost?,
    selectedId: String?,
    onBack: () -> Unit,
    onReplace: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val detail by vm.detail.collectAsState()
    val impact by vm.impact.collectAsState()
    val message by vm.message.collectAsState()
    val importState by vm.importState.collectAsState()
    val d = detail ?: return
    val focus = rememberScreenFocus()

    val actions = remember(d, selectedId) { detailActions(vm, d, selectedId, onReplace) }
    val shelf = rememberShelfState(maxOf(actions.size, 1))
    var dialogChoice by remember(impact, message, importState) { mutableStateOf(0) }
    val dialogOpen = importState !is ImportState.Idle || impact != null || message != null

    fun dialogPrimary(): Boolean {
        val im = impact
        return when {
            importState is ImportState.Preview -> {
                vm.confirmImport(); true
            }
            importState is ImportState.Error -> {
                vm.dismissError(); true
            }
            im != null -> {
                if (im.removing) vm.remove(im.id) else vm.setEnabled(im.id, false)
                true
            }
            message != null -> {
                vm.consumeMessage(); true
            }
            else -> false
        }
    }

    fun dialogCancel(): Boolean {
        if (importState is ImportState.Preview || importState is ImportState.Error) {
            vm.cancelPreview()
            vm.dismissError()
            return true
        }
        if (importState is ImportState.Reading || importState is ImportState.Installing) return false
        if (impact != null) {
            vm.clearImpact(); return true
        }
        if (message != null) {
            vm.consumeMessage(); return true
        }
        return false
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .screenFocus(focus)
            .consoleKeys(
                onUp = { if (dialogOpen) false else shelf.left() },
                onDown = { if (dialogOpen) false else shelf.right() },
                onLeft = {
                    if (dialogOpen) {
                        dialogChoice = 0; true
                    } else railHost?.enterRail() ?: false
                },
                onRight = { if (dialogOpen) { dialogChoice = 1; true } else false },
                onA = {
                    if (dialogOpen) {
                        if (dialogChoice == 0) dialogPrimary() else dialogCancel()
                    } else actions.getOrNull(shelf.selected)?.let { it.run(); true } ?: false
                },
                onB = { if (dialogCancel()) true else { onBack(); true } },
            )
            .padding(start = g.x(40), end = g.x(120), top = g.contentTop, bottom = g.x(56)),
        verticalArrangement = Arrangement.spacedBy(g.x(16)),
    ) {
        Text(
            d.name, color = ConsoleColors.TextPrimary,
            fontSize = g.t(34), fontWeight = FontWeight.Bold,
        )
        Text(
            "By ${d.author} (unverified) · v${d.version} · id ${d.id}",
            color = ConsoleColors.TextSecondary, fontSize = g.t(18),
        )
        if (!d.available) {
            Text(
                d.reason ?: "Unavailable",
                color = ConsoleColors.Warning, fontSize = g.t(18),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(g.x(12)),
        ) {
            item(key = "origins") {
                DetailCard(title = "Declared origins") {
                    if (d.origins.isEmpty()) {
                        Text("Unreadable.", color = ConsoleColors.TextTertiary, fontSize = g.t(17))
                    } else {
                        d.origins.forEach { (purpose, origins) ->
                            Text(
                                purpose, color = ConsoleColors.TextTertiary,
                                fontSize = g.t(15), fontWeight = FontWeight.Bold,
                            )
                            if (origins.isEmpty()) {
                                Text("  none", color = ConsoleColors.TextTertiary, fontSize = g.t(17))
                            } else {
                                origins.forEach {
                                    Text("  $it", color = ConsoleColors.TextSecondary, fontSize = g.t(17))
                                }
                            }
                        }
                    }
                }
            }
            item(key = "caps") {
                DetailCard(title = "Capabilities") {
                    if (d.capabilities.isEmpty()) {
                        Text("none", color = ConsoleColors.TextTertiary, fontSize = g.t(17))
                    } else {
                        Text(
                            d.capabilities.joinToString(", "),
                            color = ConsoleColors.TextSecondary, fontSize = g.t(17),
                        )
                    }
                }
            }
            item(key = "actions") {
                DetailCard(title = "Actions") {
                    actions.forEachIndexed { i, a ->
                        val focused = i == shelf.selected && !dialogOpen
                        if (a.primary) {
                            PrimaryConsoleAction(a.label, focused, onClick = a.run)
                        } else {
                            QuietConsoleAction(a.label, focused, onClick = a.run)
                        }
                    }
                }
            }
        }
        ControllerHintBar(
            listOf(
                ControllerHint(ControllerGlyph.A, "Run"),
                ControllerHint(ControllerGlyph.B, "Back"),
            ),
        )
    }

    if (dialogOpen) {
        SourcesDialog(
            importState = importState,
            impact = impact,
            message = message,
            choice = dialogChoice,
            onChoice = { dialogChoice = it },
            onPrimary = { dialogPrimary() },
            onCancel = { dialogCancel() },
        )
    }
}

private fun detailActions(
    vm: SourcesViewModel,
    d: SourceDetail,
    selectedId: String?,
    onReplace: () -> Unit,
): List<DetailAction> = buildList {
    if (d.available && d.enabled && d.id != selectedId) {
        add(DetailAction("Select", primary = true) { vm.select(d.id) })
    }
    if (d.available && d.enabled) {
        add(
            DetailAction("Disable", primary = d.id == selectedId) {
                vm.loadImpact(d.id, removing = false)
            },
        )
    } else if (d.available && !d.enabled) {
        add(DetailAction("Enable", primary = true) { vm.setEnabled(d.id, true) })
    }
    add(DetailAction("Replace from file", primary = false, run = onReplace))
    add(
        DetailAction("Remove", primary = false) {
            vm.loadImpact(d.id, removing = true)
        },
    )
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    val g = LocalConsoleGeometry.current
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(g.x(12)))
            .background(ConsoleColors.Elevated)
            .padding(horizontal = g.x(20), vertical = g.x(14)),
        verticalArrangement = Arrangement.spacedBy(g.x(8)),
    ) {
        Text(
            title, color = ConsoleColors.TextTertiary,
            fontSize = g.t(15), fontWeight = FontWeight.Bold,
        )
        content()
    }
}

