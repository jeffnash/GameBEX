package com.gamebex.client.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import com.gamebex.client.ui.SpaceGrotesk

/**
 * Runs in its own window, so D-pad focus moves natively between
 * [ConsoleDialogButton]s; callers park initial focus on the safe choice.
 */
@Composable
fun ConsoleDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    /** Focused once the window is up (a request before that is dropped). */
    initialFocus: FocusRequester? = null,
    body: @Composable ColumnScope.() -> Unit = {},
) {
    val g = LocalConsoleGeometry.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_PANEL))
        Column(
            modifier = Modifier
                .widthIn(max = g.x(640))
                .clip(shape)
                .background(ConsoleColors.HighSurface)
                .border(1.dp, ConsoleColors.Steel, shape)
                .padding(g.x(28)),
        ) {
            Text(
                title, color = ConsoleColors.TextPrimary, fontFamily = SpaceGrotesk,
                fontSize = g.tMin(24), lineHeight = g.tMin(28), fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(g.x(12)))
            ProvideTextStyle(
                TextStyle(
                    color = ConsoleColors.TextSecondary, fontFamily = SpaceGrotesk,
                    fontSize = g.tMin(17), lineHeight = g.tMin(23),
                ),
            ) {
                // Tall content scrolls; the title and buttons always stay on screen.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    content = body,
                )
            }
            Spacer(Modifier.height(g.x(24)))
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(g.x(12)),
                content = buttons,
            )
        }
        if (initialFocus != null) {
            LaunchedEffect(Unit) {
                // Two frames: the dialog window attaches, then its content lays out.
                withFrameNanos { }
                withFrameNanos { }
                runCatching { initialFocus.requestFocus() }
            }
        }
    }
}

enum class DialogTone { Neutral, Primary, Danger }

/** Callers pair [DialogTone.Danger] with an icon and a word, never color alone. */
@Composable
fun ConsoleDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: DialogTone = DialogTone.Neutral,
    icon: ImageVector? = null,
    selected: Boolean = false,
    fill: Boolean = false,
) {
    val g = LocalConsoleGeometry.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD))
    val ink = when (tone) {
        DialogTone.Danger -> ConsoleColors.Error
        DialogTone.Primary -> ConsoleColors.TextPrimary
        DialogTone.Neutral -> if (focused || selected) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary
    }
    Row(
        modifier = modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> ConsoleColors.Surface
                    selected -> ConsoleColors.AccentDim
                    else -> Color.Transparent
                },
            )
            .border(
                if (focused) g.x(ConsoleDesign.FOCUS_STROKE) else 1.dp,
                when {
                    focused -> ConsoleColors.FocusRing
                    selected -> ConsoleColors.Accent
                    else -> ConsoleColors.Steel
                },
                shape,
            )
            // One focus stop in touch AND key mode: clickable is only
            // focusable in non-touch mode, so a dialog opened after a tap
            // would show no initial focus. Keys + taps are handled here.
            .focusable()
            .onKeyEvent { e ->
                val activate = e.key == Key.Enter || e.key == Key.NumPadEnter ||
                    e.key == Key.DirectionCenter || e.key == Key.ButtonA
                if (activate && e.type == KeyEventType.KeyUp) onClick()
                activate
            }
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .semantics { role = Role.Button; onClick(label = label) { onClick(); true } }
            .heightIn(min = g.hitMin(g.x(52)))
            .padding(horizontal = g.x(20), vertical = g.x(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(g.x(22)))
            Spacer(Modifier.width(g.x(10)))
        }
        Text(
            label, color = ink, fontFamily = SpaceGrotesk,
            fontSize = g.tMin(17), lineHeight = g.tMin(21), maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontWeight = if (tone == DialogTone.Neutral && !selected) FontWeight.Medium else FontWeight.Bold,
        )
    }
}
