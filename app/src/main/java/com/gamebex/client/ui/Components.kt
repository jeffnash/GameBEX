package com.gamebex.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.gamebex.client.net.ScreenScraper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ScreenScraper login dialog (game detail screen). Passwords are
 * masked with a password keyboard; [focusSave] parks D-pad focus on Save.
 */
@Composable
fun ScreenScraperCredentialsDialog(
    creds: ScreenScraper.Companion.Creds,
    onSave: (devId: String, devPw: String, login: String, loginPw: String) -> Unit,
    onDismiss: () -> Unit,
    focusSave: Boolean = false,
) {
    var devId by remember(creds) { mutableStateOf(creds.devId) }
    var devPw by remember(creds) { mutableStateOf(creds.devPassword) }
    var login by remember(creds) { mutableStateOf(creds.ssid) }
    var loginPw by remember(creds) { mutableStateOf(creds.sspassword) }
    val saveFocus = remember { FocusRequester() }
    val pwKeys = KeyboardOptions(keyboardType = KeyboardType.Password)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ScreenScraper login") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Free at screenscraper.fr. Dev keys fetch art; your login raises the daily quota.",
                    fontSize = 12.sp,
                )
                TextField(devId, { devId = it }, label = { Text("Dev ID") }, singleLine = true)
                TextField(
                    devPw, { devPw = it }, label = { Text("Dev password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = pwKeys,
                )
                TextField(login, { login = it }, label = { Text("SS login (optional)") }, singleLine = true)
                TextField(
                    loginPw, { loginPw = it }, label = { Text("SS password (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = pwKeys,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(devId, devPw, login, loginPw) },
                modifier = if (focusSave) Modifier.focusRequester(saveFocus) else Modifier,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
    if (focusSave) {
        LaunchedEffect(Unit) { runCatching { saveFocus.requestFocus() } }
    }
}
