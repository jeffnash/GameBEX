package com.gamebex.client.ui.home.tv

import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.consoleContrastRatio
import com.gamebex.client.ui.home.model.ButtonTone
import com.gamebex.client.ui.home.model.TvGlyph
import com.gamebex.client.ui.home.model.TvTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV/action color contract: button tones, banner tones, LED.
 * Pure token mappings; legibility of every label on its fill.
 */
class TvStyleTest {

    @Test fun bannerStateLineColorsFollowTone() {
        assertEquals(ConsoleColors.Accent, TvStyle.toneColor(TvTone.NORMAL))
        assertEquals(ConsoleColors.Success, TvStyle.toneColor(TvTone.GOOD))
        assertEquals(ConsoleColors.Warning, TvStyle.toneColor(TvTone.WARN))
        assertEquals(ConsoleColors.TextTertiary, TvStyle.toneColor(TvTone.MUTED))
    }

    @Test fun twoToneBannerColorsKeyToneAndDetailGray() {
        // Glyph + stateKey in the tone colour, stateDetail light gray.
        assertEquals(ConsoleColors.Accent, TvStyle.bannerKeyColor(TvTone.NORMAL, TvGlyph.RESUME))
        assertEquals(ConsoleColors.Success, TvStyle.bannerKeyColor(TvTone.GOOD, TvGlyph.NEW))
        assertEquals(ConsoleColors.Warning, TvStyle.bannerKeyColor(TvTone.WARN, TvGlyph.WARN))
        assertEquals(ConsoleColors.TextTertiary, TvStyle.bannerKeyColor(TvTone.MUTED, TvGlyph.CLOUD))
        assertEquals(ConsoleColors.TextSecondary, TvStyle.bannerDetailColor)
    }

    @Test fun offlineStatesRenderAmber() {
        // An OFFLINE glyph is amber even when the model tone is
        // MUTED, so offline is visible. Other muted states stay gray.
        assertEquals(TvTone.WARN, TvStyle.displayTone(TvTone.MUTED, TvGlyph.OFFLINE))
        assertEquals(TvTone.MUTED, TvStyle.displayTone(TvTone.MUTED, TvGlyph.CLOUD))
        assertEquals(ConsoleColors.Warning, TvStyle.bannerKeyColor(TvTone.MUTED, TvGlyph.OFFLINE))
        assertEquals(
            ConsoleColors.Warning,
            TvStyle.ledColor(TvStyle.displayTone(TvTone.MUTED, TvGlyph.OFFLINE)),
        )
    }

    @Test fun ringPercentFloorsLikeTheMockup() {
        // The ring shows Math.floor of the percent.
        assertEquals(64, TvStyle.ringPercent(0.64f))
        assertEquals("64%", TvStyle.ringPercentLabel(0.64f))
        assertEquals(0, TvStyle.ringPercent(0f))
        assertEquals("0%", TvStyle.ringPercentLabel(0.005f))
        assertEquals(100, TvStyle.ringPercent(1f))
    }

    @Test fun primaryButtonTonesFollowSpec() {
        assertEquals(ConsoleColors.Accent, TvStyle.buttonFill(ButtonTone.PLAY))
        assertEquals(ConsoleColors.TextPrimary, TvStyle.buttonFill(ButtonTone.DOWNLOAD))
        assertEquals(ConsoleColors.Warning, TvStyle.buttonFill(ButtonTone.FIX))
        assertEquals(ConsoleColors.Success, TvStyle.buttonFill(ButtonTone.ARMED))
        assertEquals(ConsoleColors.HighSurface, TvStyle.buttonFill(ButtonTone.NEUTRAL))

        assertEquals(ConsoleColors.Background, TvStyle.buttonInk(ButtonTone.PLAY))
        assertEquals(ConsoleColors.Background, TvStyle.buttonInk(ButtonTone.DOWNLOAD))
        assertEquals(ConsoleColors.Background, TvStyle.buttonInk(ButtonTone.FIX))
        assertEquals(ConsoleColors.Background, TvStyle.buttonInk(ButtonTone.ARMED))
        assertEquals(ConsoleColors.TextPrimary, TvStyle.buttonInk(ButtonTone.NEUTRAL))
    }

    @Test fun ledIsStaticGreenAndAmberOnlyOnWarn() {
        assertEquals(ConsoleColors.Success, TvStyle.ledColor(TvTone.NORMAL))
        assertEquals(ConsoleColors.Success, TvStyle.ledColor(TvTone.GOOD))
        assertEquals(ConsoleColors.Success, TvStyle.ledColor(TvTone.MUTED))
        assertEquals(ConsoleColors.Warning, TvStyle.ledColor(TvTone.WARN))
    }

    @Test fun buttonLabelsStayLegibleOnTheirFill() {
        for (tone in ButtonTone.entries) {
            val fill = TvStyle.buttonFill(tone)
            val ink = TvStyle.buttonInk(tone)
            assertTrue(
                "$tone label contrast ${consoleContrastRatio(ink, fill)}",
                consoleContrastRatio(ink, fill) >= 4.5,
            )
        }
        // Disabled: TextTertiary on Elevated (Preparing…, Download when online).
        assertTrue(
            consoleContrastRatio(ConsoleColors.TextTertiary, ConsoleColors.Elevated) >= 4.5,
        )
    }

    @Test fun hardShadowShadesTheFill() {
        val shade = TvStyle.shade(ConsoleColors.Success, 0.55f)
        assertTrue(shade.red < ConsoleColors.Success.red)
        assertTrue(shade.green < ConsoleColors.Success.green)
        assertTrue(shade.blue < ConsoleColors.Success.blue)
        assertEquals(ConsoleColors.AccentShade, TvStyle.shadowColor(ConsoleColors.Accent))
    }

    @Test fun burstUsesAtMostFourCachedFrames() {
        assertTrue(tvNoiseFrameCount in 1..4)
    }
}
