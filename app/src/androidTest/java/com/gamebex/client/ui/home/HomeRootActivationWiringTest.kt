package com.gamebex.client.ui.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
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
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.HomeIntent
import com.gamebex.client.ui.home.model.HomeMode
import com.gamebex.client.ui.home.model.HomeScreenModel
import com.gamebex.client.ui.home.model.HomeSection
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The a11y contract at the production call site. `HomeCardSemanticsTest`
 * covers `HomeCardView` in isolation and injects its own `onActivate`
 * lambda, so it stays green even if `HomeRoot` never passes the parameter
 * (the component default falls back to `onTap`, which only selects). This
 * test drives the real `HomeRoot` with a captured `onIntent` and fires the
 * same semantics `OnClick` action TalkBack's double tap uses on an
 * UNSELECTED card. It fails if `HomeRoot` drops `onActivate`:
 *  - with the parameter, the intent is `Activate(newKey)` — one gesture;
 *  - without it, the intent is `Activate(oldKey)` — the stale selection,
 *    i.e. the gesture only selected.
 */
@RunWith(AndroidJUnit4::class)
class HomeRootActivationWiringTest {

    @get:Rule
    val compose = createComposeRule()

    private fun card(key: CardKey, position: Int) = HomeCard(
        key = key,
        kind = CardKind.GAME,
        title = key.value,
        cardLine = "",
        state = CardState.None,
        primary = HomeAction.Play(position + 1L),
        primaryLabel = "Play",
        thumb = ArtRef.None,
        tv = TvModel(TvContent.TestCard(key.value), "STATE", null, null, null),
        span = 1,
        position = position,
    )

    @Test
    fun accessibilityClickOnAnUnselectedCardActivatesThatCardInOneGesture() {
        val a = CardKey.local(1)
        val b = CardKey.local(2)
        val model = HomeScreenModel(
            mode = HomeMode.HOME,
            header = HeaderModel("Search", "Browse", null, 0),
            sections = listOf(
                HomeSection(HomeGroup.RESUME, "RESUME", null, listOf(card(a, 0), card(b, 1))),
            ),
            defaultKey = a,
        )
        val intents = mutableListOf<HomeIntent>()
        compose.setContent {
            HomeRoot(
                model = model,
                onIntent = { intents += it },
                focusMemory = rememberConsoleFocusMemory(),
                hints = rememberConsoleHints(),
                reducedMotion = true,
            )
        }
        compose.waitUntil(WAIT_MS) { compose.hasNode(tag("home-card-local:2")) }
        compose.waitForIdle()

        // The gesture a TalkBack double tap performs on the card's node.
        compose.onAllNodes(tag("home-card-local:2")).onFirst()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()

        val activates = intents.filterIsInstance<HomeIntent.Activate>()
        assertEquals(
            "one accessibility gesture must dispatch exactly one Activate: $intents",
            1,
            activates.size,
        )
        assertEquals(
            "the a11y click must activate the card it was fired on, not the stale " +
                "selection (onActivate missing at HomeRoot.kt:445 means onTap ran and " +
                "only moved the selection): $intents",
            CardKey.local(2),
            activates.single().key,
        )
        assertFalse(
            "the activation must be for the clicked card: $intents",
            activates.any { it.key == CardKey.local(1) },
        )
    }

    private fun ComposeTestRule.hasNode(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun tag(value: String) = SemanticsMatcher("node tagged $value") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag) == value
    }

    private companion object {
        const val WAIT_MS = 15_000L
    }
}
