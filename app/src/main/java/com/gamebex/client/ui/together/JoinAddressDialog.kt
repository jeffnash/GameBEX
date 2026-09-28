package com.gamebex.client.ui.together

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleDialog
import com.gamebex.client.ui.console.ConsoleDialogButton
import com.gamebex.client.ui.console.DialogTone
import com.gamebex.client.ui.console.LocalConsoleGeometry

@Composable
fun JoinAddressDialog(onDismiss: () -> Unit, onJoin: (String) -> String?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun join() { error = onJoin(text); if (error == null) keyboard?.hide() }
    LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; keyboard?.show() }
    ConsoleDialog(
        title = TogetherCopy.addressTitle,
        onDismiss = onDismiss,
        initialFocus = fieldFocus,
        body = {
            val g = LocalConsoleGeometry.current
            Text(TogetherCopy.addressPrompt, fontSize = g.t(17))
            BasicTextField(
                value = text, onValueChange = { text = it; error = null }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { join() }),
                textStyle = TextStyle(color = ConsoleColors.TextPrimary, fontSize = g.t(20)),
                decorationBox = { inner -> Box(Modifier.fillMaxSize()) { if (text.isEmpty()) Text(TogetherCopy.addressHint, color = ConsoleColors.TextTertiary, fontSize = g.t(20)); inner() } },
                modifier = Modifier.width(g.x(480)).height(g.x(84)).background(ConsoleColors.HighSurface).border(if (focused) g.x(ConsoleDesign.FOCUS_STROKE) else g.x(1), if (focused) ConsoleColors.FocusRing else ConsoleColors.Steel).padding(g.x(16)).focusRequester(fieldFocus).onFocusChanged { focused = it.isFocused },
            )
            Text(error ?: "", color = ConsoleColors.Error, fontSize = g.t(15))
        },
        buttons = {
            ConsoleDialogButton(TogetherCopy.cancel, onDismiss)
            ConsoleDialogButton(TogetherCopy.join, ::join, tone = DialogTone.Primary)
        },
    )
}
