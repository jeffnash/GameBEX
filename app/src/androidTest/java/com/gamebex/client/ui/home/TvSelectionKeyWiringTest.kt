package com.gamebex.client.ui.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.console.rememberConsoleFocusMemory
import com.gamebex.client.ui.console.rememberConsoleHints
import com.gamebex.client.ui.home.model.ArtRef
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.HeaderModel
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.HomeMode
import com.gamebex.client.ui.home.model.HomeScreenModel
import com.gamebex.client.ui.home.model.HomeSection
import com.gamebex.client.ui.home.model.SizeInfo
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * At the call site: `HomeRoot` passes `selectionKey = selectedCard.key.value`
 * to `TvPane`. With the parameter's
 * default (`channel.toString()`) the TV swap keys on the position, which is
 * exactly the failure mode: a section shift above the cursor (same card, new
 * position) re-fires the swap, and a Roll again at the same position (same
 * position, new card) misses it. The outgoing crossfade layer is the probe —
 * identical to `TvSwapKeyingTest`, but driven through the real `HomeRoot`
 * call, so it fails if the wiring regresses.
 */
@RunWith(AndroidJUnit4::class)
class TvSelectionKeyWiringTest {

    @get:Rule
    val compose = createComposeRule()

    private fun card(key: CardKey, plate: String, position: Int) = HomeCard(
        key = key,
        kind = CardKind.GAME,
        title = key.value,
        cardLine = "",
        state = CardState.None,
        primary = HomeAction.Play(1),
        primaryLabel = "Play",
        secondary = HomeAction.Go(HomeDestination.Downloads),
        secondaryLabel = "Details",
        tv = TvModel(TvContent.TestCard(plate), "STATE", null, null, null),
        span = 1,
        position = position,
    )

    private fun model(default: CardKey, token: Long, vararg cards: HomeCard) = HomeScreenModel(
        mode = HomeMode.HOME,
        header = HeaderModel("Search", "Browse", null, 0),
        sections = listOf(HomeSection(HomeGroup.RESUME, "RESUME", null, cards.toList())),
        defaultKey = default,
        selectionToken = token,
    )

    @Test
    fun sectionShiftAboveTheCursorDoesNotRefireTheSwap_tvKeysOnTheCardKeyNotThePosition() {
        val a1 = CardKey.local(1)
        val a9 = CardKey.local(9)
        val state = mutableStateOf(model(a1, 0, card(a1, "SWAP-A", 0)))
        compose.setContent {
            HomeRoot(
                model = state.value,
                onIntent = {},
                focusMemory = rememberConsoleFocusMemory(),
                hints = rememberConsoleHints(),
                reducedMotion = false,
            )
        }
        compose.waitUntil(15_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("SWAP-A"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        // A card appears ABOVE the cursor: the selected card (same key) moves
        // from position 0 to 1 and its content updates. With position keying
        // the channel change re-fires the swap and the old plate lingers.
        state.value = model(a1, 0, card(a9, "OTHER", 0), card(a1, "SWAP-A2", 1))
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithText("SWAP-A").assertDoesNotExist()
    }

}
