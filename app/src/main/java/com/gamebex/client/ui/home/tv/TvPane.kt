package com.gamebex.client.ui.home.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.gamebex.client.ui.SpaceGrotesk
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerPrefs
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.glyphLabel
import com.gamebex.client.ui.home.model.ButtonTone
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.TvModel

/** The TV set: a 672×422 bezel around the 646×378 screen, with the badge and LED in the 31 px lip. */
@Composable
fun TvPane(
    tv: TvModel,
    channel: Int,
    reducedMotion: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    selectionKey: String = channel.toString(),
) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(22))
    val radiusPx = g.x(22)
    val shadowPx = g.x(6)
    Box(modifier.size(g.x(672), g.x(422))) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRoundRect(
                        color = TvStyle.shadowColor(ConsoleColors.Background),
                        topLeft = androidx.compose.ui.geometry.Offset(
                            shadowPx.toPx(),
                            shadowPx.toPx(),
                        ),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx.toPx()),
                    )
                }
                .background(ConsoleColors.HighSurface, shape)
                .border(g.x(2), ConsoleColors.Steel, shape),
        ) {
            TvScreen(
                tv = tv,
                channel = channel,
                reducedMotion = reducedMotion,
                onTap = onTap,
                selectionKey = selectionKey,
                modifier = Modifier
                    .padding(start = g.x(13), top = g.x(13))
                    .size(g.x(646), g.x(378)),
            )
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(g.x(31))
                    .padding(horizontal = g.x(10)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TvBadge()
                TvLed(
                    TvStyle.ledColor(TvStyle.displayTone(tv.tone, tv.glyph)),
                    Modifier.size(g.x(10), g.x(6)),
                )
            }
        }
    }
}

/** [focusedButton]: −1 none, 0 primary, 1 secondary. */
@Composable
fun HomeActionRow(
    card: HomeCard?,
    focusedButton: Int,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    val secondaryLabel = card?.secondaryLabel
    Row(
        modifier.size(g.x(672), g.x(96)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeButton(
            label = card?.primaryLabel.orEmpty(),
            tone = card?.primaryTone ?: ButtonTone.NEUTRAL,
            focused = focusedButton == 0,
            enabled = card != null && card.primary != HomeAction.None,
            onClick = onPrimary,
            modifier = Modifier
                .weight(1f)
                .height(g.x(96))
                .sizeIn(minWidth = g.hitMin(g.x(84)), minHeight = g.hitMin(g.x(84))),
        )
        if (secondaryLabel != null) {
            Spacer(Modifier.width(g.x(14)))
            HomeSecondaryButton(
                label = secondaryLabel,
                focused = focusedButton == 1,
                enabled = card?.secondary != null && card.secondary != HomeAction.None,
                onClick = onSecondary,
                modifier = Modifier
                    .widthIn(min = g.x(130))
                    .height(g.x(96))
                    .sizeIn(minWidth = g.hitMin(g.x(84)), minHeight = g.hitMin(g.x(84))),
            )
        }
    }
}

@Composable
private fun HomeButton(
    label: String,
    tone: ButtonTone,
    focused: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    var pressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentEnabled by rememberUpdatedState(enabled)
    val fill = if (enabled) TvStyle.buttonFill(tone) else ConsoleColors.Elevated
    val ink = if (enabled) TvStyle.buttonInk(tone) else ConsoleColors.TextTertiary
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_PANEL))
    val radiusPx = g.x(ConsoleDesign.RADIUS_PANEL)
    val shadowPx = if (pressed) g.x(1) else g.x(6)
    val ringPadPx = g.x(4)
    val ringStrokePx = g.x(ConsoleDesign.FOCUS_STROKE)
    Row(
        modifier
            .offset {
                val shift = if (pressed) g.x(4).roundToPx() else 0
                androidx.compose.ui.unit.IntOffset(shift, shift)
            }
            .drawBehind {
                if (enabled) drawHardShadow(fill, shadowPx.toPx(), radiusPx.toPx())
                if (focused && enabled) {
                    drawFocusRing(radiusPx.toPx(), ringPadPx.toPx(), ringStrokePx.toPx())
                }
            }
            .clip(shape)
            .background(fill, shape)
            .testTag("home-primary")
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.onClick(label) {
                    val enabledNow = currentEnabled
                    if (enabledNow) currentOnClick()
                    enabledNow
                }
                if (!currentEnabled) disabled()
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!currentEnabled) return@detectTapGestures
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                    onTap = { if (currentEnabled) currentOnClick() },
                )
            }
            .padding(start = g.x(20), end = g.x(28)),
        horizontalArrangement = Arrangement.spacedBy(g.x(16)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeGlyphChip(tone = tone, enabled = enabled)
        Text(
            text = label,
            color = ink,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = g.t(26),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeGlyphChip(tone: ButtonTone, enabled: Boolean, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    val letterColor: Color = when {
        !enabled -> ConsoleColors.TextTertiary
        tone == ButtonTone.NEUTRAL -> ConsoleColors.TextPrimary
        else -> TvStyle.buttonFill(tone)
    }
    Box(
        modifier
            .size(g.x(38))
            .clip(CircleShape)
            .background(ConsoleColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyphLabel(ControllerGlyph.A, ControllerPrefs.layout, ControllerPrefs.swapConfirm),
            color = letterColor,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = g.t(18),
            maxLines = 1,
        )
    }
}

@Composable
private fun HomeSecondaryButton(
    label: String,
    focused: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    var pressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentEnabled by rememberUpdatedState(enabled)
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_PANEL))
    val radiusPx = g.x(ConsoleDesign.RADIUS_PANEL)
    val ringPadPx = g.x(4)
    val ringStrokePx = g.x(ConsoleDesign.FOCUS_STROKE)
    Box(
        modifier
            .offset {
                val shift = if (pressed) g.x(4).roundToPx() else 0
                androidx.compose.ui.unit.IntOffset(shift, shift)
            }
            .drawBehind {
                if (focused) drawFocusRing(radiusPx.toPx(), ringPadPx.toPx(), ringStrokePx.toPx())
            }
            .clip(shape)
            .background(ConsoleColors.Surface, shape)
            .border(g.x(2), ConsoleColors.Steel, shape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.onClick(label) {
                    val enabledNow = currentEnabled
                    if (enabledNow) currentOnClick()
                    enabledNow
                }
                if (!currentEnabled) disabled()
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!currentEnabled) return@detectTapGestures
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                    onTap = { if (currentEnabled) currentOnClick() },
                )
            }
            .padding(horizontal = g.x(26)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = g.t(23),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
