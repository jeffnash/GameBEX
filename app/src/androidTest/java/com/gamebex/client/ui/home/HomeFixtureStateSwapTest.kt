package com.gamebex.client.ui.home

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.debug.HomeFixtureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `HomeRoot` is hosted once, never through `key(state)`; a state swap still
 * starts each fixture state on ITS OWN query, so it can never leave stale
 * text over the wrong grid. Typing one character switches `normal` ->
 * `search` (the state swap the editor must survive mid-keystroke); the
 * second B then clears the query, which switches back to `normal`, and the
 * field must be empty with the `normal` grid back (its first card selected).
 */
@RunWith(AndroidJUnit4::class)
class HomeFixtureStateSwapTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun clearingTheQueryAfterATypedSwapEmptiesTheFieldAndRestoresTheNormalFixture() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            HomeFixtureActivity::class.java,
        ).putExtra("state", "normal")
        ActivityScenario.launch<HomeFixtureActivity>(intent).use { scenario ->
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode) }
            compose.onAllNodes(editableNode).onFirst().performTouchInput { click() }
            compose.waitUntil(WAIT_MS) { compose.editorFocused() }

            // Typing swaps the fixture to `search` (its own query is "sonic");
            // HomeRoot adopts that query instead of the fixture re-creating it.
            compose.onAllNodes(editableNode).onFirst().performTextInput("zelda")
            compose.waitUntil(WAIT_MS) { compose.fieldText() == "sonic" }

            // First B: closes editing, keeps the query.
            sendKey(scenario, KeyEvent.KEYCODE_BUTTON_B)
            compose.waitUntil(WAIT_MS) { !compose.editorFocused() }
            assertEquals("sonic", compose.fieldText())

            // Second B: SEARCH mode -> ClearSearch -> the `normal` fixture.
            sendKey(scenario, KeyEvent.KEYCODE_BUTTON_B)
            compose.waitUntil(WAIT_MS) { compose.fieldText() == "" }
            compose.waitUntil(WAIT_MS) { compose.hasNode(cardTag("home-card-local:1")) }
            assertTrue(
                "the normal fixture's first card must be back and selected: " +
                    "${compose.selectedCardTag()}",
                compose.selectedCardTag() == "home-card-local:1",
            )
        }
    }

    private fun sendKey(scenario: ActivityScenario<HomeFixtureActivity>, key: Int) {
        scenario.onActivity { a ->
            val now = android.os.SystemClock.uptimeMillis()
            a.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
            a.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
        }
        compose.waitForIdle()
    }

    private fun ComposeTestRule.hasNode(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeTestRule.editorFocused(): Boolean =
        onAllNodes(editableNode).fetchSemanticsNodes()
            .firstOrNull()?.config?.getOrNull(SemanticsProperties.Focused) == true

    private fun ComposeTestRule.fieldText(): String =
        onAllNodes(editableNode).fetchSemanticsNodes()
            .firstOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

    private fun ComposeTestRule.selectedCardTag(): String? =
        onAllNodes(cardSelected).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .firstOrNull()

    private fun cardTag(tag: String): SemanticsMatcher =
        SemanticsMatcher("card $tag") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag) == tag
        }

    private companion object {
        const val WAIT_MS = 15_000L

        val editableNode = SemanticsMatcher("editable text node") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText) != null
        }

        val cardSelected = SemanticsMatcher("selected home-card-* node") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("home-card-") == true &&
                node.config.getOrNull(SemanticsProperties.Selected) == true
        }
    }
}
