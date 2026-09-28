package com.gamebex.client.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDialog
import com.gamebex.client.ui.console.ConsoleDialogButton
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerLayout
import com.gamebex.client.ui.console.ControllerPrefs
import com.gamebex.client.ui.console.DialogTone
import com.gamebex.client.ui.console.DisplayPrefs
import com.gamebex.client.ui.console.TextSize
import com.gamebex.client.ui.console.glyphLabel
import com.gamebex.client.launch.ControllerRegistry
import com.gamebex.client.launch.EmuControllerSetup
import com.gamebex.client.launch.LaunchPrefs
import com.gamebex.client.launch.ShizukuFiles
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHIZUKU_REQUEST = 0x5b1

/**
 * Connected pads + one-press emulator setup, then button labels + confirm side. Choices apply live, so the preview line and
 * the footer hints relabel as you move; D-pad focus is native to the dialog.
 */
@Composable
fun ControllerSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val layout = ControllerPrefs.layout
    val swap = ControllerPrefs.swapConfirm
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var pads by remember { mutableStateOf<List<ControllerRegistry.Pad>?>(null) }
    var results by remember { mutableStateOf<List<EmuControllerSetup.Result>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var shizukuNote by remember { mutableStateOf<String?>(null) }
    var closeOthers by remember { mutableStateOf(LaunchPrefs.closeOthers(context)) }
    LaunchedEffect(Unit) { pads = withContext(Dispatchers.Default) { ControllerRegistry.snapshot() } }
    fun runSetup() {
        if (busy) return
        busy = true
        scope.launch {
            val now = withContext(Dispatchers.IO) {
                val found = ControllerRegistry.snapshot()
                found to EmuControllerSetup.applyAll(context, found)
            }
            pads = now.first
            results = now.second
            if (results.none { it.status.needsShizuku }) shizukuNote = null
            busy = false
        }
    }
    val setup by rememberUpdatedState(::runSetup)
    val needsShizuku = results.any { it.status.needsShizuku }
    val needsShizukuNow by rememberUpdatedState(needsShizuku)
    // Grant result → re-run setup; Shizuku (re)started while we're showing
    // a Shizuku step and already allowed → re-run too. Callbacks are on main.
    DisposableEffect(Unit) {
        val onResult = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code != SHIZUKU_REQUEST) return@OnRequestPermissionResultListener
            if (result == PackageManager.PERMISSION_GRANTED) {
                shizukuNote = null
                setup()
            } else {
                shizukuNote = "Shizuku access denied. Allow GameBEX in the Shizuku app to finish setup."
            }
        }
        val onBinder = Shizuku.OnBinderReceivedListener {
            if (needsShizukuNow && ShizukuFiles.granted() && pads.orEmpty().isNotEmpty()) setup()
        }
        runCatching { Shizuku.addRequestPermissionResultListener(onResult) }
        runCatching { Shizuku.addBinderReceivedListener(onBinder) }
        onDispose {
            runCatching { Shizuku.removeRequestPermissionResultListener(onResult) }
            runCatching { Shizuku.removeBinderReceivedListener(onBinder) }
        }
    }
    ConsoleDialog(
        title = "Controls & text",
        onDismiss = onDismiss,
        initialFocus = first,
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Controllers", color = ConsoleColors.TextTertiary)
                Text(
                    when {
                        pads == null -> "Checking…"
                        pads.orEmpty().isEmpty() -> "No controller connected."
                        else -> pads.orEmpty().joinToString(" · ") { it.label }
                    },
                )
                ConsoleDialogButton(
                    if (busy) "Setting up…" else "Set up emulators for this controller",
                    { runSetup() },
                )
                results.forEach { Text(it.line) }
                if (needsShizuku) {
                    ConsoleDialogButton(
                        "Allow Shizuku access",
                        {
                            shizukuNote = when (ShizukuFiles.requestPermission(context, SHIZUKU_REQUEST)) {
                                ShizukuFiles.Ask.GRANTED -> null.also { runSetup() }
                                ShizukuFiles.Ask.REQUESTED -> "Answer Shizuku's prompt to continue."
                                ShizukuFiles.Ask.NOT_RUNNING ->
                                    "Shizuku isn't running. Start it in the Shizuku app, then try again."
                                ShizukuFiles.Ask.OUTDATED -> "This Shizuku is too old. Update it to v11 or newer."
                                ShizukuFiles.Ask.DENIED_FOR_GOOD ->
                                    "Shizuku won't ask again. Allow GameBEX in the Shizuku app, then set up again."
                                ShizukuFiles.Ask.UNAVAILABLE -> "Install Shizuku to configure these emulators."
                            }
                        },
                        tone = DialogTone.Primary,
                    )
                    shizukuNote?.let { Text(it, color = ConsoleColors.TextTertiary) }
                }
                Text(
                    "Button labels", color = ConsoleColors.TextTertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControllerLayout.entries.forEachIndexed { i, l ->
                        ConsoleDialogButton(
                            l.label, { ControllerPrefs.set(context, l, swap) },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            selected = l == layout,
                        )
                    }
                }
                Text(
                    "Confirm button", color = ConsoleColors.TextTertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConsoleDialogButton(
                        "Bottom button", { ControllerPrefs.set(context, layout, false) }, selected = !swap,
                    )
                    ConsoleDialogButton(
                        "Right button", { ControllerPrefs.set(context, layout, true) }, selected = swap,
                    )
                }
                Text(
                    "Text size", color = ConsoleColors.TextTertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextSize.entries.forEach { t ->
                        ConsoleDialogButton(
                            t.label, { DisplayPrefs.set(context, t) },
                            selected = t == DisplayPrefs.textSize,
                        )
                    }
                }
                Text(
                    "Performance", color = ConsoleColors.TextTertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text("Close other emulators before launching (frees memory)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConsoleDialogButton(
                        "On", { closeOthers = true; LaunchPrefs.setCloseOthers(context, true) }, selected = closeOthers,
                    )
                    ConsoleDialogButton(
                        "Off", { closeOthers = false; LaunchPrefs.setCloseOthers(context, false) }, selected = !closeOthers,
                    )
                }
                Text(
                    "Confirm: ${glyphLabel(ControllerGlyph.A, layout, swap)} · " +
                        "Back: ${glyphLabel(ControllerGlyph.B, layout, swap)}",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        buttons = { ConsoleDialogButton("Done", onDismiss, tone = DialogTone.Primary) },
    )
}
