package com.gamebex.client.ui.home.tv

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.home.model.ButtonTone
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvGlyph
import com.gamebex.client.ui.home.model.TvModel
import com.gamebex.client.ui.home.model.TvTone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV and the two action buttons are one accessible unit each. The TV
 * merges its descendants into a single "Preview: {stateLine},
 * {facts}" description (so TalkBack never reads it twice); the primary and
 * secondary buttons each expose `Role.Button`, an `onClick(label)` action,
 * and `disabled()` when their action is [HomeAction.None].
 */
@RunWith(AndroidJUnit4::class)
class TvButtonsA11yTest {

    @get:Rule
    val compose = createComposeRule()

    private val facts = "GameCube · RetroArch · Dolphin · 3 saves"
    private val previewDesc = "Preview: RESUME · SAVED 12 MIN AGO, $facts"

    private val tv = TvModel(
        content = TvContent.TestCard("NO GAME"),
        stateKey = "RESUME",
        stateDetail = "SAVED 12 MIN AGO",
        glyph = TvGlyph.RESUME,
        factsLine = facts,
        tone = TvTone.NORMAL,
    )

    private fun card(primary: HomeAction, secondary: HomeAction?): HomeCard = HomeCard(
        key = CardKey.local(1),
        kind = CardKind.GAME,
        title = "Crazy Taxi",
        cardLine = "GC · saved 12 min ago",
        state = CardState.Saved(0L, 3),
        primary = primary,
        primaryLabel = "Resume",
        primaryTone = ButtonTone.PLAY,
        secondary = secondary,
        secondaryLabel = "Details",
        tv = tv,
        position = 0,
    )

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun hasClickLabel(label: String) = SemanticsMatcher("onClick labeled \"$label\"") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    @Test
    fun tvMergesItsDescendantsIntoOnePreviewDescriptionWithStateAndFacts() {
        compose.setContent { TvScreen(tv = tv, channel = 1, reducedMotion = true, onTap = {}) }
        // One node carries the whole description…
        val node = compose.onNodeWithContentDescription(previewDesc)
        node.assertExists()
        // …and the facts live on that same (merged) node, not on a second one.
        node.assert(hasText(facts))
    }

    @Test
    fun primaryButtonExposesButtonRoleAndAnOnClickLabeledWithItsAction() {
        compose.setContent {
            HomeActionRow(
                card = card(HomeAction.Play(1L), HomeAction.Go(HomeDestination.Game(1L))),
                focusedButton = -1,
                onPrimary = {},
                onSecondary = {},
            )
        }
        val primary = compose.onNodeWithTag("home-primary")
        primary.assert(hasRole(Role.Button))
        primary.assert(hasClickLabel("Resume"))
        primary.assertIsEnabled()
    }

    @Test
    fun primaryButtonIsDisabledWhenItsActionIsNone() {
        compose.setContent {
            HomeActionRow(
                card = card(HomeAction.None, HomeAction.Go(HomeDestination.Game(1L))),
                focusedButton = -1,
                onPrimary = {},
                onSecondary = {},
            )
        }
        val primary = compose.onNodeWithTag("home-primary")
        primary.assertIsNotEnabled()
        primary.assert(hasRole(Role.Button))
        primary.assert(hasClickLabel("Resume"))
    }

    @Test
    fun secondaryButtonExposesButtonRoleAndAnOnClickLabeledWithItsAction() {
        compose.setContent {
            HomeActionRow(
                card = card(HomeAction.Play(1L), HomeAction.Go(HomeDestination.Game(1L))),
                focusedButton = -1,
                onPrimary = {},
                onSecondary = {},
            )
        }
        val secondary = compose.onNodeWithText("Details")
        secondary.assert(hasRole(Role.Button))
        secondary.assert(hasClickLabel("Details"))
        secondary.assertIsEnabled()
    }

    @Test
    fun secondaryButtonIsDisabledWhenItsActionIsNone() {
        compose.setContent {
            HomeActionRow(
                card = card(HomeAction.Play(1L), HomeAction.None),
                focusedButton = -1,
                onPrimary = {},
                onSecondary = {},
            )
        }
        val secondary = compose.onNodeWithText("Details")
        secondary.assertIsNotEnabled()
        secondary.assert(hasRole(Role.Button))
        secondary.assert(hasClickLabel("Details"))
    }
}
