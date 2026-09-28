package com.gamebex.client.ui.console

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import coil.request.ImageRequest
import com.gamebex.client.sources.SourceImage
import com.gamebex.client.sources.rememberSourceImageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class GameEnvironmentModel(
    val heroPath: String?,
    val dominantColor: Color?,
    val secondaryColor: Color? = null,
    /** Remote hero (catalog covers): scoped loader, offline degrades clean. */
    val heroImage: SourceImage? = null,
    /** Artwork revision: busts image + palette caches when art is replaced. */
    val artRevision: Any? = null,
)

/**
 * Versioned cache key for artwork at a stable path: explicit [revision] wins,
 * else the file's mtime.
 */
fun artCacheKey(path: String, revision: Any?, lastModified: Long): String =
    if (revision != null) "$path#$revision" else "$path@$lastModified"

/** Coil request for a local art file, keyed so replacements reload. */
fun artImageRequest(context: Context, file: File, revision: Any?): ImageRequest {
    val key = artCacheKey(file.absolutePath, revision, file.lastModified())
    return ImageRequest.Builder(context)
        .data(file)
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .build()
}

/**
 * Game-reactive backdrop: right-biased hero, dark mask, then palette glow,
 * over a shell base the caller paints. Never a focus owner.
 *
 * @param region geometry of the hero zone in design px: (x, y, w, h).
 */
@Composable
fun BoxScope.GameEnvironment(
    model: GameEnvironmentModel,
    region: GameEnvRegion,
    reducedMotion: Boolean = isSystemReducedMotion(),
    modifier: Modifier = Modifier,
    /**
     * True when the caller paints one continuous page scrim (Home):
     * skip the directional mask, feather the hero's left 80px and
     * terminate its bottom fully into the void so no hard edge shows.
     */
    continuousScrim: Boolean = false,
) {
    val g = LocalConsoleGeometry.current
    val context = LocalContext.current
    val file = model.heroPath?.let { File(it) }?.takeIf { it.isFile }
    val scoped = rememberSourceImageModel(model.heroImage)
    // Versioned request: a replaced file at the same path reloads instead of
    // serving the stale cached bitmap.
    val hero: Any? = remember(file, scoped, model.artRevision) {
        if (file != null) artImageRequest(context, file, model.artRevision)
        else scoped?.request
    }
    val loader = scoped?.loader ?: LocalImageLoader.current
    // Crossfade key includes the revision so replacements re-fade.
    val heroState = hero to model.artRevision
    Box(
        modifier = modifier
            .align(Alignment.TopStart)
            .offset(x = g.x(region.x), y = g.x(region.y))
            .width(g.x(region.w))
            .fillMaxHeight(if (region.h >= 700) 1f else region.h / 720f),
    ) {
        if (hero != null) {
            if (reducedMotion) {
                AsyncImage(
                    model = hero, imageLoader = loader, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            } else {
                Crossfade(
                    targetState = heroState,
                    animationSpec = tween(ConsoleMotion.HERO_MS),
                    label = "hero-xfade",
                ) { (h, _) ->
                    if (h != null) {
                        AsyncImage(
                            model = h, imageLoader = loader, contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        } else if (model.dominantColor != null) {
            // No art: color wash plus a fixed circle motif, never a broken tile.
            val wash = model.dominantColor
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                wash.copy(alpha = 0.5f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawCircle(
                    color = wash.copy(alpha = 0.10f),
                    radius = w * 0.30f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.24f),
                )
                drawCircle(
                    color = wash.copy(alpha = 0.08f),
                    radius = w * 0.18f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.72f),
                )
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
                    radius = w * 0.12f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.92f, h * 0.60f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                )
            }
        }
        if (continuousScrim) {
            val feather = 80f / region.w
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to ConsoleColors.Background,
                            feather to Color.Transparent,
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.6f to Color.Transparent,
                            1f to ConsoleColors.Background,
                        ),
                    ),
            )
        } else {
            // Directional dark mask: functional left/center stays readable.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                ConsoleColors.Background,
                                ConsoleColors.Background.copy(alpha = 0.72f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                ConsoleColors.Background.copy(alpha = 0.55f),
                            ),
                        ),
                    ),
            )
        }
        if (model.dominantColor != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.16f)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(model.dominantColor, Color.Transparent),
                        ),
                    ),
            )
        }
    }
}

data class GameEnvRegion(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Average color of local art from a ~32px sample, probed off the main thread;
 * [fallback] until resolved. [revision] re-probes when art is replaced.
 */
@Composable
fun rememberArtColor(artPath: String?, fallback: Color, revision: Any? = null): Color {
    var color by remember(artPath, revision) { mutableStateOf<Color?>(null) }
    LaunchedEffect(artPath, revision) {
        color = withContext(Dispatchers.IO) {
            artPath?.let { probeAverageColor(File(it)) }
        }
    }
    return color ?: fallback
}

fun probeAverageColor(file: File): Color? {
    if (!file.isFile || file.length() > 20 * 1024 * 1024) return null
    return try {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > 32 || opts.outHeight / sample > 32) sample *= 2
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        var r = 0L
        var gg = 0L
        var b = 0L
        var n = 0L
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                // Skip near-black/near-white pixels so letterbox doesn't win.
                val lum = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) +
                    0.114f * (p and 0xFF)
                if (lum < 12 || lum > 243) continue
                r += (p shr 16) and 0xFF
                gg += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        bmp.recycle()
        if (n == 0L) return null
        Color((r / n).toInt(), (gg / n).toInt(), (b / n).toInt())
    } catch (_: Exception) {
        null
    }
}
