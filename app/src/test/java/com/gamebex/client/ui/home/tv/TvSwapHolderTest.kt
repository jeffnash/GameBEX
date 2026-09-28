package com.gamebex.client.ui.home.tv

import com.gamebex.client.ui.home.model.TvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel-change effects: the burst arms in the same call as the content
 * swap (same frame), the old content is fully visible under it at swap
 * time (no bare cut), the burst runs 160 ms in three steps and the
 * crossfade completes at 120 ms underneath. Times are fed explicitly —
 * virtual time, no polling.
 */
class TvSwapHolderTest {

    private val first = TvContent.TestCard("A")
    private val second = TvContent.TestCard("B")

    @Test fun firstCompositionNeverBursts() {
        val swap = TvSwapHolder.idle()
        assertNull(swap.from)
        assertNull(swap.burstAlpha.value)
        assertEquals(1f, swap.newAlpha.value, 0f)
        assertFalse(swap.fading.value)
        assertFalse(swap.animating)
    }

    @Test fun burstAndCrossfadeArmInSameCallAsTheSwap() {
        val swap = TvSwapHolder.swap(first, reducedMotion = false)
        // Armed before any clock tick: the swap frame itself shows the static
        // over the fully visible outgoing content.
        assertEquals(TvTiming.BURST_ALPHAS[0], swap.burstAlpha.value)
        assertEquals(0, swap.burstStep.value)
        assertEquals(0f, swap.newAlpha.value, 0f)
        assertEquals(1f, swap.oldAlpha, 0f)
        assertSame(first, swap.from)
        assertTrue(swap.animating)
    }

    @Test fun reducedMotionSnapsWithoutEffects() {
        val swap = TvSwapHolder.swap(first, reducedMotion = true)
        assertNull(swap.burstAlpha.value)
        assertEquals(1f, swap.newAlpha.value, 0f)
        assertFalse(swap.fading.value)
        assertFalse(swap.animating)
    }

    @Test fun burstRuns160msInThreeSteps() {
        val swap = TvSwapHolder.swap(second, reducedMotion = false)
        // Steps are exact thirds of 160 ms: [0,53), [53,107), [107,160).
        swap.advance(0)
        assertEquals(TvTiming.BURST_ALPHAS[0], swap.burstAlpha.value)
        swap.advance(60)
        assertEquals(TvTiming.BURST_ALPHAS[1], swap.burstAlpha.value)
        swap.advance(120)
        assertEquals(TvTiming.BURST_ALPHAS[2], swap.burstAlpha.value)
        swap.advance(159)
        assertEquals(TvTiming.BURST_ALPHAS[2], swap.burstAlpha.value)
        swap.advance(160)
        assertNull(swap.burstAlpha.value)
    }

    @Test fun crossfadeCompletesAt120msUnderTheLongerBurst() {
        val swap = TvSwapHolder.swap(first, reducedMotion = false)
        swap.advance(60)
        val mid = swap.newAlpha.value
        assertTrue("mid alpha $mid", mid > 0f && mid < 1f)
        assertTrue(swap.fading.value)
        swap.advance(120)
        assertEquals(1f, swap.newAlpha.value, 0f)
        assertEquals(0f, swap.oldAlpha, 0f)
        assertFalse(swap.fading.value)
        // The burst outlives the crossfade (160 ms vs 120 ms).
        assertNotNull(swap.burstAlpha.value)
        assertTrue(swap.animating)
    }

    @Test fun effectsEndAfterTheBurst() {
        val swap = TvSwapHolder.swap(first, reducedMotion = false)
        swap.advance(TvTiming.BURST_MS)
        assertNull(swap.burstAlpha.value)
        assertFalse(swap.fading.value)
        assertFalse(swap.animating)
    }

    @Test fun timingMatchesFixes() {
        assertEquals(160, TvTiming.BURST_MS)
        assertEquals(120, TvTiming.FADE_MS)
        assertTrue(tvNoiseFrameCount in 1..4)
    }
}
