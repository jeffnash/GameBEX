package com.gamebex.client.ui.console

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * The one primary CTA for Continue / Play / Get / Install & Continue /
 * Finish Setup / Fix Issues.
 */
@Composable
fun PrimaryConsoleAction(
    label: String,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    controllerGlyph: ControllerGlyph? = ControllerGlyph.A,
    enabled: Boolean = true,
    reducedMotion: Boolean = isSystemReducedMotion(),
) {
    val g = LocalConsoleGeometry.current
    val targetScale = if (focused) ConsoleDesign.FOCUS_CONTROL_SCALE else 1f
    val animScale by animateFloatAsState(
        targetScale,
        animationSpec = ConsoleMotion.focus(),
        label = "cta-scale",
    )
    val scale = if (reducedMotion) targetScale else animScale
    val radius = g.x(ConsoleDesign.RADIUS_CARD)
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(radius))
            .background(if (enabled) ConsoleColors.HighSurface else ConsoleColors.Elevated)
            .then(
                if (focused && enabled) {
                    Modifier.border(g.x(ConsoleDesign.FOCUS_STROKE), ConsoleColors.FocusRing, RoundedCornerShape(radius))
                } else {
                    Modifier.border(g.x(1), ConsoleColors.Steel, RoundedCornerShape(radius))
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = g.x(22), vertical = g.x(16)),
        horizontalArrangement = Arrangement.spacedBy(g.x(12)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (controllerGlyph != null) {
            ControllerGlyphBadge(controllerGlyph, filled = focused && enabled)
        }
        Text(
            label.uppercase(),
            color = if (enabled) ConsoleColors.TextPrimary else ConsoleColors.TextTertiary,
            fontSize = g.t(17),
            lineHeight = g.t(22),
            fontWeight = FontWeight.Bold,
            letterSpacing = g.t(17) * 0.04f,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Secondary action beside a primary CTA (e.g. "Start fresh"). */
@Composable
fun SecondaryConsoleAction(
    label: String,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reducedMotion: Boolean = isSystemReducedMotion(),
) {
    val g = LocalConsoleGeometry.current
    val active = focused && enabled
    val targetScale = if (active) ConsoleDesign.FOCUS_CONTROL_SCALE else 1f
    val animScale by animateFloatAsState(
        targetScale,
        animationSpec = ConsoleMotion.focus(),
        label = "secondary-scale",
    )
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD))
    Row(
        modifier = modifier
            .scale(if (reducedMotion) targetScale else animScale)
            .clip(shape)
            .background(if (active) ConsoleColors.HighSurface else Color.Transparent)
            .border(
                if (active) g.x(ConsoleDesign.FOCUS_STROKE) else g.x(1),
                if (active) ConsoleColors.FocusRing else ConsoleColors.Steel,
                shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = g.x(18), vertical = g.x(16)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            color = when {
                !enabled -> ConsoleColors.TextTertiary
                focused -> ConsoleColors.TextPrimary
                else -> ConsoleColors.TextSecondary
            },
            fontSize = g.t(15),
            lineHeight = g.t(22),
            fontWeight = FontWeight.Bold,
            letterSpacing = g.t(15) * 0.04f,
            maxLines = 1,
        )
    }
}

/** Secondary quiet action (tabs, details links): no ring unless focused. */
@Composable
fun QuietConsoleAction(
    label: String,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    Text(
        label,
        color = if (focused) ConsoleColors.TextPrimary else ConsoleColors.TextTertiary,
        fontSize = g.t(17),
        lineHeight = g.t(20),
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier
            .clip(RoundedCornerShape(g.x(8)))
            .clickable(onClick = onClick)
            .padding(horizontal = g.x(10), vertical = g.x(6)),
    )
}
