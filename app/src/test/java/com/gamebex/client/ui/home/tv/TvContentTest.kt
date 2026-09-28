package com.gamebex.client.ui.home.tv

import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.home.HomeCardFactory
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvGlyph
import com.gamebex.client.ui.home.model.TvTone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test-card label rules: the label type never grows past the Home scale,
 * so the 140 px mockup plate keeps its spacing and the label wraps to
 * two full lines at any text size. Plus the bed and notice decisions.
 */
class TvContentTest {

    @Test fun labelScaleCapsAtTheHomeScale() {
        assertEquals(1.1f, testCardLabelScale(1.1f))
        assertEquals(1.1f, testCardLabelScale(1.25f))
        assertEquals(1.1f, testCardLabelScale(1.4f))
        assertEquals(1f, testCardLabelScale(1f))
        assertEquals(0.9f, testCardLabelScale(0.9f))
    }

    @Test fun noGameTvShowsTheFullNoGamePlateAndTheNoGameInsertedBanner() {
        // The plate label is "NO GAME" (short enough never to
        // truncate) and the banner above carries "NO GAME INSERTED".
        val tv = HomeCardFactory { error("readiness must not run") }.noGameTv()
        assertEquals(TvContent.TestCard("NO GAME"), tv.content)
        assertEquals("NO GAME INSERTED", tv.stateLine)
    }

    @Test fun tvBedUnderlayIsTheMockupDarkWash() {
        // Background at 0.6 under the checker (`.bed:before`),
        // so a light case stands out on the bed.
        val underlay = tvBedUnderlay()
        assertEquals(ConsoleColors.Background.copy(alpha = 1f), underlay.copy(alpha = 1f))
        assertEquals(0.6f, underlay.alpha)
    }

    @Test fun noticeBedMatchesGlyphAndTone() {
        // Plug = the blue no-signal screen whatever the tone;
        // warn = amber; the dice/folder notices sit on the plain dark bed.
        assertEquals(noticeBed(TvGlyph.PLUG, TvTone.NORMAL), noticeBed(TvGlyph.PLUG, TvTone.WARN))
        assertEquals(noticeBed(TvGlyph.PLUG, TvTone.NORMAL), noticeBed(TvGlyph.PLUG, TvTone.MUTED))
        assertEquals(ConsoleColors.Warning, noticeBed(TvGlyph.WARN, TvTone.WARN))
        assertEquals(ConsoleColors.Success, noticeBed(TvGlyph.DICE, TvTone.GOOD))
        assertEquals(ConsoleColors.Surface, noticeBed(TvGlyph.DICE, TvTone.NORMAL))
        assertEquals(ConsoleColors.Surface, noticeBed(TvGlyph.FOLDER, TvTone.NORMAL))
        assertEquals(ConsoleColors.HighSurface, noticeBed(TvGlyph.FOLDER, TvTone.MUTED))
    }
}
