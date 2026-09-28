package com.gamebex.client.ui.system

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.gamebex.client.data.PlatformAliases
import com.gamebex.client.data.local.GameFolders
import com.gamebex.client.data.local.RomStore
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.ControllerLayout
import com.gamebex.client.ui.console.ControllerPrefs
import com.gamebex.client.ui.console.glyphLabel
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.consoleSelected
import com.gamebex.client.ui.console.isSystemReducedMotion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * System › Game folders: user library roots (ES-DE style `<root>/<system>`).
 * Modal over the System screen; SystemScreen forwards every controller key
 * here while [open]. List mode: ADD FOLDER, then one row per root (A arms
 * removal, A again removes). Picker mode: storage volumes -> directories,
 * A enters, Y (or the top row) uses the current folder, B goes up.
 */
class GameFoldersState(
    private val context: Context,
    /** Roots changed: (added path or null, removed path or null). */
    private val onChanged: (added: String?, removed: String?) -> Unit,
) {
    enum class Mode { LIST, PICK }

    data class Entry(val label: String, val dir: File, val hint: String? = null)

    var open by mutableStateOf(false); private set
    var mode by mutableStateOf(Mode.LIST); private set
    var roots by mutableStateOf<List<String>>(emptyList()); private set
    var builtIns by mutableStateOf<List<String>>(emptyList()); private set
    var selected by mutableIntStateOf(0); private set
    var armedRemove by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set

    /** Picker: null = storage volume list. */
    var pickDir by mutableStateOf<File?>(null); private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
    var loading by mutableStateOf(false)

    fun show() {
        reload()
        mode = Mode.LIST
        selected = 0
        armedRemove = null
        message = null
        open = true
    }

    fun reload() {
        val built = RomStore.builtInRoots(context).map { it.second }
        builtIns = built.map { GameFolders.canonical(it) ?: it.absolutePath }
        roots = GameFolders.stored(context)
    }

    /** Rows in list mode: 0 = ADD FOLDER, then roots. */
    private val listCount get() = 1 + roots.size

    /** Rows in picker mode: 0 = USE THIS FOLDER (only inside a dir), then entries. */
    private val pickOffset get() = if (pickDir == null) 0 else 1
    private val pickCount get() = pickOffset + entries.size

    fun up(): Boolean {
        if (selected > 0) selected -= 1
        armedRemove = null
        return true
    }

    fun down(): Boolean {
        val n = if (mode == Mode.LIST) listCount else pickCount
        if (selected < n - 1) selected += 1
        armedRemove = null
        return true
    }

    fun a(): Boolean {
        if (mode == Mode.LIST) {
            if (selected == 0) startPicker() else armOrRemove(roots.getOrNull(selected - 1))
            return true
        }
        if (pickDir != null && selected == 0) return useCurrent()
        entries.getOrNull(selected - pickOffset)?.let { enter(it.dir) }
        return true
    }

    fun b(): Boolean {
        when {
            mode == Mode.PICK && pickDir != null -> {
                val parent = pickDir?.parentFile
                // Stop at the volume list instead of walking into /storage.
                val atVolume = volumes().any { it.dir.absolutePath == pickDir?.absolutePath }
                enter(if (atVolume || parent == null) null else parent)
            }
            mode == Mode.PICK -> {
                mode = Mode.LIST
                selected = 0
            }
            else -> open = false
        }
        return true
    }

    fun y(): Boolean = if (mode == Mode.PICK && pickDir != null) useCurrent() else true

    fun selectRow(i: Int) {
        armedRemove = null
        selected = i
    }

    private fun armOrRemove(path: String?) {
        path ?: return
        if (armedRemove != path) {
            armedRemove = path
            return
        }
        GameFolders.remove(context, path)
        armedRemove = null
        reload()
        selected = selected.coerceAtMost(listCount - 1)
        message = "Removed. Its games stay in your folder, hidden from the library."
        onChanged(null, path)
    }

    private fun startPicker() {
        mode = Mode.PICK
        message = null
        enter(null)
    }

    private fun enter(dir: File?) {
        pickDir = dir
        selected = 0
        entries = if (dir == null) volumes() else emptyList()
        loading = dir != null
    }

    /** IO: list [pickDir]'s visible subdirectories (runs from the panel's effect). */
    suspend fun loadEntries() {
        val dir = pickDir ?: return
        val list = withContext(Dispatchers.IO) {
            (dir.listFiles() ?: emptyArray())
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
                .map { d ->
                    val sys = PlatformAliases.resolve(d.name)
                    Entry(d.name, d, sys?.let { "system: $it" })
                }
        }
        if (pickDir == dir) {
            entries = list
            loading = false
        }
    }

    private fun useCurrent(): Boolean {
        val dir = pickDir ?: return true
        val built = RomStore.builtInRoots(context).map { it.second }
        when (val r = GameFolders.add(context, dir, built)) {
            is GameFolders.AddResult.Added -> {
                reload()
                mode = Mode.LIST
                selected = 1 + roots.indexOf(r.path).coerceAtLeast(0)
                message = "Added. Scanning for games…"
                onChanged(r.path, null)
            }
            is GameFolders.AddResult.Rejected -> message = r.reason
        }
        return true
    }

    /** Storage volumes: internal shared storage + mounted removable cards. */
    fun volumes(): List<Entry> {
        val out = mutableListOf<Entry>()
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory()
        out += Entry("Internal storage", primary, primary.absolutePath)
        val sd = Regex("^/storage/[0-9A-F]{4}-[0-9A-F]{4}")
        context.getExternalFilesDirs(null)?.forEach { d ->
            val m = sd.find(d?.absolutePath ?: return@forEach) ?: return@forEach
            if (out.none { it.dir.absolutePath == m.value }) {
                out += Entry("SD card", File(m.value), m.value)
            }
        }
        return out
    }
}

@Composable
fun GameFoldersPanel(state: GameFoldersState) {
    if (!state.open) return
    val g = LocalConsoleGeometry.current
    val reducedMotion = isSystemReducedMotion()
    LaunchedEffect(state.pickDir, state.mode) {
        if (state.mode == GameFoldersState.Mode.PICK) state.loadEntries()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.selected, state.mode, state.pickDir) {
        val i = state.selected.coerceAtLeast(0)
        runCatching {
            if (reducedMotion) listState.scrollToItem(i) else listState.animateScrollToItem(i)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleColors.Scrim90)
            .clickable(enabled = true, onClick = {}), // swallow touches behind the modal
    ) {
        Column(
            modifier = Modifier
                .offset(x = g.x(48), y = g.x(36))
                .width(g.x(920))
                .heightIn(max = g.x(600))
                .clip(RoundedCornerShape(g.x(ConsoleDesign.RADIUS_PANEL)))
                .background(ConsoleColors.Elevated)
                .border(g.x(1), ConsoleColors.Steel, RoundedCornerShape(g.x(ConsoleDesign.RADIUS_PANEL)))
                .padding(g.x(24)),
        ) {
            val picking = state.mode == GameFoldersState.Mode.PICK
            Text(
                if (picking) "CHOOSE A GAME FOLDER" else "GAME FOLDERS",
                color = ConsoleColors.TextTertiary, fontSize = g.tMin(15),
                lineHeight = g.tMin(18), fontWeight = FontWeight.Bold,
            )
            Text(
                if (picking) state.pickDir?.absolutePath ?: "Storage"
                else "Folders with one subfolder per system (snes, psx, megadrive…)",
                color = ConsoleColors.TextPrimary, fontSize = g.t(22), lineHeight = g.t(28),
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = g.x(6)),
            )
            if (!picking) {
                state.builtIns.forEach {
                    Text(
                        "GameBEX library · $it",
                        color = ConsoleColors.TextSecondary, fontSize = g.tMin(15),
                        lineHeight = g.tMin(20), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = g.x(4)),
                    )
                }
                Text(
                    "GameBEX only reads your folders — it never writes, moves, or deletes files there.",
                    color = ConsoleColors.TextTertiary, fontSize = g.tMin(15), lineHeight = g.tMin(20),
                    modifier = Modifier.padding(top = g.x(4)),
                )
            }
            state.message?.let {
                Text(
                    it, color = ConsoleColors.Warning, fontSize = g.tMin(15), lineHeight = g.tMin(20),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = g.x(8)),
                )
            }
            val rows: List<Triple<String, String?, Boolean>> = if (!picking) {
                listOf(Triple("ADD FOLDER", null, false)) + state.roots.map { p ->
                    val armed = state.armedRemove == p
                    Triple(p, if (armed) "PRESS ${key(ControllerGlyph.A)} AGAIN TO REMOVE" else "${key(ControllerGlyph.A)} · REMOVE", armed)
                }
            } else {
                val use = if (state.pickDir != null) {
                    listOf(Triple("USE THIS FOLDER", key(ControllerGlyph.Y), false))
                } else emptyList()
                use + state.entries.map { Triple(it.label, it.hint, false) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(top = g.x(14)).fillMaxWidth().heightIn(max = g.x(380)),
            ) {
                itemsIndexed(rows) { i, (label, trailing, danger) ->
                    val focused = i == state.selected
                    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = g.x(3))
                            .heightIn(min = g.hitMin(g.x(56)))
                            .clip(shape)
                            .background(if (focused) ConsoleColors.HighSurface else ConsoleColors.Surface)
                            .consoleSelected(focused, label)
                            .then(
                                if (focused) Modifier.border(
                                    g.x(ConsoleDesign.FOCUS_STROKE),
                                    if (danger) ConsoleColors.Error else ConsoleColors.FocusRing, shape,
                                ) else Modifier.border(g.x(1), ConsoleColors.Steel, shape),
                            )
                            .clickable {
                                if (i == state.selected) state.a() else state.selectRow(i)
                            }
                            .padding(horizontal = g.x(18)),
                    ) {
                        Text(
                            label,
                            color = if (focused) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary,
                            fontSize = g.t(18), lineHeight = g.t(22),
                            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        trailing?.let {
                            Text(
                                it,
                                color = if (danger) ConsoleColors.Error else ConsoleColors.TextTertiary,
                                fontSize = g.tMin(14), lineHeight = g.tMin(18),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = g.x(12)),
                            )
                        }
                    }
                }
            }
            if (picking && state.loading) {
                Text(
                    "Reading folder…", color = ConsoleColors.TextTertiary,
                    fontSize = g.tMin(15), lineHeight = g.tMin(20),
                    modifier = Modifier.padding(top = g.x(8)),
                )
            } else if (picking && state.pickDir != null && state.entries.isEmpty()) {
                Text(
                    "No subfolders here.", color = ConsoleColors.TextTertiary,
                    fontSize = g.tMin(15), lineHeight = g.tMin(20),
                    modifier = Modifier.padding(top = g.x(8)),
                )
            }
            Box(modifier = Modifier.height(g.x(10)))
            Text(
                foldersCaption(foldersHints(picking, picking && state.pickDir != null)),
                color = ConsoleColors.TextTertiary, fontSize = g.tMin(14), lineHeight = g.tMin(16),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The panel's keys: its folder list, the storage roots, or inside a folder.
 * The hint strip and the panel's own caption both come from this. Pure.
 */
internal fun foldersHints(picking: Boolean, inFolder: Boolean): List<ControllerHint> = when {
    inFolder -> listOf(
        ControllerHint(ControllerGlyph.A, "Open"),
        ControllerHint(ControllerGlyph.Y, "Use this folder"),
        ControllerHint(ControllerGlyph.B, "Up"),
    )
    picking -> listOf(ControllerHint(ControllerGlyph.A, "Open"), ControllerHint(ControllerGlyph.B, "Back"))
    else -> listOf(ControllerHint(ControllerGlyph.A, "Select"), ControllerHint(ControllerGlyph.B, "Close"))
}

/** "A OPEN · B BACK", with each key named as the pad prints it. Pure. */
internal fun foldersCaption(
    hints: List<ControllerHint>,
    layout: ControllerLayout = ControllerPrefs.layout,
    swap: Boolean = ControllerPrefs.swapConfirm,
): String = hints.joinToString(" · ") { h ->
    listOfNotNull(h.glyph?.let { glyphLabel(it, layout, swap) }, h.text.uppercase()).joinToString(" ")
}

private fun key(glyph: ControllerGlyph): String =
    glyphLabel(glyph, ControllerPrefs.layout, ControllerPrefs.swapConfirm)
