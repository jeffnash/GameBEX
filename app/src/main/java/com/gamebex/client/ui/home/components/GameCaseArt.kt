package com.gamebex.client.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.ui.Silkscreen
import com.gamebex.client.ui.SpaceGrotesk
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.consoleLuminance
import com.gamebex.client.ui.home.model.HomeCopy
import java.util.Locale

/**
 * Procedural game case for missing or failed art (mockup
 * `.cover` / `.mbed`).
 *
 * A case in the system's colour: a darker top band carrying the system
 * name in Silkscreen (short tag in thumbnails, full name on the TV), the
 * title bottom-left on large sizes, and a light label stripe on small
 * sizes. Renders nothing textual the user must read — the card/TV
 * semantics carry the description.
 */

enum class CaseArtSize {
    /** 48×64 All-tile stack cell: bare case with band and label stripe. */
    MINI,

    /** 112×84 card thumbnail: the `.mbed` look — darkened bed, case centered. */
    THUMB,

    /** TV cover panel and mosaic cells: full case with system name and title. */
    LARGE,
}

private val GC_VIOLET = Color(0xFF7D3C98)
private val PS2_BLUE = Color(0xFF2F6FB5)
private val N64_GREEN = Color(0xFF2E7348)
private val SNES_LAVENDER = Color(0xFFB3A3E6)
private val GBA_INDIGO = Color(0xFF3A4E96)
private val PSX_GREY = Color(0xFF65626F)
private val DC_ORANGE = Color(0xFFE07A1F)
private val NDS_SILVER = Color(0xFFE9E1D2)
private val NES_RED = Color(0xFFC0392B)
private val GENESIS_CHARCOAL = Color(0xFF2B2F3A)
private val SMS_TEAL = Color(0xFF15727F)
private val GG_AMBER = Color(0xFFE8A33D)
private val N3DS_SKY = Color(0xFF4EA8DE)
private val PSP_MAUVE = Color(0xFFA64D79)
private val SATURN_BURNT = Color(0xFFA04000)
private val WII_MINT = Color(0xFF48C9B0)
private val GB_PEA = Color(0xFF9BA88D)
private val GBC_BERRY = Color(0xFFC2185B)
private val ARCADE_RED = Color(0xFFE63946)
private val MAME_WINE = Color(0xFF7B1E2B)
private val FBNEO_CORAL = Color(0xFFFF7A5C)
private val PCE_ROSE = Color(0xFFB03A5D)
private val MSX_GOLD = Color(0xFFB7950B)

/** Fixed per-system palette; order is part of the hash contract. */
internal val CASE_PALETTE: List<Color> = listOf(
    GC_VIOLET, PS2_BLUE, N64_GREEN, SNES_LAVENDER,
    GBA_INDIGO, PSX_GREY, DC_ORANGE, NDS_SILVER,
)

/**
 * One fixed colour per system folder: every common folder resolves here
 * to its own colour, so no two systems share a case colour. Alternate
 * spellings of the same system (`n3ds`/`3ds`, `tg16`/`pcengine`/`pce`)
 * share that system's colour; the arcade folders (`arcade`, `mame`,
 * `fbneo`) each carry their own colour.
 */
private val CASE_BY_FOLDER: Map<String, Color> = mapOf(
    "gc" to GC_VIOLET,
    "ps2" to PS2_BLUE,
    "n64" to N64_GREEN,
    "snes" to SNES_LAVENDER,
    "gba" to GBA_INDIGO,
    "psx" to PSX_GREY,
    "dreamcast" to DC_ORANGE,
    "nds" to NDS_SILVER,
    "nes" to NES_RED,
    "genesis" to GENESIS_CHARCOAL,
    "mastersystem" to SMS_TEAL,
    "gamegear" to GG_AMBER,
    "3ds" to N3DS_SKY,
    "n3ds" to N3DS_SKY,
    "psp" to PSP_MAUVE,
    "saturn" to SATURN_BURNT,
    "wii" to WII_MINT,
    "gb" to GB_PEA,
    "gbc" to GBC_BERRY,
    "arcade" to ARCADE_RED,
    "mame" to MAME_WINE,
    "fbneo" to FBNEO_CORAL,
    "pce" to PCE_ROSE,
    "tg16" to PCE_ROSE,
    "pcengine" to PCE_ROSE,
    "msx" to MSX_GOLD,
)

/**
 * The case colour for a system folder: the fixed palette above, otherwise
 * a stable hash of the folder (or title) into that palette. [fallback]
 * colours cases whose folder is unknown to the caller (pure).
 */
internal fun caseColorFor(
    systemFolder: String?,
    title: String = "",
    fallback: Color? = null,
): Color {
    val folder = systemFolder?.trim()?.lowercase(Locale.ENGLISH).orEmpty()
    CASE_BY_FOLDER[folder]?.let { return it }
    if (folder.isNotEmpty()) return CASE_PALETTE[Math.floorMod(folder.hashCode(), CASE_PALETTE.size)]
    fallback?.let { return it }
    val key = title.trim().lowercase(Locale.ENGLISH)
    return CASE_PALETTE[Math.floorMod(key.hashCode(), CASE_PALETTE.size)]
}

/**
 * Ink that stays legible on [color] (≥ 4.5:1, pinned by tests): dark on
 * light cases, cream otherwise. The palette is tuned so every entry is on
 * one side of this threshold.
 */
internal fun caseInk(color: Color): Color =
    if (consoleLuminance(color) > 0.19) ConsoleColors.Background else ConsoleColors.TextPrimary

/**
 * Band label: the short tag ("GC") in thumbnails, the full system name on
 * the TV. Null folder (or unknown) draws an empty band.
 */
internal fun caseTag(systemFolder: String?, full: Boolean): String? {
    val folder = systemFolder?.trim()?.lowercase(Locale.ENGLISH).orEmpty()
    if (folder.isEmpty()) return null
    return if (full) {
        PlatformRegistry.byFolder[folder]?.display ?: HomeCopy.systemShort(folder)
    } else {
        HomeCopy.systemShort(folder)
    }
}

/**
 * Band tag style: the line box is exactly [fontSize] tall —
 * `lineHeight = fontSize`, no font padding, both leadings trimmed — so the
 * system name renders fully inside the band instead of being clipped
 * mid-glyph by the band's weight.
 */
internal fun caseBandStyle(fontSize: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.text.TextStyle =
    androidx.compose.ui.text.TextStyle(
        lineHeight = fontSize,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            trim = LineHeightStyle.Trim.Both,
            alignment = LineHeightStyle.Alignment.Center,
        ),
    )

@Composable
fun GameCaseArt(
    systemFolder: String?,
    title: String,
    size: CaseArtSize,
    modifier: Modifier = Modifier,
    fallbackColor: Color? = null,
) {
    val color = caseColorFor(systemFolder, title, fallbackColor)
    val tag = caseTag(systemFolder, full = size == CaseArtSize.LARGE)
    when (size) {
        CaseArtSize.MINI -> CaseFace(color, tag, title = null, modifier)
        CaseArtSize.THUMB -> {
            // `.mbed`: the case sits centered on a darkened bed of its own colour.
            val g = LocalConsoleGeometry.current
            Box(
                modifier
                    .background(color)
                    .drawBehind {
                        drawRect(ConsoleColors.Background.copy(alpha = 0.55f))
                    },
                contentAlignment = Alignment.Center,
            ) {
                CaseFace(
                    color = color,
                    tag = tag,
                    title = null,
                    modifier = Modifier
                        .fillMaxWidth(0.465f)
                        .fillMaxHeight(0.86f)
                        .drawBehind {
                            val o = g.x(3).toPx()
                            drawRoundRect(
                                color = ConsoleColors.Background.copy(alpha = 0.45f),
                                topLeft = Offset(o, o),
                                size = this.size,
                                cornerRadius = CornerRadius(g.x(2).toPx()),
                            )
                        }
                        .clip(RoundedCornerShape(g.x(2))),
                )
            }
        }
        CaseArtSize.LARGE -> CaseFace(color, tag, title, modifier)
    }
}

/**
 * The case face itself: top band with the system tag (Silkscreen), the
 * title bottom-left on large cases, else a light label stripe.
 */
@Composable
private fun CaseFace(
    color: Color,
    tag: String?,
    title: String?,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    BoxWithConstraints(
        modifier
            .background(color)
            .clip(RoundedCornerShape(g.x(2))),
    ) {
        val density = LocalDensity.current
        val caseH = maxHeight
        val caseW = maxWidth
        val large = title != null
        // Mockup `.cv-sys` on the TV cover: band 26/238, Silkscreen 12/238.
        // Thumbnail cases shrink the band but keep the short tag legible.
        val bandWeight = if (large) 0.11f else 0.16f
        val bandFont = with(density) { (caseH * (if (large) 0.05f else 0.13f)).toSp() }
        val titleFont = with(density) { (caseH * 0.095f).toSp() }
        Column(Modifier.fillMaxSize()) {
            // Top band (mockup `.cv-sys` / `.mbed i:before`).
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(bandWeight)
                    .background(ConsoleColors.Background.copy(alpha = 0.32f)),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (tag != null) {
                    Text(
                        text = tag,
                        // Light cases get dark ink.
                        color = caseInk(color),
                        fontFamily = Silkscreen,
                        fontSize = bandFont,
                        lineHeight = bandFont,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        style = caseBandStyle(bandFont),
                        modifier = Modifier.padding(horizontal = caseH * 0.03f),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (large) {
                    // The title bottom-left (mockup `.cover .cv-t`).
                    Text(
                        text = title.orEmpty(),
                        color = caseInk(color),
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Bold,
                        fontSize = titleFont,
                        lineHeight = titleFont,
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
                            .align(Alignment.BottomStart)
                            .padding(
                                start = caseH * 0.03f,
                                end = caseH * 0.03f,
                                bottom = caseH * 0.04f,
                            ),
                    )
                } else {
                    // Light label stripe (mockup `.mbed i:after`).
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = caseW * 0.12f,
                                end = caseW * 0.28f,
                                bottom = caseH * 0.11f,
                            )
                            .fillMaxWidth(0.6f)
                            .height(caseH * 0.07f)
                            .background(caseInk(color).copy(alpha = 0.8f)),
                    )
                }
            }
        }
    }
}
