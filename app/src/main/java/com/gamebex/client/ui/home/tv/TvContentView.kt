package com.gamebex.client.ui.home.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.gamebex.client.R
import com.gamebex.client.ui.Silkscreen
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.rememberArtColor
import com.gamebex.client.ui.home.components.ArtImage
import com.gamebex.client.ui.home.components.CaseArtSize
import com.gamebex.client.ui.home.components.GameCaseArt
import com.gamebex.client.ui.home.components.PlugGlyph
import com.gamebex.client.ui.home.components.artFile
import com.gamebex.client.ui.home.components.caseColorFor
import com.gamebex.client.ui.home.components.rememberArtImage
import com.gamebex.client.ui.home.model.ArtKind
import com.gamebex.client.ui.home.model.ArtRef
import com.gamebex.client.ui.home.model.TvContent
import kotlin.math.min

/**
 * TV picture area.
 *
 * - `Picture`: a save shot fills the screen; a cover sits centered at the
 *   mockup geometry (176×238 at top 22, centre y 141) on a bed tinted
 *   from the art — falling back to the **system colour**, never the fixed
 *   maroon. Missing or failed art renders the procedural [GameCaseArt].
 * - `Mosaic`: 2–3 covers spread side by side.
 * - `TestCard`: SMPTE-style color bars plus the Silkscreen label.
 * - `PlugCard`: blue no-signal screen with a pixel plug glyph; the banner
 *   already carries the text, so the screen shows none.
 *
 * Art resolves exactly like `GameCover`: local files versioned by
 * [ArtRef.revision], plugin URLs only through the owning source's loader,
 * decoded at an explicit 646×378.
 */

@Composable
fun TvContentView(content: TvContent, modifier: Modifier = Modifier) {
    when (content) {
        is TvContent.Picture -> TvPicture(content.art, content.dim, modifier)
        is TvContent.Mosaic -> TvMosaic(content.arts, modifier)
        is TvContent.TestCard -> TvTestCard(content.label, modifier)
        is TvContent.PlugCard -> TvPlugCard(modifier)
        is TvContent.Notice -> TvNotice(content.glyph, content.label, content.tone, modifier)
    }
}

/** grayscale(.5) brightness(.55), the mockup's dimmed-cover filter. */
private fun dimColorFilter(): ColorFilter {
    val matrix = ColorMatrix().apply {
        setToSaturation(0.5f)
        timesAssign(
            ColorMatrix(
                floatArrayOf(
                    0.55f, 0f, 0f, 0f, 0f,
                    0f, 0.55f, 0f, 0f, 0f,
                    0f, 0f, 0.55f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
    return ColorFilter.colorMatrix(matrix)
}

@Composable
private fun TvPicture(art: ArtRef, dim: Boolean, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    val filter = remember(dim) { if (dim) dimColorFilter() else null }
    val image = rememberTvArtImage(art)
    val painter = image?.let { rememberAsyncImagePainter(it.model, it.loader) }
    val failed = painter != null && painter.state is AsyncImagePainter.State.Error
    if (art.kind == ArtKind.SAVE_THUMB && painter != null && !failed) {
        // A save shot fills the screen.
        Box(modifier) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = filter,
            )
        }
    } else {
        TvBed(art, modifier) {
            val coverMod = Modifier
                .align(Alignment.TopCenter)
                .offset(y = g.x(22))
                .size(g.x(176), g.x(238))
            val showArt = art.kind != ArtKind.NONE && painter != null && !failed
            if (showArt) {
                Box(
                    coverMod
                        .drawBehind {
                            val o = g.x(10).toPx()
                            drawRoundRect(
                                color = ConsoleColors.Background.copy(alpha = 0.5f),
                                topLeft = Offset(o, o),
                                size = size,
                                cornerRadius = CornerRadius(g.x(6).toPx()),
                            )
                        }
                        .clip(RoundedCornerShape(g.x(6)))
                        .alpha(if (dim) 0.55f else 1f),
                ) {
                    Image(
                        painter = painter!!,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        colorFilter = filter,
                    )
                }
            } else {
                Box(
                    coverMod.drawBehind {
                        val o = g.x(10).toPx()
                        drawRoundRect(
                            color = ConsoleColors.Background.copy(alpha = 0.5f),
                            topLeft = Offset(o, o),
                            size = size,
                            cornerRadius = CornerRadius(g.x(6).toPx()),
                        )
                    },
                ) {
                    GameCaseArt(
                        systemFolder = art.systemFolder,
                        title = art.title.orEmpty(),
                        size = CaseArtSize.LARGE,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(g.x(6)))
                            .alpha(if (dim) 0.55f else 1f),
                    )
                }
            }
        }
    }
}

/** Coil image for [art] at the TV decode size (646×378), or null. */
@Composable
private fun rememberTvArtImage(art: ArtRef): ArtImage? =
    rememberArtImage(art, 646, 378)

/** Art-color bed with the mockup checkerboard texture. */
@Composable
private fun TvBed(art: ArtRef, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    // The bed tints from real art, and falls back to the system colour —
    // never the fixed maroon.
    val bedColor = rememberArtColor(
        artPath = artFile(art)?.path,
        fallback = caseColorFor(art.systemFolder, art.title.orEmpty()),
        revision = art.revision,
    )
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(bedColor) },
        )
        // The mockup's `.bed:before` — a dark wash under the
        // checker, so a light case (lavender SNES) stands out on the bed.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(tvBedUnderlay()) },
        )
        TvBedTexture(Modifier.fillMaxSize())
        content()
    }
}

/**
 * The bed's dark wash (pure): `Background` at 60% under the
 * checker, like the mockup's `.bed:before`.
 */
internal fun tvBedUnderlay(): Color = ConsoleColors.Background.copy(alpha = 0.6f)

@Composable
private fun TvMosaic(arts: List<ArtRef>, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    Box(modifier.background(ConsoleColors.Surface)) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(top = g.x(22), bottom = g.x(110), start = g.x(70), end = g.x(70)),
            horizontalArrangement = Arrangement.spacedBy(g.x(10)),
        ) {
            arts.take(3).forEach { art ->
                MosaicCell(art, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun MosaicCell(art: ArtRef, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    val image = rememberTvArtImage(art)
    val painter = image?.let { rememberAsyncImagePainter(it.model, it.loader) }
    val failed = painter != null && painter.state is AsyncImagePainter.State.Error
    Box(
        modifier.clip(RoundedCornerShape(g.x(3))),
        contentAlignment = Alignment.Center,
    ) {
        if (art.kind != ArtKind.NONE && painter != null && !failed) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            GameCaseArt(
                systemFolder = art.systemFolder,
                title = art.title.orEmpty(),
                size = CaseArtSize.LARGE,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** SMPTE-style bars from the token palette (colors must come from ConsoleColors). */
private val BARS_TOP = listOf(
    ConsoleColors.TextPrimary, ConsoleColors.Warning, ConsoleColors.Success,
    ConsoleColors.Accent, ConsoleColors.TextSecondary, ConsoleColors.Error,
    ConsoleColors.Steel,
)
private val BARS_BOTTOM = listOf(
    ConsoleColors.Accent, ConsoleColors.Background, ConsoleColors.Error,
    ConsoleColors.Background, ConsoleColors.Success, ConsoleColors.Background,
    ConsoleColors.TextPrimary,
)

/**
 * SMPTE-style test card. The label plate follows the mockup `.bars
 * .logo2`: 140 wide, padding 16 top / 12 bottom, 10 gap, logo 60×55,
 * Silkscreen 14 bold with trimmed line height, the plate's
 * centre at 36% of the screen height.
 *
 * The plate hugs its widest label line and never goes below the mockup's
 * 140 px, so "NO GAME INSERTED" wraps to two full lines instead of
 * breaking mid-word ("NO GAME I...") at Larger text.
 */
@Composable
private fun TvTestCard(label: String, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    Box(modifier.background(ConsoleColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().weight(0.84f)) {
                BARS_TOP.forEach { color ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(color))
                }
            }
            Row(Modifier.fillMaxWidth().weight(0.16f)) {
                BARS_BOTTOM.forEach { color ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(color))
                }
            }
        }
        val labelSp = testCardLabelSp(g)
        Column(
            Modifier
                .align { size, space, _ ->
                    IntOffset(
                        (space.width - size.width) / 2,
                        (space.height * 0.36f).toInt() - size.height / 2,
                    )
                }
                .widthIn(min = g.x(140))
                .width(IntrinsicSize.Min)
                .background(ConsoleColors.Background)
                .border(g.x(3), ConsoleColors.TextPrimary)
                .padding(top = g.x(16), bottom = g.x(12), start = g.x(16), end = g.x(16)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painterResource(R.drawable.gamebex_icon_foreground),
                contentDescription = null,
                modifier = Modifier.size(g.x(60), g.x(55)),
            )
            Spacer(Modifier.height(g.x(10)))
            Text(
                text = label,
                color = ConsoleColors.TextPrimary,
                fontFamily = Silkscreen,
                fontWeight = FontWeight.Bold,
                fontSize = labelSp,
                lineHeight = labelSp,
                letterSpacing = labelSp * 0.06f,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        trim = LineHeightStyle.Trim.Both,
                        alignment = LineHeightStyle.Alignment.Center,
                    ),
                ),
            )
        }
    }
}

/**
 * Test-card label scale: the user text scale capped
 * at 1.1, so the plate keeps the mockup's spacing at Larger text instead
 * of growing until the label breaks mid-word. Pure.
 */
internal fun testCardLabelScale(textScale: Float): Float = min(textScale, 1.1f)

/** Silkscreen 14 px × geometry × [testCardLabelScale]. */
internal fun testCardLabelSp(g: com.gamebex.client.ui.console.ConsoleGeometry): androidx.compose.ui.unit.TextUnit =
    (14f * g.scale * testCardLabelScale(g.textScale)).sp

/**
 * The no-plugin TV card: a blue no-signal screen with a pixel plug glyph
 * and **no label** — the banner below already carries the text. The blue
 * is mockup `#1A2A8A`; no console token is blue.
 */
private val NO_SIGNAL_BLUE = Color(0xFF1A2A8A)

@Composable
private fun TvPlugCard(modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    Box(modifier.background(NO_SIGNAL_BLUE)) {
        PlugGlyph(
            tint = ConsoleColors.TextPrimary,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = g.x(80))
                .size(g.x(96)),
        )
    }
}

/**
 * The invite/fix TV: the pixel glyph on its tone bed with
 * the notice label under it. The plug notice is the blue no-signal screen;
 * warn is amber.
 */
internal fun noticeBed(glyph: com.gamebex.client.ui.home.model.TvGlyph, tone: com.gamebex.client.ui.home.model.TvTone): Color =
    when {
        glyph == com.gamebex.client.ui.home.model.TvGlyph.PLUG -> NO_SIGNAL_BLUE
        tone == com.gamebex.client.ui.home.model.TvTone.WARN -> ConsoleColors.Warning
        tone == com.gamebex.client.ui.home.model.TvTone.GOOD -> ConsoleColors.Success
        tone == com.gamebex.client.ui.home.model.TvTone.MUTED -> ConsoleColors.HighSurface
        else -> ConsoleColors.Surface
    }

/** Silkscreen 24 px × geometry × [testCardLabelScale], trimmed. */
internal fun noticeLabelSp(g: com.gamebex.client.ui.console.ConsoleGeometry): androidx.compose.ui.unit.TextUnit =
    (24f * g.scale * testCardLabelScale(g.textScale)).sp

@Composable
private fun TvNotice(
    glyph: com.gamebex.client.ui.home.model.TvGlyph,
    label: String,
    tone: com.gamebex.client.ui.home.model.TvTone,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    val bed = noticeBed(glyph, tone)
    val ink = com.gamebex.client.ui.home.components.caseInk(bed)
    val labelSp = noticeLabelSp(g)
    Box(modifier.background(bed)) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = g.x(80)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TvStateGlyph(glyph, ink, Modifier.size(g.x(96)))
            Spacer(Modifier.height(g.x(28)))
            Text(
                text = label,
                color = ink,
                fontFamily = Silkscreen,
                fontWeight = FontWeight.Bold,
                fontSize = labelSp,
                lineHeight = labelSp,
                letterSpacing = labelSp * 0.06f,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        trim = LineHeightStyle.Trim.Both,
                        alignment = LineHeightStyle.Alignment.Center,
                    ),
                ),
                modifier = Modifier
                    .widthIn(max = g.x(520))
                    .padding(horizontal = g.x(48)),
            )
        }
    }
}
