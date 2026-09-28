package com.gamebex.client.ui.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Pixel-grid glyphs: each is a list of axis-aligned rects on its icon grid so
 * edges stay crisp at thumbnail sizes.
 */

/** (x, y, w, h) on the glyph's icon grid. */
private class PixelShape(val gridW: Int, val gridH: Int, val rects: Array<IntArray>)

private fun r(x: Int, y: Int, w: Int, h: Int) = intArrayOf(x, y, w, h)

/** The source is 12×8 with only 7 rows used. */
private val CLOUD = PixelShape(
    12, 7,
    arrayOf(r(4, 0, 3, 1), r(3, 1, 5, 1), r(1, 2, 9, 1), r(1, 3, 10, 1), r(0, 4, 12, 3)),
)

/** Triangle with the "!" knocked out; the 11×11 source uses 9 rows. */
private val WARN = PixelShape(
    11, 9,
    arrayOf(
        r(5, 0, 1, 1),
        r(4, 1, 2, 2),
        r(3, 3, 2, 2), r(6, 3, 2, 2),
        r(2, 5, 3, 2), r(6, 5, 3, 2),
        r(1, 7, 9, 1),
        r(1, 8, 4, 1), r(6, 8, 4, 1),
    ),
)

private val PLUG = PixelShape(
    12, 12,
    arrayOf(
        r(3, 0, 1, 3), r(8, 0, 1, 3),
        r(1, 3, 10, 3),
        r(2, 6, 8, 1),
        r(3, 7, 6, 1),
        r(5, 8, 2, 2),
        r(6, 10, 4, 1),
        r(9, 11, 1, 1),
    ),
)

private val FOLDER = PixelShape(
    12, 12,
    arrayOf(
        r(0, 2, 5, 1),
        r(0, 3, 12, 1),
        r(0, 4, 1, 6), r(11, 4, 1, 6),
        r(0, 10, 12, 1),
    ),
)

/** Die face ring with five pips. */
private val DICE = PixelShape(
    12, 12,
    arrayOf(
        r(1, 1, 10, 1), r(1, 10, 10, 1),
        r(1, 2, 1, 8), r(10, 2, 1, 8),
        r(3, 3, 2, 2), r(7, 3, 2, 2), r(5, 5, 2, 2), r(3, 7, 2, 2), r(7, 7, 2, 2),
    ),
)

/** Arrow over a ground bar. */
private val DOWNLOAD = PixelShape(
    12, 12,
    arrayOf(
        r(5, 0, 2, 6),
        r(3, 6, 6, 1),
        r(4, 7, 4, 1),
        r(5, 8, 2, 1),
        r(0, 10, 12, 2),
    ),
)

private fun DrawScope.drawPixelShape(shape: PixelShape, tint: Color) {
    val u = size.width / shape.gridW
    val v = size.height / shape.gridH
    for (rect in shape.rects) {
        drawRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(rect[0] * u, rect[1] * v),
            size = androidx.compose.ui.geometry.Size(rect[2] * u, rect[3] * v),
        )
    }
}

@Composable
private fun PixelGlyph(shape: PixelShape, tint: Color, modifier: Modifier) {
    Canvas(modifier) { drawPixelShape(shape, tint) }
}

@Composable
fun CloudGlyph(tint: Color, modifier: Modifier = Modifier) =
    PixelGlyph(CLOUD, tint, modifier)

@Composable
fun WarnGlyph(tint: Color, modifier: Modifier = Modifier) =
    PixelGlyph(WARN, tint, modifier)

@Composable
fun PlugGlyph(tint: Color, modifier: Modifier = Modifier) =
    PixelGlyph(PLUG, tint, modifier)

@Composable
fun FolderGlyph(tint: Color, modifier: Modifier = Modifier) =
    PixelGlyph(FOLDER, tint, modifier)

@Composable
fun DiceGlyph(tint: Color, modifier: Modifier = Modifier) =
    PixelGlyph(DICE, tint, modifier)

@Composable
fun DownloadGlyph(tint: Color, modifier: Modifier = Modifier) =
    PixelGlyph(DOWNLOAD, tint, modifier)
