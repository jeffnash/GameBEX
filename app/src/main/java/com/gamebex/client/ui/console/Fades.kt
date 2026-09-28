package com.gamebex.client.ui.console

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * Fades the last [fade] of a horizontally overflowing row to transparent.
 * The offscreen layer clips to bounds.
 */
fun Modifier.fadeTrailing(fade: Dp, enabled: Boolean = true): Modifier =
    if (!enabled) this else graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val start = size.width - fade.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black, 1f to Color.Transparent,
                    startX = start, endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
