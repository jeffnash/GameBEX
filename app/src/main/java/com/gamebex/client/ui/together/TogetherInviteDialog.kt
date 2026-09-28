package com.gamebex.client.ui.together

import androidx.compose.foundation.focusable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.gamebex.client.together.TogetherController
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.console.ConsoleDialog
import com.gamebex.client.ui.console.ConsoleDialogButton
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.DialogTone
import com.gamebex.client.ui.console.LocalConsoleGeometry

@Composable
fun TogetherInviteDialog(controller: TogetherController, route: String?, onJoined: () -> Unit) {
    val invite by controller.invite.collectAsState()
    if (invite == null || route?.startsWith("together") == true) return
    val target = invite!!
    val first = remember { FocusRequester() }
    ConsoleDialog(
        title = TogetherCopy.inviteTitle,
        onDismiss = { controller.dismissInvite() },
        initialFocus = first,
        body = { Text(TogetherCopy.inviteBody(target.name, target.gameTitle, togetherPlatformName(target.folder)), fontSize = LocalConsoleGeometry.current.t(18)) },
        buttons = {
            ConsoleDialogButton(TogetherCopy.notNow, { controller.dismissInvite() }, Modifier.consoleKeys(onB = { controller.dismissInvite(); true }))
            ConsoleDialogButton(TogetherCopy.join, { controller.join(target); onJoined() }, Modifier.focusRequester(first).consoleKeys(onB = { controller.dismissInvite(); true }), tone = DialogTone.Primary)
        },
    )
}
