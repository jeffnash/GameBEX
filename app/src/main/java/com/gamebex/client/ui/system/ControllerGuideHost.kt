package com.gamebex.client.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gamebex.client.launch.ControllerGuide
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDialog
import com.gamebex.client.ui.console.ConsoleDialogButton
import com.gamebex.client.ui.console.DialogTone

/** App-level slot for the NetherSX2 one-time controller card. */
@Composable
fun ControllerGuideHost() {
    DisposableEffect(Unit) {
        ControllerGuide.hosts++
        onDispose { ControllerGuide.hosts-- }
    }
    val card by ControllerGuide.card.collectAsState()
    val c = card ?: return
    val context = LocalContext.current
    val ok = remember(c) { FocusRequester() }
    ConsoleDialog(
        title = "One step for PS2 controls",
        onDismiss = { ControllerGuide.dismiss(c) },
        initialFocus = ok,
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NetherSX2 keeps its button mapping private, so map ${c.padName} once inside it:")
                Text("1. Start the game, then open NetherSX2's pause menu → Controller Settings.")
                Text("2. Controller Port 1 → Automatic Mapping → pick ${c.padName}.")
                Text("3. That's it. Touchscreen → Hide With External Controller is already set by GameBEX.")
                Text("You only need this once per controller.", color = ConsoleColors.TextTertiary)
            }
        },
        buttons = {
            ConsoleDialogButton("Don't show again", { ControllerGuide.neverAgain(context, c) })
            ConsoleDialogButton(
                "Got it", { ControllerGuide.gotIt(context, c) },
                modifier = Modifier.focusRequester(ok), tone = DialogTone.Primary,
            )
        },
    )
}
