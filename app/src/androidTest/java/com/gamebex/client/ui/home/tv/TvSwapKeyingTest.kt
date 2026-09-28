package com.gamebex.client.ui.home.tv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The swap (crossfade + static burst) keys on the selection, not the
 * position. It fires when the selected card changes — including
 * "Roll again" landing at the same position — and never when sections shift
 * above the cursor (same card, new position). The outgoing content layer is
 * the observable: it is composed only while the 120 ms crossfade runs, so a
 * still-present old plate after one frame means the swap armed.
 */
@RunWith(AndroidJUnit4::class)
class TvSwapKeyingTest {

    @get:Rule
    val compose = createComposeRule()

    /** Distinct plate labels; the shared state key keeps the banner stable. */
    private fun model(plate: String) = TvModel(
        content = TvContent.TestCard(plate),
        stateKey = "STATE",
        stateDetail = null,
        glyph = null,
        factsLine = null,
    )

    @Test
    fun swapFiresWhenTheSelectedCardChangesAtTheSamePosition() {
        // Roll again at position 3: same channel, different selected card.
        val state = mutableStateOf(model("SWAP-A") to "local:1")
        compose.setContent {
            TvScreen(
                tv = state.value.first,
                channel = 3,
                reducedMotion = false,
                onTap = {},
                selectionKey = state.value.second,
            )
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        state.value = model("SWAP-B") to "local:2"
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // The outgoing plate is still on screen under the static burst: armed.
        compose.onNodeWithText("SWAP-A").assertExists()
    }

    @Test
    fun positionShiftAboveTheCursorDoesNotFireTheSwap() {
        // Sections shifted above the cursor: same selection, position 3 → 2.
        val state = mutableStateOf(3 to model("SWAP-A"))
        compose.setContent {
            TvScreen(
                tv = state.value.second,
                channel = state.value.first,
                selectionKey = "local:1",
                reducedMotion = false,
                onTap = {},
            )
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        state.value = 2 to model("SWAP-A2")
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // No outgoing layer: the content switched without a crossfade/burst.
        compose.onNodeWithText("SWAP-A").assertDoesNotExist()
    }
}
