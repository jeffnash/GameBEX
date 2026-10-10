package com.gamebex.client.stream.guest

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal class FlycastTexture(private val source: Bitmap) {
    private val pixels = IntArray(source.width * source.height).also {
        source.getPixels(it, 0, source.width, 0, 0, source.width, source.height)
    }
    private data class Key(val left: Float, val top: Float, val width: Float, val height: Float, val alpha: Int, val gray: Int, val inset: Int)
    private var lastKey: Key? = null
    private var lastRaster: Pair<ImageBitmap, IntOffset>? = null

    fun raster(rect: RectF, alpha: Int, gray: Float, inset: Int): Pair<ImageBitmap, IntOffset>? {
        if (source.width <= inset * 2 || source.height <= inset * 2) return null
        val key = Key(rect.left, rect.top, rect.width(), rect.height(), alpha, (gray * 255).roundToInt().coerceIn(0, 255), inset)
        if (key == lastKey) return lastRaster
        val left = floor(rect.left).toInt(); val top = floor(rect.top).toInt()
        val width = ceil(rect.right).toInt() - left; val height = ceil(rect.bottom).toInt() - top
        if (width <= 0 || height <= 0 || width.toLong() * height > 16_000_000) return null
        val sampled = renderStraightFrac(pixels, source.width, source.height, width, height, left, top,
            key.left, key.top, key.width, key.height, 1f, inset, key.gray, alpha, premultiplyOutput = true)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            // Sampling is finished; label these final pixels as premultiplied without another color conversion.
            setPremultiplied(false)
            setPixels(sampled, 0, width, 0, 0, width, height)
            setPremultiplied(true)
        }
        return (bitmap.asImageBitmap() to IntOffset(left, top)).also { lastKey = key; lastRaster = it }
    }
}
