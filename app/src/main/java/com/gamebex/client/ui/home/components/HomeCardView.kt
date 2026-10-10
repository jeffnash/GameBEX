package com.gamebex.client.ui.home.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamebex.client.ui.Silkscreen
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleMotion
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.isSystemReducedMotion
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.HomeSection

/**
 * Card + group-label dispatch. The body routes [HomeCard.kind] to
 * GameCard / AllTile / InviteCard / QuietLinkRow and carries the shared
 * semantics.
 *
 * The focus look is the pink border, lighter fill and 5 px hard shadow in
 * [CardBox].
 *
 * Accessibility: the semantics `onClick` runs the card's primary action
 * **directly** via [onActivate] — a TalkBack double tap must not stop at
 * "select". Touch keeps the select-then-activate [onTap].
 */
@Composable
fun HomeCardView(
    card: HomeCard,
    selected: Boolean,
    focused: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit = { onTap() },
) {
    val g = LocalConsoleGeometry.current
    val line = card.cardLine
    Box(modifier = modifier.sizeIn(minWidth = g.hitMin(g.x(84)), minHeight = g.hitMin(g.x(84)))) {
        val isSelected = selected
        val w = if (card.span >= 2) 848 else 417
        val h = if (card.kind == CardKind.QUIET_LINK) 84 else 104
        val tap by rememberUpdatedState(onTap)
        val activate by rememberUpdatedState(onActivate)
        val cardSemantics = Modifier
            .size(g.x(w), g.x(h))
            .testTag("home-card-${card.key.value}")
            .semantics(mergeDescendants = true) {
                contentDescription = "${card.title}, $line"
                this.selected = isSelected
                onClick(label = card.primaryLabel) {
                    activate()
                    true
                }
            }
        when (card.kind) {
            CardKind.GAME -> GameCard(card, selected, focused, onTap, cardSemantics)
            CardKind.ALL_TILE -> AllTile(card, selected, focused, onTap, cardSemantics)
            CardKind.INVITE -> InviteCard(card, selected, focused, onTap, cardSemantics)
            CardKind.QUIET_LINK -> QuietLinkRow(card, selected, focused, onTap, cardSemantics)
        }
    }
}

/** Silkscreen 17 px uppercase in the group color + 16 px tertiary detail. */
@Composable
fun GroupLabel(section: HomeSection, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(g.x(36))
            // Group labels are headings for screen readers.
            .semantics { heading() },
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = section.label,
            color = groupLabelColor(section.group),
            fontFamily = Silkscreen,
            fontSize = g.t(17),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        section.detail?.let {
            Spacer(Modifier.size(g.x(12)))
            Text(
                text = it,
                color = ConsoleColors.TextTertiary,
                fontSize = g.t(16),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = g.x(2)),
            )
        }
    }
}

/** Group-label colors. */
@Composable
internal fun groupLabelColor(group: HomeGroup): Color = when (group) {
    HomeGroup.RESUME, HomeGroup.SETUP -> ConsoleColors.Accent
    HomeGroup.DOWNLOADING -> ConsoleColors.Success
    HomeGroup.NEW -> ConsoleColors.TextPrimary
    HomeGroup.SEARCH_YOURS, HomeGroup.SEARCH_SITE -> ConsoleColors.TextSecondary
    HomeGroup.SURPRISE -> ConsoleColors.Warning
}

/**
 * Shared card chrome: radius 10 container, 2 px Steel border (3 px pink
 * when focused), and a hard pink offset shadow drawn **outside** the card
 * only — the card region is punched out of the shadow path, so a
 * transparent tile can never tint its own fill.
 *
 * Focus look: focused = pink border + HighSurface fill + 5 px pink hard
 * shadow; selected but unfocused = muted-lavender border + the lighter
 * fill; pressed = 4 px down-right shift and the shadow collapses to 1 px.
 *
 * Taps: a stable `pointerInput(Unit)` with `rememberUpdatedState(onTap)`,
 * and `pressed` reset in a `try/finally` around `tryAwaitRelease()`, so a
 * card can neither stick pressed nor drop taps while downloads churn the
 * grid.
 */
@Composable
internal fun CardBox(
    selected: Boolean,
    focused: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = ConsoleColors.Surface,
    dashed: Boolean = false,
    borderless: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val reducedMotion = isSystemReducedMotion()
    var pressed by remember { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val shift by animateDpAsState(
        targetValue = if (pressed) g.x(4) else g.x(0),
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            tween(ConsoleMotion.PRESS_MS, easing = FastOutSlowInEasing)
        },
        label = "card-press",
    )
    val shadowPx = CardChrome.shadowOffsetPx(focused, pressed)
    val radius = g.x(CardChrome.CARD_RADIUS)
    val shape = RoundedCornerShape(radius)
    val borderWidth = if (borderless) 0.dp else g.x(CardChrome.borderWidthPx(focused))
    val borderColor = CardChrome.borderColor(focused, selected)
    val fillColor = CardChrome.fill(focused, selected, fill)
    Box(modifier = modifier.offset { androidx.compose.ui.unit.IntOffset(shift.roundToPx(), shift.roundToPx()) }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (shadowPx > 0) {
                        drawOutsideShadow(
                            offsetPx = g.x(shadowPx).toPx(),
                            radiusPx = radius.toPx(),
                        )
                    }
                }
                .clip(shape)
                .background(fillColor)
                .then(
                    when {
                        borderless -> Modifier
                        dashed -> Modifier.dashedBorder(borderWidth, borderColor, radius)
                        else -> Modifier.border(borderWidth, borderColor, shape)
                    },
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                pressed = false
                            }
                        },
                        onTap = { currentOnTap() },
                    )
                },
            content = content,
        )
    }
}

/**
 * The hard shadow, punched so it exists only **outside** the card:
 * offset rounded rect minus the card's own rect.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOutsideShadow(
    offsetPx: Float,
    radiusPx: Float,
) {
    val sz = this.size
    val shadowPath = Path().apply {
        addRoundRect(
            RoundRect(
                offsetPx,
                offsetPx,
                offsetPx + sz.width,
                offsetPx + sz.height,
                CornerRadius(radiusPx),
            ),
        )
    }
    val cardPath = Path().apply {
        addRoundRect(
            RoundRect(0f, 0f, sz.width, sz.height, CornerRadius(radiusPx)),
        )
    }
    drawPath(
        Path.combine(PathOperation.Difference, shadowPath, cardPath),
        color = ConsoleColors.AccentShade,
    )
}

/**
 * 1 px-grid dashed rounded border (All tile). Dash length scales with the
 * stroke so it stays legible at any geometry scale.
 */
private fun Modifier.dashedBorder(width: androidx.compose.ui.unit.Dp, color: Color, radius: androidx.compose.ui.unit.Dp): Modifier =
    drawWithContent {
        drawContent()
        if (width <= 0.dp) return@drawWithContent
        val w = width.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(w / 2, w / 2),
            size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(radius.toPx() - w / 2),
            style = Stroke(
                width = w,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 3, w * 3)),
            ),
        )
    }
