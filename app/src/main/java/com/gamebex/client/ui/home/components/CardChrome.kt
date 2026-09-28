package com.gamebex.client.ui.home.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import com.gamebex.client.ui.console.ConsoleColors

/** Card-chrome rules, kept out of the composables so they are unit-testable and shared by every card kind. */
internal object CardChrome {
    /** In design px. */
    const val CARD_RADIUS = 10

    /**
     * Hard shadow offset in design px, drawn outside the card only. Selected but
     * unfocused cards get none so a transparent tile never renders as a solid slab.
     */
    fun shadowOffsetPx(focused: Boolean, pressed: Boolean): Int = when {
        pressed -> 1
        focused -> 5
        else -> 0
    }

    fun fill(focused: Boolean, selected: Boolean, base: Color): Color =
        if (focused || selected) ConsoleColors.HighSurface else base

    /** Selected uses TextTertiary, the closest token to the design's muted lavender (#6A6480). */
    fun borderColor(focused: Boolean, selected: Boolean): Color = when {
        focused -> ConsoleColors.Accent
        selected -> ConsoleColors.TextTertiary
        else -> ConsoleColors.Steel
    }

    fun borderWidthPx(focused: Boolean): Int = if (focused) 3 else 2
}

/**
 * Two title lines measure exactly 2 × fontSize because [cardTitleStyle] trims
 * both outer leadings and pins lineHeight to fontSize. Pinned by CardArtTest.
 */
internal const val TITLE_TWO_LINE_FACTOR = 2.0f

/** Title size in design px: the largest step whose two lines fit [availHeightPx]; at 22 the caller ellipsizes. */
internal fun titleFontPx(availHeightPx: Float, homeScale: Float): Float {
    for (px in floatArrayOf(28f, 26f, 24f)) {
        if (TITLE_TWO_LINE_FACTOR * px * homeScale <= availHeightPx) return px
    }
    return 22f
}

internal fun cardTitleStyle(
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
): TextStyle = TextStyle(
    color = color,
    fontFamily = fontFamily,
    fontWeight = fontWeight,
    fontSize = fontSize,
    lineHeight = fontSize,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        trim = LineHeightStyle.Trim.Both,
        alignment = LineHeightStyle.Alignment.Center,
    ),
)
