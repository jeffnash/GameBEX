package com.gamebex.client.ui.home.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.isSystemReducedMotion

private const val SEGMENTS = 16
private const val GAP_DEG = 7f
private const val SPIN_MS = 700

internal enum class RingFill { DETERMINATE, SPINNER, STATIC }

/** A paused download of unknown size draws a static ring, never a spinner. */
internal fun ringFill(progress: Float?, paused: Boolean): RingFill = when {
    progress != null -> RingFill.DETERMINATE
    paused -> RingFill.STATIC
    else -> RingFill.SPINNER
}

/**
 * Segmented download ring. [progress] runs 0–1; `null` is indeterminate: a
 * travelling highlight that holds still with reduced motion or when [paused].
 */
@Composable
fun SegmentRing(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = ConsoleColors.Success,
    track: Color = ConsoleColors.Steel,
    paused: Boolean = false,
    reducedMotion: Boolean = isSystemReducedMotion(),
) {
    val fill = ringFill(progress, paused)
    val phase = if (fill == RingFill.SPINNER && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "ring-spin")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(SPIN_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ring-phase",
        )
        p
    } else {
        0f
    }
    Canvas(modifier) {
        val stroke = (size.minDimension / 7f).coerceAtLeast(1f)
        val diameter = size.minDimension - stroke
        val topLeft = androidx.compose.ui.geometry.Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f,
        )
        val segDeg = 360f / SEGMENTS
        val lit = progress?.let { (it.coerceIn(0f, 1f) * SEGMENTS).toInt() } ?: -1
        val head = (phase * SEGMENTS).toInt()
        for (i in 0 until SEGMENTS) {
            val filled = when {
                progress != null -> i < lit
                else -> (i - head + SEGMENTS) % SEGMENTS in 0..2
            }
            drawArc(
                color = if (filled) color else track,
                startAngle = i * segDeg + GAP_DEG / 2f - 90f,
                sweepAngle = segDeg - GAP_DEG,
                useCenter = false,
                topLeft = topLeft,
                size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
        }
    }
}
