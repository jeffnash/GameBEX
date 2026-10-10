package com.gamebex.client.ui.home.components

import kotlin.math.min

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.isSystemReducedMotion
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.LinkKind

/**
 * Quiet link row, 848×84: borderless, with an icon, the title, an optional
 * two-line body and a trailing action label.
 */
@Composable
internal fun QuietLinkRow(
    card: HomeCard,
    selected: Boolean,
    focused: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    val ts = homeTextScale(g)
    val kind = linkKindOf(card)
    CardBox(
        selected = selected,
        focused = focused,
        onTap = onTap,
        modifier = modifier,
        fill = ConsoleColors.Background,
        borderless = true,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = g.x(18), end = g.x(18)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (kind) {
                LinkKind.SEARCHING -> SearchSpinner()
                LinkKind.NO_PLUGIN_HINT -> PlugGlyph(
                    tint = ConsoleColors.TextTertiary,
                    modifier = Modifier.size(g.x(30)),
                )
                LinkKind.OFFLINE_NOTE -> CloudGlyph(
                    tint = ConsoleColors.TextTertiary,
                    modifier = Modifier.size(g.x(30), g.x(20)),
                )
                else -> Unit
            }
            Spacer(Modifier.width(g.x(14)))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                val body = card.body
                Text(
                    text = card.title,
                    color = if (selected || focused) {
                        ConsoleColors.TextPrimary
                    } else {
                        ConsoleColors.TextSecondary
                    },
                    fontSize = (20f * g.scale * ts).sp,
                    lineHeight = (25f * g.scale * ts).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (body == null) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (body != null) {
                    Spacer(Modifier.size(g.x(4)))
                    val bodySp = (22f * g.scale * ts).sp
                    val bodyMax = with(androidx.compose.ui.platform.LocalDensity.current) {
                        (twoLineBudgetPx(bodySp.toPx())).toDp()
                    }
                    Text(
                        text = body,
                        color = ConsoleColors.TextTertiary,
                        fontSize = (18f * g.scale * ts).sp,
                        lineHeight = bodySp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = linkBodyStyle(bodySp),
                        modifier = Modifier.heightIn(max = bodyMax),
                    )
                }
            }
            val label = card.primaryLabel
            if (label.isNotBlank()) {
                Spacer(Modifier.width(g.x(14)))
                Text(
                    text = label,
                    color = ConsoleColors.TextTertiary,
                    fontSize = (18f * g.scale * ts).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(g.x(6)))
                Text(
                    text = "▸",
                    color = ConsoleColors.TextTertiary,
                    fontSize = (16f * g.scale * ts).sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** `HomeCard` has no kind field; the CardKey carries it ("link:SEARCHING"). */
internal fun linkKindOf(card: HomeCard): LinkKind? =
    card.key.value.removePrefix("link:")
        .let { name -> LinkKind.entries.firstOrNull { it.name == name } }

/** Two line boxes of [lineHeightPx], each rounded up so a fractional line height never clips the second line. */
internal fun twoLineBudgetPx(lineHeightPx: Float): Float =
    kotlin.math.ceil(lineHeightPx) * 2f

/** Trims leadings and font padding so the body measures exactly [twoLineBudgetPx]. */
internal fun linkBodyStyle(lineHeight: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.text.TextStyle =
    androidx.compose.ui.text.TextStyle(
        lineHeight = lineHeight,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        ),
    )

@Composable
private fun SearchSpinner() {
    val g = LocalConsoleGeometry.current
    val reducedMotion = isSystemReducedMotion()
    val phase by if (reducedMotion) {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    } else {
        val transition = rememberInfiniteTransition(label = "quiet-spin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "quiet-phase",
        )
    }
    Canvas(Modifier.size(g.x(20))) {
        val stroke = g.x(3).toPx()
        val d = size.minDimension - stroke
        drawArc(
            color = ConsoleColors.Steel,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(d, d),
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
        drawArc(
            color = ConsoleColors.Success,
            startAngle = phase - 90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(d, d),
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
    }
}

private fun homeTextScale(g: ConsoleGeometry): Float = min(g.textScale, 1.1f)
