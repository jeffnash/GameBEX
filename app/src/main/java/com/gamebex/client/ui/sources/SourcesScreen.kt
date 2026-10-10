package com.gamebex.client.ui.sources

import androidx.compose.runtime.mutableIntStateOf
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.gamebex.client.sources.InstalledSource
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleFocusMemory
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.ControllerHintBar
import com.gamebex.client.ui.console.FollowSelected
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.PrimaryConsoleAction
import com.gamebex.client.ui.console.QuietConsoleAction
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.rememberShelfState
import com.gamebex.client.ui.console.screenFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One status per row, derived from the installed entry + selection. Pure; tested. */
enum class SourceRowStatus { Selected, Enabled, Disabled, Incompatible, Unavailable }

fun sourceRowStatus(entry: InstalledSource, selectedId: String?): SourceRowStatus = when {
    !entry.available && entry.reason?.contains("incompatible") == true -> SourceRowStatus.Incompatible
    !entry.available -> SourceRowStatus.Unavailable
    !entry.enabled -> SourceRowStatus.Disabled
    entry.id == selectedId -> SourceRowStatus.Selected
    else -> SourceRowStatus.Enabled
}

fun SourceRowStatus.label(): String = when (this) {
    SourceRowStatus.Selected -> "Selected"
    SourceRowStatus.Enabled -> "Enabled"
    SourceRowStatus.Disabled -> "Disabled"
    SourceRowStatus.Incompatible -> "Incompatible"
    SourceRowStatus.Unavailable -> "Unavailable"
}

/** Picker MIME set: providers label JSON differently; bytes are validated regardless. */
private val PICKER_MIMES = arrayOf("application/json", "text/plain", "application/octet-stream")

/** Bounded picker read: definitions are small; oversized picks are rejected. */
private suspend fun readPickerBytes(
    context: android.content.Context,
    uri: Uri,
    capBytes: Long = 4L * 1024 * 1024,
): ByteArray? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openInputStream(uri)?.use { inp ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                total += n
                if (total > capBytes) return@withContext null
                out.write(buf, 0, n)
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }
}

private sealed interface FocusItem {
    data object Import : FocusItem
    data class Row(val id: String) : FocusItem
}

/**
 * Sources management (console). Header + import action, installed rows
 * with one status each, a static built-in BIOS row, and detail/impact/
 * import dialogs. Back closes the topmost layer first.
 */
@Composable
fun SourcesScreen(
    vm: SourcesViewModel,
    railHost: RailHost?,
    focusMemory: ConsoleFocusMemory,
    onBack: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by vm.installed.collectAsState()
    val selected by vm.selected.collectAsState()
    val importState by vm.importState.collectAsState()
    val detail by vm.detail.collectAsState()
    val impact by vm.impact.collectAsState()
    val message by vm.message.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            vm.onPickerBytes(null)
        } else {
            scope.launch {
                vm.onPickerBytes(readPickerBytes(context, uri))
            }
        }
    }
    fun launchPicker() = picker.launch(PICKER_MIMES)

    val items = remember(installed) {
        buildList {
            add(FocusItem.Import)
            installed.forEach { add(FocusItem.Row(it.id)) }
        }
    }
    val shelf = rememberShelfState(maxOf(items.size, 1), focusMemory.recall("sources", 0))
    focusMemory.store("sources", shelf.selected)
    val listState = rememberLazyListState()
    FollowSelected(listState, shelf.selected, items.size)
    val focus = rememberScreenFocus()

    val dialogOpen = importState !is ImportState.Idle || impact != null || message != null
    var dialogChoice by remember(dialogOpen) { mutableIntStateOf(0) }

    fun activate(item: FocusItem?): Boolean {
        when (item) {
            FocusItem.Import -> launchPicker()
            is FocusItem.Row -> vm.loadDetail(item.id)
            null -> return false
        }
        return true
    }

    fun dialogPrimary(): Boolean {
        return when (val s = importState) {
            is ImportState.Preview -> {
                vm.confirmImport(); true
            }
            is ImportState.Error -> {
                vm.dismissError(); true
            }
            else -> when {
                impact != null -> {
                    val im = impact!!
                    if (im.removing) vm.remove(im.id) else vm.setEnabled(im.id, false)
                    true
                }
                message != null -> {
                    vm.consumeMessage(); true
                }
                else -> false
            }
        }
    }

    fun dialogCancel(): Boolean {
        return when (importState) {
            is ImportState.Preview, is ImportState.Error -> {
                vm.cancelPreview()
                vm.dismissError()
                true
            }
            ImportState.Reading, ImportState.Installing -> false
            ImportState.Idle -> when {
                impact != null -> {
                    vm.clearImpact(); true
                }
                message != null -> {
                    vm.consumeMessage(); true
                }
                else -> false
            }
        }
    }

    // Detail view replaces the list; Back returns to the invoking row.
    val showing = detail
    if (showing != null) {
        SourceDetailView(
            vm = vm,
            railHost = railHost,
            selectedId = selected?.id,
            onBack = { vm.clearDetail() },
            onReplace = { launchPicker() },
        )
        return
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
                onRight = {
                    if (dialogOpen) {
                        dialogChoice = 1; true
                    } else false
                },
                onA = {
                    if (dialogOpen) {
                        if (dialogChoice == 0) dialogPrimary() else dialogCancel()
                    } else activate(items.getOrNull(shelf.selected))
                },
                onB = { if (dialogCancel()) true else { onBack(); true } },
            )
            .padding(start = g.x(40), end = g.x(120), top = g.contentTop, bottom = g.x(56)),
        verticalArrangement = Arrangement.spacedBy(g.x(16)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Sources", color = ConsoleColors.TextPrimary,
                fontSize = g.t(34), fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "ROM catalogs are separate files you import. Your library never depends on them.",
            color = ConsoleColors.TextSecondary, fontSize = g.t(18),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(g.x(12)),
        ) {
            itemsIndexed(
                items,
                key = { _, it ->
                    when (it) {
                        FocusItem.Import -> "import"
                        is FocusItem.Row -> "row:${it.id}"
                    }
                },
            ) { i, item ->
                when (item) {
                    FocusItem.Import -> ImportRow(
                        focused = i == shelf.selected,
                        onPick = { activate(item) },
                    )
                    is FocusItem.Row -> {
                        val entry = installed.firstOrNull { it.id == item.id } ?: return@itemsIndexed
                        SourceRow(
                            entry = entry,
                            status = sourceRowStatus(entry, selected?.id),
                            focused = i == shelf.selected,
                            onPick = { activate(item) },
                        )
                    }
                }
            }
            item(key = "builtin-header") {
                Text(
                    "Built-in", color = ConsoleColors.TextTertiary,
                    fontSize = g.t(15), fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = g.x(8)),
                )
            }
            item(key = "builtin-bios") {
                BiosRow()
            }
        }
        ControllerHintBar(
            listOf(
                ControllerHint(ControllerGlyph.A, "Open"),
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

@Composable
private fun ImportRow(focused: Boolean, onPick: () -> Unit) {
    val g = LocalConsoleGeometry.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(g.x(12)))
            .background(if (focused) ConsoleColors.HighSurface else ConsoleColors.Elevated)
            .border(
                g.x(1.5f),
                if (focused) ConsoleColors.FocusRing else ConsoleColors.Surface,
                RoundedCornerShape(g.x(12)),
            )
            .clickable(onClick = onPick)
            .padding(horizontal = g.x(20), vertical = g.x(14)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Import source", color = ConsoleColors.TextPrimary,
            fontSize = g.t(20), fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text("+ JSON", color = ConsoleColors.TextSecondary, fontSize = g.t(17))
    }
}

@Composable
private fun SourceRow(
    entry: InstalledSource,
    status: SourceRowStatus,
    focused: Boolean,
    onPick: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(g.x(12)))
            .background(if (focused) ConsoleColors.HighSurface else ConsoleColors.Elevated)
            .border(
                g.x(1.5f),
                if (focused) ConsoleColors.FocusRing else ConsoleColors.Surface,
                RoundedCornerShape(g.x(12)),
            )
            .clickable(onClick = onPick)
            .padding(horizontal = g.x(20), vertical = g.x(14)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name.ifBlank { entry.id },
                color = ConsoleColors.TextPrimary, fontSize = g.t(20),
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                entry.revision.take(8),
                color = ConsoleColors.TextTertiary, fontSize = g.t(15),
            )
        }
        Text(status.label(), color = ConsoleColors.TextSecondary, fontSize = g.t(17))
    }
}

@Composable
private fun BiosRow() {
    val g = LocalConsoleGeometry.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(g.x(12)))
            .background(ConsoleColors.Elevated)
            .padding(horizontal = g.x(20), vertical = g.x(14)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "RetroBIOS · Default", color = ConsoleColors.TextPrimary,
            fontSize = g.t(20), modifier = Modifier.weight(1f),
        )
        Text("Built-in", color = ConsoleColors.TextTertiary, fontSize = g.t(17))
    }
}

@Composable
internal fun SourcesDialog(
    importState: ImportState,
    impact: SourceImpact?,
    message: String?,
    choice: Int,
    onChoice: (Int) -> Unit,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val title: String
    val body: String
    val primary: String
    val busy: Boolean
    when {
        importState is ImportState.Preview -> {
            val p = importState
            title = if (p.isReplacement) "Replace source?" else "Import source?"
            body = buildString {
                appendLine("${p.name} · v${p.version}")
                appendLine("By ${p.author} (unverified) · id ${p.id}")
                if (p.isReplacement && p.addedOrigins.isNotEmpty()) {
                    appendLine("Adds ${com.gamebex.client.ui.countOf(p.addedOrigins.size, "new origin")}:")
                    p.addedOrigins.take(8).forEach { appendLine("  $it") }
                } else if (p.isReplacement) {
                    appendLine("No new origins.")
                }
            }
            primary = if (p.isReplacement) "Replace" else "Import"
            busy = false
        }
        importState is ImportState.Error -> {
            title = "Couldn't import"
            body = importState.message
            primary = "OK"
            busy = false
        }
        importState is ImportState.Reading || importState is ImportState.Installing -> {
            title = if (importState is ImportState.Reading) "Reading…" else "Installing…"
            body = ""
            primary = ""
            busy = true
        }
        impact != null -> {
            title = if (impact.removing) "Remove ${impact.name}?" else "Disable ${impact.name}?"
            body = if (impact.jobCount > 0) {
                "${com.gamebex.client.ui.countOf(impact.jobCount, "download")} " +
                    "${com.gamebex.client.ui.verbFor(impact.jobCount, "uses", "use")} this source. Their downloaded " +
                    "bytes and your games will be kept."
            } else {
                "No downloads use this source. Your games will be kept."
            }
            primary = if (impact.removing) "Remove" else "Disable"
            busy = false
        }
        message != null -> {
            title = "Sources"
            body = message
            primary = "OK"
            busy = false
        }
        else -> return
    }
    Box(
        modifier = Modifier.fillMaxSize()
            .background(ConsoleColors.Background.copy(alpha = 0.82f))
            .padding(horizontal = g.x(200)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(g.x(16)))
                .background(ConsoleColors.Elevated)
                .border(g.x(1.5f), ConsoleColors.FocusRing, RoundedCornerShape(g.x(16)))
                .padding(horizontal = g.x(28), vertical = g.x(24)),
            verticalArrangement = Arrangement.spacedBy(g.x(12)),
        ) {
            Text(title, color = ConsoleColors.TextPrimary, fontSize = g.t(24), fontWeight = FontWeight.Bold)
            if (busy) {
                CircularProgressIndicator(color = ConsoleColors.Accent)
            } else {
                if (body.isNotBlank()) {
                    Text(body.trim(), color = ConsoleColors.TextSecondary, fontSize = g.t(18))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(g.x(16))) {
                    PrimaryConsoleAction(primary, choice == 0, onClick = onPrimary)
                    QuietConsoleAction(
                        if (importState is ImportState.Error || message != null) "Back" else "Cancel",
                        choice == 1,
                        onClick = onCancel,
                    )
                }
            }
        }
    }
}
