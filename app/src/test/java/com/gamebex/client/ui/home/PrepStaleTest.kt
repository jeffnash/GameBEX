package com.gamebex.client.ui.home

import com.gamebex.client.data.PlayStates
import com.gamebex.client.ui.home.model.ButtonTone
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.SystemTag
import com.gamebex.client.ui.home.model.TvGlyph
import com.gamebex.client.ui.home.model.TvTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Preparing card with no live prep run is a stall, not progress —
 * after 3 quiet ticks it flips to NeedsFix ("Needs setup" + "Fix setup"),
 * and Detail offers the Retry.
 */
class PrepStaleTest {

    private val now = 1_700_000_000_000L
    private val gc = SystemTag("gc", "GC", "GameCube", "RetroArch · Dolphin")
    private val factory = HomeCardFactory { PlayStates.Status.Ready }

    private fun preparingCard(id: Long = 7) = factory.gameCard(
        key = CardKey.local(id), title = "Crazy Taxi", system = gc,
        state = CardState.Preparing(), nowMs = now,
        freeBytes = 30_494_258_216L, siteName = "site", gameId = id,
    )

    @Test fun quietBelowThresholdStaysPreparing() {
        val seen = mutableMapOf<Long, Int>()
        assertTrue(stalePrepTick(setOf(7), seen).isEmpty())
        assertTrue(stalePrepTick(setOf(7), seen).isEmpty())
        assertEquals(mapOf(7L to 2), seen)
    }

    @Test fun thirdQuietTickFlipsOnce() {
        val seen = mutableMapOf<Long, Int>()
        stalePrepTick(setOf(7), seen)
        stalePrepTick(setOf(7), seen)
        assertEquals(setOf(7L), stalePrepTick(setOf(7), seen))
        // Patched cards leave Preparing, but even a 4th quiet tick re-fires
        // nothing: the flip happens exactly once per quiet run.
        assertTrue(stalePrepTick(setOf(7), seen).isEmpty())
    }

    @Test fun liveRunResetsTheCount() {
        val seen = mutableMapOf<Long, Int>()
        stalePrepTick(setOf(7), seen)
        stalePrepTick(setOf(7), seen)
        // The game went live (off the quiet set): forgotten.
        assertTrue(stalePrepTick(emptySet(), seen).isEmpty())
        assertTrue(seen.isEmpty())
        // A later quiet run starts from zero.
        assertTrue(stalePrepTick(setOf(7), seen).isEmpty())
        assertTrue(stalePrepTick(setOf(7), seen).isEmpty())
        assertEquals(setOf(7L), stalePrepTick(setOf(7), seen))
    }

    @Test fun eachGameTrackedIndependently() {
        val seen = mutableMapOf<Long, Int>()
        stalePrepTick(setOf(7, 9), seen)
        stalePrepTick(setOf(7, 9), seen)
        // 9 went live; only 7 crosses.
        assertEquals(setOf(7L), stalePrepTick(setOf(7), seen))
        assertEquals(mapOf(7L to 3), seen)
    }

    @Test fun staleCardSaysNeedsSetupWithWorkingButton() {
        val card = factory.prepStale(preparingCard(), HomeCopy.prepStalled, now)
        assertTrue(card.state is CardState.NeedsFix)
        assertEquals(HomeCopy.prepStalled, (card.state as CardState.NeedsFix).note)
        assertEquals("⚠ Needs setup", card.cardLine)
        assertEquals(HomeAction.Go(HomeDestination.Game(7)), card.primary)
        assertEquals("Fix setup", card.primaryLabel)
        assertEquals(ButtonTone.FIX, card.primaryTone)
        assertEquals(HomeAction.Go(HomeDestination.Game(7)), card.secondary)
        assertEquals("Details", card.secondaryLabel)
    }

    @Test fun staleCardTvShowsTheNote() {
        val card = factory.prepStale(preparingCard(), HomeCopy.prepStalled, now)
        assertEquals("CAN'T START YET", card.tv.stateKey)
        assertEquals(HomeCopy.prepStalled, card.tv.factsLine)
        assertEquals(TvGlyph.WARN, card.tv.glyph)
        assertEquals(TvTone.WARN, card.tv.tone)
    }
}
