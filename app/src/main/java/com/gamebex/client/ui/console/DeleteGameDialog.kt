package com.gamebex.client.ui.console

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.ui.formatSize
import com.gamebex.client.ui.home.model.HomeCopy

/**
 * Destructive confirm for "Delete game" (Detail Advanced row, Library
 * Select key). Red + trash icon + the word "Delete" (never color alone);
 * initial focus parks on Cancel so a stray A press backs out. Cancel is a
 * real button because gamepad B can't reach dialog windows.
 *
 * [fromSource] switches to the source-game copy: the saves
 * stay and the game is downloadable again from Home. Callers pass it when
 * `game.sourceRefJson != null`; default keeps the imported-game dialog.
 */
@Composable
fun DeleteGameDialog(
    game: GameEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    fromSource: Boolean = false,
) {
    val cancelFocus = remember(game.id) { FocusRequester() }
    ConsoleDialog(
        title = if (fromSource) HomeCopy.deleteSourceGameTitle(game.title) else "Delete ${game.title}?",
        onDismiss = onDismiss,
        initialFocus = cancelFocus,
        body = {
            Text(
                if (fromSource) {
                    HomeCopy.deleteSourceGameBody(game.title)
                } else {
                    "Removes ${game.fileName} (${formatSize(game.sizeBytes)}) and its prepared " +
                        "files from this device. Emulator saves are kept. This can't be undone."
                },
            )
        },
        buttons = {
            ConsoleDialogButton("Cancel", onDismiss, Modifier.focusRequester(cancelFocus))
            ConsoleDialogButton("Delete", onConfirm, tone = DialogTone.Danger, icon = Icons.Filled.Delete)
        },
    )
}
