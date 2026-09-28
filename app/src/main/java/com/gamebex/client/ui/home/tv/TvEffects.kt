package com.gamebex.client.ui.home.tv

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntSize
import com.gamebex.client.R
import com.gamebex.client.ui.Silkscreen
import com.gamebex.client.ui.SpaceGrotesk
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.home.components.CloudGlyph
import com.gamebex.client.ui.home.components.DiceGlyph
import com.gamebex.client.ui.home.components.DownloadGlyph
import com.gamebex.client.ui.home.components.FolderGlyph
import com.gamebex.client.ui.home.components.PlugGlyph
import com.gamebex.client.ui.home.components.SegmentRing
import com.gamebex.client.ui.home.components.WarnGlyph
import com.gamebex.client.ui.home.model.ButtonTone
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvGlyph
import com.gamebex.client.ui.home.model.TvTone
import kotlin.math.floor
import kotlin.random.Random

/**
 * TV look tokens and effects.
 *
 * Tone → token colors live in [TvStyle] and channel-change timing in
 * [TvTiming] / [TvSwapHolder]; both pure and unit-tested. Effects are static
 * by default: the only animation is the 160 ms static burst on a channel
 * change with the 120 ms content crossfade under it and the channel flash,
 * all skipped under reduced motion. The LED never animates.
 */

/** Tone and button colors. Pure; tested. */
object TvStyle {
    /** Banner key color by tone: NORMAL pink, GOOD mint, WARN amber, MUTED gray. */
    fun toneColor(tone: TvTone): Color = when (tone) {
        TvTone.NORMAL -> ConsoleColors.Accent
        TvTone.GOOD -> ConsoleColors.Success
        TvTone.WARN -> ConsoleColors.Warning
        TvTone.MUTED -> ConsoleColors.TextTertiary
    }

    /**
     * Display tone of a banner state: an OFFLINE glyph always
     * renders amber so offline is visible; every other state keeps the tone
     * the model set.
     */
    fun displayTone(tone: TvTone, glyph: TvGlyph?): TvTone =
        if (glyph == TvGlyph.OFFLINE) TvTone.WARN else tone

    /** Two-tone banner: glyph + stateKey in this color. */
    fun bannerKeyColor(tone: TvTone, glyph: TvGlyph?): Color = toneColor(displayTone(tone, glyph))

    /** Two-tone banner: stateDetail in light gray. */
    val bannerDetailColor: Color = ConsoleColors.TextSecondary

    /** Download-ring percent: floor of the 0..1 [progress] (mockup `Math.floor`). */
    fun ringPercent(progress: Float): Int =
        floor(progress * 100f + 0.001f).toInt().coerceIn(0, 100)

    /** Download-ring center label (mockup `.bed .ringbox b`). */
    fun ringPercentLabel(progress: Float): String = "${ringPercent(progress)}%"

    /** Primary button fill. */
    fun buttonFill(tone: ButtonTone): Color = when (tone) {
        ButtonTone.PLAY -> ConsoleColors.Accent
        ButtonTone.DOWNLOAD -> ConsoleColors.TextPrimary
        ButtonTone.FIX -> ConsoleColors.Warning
        ButtonTone.ARMED -> ConsoleColors.Success
        ButtonTone.NEUTRAL -> ConsoleColors.HighSurface
    }

    /** Label ink on [buttonFill]. */
    fun buttonInk(tone: ButtonTone): Color = when (tone) {
        ButtonTone.PLAY, ButtonTone.DOWNLOAD, ButtonTone.FIX, ButtonTone.ARMED ->
            ConsoleColors.Background
        ButtonTone.NEUTRAL -> ConsoleColors.TextPrimary
    }

    /** LED: static green, amber on WARN. */
    fun ledColor(tone: TvTone): Color =
        if (tone == TvTone.WARN) ConsoleColors.Warning else ConsoleColors.Success

    /** Hard pixel-shadow under buttons and the bezel (mockup's offset shadows). */
    fun shadowColor(fill: Color): Color = when (fill) {
        ConsoleColors.Accent -> ConsoleColors.AccentShade
        else -> shade(fill, 0.55f)
    }

    /** Darkened fill for hard offset shadows. */
    fun shade(fill: Color, factor: Float): Color =
        Color(fill.red * factor, fill.green * factor, fill.blue * factor)
}

/**
 * Channel-change timing: burst 160 ms in three steps (mockup
 * `.noise.go{animation:tvz .16s steps(3) 1}`), content crossfade 120 ms
 * (mockup `.screen .layer{transition:opacity .12s ease}`) under the burst.
 * Pure; tested with explicit times (virtual time, no polling).
 */
object TvTiming {
    /** Total static burst. */
    const val BURST_MS = 160

    /** Content crossfade under the burst. */
    const val FADE_MS = 120

    /** Burst step opacities; at most 4 cached noise frames. */
    val BURST_ALPHAS = listOf(0.95f, 0.6f, 0.25f)

    /** Burst step index at [elapsedMs] since the swap. */
    fun burstStepAt(elapsedMs: Int): Int =
        ((elapsedMs.coerceAtLeast(0) * BURST_ALPHAS.size) / BURST_MS)
            .coerceIn(0, BURST_ALPHAS.size - 1)

    /** Burst opacity at [elapsedMs], or null once the burst is over. */
    fun burstAlphaAt(elapsedMs: Int): Float? = when {
        elapsedMs < 0 -> BURST_ALPHAS[0]
        elapsedMs >= BURST_MS -> null
        else -> BURST_ALPHAS[burstStepAt(elapsedMs)]
    }

    /** Incoming-content alpha at [elapsedMs] (0..1, mockup CSS `ease`). */
    fun crossfadeAlpha(elapsedMs: Int): Float =
        FastOutSlowInEasing.transform((elapsedMs.toFloat() / FADE_MS).coerceIn(0f, 1f))
}

/**
 * One channel swap's effects. Created per swap via
 * `remember(channel)` in the same composition that shows the new content:
 * the constructor pins the outgoing [from] content and arms the static burst,
 * so the burst starts in the swap frame and the old content is still fully
 * visible under it — there is never a bare cut before the static.
 * [advance] is fed from a frame clock; state is snapshot-backed so the draw
 * phase picks up every tick without recomposition.
 */
class TvSwapHolder private constructor(
    val from: TvContent?,
    reducedMotion: Boolean,
) {
    companion object {
        /** First composition: nothing animates. */
        fun idle(): TvSwapHolder = TvSwapHolder(null, true)

        /** Channel change: crossfade [from] out under the burst, or snap. */
        fun swap(from: TvContent, reducedMotion: Boolean): TvSwapHolder =
            TvSwapHolder(from, reducedMotion)
    }

    /** True while the crossfade runs; read in composition (stable across frames). */
    val fading = mutableStateOf(from != null && !reducedMotion)

    /** Incoming content alpha (draw phase). */
    val newAlpha = mutableFloatStateOf(if (fading.value) 0f else 1f)

    /** Current burst step opacity, or null when the burst is over (draw phase). */
    val burstAlpha = mutableStateOf(if (fading.value) TvTiming.BURST_ALPHAS[0] else null)

    /** Current burst noise frame index (draw phase). */
    val burstStep = mutableIntStateOf(0)

    /** Outgoing content alpha (draw phase). */
    val oldAlpha: Float get() = 1f - newAlpha.value

    /** True until the crossfade and the burst have both finished. */
    val animating: Boolean get() = fading.value || burstAlpha.value != null

    /** Advance both effects to [elapsedMs] since the swap. */
    fun advance(elapsedMs: Int) {
        if (fading.value) {
            newAlpha.value = TvTiming.crossfadeAlpha(elapsedMs)
            if (elapsedMs >= TvTiming.FADE_MS) {
                newAlpha.value = 1f
                fading.value = false
            }
        }
        burstAlpha.value = TvTiming.burstAlphaAt(elapsedMs)
        burstStep.value = TvTiming.burstStepAt(elapsedMs)
    }
}

/** One cached scanline bitmap: [periodPx] period, 12% black half-duty. */
private fun scanlineBrush(periodPx: Int): ShaderBrush {
    val bitmap = ImageBitmap(2, periodPx)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val paint = Paint().apply { color = Color.Black.copy(alpha = 0.12f) }
    canvas.drawRect(Rect(0f, 0f, 2f, periodPx / 2f), paint)
    return ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

/**
 * CRT bed checkerboard (mockup `.bed:before`): 8-design-px tile of 4px
 * squares, dark quadrants only.
 */
private fun checkerBrush(periodPx: Int, cell: Color): ShaderBrush {
    val p = periodPx.coerceAtLeast(4)
    val half = p / 2
    val bitmap = ImageBitmap(p, p)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val paint = Paint().apply { color = cell }
    canvas.drawRect(Rect(0f, 0f, half.toFloat(), half.toFloat()), paint)
    canvas.drawRect(Rect(half.toFloat(), half.toFloat(), p.toFloat(), p.toFloat()), paint)
    return ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

/** Deterministic static-burst noise frame (54×33, chunky pixels). */
private fun noiseFrame(seed: Int, width: Int = 54, height: Int = 33): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val paint = Paint()
    val random = Random(seed)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val v = random.nextInt(236)
            paint.color = Color(v / 255f, v / 255f, (v + 10).coerceAtMost(255) / 255f)
            canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
        }
    }
    return bitmap
}

/** One filled horizontal run of a pixel glyph (inclusive columns). */
private data class GlyphRun(val y: Int, val x0: Int, val x1: Int)

private data class PixelShape(val cols: Int, val rows: Int, val runs: List<GlyphRun>)

/** Merge filled cells into per-row runs (one drawRect per run, no seams). */
private fun pixelShape(cols: Int, rows: Int, cells: Set<Pair<Int, Int>>): PixelShape =
    PixelShape(
        cols,
        rows,
        cells.groupBy({ it.second }, { it.first }).flatMap { (y, xs) ->
            val sorted = xs.sorted()
            val runs = mutableListOf<GlyphRun>()
            var start = sorted.first()
            var prev = start
            for (x in sorted.drop(1)) {
                if (x == prev + 1) {
                    prev = x
                    continue
                }
                runs += GlyphRun(y, start, prev)
                start = x
                prev = x
            }
            runs += GlyphRun(y, start, prev)
            runs
        },
    )

/** Mockup `PLAY` (6×7): the RESUME play triangle. */
private fun playShape(): PixelShape = pixelShape(6, 7, buildSet {
    for (y in 0..6) {
        val last = minOf(y, 6 - y, 3)
        for (x in 0..last) add(x to y)
    }
})

/** Mockup `U.cloud` (12×8): the cloud mark (base of [offlineShape]). */
private fun cloudShape(): PixelShape = pixelShape(12, 8, buildSet {
    val rowSpans = listOf(4..6, 3..7, 1..9, 1..10, 0..11, 0..11, 0..11)
    rowSpans.forEachIndexed { y, span -> span.forEach { x -> add(x to y) } }
})

/** NEW: diamond sparkle (no mockup glyph exists for NEW). */
private fun newShape(): PixelShape = pixelShape(7, 7, buildSet {
    val rowSpans = listOf(3..3, 2..4, 1..5, 0..6, 1..5, 2..4, 3..3)
    rowSpans.forEachIndexed { y, span -> span.forEach { x -> add(x to y) } }
})

/** OFFLINE: the cloud mark slashed through. */
private fun offlineShape(): PixelShape = pixelShape(12, 8, buildSet {
    cloudShape().runs.forEach { run ->
        for (x in run.x0..run.x1) {
            val diagonal = x + run.y
            if (diagonal != 10 && diagonal != 12) add(x to run.y)
        }
    }
    // Slash on the x+y==11 diagonal; the gap runs along x+y==10 and ==12.
    for (y in 0..7) add((11 - y) to y)
})

/** Draw one local pixel shape stretched into the given box. */
@Composable
private fun PixelCanvas(shape: PixelShape, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val cellW = size.width / shape.cols
        val cellH = size.height / shape.rows
        for (run in shape.runs) {
            drawRect(
                color = color,
                topLeft = Offset(run.x0 * cellW, run.y * cellH),
                // + epsilon keeps adjacent rows seam-free at fractional cells.
                size = Size((run.x1 - run.x0 + 1) * cellW, cellH + 0.5f),
            )
        }
    }
}

/**
 * Leading pixel glyph of the banner state key. CLOUD, WARN and DOWNLOAD
 * render through the shared `components.PixelGlyphs` ports so the banner and
 * the card state lines draw identical marks; RESUME, NEW and OFFLINE are
 * banner-only shapes from the same mockup SVG paths.
 */
@Composable
fun TvStateGlyph(glyph: TvGlyph, color: Color, modifier: Modifier = Modifier) {
    when (glyph) {
        TvGlyph.CLOUD -> CloudGlyph(color, modifier)
        TvGlyph.WARN -> WarnGlyph(color, modifier)
        TvGlyph.DOWNLOAD -> DownloadGlyph(color, modifier)
        TvGlyph.RESUME -> PixelCanvas(playShape(), color, modifier)
        TvGlyph.NEW -> PixelCanvas(newShape(), color, modifier)
        TvGlyph.OFFLINE -> PixelCanvas(offlineShape(), color, modifier)
        TvGlyph.PLUG -> PlugGlyph(color, modifier)
        TvGlyph.DICE -> DiceGlyph(color, modifier)
        TvGlyph.FOLDER -> FolderGlyph(color, modifier)
    }
}

/**
 * Scanlines (2 px period, 12% black, one cached bitmap) plus the CRT edge
 * vignette of the mockup (`inset 0 0 60px rgba(0,0,0,.55)`). The measured
 * mockup edge darkens the picture to ~76% over a 60 px reach; the vignette
 * reproduces that lightly (a stronger 0.55/103 px gradient smeared the test
 * card's left bar).
 */
@Composable
fun TvScreenEffects(modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    val density = LocalDensity.current
    val periodPx = with(density) { g.x(2).toPx() }.toInt().coerceAtLeast(2)
    val brush = remember(periodPx) { scanlineBrush(periodPx) }
    Box(
        modifier.drawWithCache {
            onDrawBehind {
                drawRect(brush)
                val edge = ConsoleColors.Background.copy(alpha = 0.26f)
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to edge, 0.16f to Color.Transparent,
                        0.84f to Color.Transparent, 1f to edge,
                    ),
                )
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to edge, 0.10f to Color.Transparent,
                        0.90f to Color.Transparent, 1f to edge,
                    ),
                )
            }
        },
    )
}

/** The TV bed's darkened checkerboard texture. */
@Composable
fun TvBedTexture(modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    val density = LocalDensity.current
    val periodPx = with(density) { g.x(8).toPx() }.toInt().coerceAtLeast(4)
    val brush = remember(periodPx) {
        checkerBrush(periodPx, ConsoleColors.Background.copy(alpha = 0.35f))
    }
    Box(modifier.drawWithCache { onDrawBehind { drawRect(brush) } })
}

/**
 * Static burst: three cached noise frames over 160 ms, armed by
 * [TvSwapHolder] in the same composition as the content swap — the first
 * noise frame lands in the swap frame, so there is never a bare cut before
 * the static. Keyed on the channel change, so a download progress update on
 * the selected card never re-bursts. Draws nothing between swaps and under
 * reduced motion.
 */
@Composable
fun TvStaticBurst(swap: TvSwapHolder, modifier: Modifier = Modifier) {
    val frames = remember { List(TvTiming.BURST_ALPHAS.size) { noiseFrame(it) } }
    Canvas(modifier) {
        val alpha = swap.burstAlpha.value ?: return@Canvas
        val frame = swap.burstStep.value.coerceIn(0, frames.size - 1)
        drawImage(
            frames[frame],
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = alpha,
            filterQuality = FilterQuality.None,
        )
    }
}

// Static-burst contract: "at most 4 cached noise frames".
internal val tvNoiseFrameCount: Int get() = TvTiming.BURST_ALPHAS.size

/**
 * The large download ring over the TV art (mockup `.bed .ringbox`): a
 * 150 px [SegmentRing] on a dark disc with the percentage in mint, centred
 * over the cover.
 */
@Composable
fun TvDownloadRing(progress: Float, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(g.x(16))
                .clip(CircleShape)
                .background(ConsoleColors.Background.copy(alpha = 0.72f)),
        )
        SegmentRing(progress = progress, modifier = Modifier.fillMaxSize())
        Text(
            text = TvStyle.ringPercentLabel(progress),
            color = ConsoleColors.Success,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = g.t(36),
            lineHeight = g.t(36),
            maxLines = 1,
        )
    }
}

/**
 * "CH 01" flash on a selection swap (mockup `.chn`): shows 1.5 s, fades out
 * over 0.5 s. With [reducedMotion] it snaps on and off, never animating.
 * [channel] is 0 when there is no channel to show; the flash keys on
 * [swapKey] (the selection), so a position shift above the cursor never
 * re-triggers it.
 */
@Composable
fun TvChannelIndicator(
    channel: Int,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    swapKey: Any = channel,
) {
    if (channel <= 0) return
    var shown by remember(swapKey) { mutableStateOf(true) }
    LaunchedEffect(swapKey) {
        kotlinx.coroutines.delay(1500)
        shown = false
    }
    val animated by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(500),
        label = "tv-channel",
    )
    val alpha = if (reducedMotion) if (shown) 1f else 0f else animated
    if (alpha <= 0f) return
    val g = LocalConsoleGeometry.current
    Text(
        text = HomeCopy.tvChannelPrefix + channel.toString().padStart(2, '0'),
        color = ConsoleColors.Success.copy(alpha = alpha),
        fontFamily = Silkscreen,
        fontWeight = FontWeight.Bold,
        fontSize = g.t(26),
        modifier = modifier,
    )
}

/** LED with its static halo (never blinks). */
@Composable
fun TvLed(color: Color, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    Box(
        modifier.drawWithCache {
            onDrawBehind {
                val halo = g.x(4).toPx()
                drawRoundRect(
                    color = color.copy(alpha = 0.25f),
                    topLeft = Offset(-halo, -halo),
                    size = Size(
                        size.width + 2 * halo,
                        size.height + 2 * halo,
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(halo),
                )
                drawRoundRect(color = color)
            }
        },
    )
}

/**
 * "♥ GAMEBEX" bezel badge (mockup `.bezel .badge`): 12 px
 * Silkscreen bold, hugging its caps so the lip row centres it exactly in the
 * 31 px lip. The mockup icon is 15×14 at 80% opacity.
 */
@Composable
fun TvBadge(modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(g.x(8)),
    ) {
        Image(
            painterResource(R.drawable.gamebex_icon_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(width = g.x(15), height = g.x(14))
                .alpha(0.8f),
        )
        Text(
            text = HomeCopy.tvBadge,
            color = ConsoleColors.TextTertiary,
            fontFamily = Silkscreen,
            fontWeight = FontWeight.Bold,
            fontSize = g.t(12),
            lineHeight = g.t(12),
            letterSpacing = g.t(12) * 0.08f,
            style = TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    trim = LineHeightStyle.Trim.Both,
                    alignment = LineHeightStyle.Alignment.Center,
                ),
            ),
            maxLines = 1,
        )
    }
}

/**
 * Focused-button ring: [strokePx] of [ConsoleColors.FocusRing] outside the
 * button, [padPx] off its edge (mockup `outline-offset:4px`).
 */
internal fun DrawScope.drawFocusRing(radiusPx: Float, padPx: Float, strokePx: Float) {
    drawRoundRect(
        color = ConsoleColors.FocusRing,
        topLeft = Offset(-padPx, -padPx),
        size = Size(size.width + 2 * padPx, size.height + 2 * padPx),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx + padPx),
        style = Stroke(width = strokePx),
    )
}

/** Hard pixel-shadow behind a rounded rect, drawn outside the bounds. */
internal fun DrawScope.drawHardShadow(fill: Color, offsetPx: Float, radiusPx: Float) {
    drawRoundRect(
        color = TvStyle.shadowColor(fill),
        topLeft = Offset(offsetPx, offsetPx),
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx),
    )
}
