package com.gamebex.client.ui.home.components

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Home card renders live prep progress ("Extracting archive… 312 of
 * 996 MB · 31%") and the 20 s stall line ("Still working…") instead of a
 * bare "preparing…". The copy itself is covered by HomeCopyTest; this
 * proves the card shows it.
 */
@RunWith(AndroidJUnit4::class)
class HomeCardProgressTest {

    @get:Rule
    val compose = createComposeRule()

    private fun preparingCard(state: CardState.Preparing): HomeCard {
        val line = HomeCopy.cardLine(state, system = null, nowMs = 0L)
        return HomeCard(
            key = CardKey("game:sonic-adventure"),
            kind = CardKind.GAME,
            title = "Sonic Adventure",
            cardLine = line,
            state = state,
            primary = HomeAction.None,
            primaryLabel = "",
            tv = TvModel(TvContent.TestCard("NO GAME"), "PREPARING", null, null, null),
            position = 0,
        )
    }

    private fun show(card: HomeCard) {
        compose.setContent {
            Box {
                HomeCardView(
                    card = card,
                    selected = false,
                    focused = false,
                    onTap = {},
                    onActivate = {},
                )
            }
        }
    }

    @Test
    fun livePrepShowsPhaseBytesAndPercent() {
        val card = preparingCard(
            CardState.Preparing(
                read = 312L * 1024 * 1024,
                total = 996L * 1024 * 1024,
                phase = "Extracting archive",
            ),
        )
        show(card)
        compose.onNodeWithText(card.cardLine, substring = true).assertIsDisplayed()
        compose.onNodeWithText("31%", substring = true).assertIsDisplayed()
    }

    @Test
    fun stalledPrepSaysStillWorking() {
        val card = preparingCard(
            CardState.Preparing(
                read = 312L * 1024 * 1024,
                total = 996L * 1024 * 1024,
                phase = "Extracting archive",
                stalled = true,
            ),
        )
        show(card)
        compose.onNodeWithText("Still working…", substring = true).assertIsDisplayed()
    }

    @Test
    fun silentPrepKeepsBarePreparing() {
        val card = preparingCard(CardState.Preparing())
        show(card)
        compose.onNodeWithText("preparing…", substring = true).assertIsDisplayed()
    }
}
