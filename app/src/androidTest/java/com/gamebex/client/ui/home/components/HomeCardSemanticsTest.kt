package com.gamebex.client.ui.home.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.HomeSection
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvModel
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The card's semantics `onClick` — what a TalkBack double tap fires —
 * runs the card's primary action directly, never the touch
 * select-then-activate. Group labels are headings.
 */
@RunWith(AndroidJUnit4::class)
class HomeCardSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun gameCard() = HomeCard(
        key = CardKey("game:1"),
        kind = CardKind.GAME,
        title = "Sonic the Hedgehog 2",
        cardLine = "GEN · played yesterday",
        primary = HomeAction.Play(1),
        primaryLabel = "Resume",
        tv = TvModel(TvContent.TestCard("NO GAME"), "RESUME", null, null, null),
        position = 0,
    )

    @Test
    fun accessibilityClickRunsThePrimaryActionNotSelect() {
        var taps = 0
        var activations = 0
        compose.setContent {
            Box {
                HomeCardView(
                    card = gameCard(),
                    selected = false,
                    focused = false,
                    onTap = { taps++ },
                    onActivate = { activations++ },
                )
            }
        }
        // The accessibility double tap fires the node's OnClick action.
        compose.onNodeWithTag("home-card-game:1")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals("a11y click must activate the primary action", 1, activations)
            assertEquals("a11y click must not stop at select", 0, taps)
        }
    }

    @Test
    fun groupLabelsAreHeadings() {
        compose.setContent {
            Box {
                GroupLabel(
                    HomeSection(
                        group = HomeGroup.RESUME,
                        label = "RESUME",
                        detail = "2 with saves",
                        cards = emptyList(),
                    ),
                    modifier = Modifier,
                )
            }
        }
        val isHeading = SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.Heading,
        )
        compose.onNodeWithText("RESUME")
            .assert(hasAnyAncestor(isHeading))
    }

    @Test
    fun quietLinkBodyIsCappedAtTwoLineBoxes() {
        // The body's two-line budget: a body long enough for many lines
        // measures exactly two line boxes + ellipsis. The cap comes from the
        // fixed 84 px link-row geometry plus the trimmed two-line budget; the
        // heightIn clamp stays as belt-and-braces.
        val longBody = ("Add one in System -> Game sources to search a site from here. ").repeat(6)
        val card = HomeCard(
            key = CardKey("link:NO_PLUGIN_HINT"),
            kind = CardKind.QUIET_LINK,
            title = "No ROM site plugin",
            cardLine = longBody,
            body = longBody,
            primary = HomeAction.None,
            primaryLabel = "",
            tv = TvModel(TvContent.TestCard("NO GAME"), "NO ROM SITE PLUGIN", null, null, null),
            position = 0,
        )
        var linePx = 0f
        compose.setContent {
            // Coupled to the production formula bodySp = 22f * g.scale * ts
            // (ts = min(g.textScale, 1.1)), not to a bare constant, so a
            // change to the geometry or text scale moves this test too.
            val g = com.gamebex.client.ui.console.LocalConsoleGeometry.current
            linePx = with(androidx.compose.ui.platform.LocalDensity.current) {
                (22f * g.scale * min(g.textScale, 1.1f)).sp.toPx()
            }
            Box(Modifier.size(width = 848.dp, height = 84.dp)) {
                QuietLinkRow(card, selected = true, focused = false, onTap = {})
            }
        }
        val h = compose.onNodeWithText(longBody)
            .fetchSemanticsNode()
            .size
            .height
        // Exactly two line boxes: not one (shaved), not three (overflow).
        org.junit.Assert.assertTrue(
            "body must lay out exactly two line boxes; height=$h line=$linePx",
            h >= 2 * linePx - 2f && h <= 2 * linePx + 2f,
        )
    }
}
