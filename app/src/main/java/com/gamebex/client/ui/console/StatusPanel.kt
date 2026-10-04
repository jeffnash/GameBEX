package com.gamebex.client.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.gamebex.client.ui.presentation.LaunchStatus
import com.gamebex.client.ui.presentation.Tone
import com.gamebex.client.ui.presentation.label

/**
 * Quick-play status: A recovers, optional X recovery, B dismisses or cancels.
 * Color lives in the icon only; the copy stays cream so the word carries the
 * meaning.
 */
@Composable
fun LaunchStatusPanel(
    status: LaunchStatus,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSecondaryAction: () -> Unit = {},
) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_PANEL))
    Row(
        modifier = modifier
            .widthIn(max = g.x(520))
            .clip(shape)
            .background(ConsoleColors.HighSurface)
            .border(g.x(1), ConsoleColors.Steel, shape)
            .padding(horizontal = g.x(16), vertical = g.x(12)),
        horizontalArrangement = Arrangement.spacedBy(g.x(12)),
        verticalAlignment = Alignment.Top,
    ) {
        val iconMod = Modifier.padding(top = g.x(1)).size(g.x(20))
        when {
            status.busy -> {
                val fraction = status.coreProgress?.fraction
                if (fraction == null) CircularProgressIndicator(
                    color = ConsoleColors.Accent, trackColor = ConsoleColors.Steel,
                    strokeWidth = g.x(2), modifier = iconMod,
                ) else CircularProgressIndicator(
                    progress = { fraction },
                    color = ConsoleColors.Accent, trackColor = ConsoleColors.Steel,
                    strokeWidth = g.x(2), modifier = iconMod,
                )
            }
            status.tone == Tone.Blocked -> Icon(
                Icons.Filled.Close, "Blocked", tint = ConsoleColors.Error, modifier = iconMod,
            )
            else -> Icon(
                Icons.Filled.Warning, "Needs attention", tint = ConsoleColors.Warning, modifier = iconMod,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(g.x(8))) {
            Text(
                status.text,
                color = if (status.busy) ConsoleColors.TextSecondary else ConsoleColors.TextPrimary,
                fontSize = g.t(16), lineHeight = g.t(20), maxLines = 3,
            )
            if (!status.busy || status.canCancel) {
                Row(horizontalArrangement = Arrangement.spacedBy(g.x(20))) {
                    status.action?.let { a ->
                        PanelControl(ControllerGlyph.A, a.label(), strong = true, onClick = onAction)
                    }
                    status.secondaryAction?.let { a ->
                        PanelControl(ControllerGlyph.X, a.label(), strong = true, onClick = onSecondaryAction)
                    }
                    PanelControl(ControllerGlyph.B, if (status.canCancel) "Cancel" else "Dismiss", strong = false, onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun PanelControl(glyph: ControllerGlyph, label: String, strong: Boolean, onClick: () -> Unit) {
    val g = LocalConsoleGeometry.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CHIP)))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(g.x(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControllerGlyphBadge(glyph, filled = strong)
        Text(
            label,
            color = if (strong) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary,
            fontSize = g.t(15), lineHeight = g.t(18),
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
