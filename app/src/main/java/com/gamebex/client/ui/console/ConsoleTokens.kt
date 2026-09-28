package com.gamebex.client.ui.console

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object ConsoleColors {
    val Background = Color(0xFF0D0D17) // void
    val Elevated = Color(0xFF111120)
    val Surface = Color(0xFF141423)
    val HighSurface = Color(0xFF1C1C2C)
    /** Dividers, idle strokes, progress tracks. */
    val Steel = Color(0xFF2A2A3A)

    /** Cream: never pure white for text. */
    val TextPrimary = Color(0xFFFFF6E9)
    val TextSecondary = Color(0xFFC8C2C8)
    // Tertiary floor: 4.5:1 on HighSurface for small meaningful labels.
    val TextTertiary = Color(0xFF9A94A1)

    val Accent = Color(0xFFFF4D8D)
    /** Pixel shade: hard shadows and pressed states. */
    val AccentShade = Color(0xFFA91E5C)
    val AccentDim = Color(0xFF321425)
    val Success = Color(0xFF4ADE9E)
    val Warning = Color(0xFFE2B45F)
    val Error = Color(0xFFE76E5C)

    val RailActive = Color(0xFF241321)
    val FocusRing = Color(0xFFFF4D8D)

    val Scrim70 = Color(0xB30D0D17)
    val Scrim90 = Color(0xE60D0D17)
}

/** Design-canvas px at 1600x720; scale them through [ConsoleGeometry]. */
object ConsoleDesign {
    const val WIDTH = 1600f
    const val HEIGHT = 720f
    const val RAIL_WIDTH = 96f
    const val CONTENT_LEFT = 120f
    const val CONTENT_RIGHT = 1480f
    const val CONTENT_TOP = 28f
    const val CONTENT_BOTTOM = 648f
    const val FOCUS_ANCHOR_X = 700f
    const val HINT_TOP = 668f
    const val HINT_BOTTOM = 708f

    /** Hint band when the rail is hidden on Home. */
    const val HINT_LEFT_FULL = 24f
    const val HINT_RIGHT_FULL = 1576f

    const val FOCUS_GAME_SCALE = 1.035f
    const val FOCUS_CONTROL_SCALE = 1.02f
    const val FOCUS_LIFT_PX = -4f

    const val RADIUS_PANEL = 12f
    const val RADIUS_CARD = 8f
    const val RADIUS_CHIP = 6f
    const val RADIUS_BADGE = 4f
    const val FOCUS_STROKE = 3f
    const val HARD_SHADOW = 4f
}

/** Brand motion: short ease-outs, no springs. */
object ConsoleMotion {
    const val FOCUS_MS = 110
    const val PRESS_MS = 70
    const val PANEL_MS = 160
    const val HERO_MS = 220
    fun <T> focus() = androidx.compose.animation.core.tween<T>(
        FOCUS_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing,
    )
}

/** Floor for meaningful labels: canvas type never renders below this. */
val MinReadableType: TextUnit = 10.sp

/** Minimum touch target (Material guideline); visuals may stay smaller. */
val TouchMinTarget: Dp = 48.dp

/**
 * Scaled accessor, provided once per console surface via [ConsoleGeometryRoot].
 * Usage: `val g = LocalConsoleGeometry.current; Modifier.width(g.x(148))`.
 */
class ConsoleGeometry(val scale: Float, val textScale: Float = 1f) {
    fun x(px: Float): Dp = (px * scale).dp
    fun x(px: Int): Dp = (px * scale).dp
    /** Canvas type × [textScale] (handheld text size setting). */
    fun t(px: Float): TextUnit = (px * scale * textScale).sp
    fun t(px: Int): TextUnit = (px * scale * textScale).sp

    /** Scaled type with a readability floor for meaningful labels. */
    fun tMin(px: Int, min: TextUnit = MinReadableType): TextUnit = floorType(t(px), min)
    fun tMin(px: Float, min: TextUnit = MinReadableType): TextUnit = floorType(t(px), min)

    private fun floorType(v: TextUnit, min: TextUnit): TextUnit =
        if (v.isSp && min.isSp && v.value >= min.value) v else min

    /** Touch target never below the guideline; pass the visual size. */
    fun hitMin(visual: Dp, min: Dp = TouchMinTarget): Dp = maxOf(visual, min)

    val railWidth: Dp get() = x(ConsoleDesign.RAIL_WIDTH)
    val contentStart: Dp get() = x(ConsoleDesign.CONTENT_LEFT)
    val contentTop: Dp get() = x(ConsoleDesign.CONTENT_TOP)
    val anchorX: Dp get() = x(ConsoleDesign.FOCUS_ANCHOR_X)
    /** Anchor in content-box coordinates (shelves live right of the rail). */
    val anchorXLocal: Dp get() = x(ConsoleDesign.FOCUS_ANCHOR_X - ConsoleDesign.RAIL_WIDTH)
}

val LocalConsoleGeometry = compositionLocalOf { ConsoleGeometry(1f) }

/**
 * Fit-both-dimensions scale: the 1600x720 canvas keeps its exact ratio and
 * never overflows a reduced-height window.
 */
fun consoleScale(windowWdp: Float, windowHdp: Float): Float =
    minOf(windowWdp / ConsoleDesign.WIDTH, windowHdp / ConsoleDesign.HEIGHT)
        .takeIf { it.isFinite() && it > 0f } ?: 1f

fun consoleCanvasDp(scale: Float): Pair<Dp, Dp> =
    (ConsoleDesign.WIDTH * scale).dp to (ConsoleDesign.HEIGHT * scale).dp

/**
 * Scale comes from measured window constraints, not
 * Configuration.screenWidthDp: with a full-bleed cutout (cutoutMode=always)
 * screenWidthDp still reports the cutout-safe width (877dp vs 917dp on one
 * device), which shrinks every position about 4%.
 */
@Composable
fun ConsoleGeometryRoot(
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleColors.Background),
    ) {
        val density = LocalDensity.current
        val view = LocalView.current
        // The canvas never rescales when the keyboard opens, or the whole UI
        // shrinks. Scale uses the window height without the IME:
        // (a) the IME is an inset: add it back to the measured height;
        // (b) adjustResize shrinks the window with no inset: keep the height
        //     last measured without the keyboard.
        // Rotation and split-screen happen with no IME and re-measure.
        val imeBottomDp = with(density) {
            WindowInsets.ime.getBottom(this).toDp()
        }
        val imeShowing = imeBottomDp > 0.dp ||
            androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        var anchoredH by remember { mutableStateOf(maxHeight.value) }
        val heightDp = when {
            imeBottomDp > 0.dp -> maxHeight.value + imeBottomDp.value
            imeShowing -> maxOf(maxHeight.value, anchoredH)
            else -> maxHeight.value
        }
        if (!imeShowing && anchoredH != maxHeight.value) anchoredH = maxHeight.value
        val scale = remember(maxWidth.value, heightDp) {
            consoleScale(maxWidth.value, heightDp)
        }
        val textScale = DisplayPrefs.textSize.factor
        val g = remember(scale, textScale) { ConsoleGeometry(scale, textScale) }
        CompositionLocalProvider(LocalConsoleGeometry provides g) {
            // Top-aligned while the keyboard clips the bottom, so the header
            // and the search field stay visible.
            val (w, h) = consoleCanvasDp(scale)
            Box(
                modifier = Modifier
                    .size(w, h)
                    .align(if (h > maxHeight) Alignment.TopCenter else Alignment.Center),
            ) {
                content()
            }
        }
    }
}

/** System reduced-motion signal: animator duration scale set to zero. */
@Composable
fun isSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    // A ContentObserver keeps this live, so consumers recompose when the
    // system setting changes mid-session.
    val reduced = remember(resolver) {
        androidx.compose.runtime.mutableStateOf(reducedMotionOf(resolver))
    }
    androidx.compose.runtime.DisposableEffect(resolver) {
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                reduced.value = reducedMotionOf(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced.value
}

internal fun reducedMotionOf(resolver: android.content.ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** WCAG relative luminance of an sRGB color. */
fun consoleLuminance(c: Color): Double {
    fun lin(v: Float): Double {
        val s = v.coerceIn(0f, 1f).toDouble()
        return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

/** WCAG contrast ratio (>= 4.5 for meaningful small text). */
fun consoleContrastRatio(a: Color, b: Color): Double {
    val la = consoleLuminance(a)
    val lb = consoleLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

