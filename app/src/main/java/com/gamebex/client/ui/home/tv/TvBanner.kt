package com.gamebex.client.ui.home.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.gamebex.client.ui.Silkscreen
import com.gamebex.client.ui.SpaceGrotesk
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.home.model.TvGlyph
import com.gamebex.client.ui.home.model.TvModel

/** Mockup `.osd .l1 svg` glyph boxes: 14×17, cloud 24×16. */
private fun glyphBox(glyph: TvGlyph?): Pair<Float, Float> = when (glyph) {
    TvGlyph.CLOUD, TvGlyph.OFFLINE -> 24f to 16f
    null -> 0f to 0f
    else -> 14f to 17f
}

/**
 * TV banner type at the mockup size: both
 * banner lines are pinned to the mockup and never inflated by the app's
 * text-size preference (which defaults to Large = 1.25 and made the state
 * line about 1.2× too big). Canvas scale still applies — the TV is fixed on
 * the canvas, so its type scales with the canvas but not with text size.
 */
internal fun ConsoleGeometry.mockupType(px: Int): TextUnit = t(px) / textScale

/** How the state line was fitted (pure policy result; tested). */
internal data class StateLineFit(
    /** Type size in sp — never below the 15 px mockup floor. */
    val sizeSp: Float,
    /** Letter-spacing factor (em). */
    val tracking: Float,
    /** Width reserved for `glyph + gap + key` (px); the key is never cut. */
    val keyNeedPx: Float,
    /** Width the detail may use (px); the detail ellipsizes inside it. */
    val detailAvailPx: Float,
    /** True when the line did not fit at the floor and the detail is cut to fit. */
    val detailEllipsized: Boolean,
)

/**
 * Fit the two-tone state line into [availPx]:
 * always ONE line, never wraps. Measure with the real font metrics at the
 * real available width and step key and detail down TOGETHER (mockup 19 px
 * down to a 15 px floor). If the line still does not fit at the floor, the
 * detail ellipsizes into whatever is left after the key — the key ("RESUME",
 * "NEEDS THE SATURN BIOS") is always rendered whole.
 */
internal fun fitStateLine(
    key: String,
    detail: String?,
    sizes: List<Float>,
    trackings: List<Float>,
    availPx: Float,
    glyphPx: Float,
    gapPx: Float,
    splitPx: Float,
    measure: (text: String, sizeSp: Float, tracking: Float) -> Float,
): StateLineFit {
    require(sizes.isNotEmpty() && sizes.size == trackings.size)
    fun keyNeed(sizeSp: Float, tracking: Float): Float =
        glyphPx + gapPx + measure(key, sizeSp, tracking)

    fun detailNeed(sizeSp: Float, tracking: Float): Float =
        if (detail == null) 0f else measure(detail, sizeSp, tracking)

    fun oneLineNeed(sizeSp: Float, tracking: Float): Float =
        keyNeed(sizeSp, tracking) +
            (if (detail == null) 0f else splitPx + detailNeed(sizeSp, tracking))

    for (i in sizes.indices) {
        if (oneLineNeed(sizes[i], trackings[i]) <= availPx) {
            return StateLineFit(
                sizeSp = sizes[i],
                tracking = trackings[i],
                keyNeedPx = keyNeed(sizes[i], trackings[i]),
                detailAvailPx = if (detail == null) 0f else detailNeed(sizes[i], trackings[i]),
                detailEllipsized = false,
            )
        }
    }
    // Floor reached and still no fit: the detail is cut, the key is not.
    val last = sizes.lastIndex
    val keyNeedPx = keyNeed(sizes[last], trackings[last])
    val detailAvailPx =
        if (detail == null) 0f else (availPx - keyNeedPx - splitPx).coerceAtLeast(0f)
    return StateLineFit(
        sizeSp = sizes[last],
        tracking = trackings[last],
        keyNeedPx = keyNeedPx,
        detailAvailPx = detailAvailPx,
        detailEllipsized = detail != null &&
            detailNeed(sizes[last], trackings[last]) > detailAvailPx,
    )
}

/** State-line candidates: mockup px 19…15 at 0.04em — 15 px floor. */
internal fun stateLineCandidates(g: ConsoleGeometry): Pair<List<Float>, List<Float>> {
    val sizes = (19 downTo 15).map { g.mockupType(it).value }
    val trackings = (19 downTo 15).map { 0.04f }
    return sizes to trackings
}

/**
 * On-screen banner.
 *
 * Two-tone state line: `glyph` + `stateKey` in the tone colour (NORMAL pink,
 * GOOD mint, WARN amber, MUTED gray; an OFFLINE glyph is always amber so
 * offline is visible), then `stateDetail` in light gray,
 * then the facts line. Gradient scrim; state line Silkscreen 19 px bold with
 * the mockup's hard shadow and bottom-aligned glyph (`.osd .l1`), facts line
 * Space Grotesk 21 px. Never the title: the card already has it.
 *
 * The state line is sized to the mockup and fitted with [fitStateLine]:
 * always one line — key and detail step down together from 19 px to a 15 px
 * floor, and if the copy still does not fit, only the detail ellipsizes and
 * the key stays whole. The facts line is pinned to the mockup too and stays
 * one line, ellipsized.
 */
@Composable
fun TvBanner(tv: TvModel, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val shadowOffset = with(density) { Offset(g.x(2).toPx(), g.x(2).toPx()) }
    val keyColor = TvStyle.bannerKeyColor(tv.tone, tv.glyph)
    val stateShadow = TextStyle(
        shadow = Shadow(
            color = ConsoleColors.Background,
            offset = shadowOffset,
            blurRadius = 0f,
        ),
    )
    val (glyphW, glyphH) = glyphBox(tv.glyph)
    val glyphPx = with(density) { g.x(glyphW).toPx() }
    val gapPx = with(density) { g.x(10).toPx() }
    val splitPx = with(density) { g.x(12).toPx() }
    val availPx = with(density) { g.x(646 - 44).toPx() }

    val fit = remember(tv.stateKey, tv.stateDetail, availPx, glyphPx, gapPx, splitPx, g.scale) {
        val (sizes, trackings) = stateLineCandidates(g)
        fun width(text: String, sizeSp: Float, tracking: Float): Float =
            measurer.measure(
                text = text,
                style = TextStyle(
                    fontFamily = Silkscreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = sizeSp.toCompose(),
                    letterSpacing = (sizeSp * tracking).toCompose(),
                ),
                constraints = Constraints(maxWidth = Int.MAX_VALUE),
                softWrap = false,
            ).size.width.toFloat()
        fitStateLine(
            key = tv.stateKey,
            detail = tv.stateDetail,
            sizes = sizes,
            trackings = trackings,
            availPx = availPx,
            glyphPx = glyphPx,
            gapPx = gapPx,
            splitPx = splitPx,
            measure = ::width,
        )
    }

    val stateSize = fit.sizeSp.toCompose()
    val detailDp = with(density) { fit.detailAvailPx.toDp() }

    @Composable
    fun stateText(text: String, color: Color, mod: Modifier, overflow: TextOverflow) = Text(
        text = text,
        color = color,
        fontFamily = Silkscreen,
        fontWeight = FontWeight.Bold,
        fontSize = stateSize,
        lineHeight = stateSize * 1.1f,
        letterSpacing = stateSize * fit.tracking,
        style = stateShadow,
        maxLines = 1,
        softWrap = false,
        overflow = overflow,
        modifier = mod,
    )

    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.3f to ConsoleColors.Background.copy(alpha = 0.88f),
                    1f to ConsoleColors.Background.copy(alpha = 0.95f),
                ),
            )
            .padding(
                top = g.x(34),
                start = g.x(22),
                end = g.x(22),
                bottom = g.x(16),
            ),
        verticalArrangement = Arrangement.spacedBy(g.x(8)),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(g.x(12)),
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                // The key is measured whole and never constrained or cut:
                // unbounded width, visible overflow.
                Modifier.wrapContentWidth(unbounded = true),
                horizontalArrangement = Arrangement.spacedBy(g.x(10)),
                verticalAlignment = Alignment.Bottom,
            ) {
                tv.glyph?.let { glyph ->
                    TvStateGlyph(glyph, keyColor, Modifier.size(g.x(glyphW), g.x(glyphH)))
                }
                stateText(tv.stateKey, keyColor, Modifier, TextOverflow.Visible)
            }
            tv.stateDetail?.let { detail ->
                // The detail alone may ellipsize, inside the width the fit
                // left after the key.
                val detailMod =
                    if (fit.detailEllipsized) Modifier.width(detailDp) else Modifier
                stateText(detail, TvStyle.bannerDetailColor, detailMod, TextOverflow.Ellipsis)
            }
        }
        tv.factsLine?.let { facts ->
            Text(
                text = facts,
                color = ConsoleColors.TextPrimary,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = g.mockupType(21),
                lineHeight = g.mockupType(27),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** sp from a plain float (sizes live in sp throughout the banner). */
private fun Float.toCompose(): TextUnit = this.sp
