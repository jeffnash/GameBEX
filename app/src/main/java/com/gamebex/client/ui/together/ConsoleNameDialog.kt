package com.gamebex.client.ui.together

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.platform.TogetherPrefs
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleDialog
import com.gamebex.client.ui.console.ConsoleDialogButton
import com.gamebex.client.ui.console.DialogTone
import com.gamebex.client.ui.console.LocalConsoleGeometry

/**
 * Edits the console name other phones see (the Join-by-address pattern: IME
 * Done saves, B cancels). Saving blank, or "Use phone name", goes back to the
 * device name. [onSave] gets the raw text; the controller sanitizes it.
 */
@Composable
fun ConsoleNameDialog(customName: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(customName.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun finish(name: String?) { keyboard?.hide(); onSave(name) }
    LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; keyboard?.show() }
    ConsoleDialog(
        title = TogetherCopy.renameTitle,
        onDismiss = onDismiss,
        initialFocus = fieldFocus,
        body = {
            val g = LocalConsoleGeometry.current
            Text(TogetherCopy.renamePrompt, fontSize = g.t(17))
            BasicTextField(
                value = text,
                onValueChange = { if (it.codePointCount(0, it.length) <= TogetherPrefs.MAX_CUSTOM_NAME) text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finish(text) }),
                textStyle = TextStyle(color = ConsoleColors.TextPrimary, fontSize = g.t(20)),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(TogetherCopy.renameHint, color = ConsoleColors.TextTertiary, fontSize = g.t(20))
                        inner()
                    }
                },
                modifier = Modifier.width(g.x(480)).heightIn(min = g.x(84))
                    .background(ConsoleColors.HighSurface)
                    .border(if (focused) g.x(ConsoleDesign.FOCUS_STROKE) else g.x(1), if (focused) ConsoleColors.FocusRing else ConsoleColors.Steel)
                    .padding(g.x(16))
                    .focusRequester(fieldFocus)
                    .onFocusChanged { focused = it.isFocused },
            )
        },
        buttons = {
            ConsoleDialogButton(TogetherCopy.usePhoneName, { finish(null) })
            ConsoleDialogButton(TogetherCopy.cancel, onDismiss)
            ConsoleDialogButton(TogetherCopy.save, { finish(text) }, tone = DialogTone.Primary)
        },
    )
}
