package com.gamebex.client.ui.home

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.debug.HomeFixtureActivity
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.HomeIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real input paths (real touch taps, real key events, real IME actions):
 *  - tapping the TEXT itself starts editing (editor real focus);
 *  - IME Search hides the keyboard and clears focus;
 *  - B hides the keyboard and keeps the query (first B closes editing,
 *    second B clears);
 *  - A and D-pad Down submit to the first result; hardware Enter submits
 *    and never reaches the A handler (no Activate);
 *  - with the keyboard open the cards keep their exact pixel height, the
 *    search field stays fully visible and the results scroll (the canvas
 *    never rescales while typing);
 *  - the card's accessibility onClick runs the PRIMARY action directly.
 */
@RunWith(AndroidJUnit4::class)
class HomeKeyboardImeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun tappingTheTextStartsEditing_imeSearchHidesTheKeyboardAndClearsFocus() {
        launch("normal") { scenario ->
            val base = windowHeightPx(scenario)
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode) }
            // A REAL touch on the text itself — the editor's own tap handling
            // takes focus; Home's editing state must follow that focus.
            compose.onAllNodes(editableNode).onFirst().performTouchInput { click() }
            compose.waitUntil(WAIT_MS) { compose.editorFocused() }
            compose.waitUntil(WAIT_MS) { keyboardUp(scenario, base) }

            compose.onAllNodes(editableNode).onFirst().performImeAction()

            compose.waitUntil(WAIT_MS) {
                !keyboardUp(scenario, base) && !compose.editorFocused()
            }
        }
    }

    @Test
    fun bHidesTheKeyboardAndKeepsTheQuery_firstBClosesEditingSecondBClears() {
        launch("normal") { scenario ->
            val base = windowHeightPx(scenario)
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode) }
            compose.onAllNodes(editableNode).onFirst().performTouchInput { click() }
            compose.waitUntil(WAIT_MS) { compose.editorFocused() }
            compose.onAllNodes(editableNode).onFirst().performTextInput("zelda")
            // The fixture swaps to `search`, whose own query is "sonic".
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode.withText("sonic")) }
            compose.waitUntil(WAIT_MS) { keyboardUp(scenario, base) }

            sendKey(scenario, KeyEvent.KEYCODE_BUTTON_B)
            compose.waitUntil(WAIT_MS) {
                !keyboardUp(scenario, base) && !compose.editorFocused()
            }
            assertEquals("sonic", compose.fieldText()) // query kept

            sendKey(scenario, KeyEvent.KEYCODE_BUTTON_B)
            compose.waitUntil(WAIT_MS) { compose.fieldText() == "" } // second B clears
        }
    }

    @Test
    fun downWhileEditingSubmitsToTheFirstResultWithoutActivating() {
        launch("normal") { scenario ->
            val base = windowHeightPx(scenario)
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode) }
            compose.onAllNodes(editableNode).onFirst().performTouchInput { click() }
            compose.waitUntil(WAIT_MS) { compose.editorFocused() }
            compose.onAllNodes(editableNode).onFirst().performTextInput("zelda")
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode.withText("sonic")) }
            scenario.onActivity { it.observedIntents.clear() }

            sendKey(scenario, KeyEvent.KEYCODE_DPAD_DOWN)

            compose.waitUntil(WAIT_MS) {
                !compose.editorFocused() && !keyboardUp(scenario, base)
            }
            compose.waitUntil(WAIT_MS) { compose.selectedCardTag() == compose.firstCardTag() }
            assertTrue(
                "submitting must never activate a card: ${compose.intents(scenario)}",
                compose.intents(scenario).none { it is HomeIntent.Activate },
            )
        }
    }

    @Test
    fun aWhileEditingSubmitsToTheFirstResultWithoutActivating() {
        launch("normal") { scenario ->
            val base = windowHeightPx(scenario)
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode) }
            compose.onAllNodes(editableNode).onFirst().performTouchInput { click() }
            compose.waitUntil(WAIT_MS) { compose.editorFocused() }
            compose.onAllNodes(editableNode).onFirst().performTextInput("zelda")
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode.withText("sonic")) }
            scenario.onActivity { it.observedIntents.clear() }

            sendKey(scenario, KeyEvent.KEYCODE_BUTTON_A)

            compose.waitUntil(WAIT_MS) {
                !compose.editorFocused() && !keyboardUp(scenario, base)
            }
            compose.waitUntil(WAIT_MS) { compose.selectedCardTag() == compose.firstCardTag() }
            assertTrue(
                "A while editing submits; it must not Activate: ${compose.intents(scenario)}",
                compose.intents(scenario).none { it is HomeIntent.Activate },
            )
        }
    }

    @Test
    fun hardwareEnterWhileEditingSubmitsAndNeverReachesTheAHandler() {
        launch("normal") { scenario ->
            val base = windowHeightPx(scenario)
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode) }
            compose.onAllNodes(editableNode).onFirst().performTouchInput { click() }
            compose.waitUntil(WAIT_MS) { compose.editorFocused() }
            compose.onAllNodes(editableNode).onFirst().performTextInput("zelda")
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode.withText("sonic")) }
            scenario.onActivity { it.observedIntents.clear() }

            // FocusArcade maps Enter -> A; while editing it must submit the
            // search and NEVER activate the selected card.
            sendKey(scenario, KeyEvent.KEYCODE_ENTER)

            compose.waitUntil(WAIT_MS) {
                !compose.editorFocused() && !keyboardUp(scenario, base)
            }
            compose.waitUntil(WAIT_MS) { compose.selectedCardTag() == compose.firstCardTag() }
            assertTrue(
                "Enter while editing must not reach the A handler: ${compose.intents(scenario)}",
                compose.intents(scenario).none { it is HomeIntent.Activate },
            )
        }
    }

    @Test
    fun firstKeystrokeModeChangeKeepsTheEditorFocused() {
        // The first keystroke switches HOME -> SEARCH (the fixture swaps
        // model states); the field keeps real focus (its focus ring) across
        // that rebuild.
        launch("normal") { scenario ->
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode) }
            compose.onAllNodes(editableNode).onFirst().performTouchInput { click() }
            compose.waitUntil(WAIT_MS) { compose.editorFocused() }
            compose.onAllNodes(editableNode).onFirst().performTextInput("z")
            // The mode change lands (fixture: `search`, query "sonic") and
            // the editor still holds focus.
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode.withText("sonic")) }
            assertTrue("the editor lost focus on the mode change", compose.editorFocused())
            assertFalse(compose.intents(scenario).any { it is HomeIntent.Activate })
        }
    }

    @Test
    fun keyboardOpen_keepsCardPixelSize_fieldVisible_resultsScrollable() {
        // The canvas never rescales while typing. Cards keep their exact px
        // height, the search field stays fully visible above the keyboard
        // and the results list scrolls. (The app is landscape-locked, so
        // this is the landscape run; the layout rule is orientation-agnostic.)
        launch("normal") { scenario ->
            val base = windowHeightPx(scenario)
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode) }
            val closedHeightPx = compose.onAllNodes(cardNode).fetchSemanticsNodes()
                .minByOrNull { it.boundsInRoot.top }!!.size.height

            compose.onAllNodes(editableNode).onFirst().performTouchInput { click() }
            compose.waitUntil(WAIT_MS) { compose.editorFocused() }
            compose.onAllNodes(editableNode).onFirst().performTextInput("zelda")
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode.withText("sonic")) }
            compose.waitUntil(WAIT_MS) { keyboardUp(scenario, base) }
            compose.waitForIdle()

            val openHeightPx = compose.onAllNodes(cardNode).fetchSemanticsNodes()
                .minByOrNull { it.boundsInRoot.top }!!.size.height
            assertEquals(
                "cards must keep their exact pixel size with the keyboard open",
                closedHeightPx,
                openHeightPx,
            )

            // The search field is fully visible: inside the window and above
            // the keyboard's top edge.
            val field = compose.onAllNodes(editableNode).fetchSemanticsNodes().first()
            val imeTop = imeTopPx(scenario)
            assertTrue(
                "field bottom ${field.boundsInRoot.bottom} must stay above the keyboard top $imeTop",
                field.boundsInRoot.bottom <= imeTop,
            )
            assertTrue("field top must stay on screen", field.boundsInRoot.top >= 0f)

            // The results list is a real scroller in the strip (the grid
            // scrolls — the field never moves).
            val grid = compose.onAllNodes(scrollableNode).fetchSemanticsNodes()
            assertTrue("no scrollable results list while editing", grid.isNotEmpty())
        }
    }

    @Test
    fun cardAccessibilityClickRunsThePrimaryActionDirectly() {
        // The a11y double-tap (the semantics OnClick) runs the card's
        // PRIMARY action in one gesture — select AND activate.
        launch("normal") { scenario ->
            compose.waitUntil(WAIT_MS) { compose.hasNode(cardTag("home-card-local:2")) }
            scenario.onActivity { it.observedIntents.clear() }

            compose.onNode(cardTag("home-card-local:2"))
                .performSemanticsAction(SemanticsActions.OnClick) { it() }

            compose.waitUntil(WAIT_MS) {
                compose.intents(scenario).any { it == HomeIntent.Activate(CardKey.local(2)) }
            }
        }
    }

    private fun launch(state: String, block: (ActivityScenario<HomeFixtureActivity>) -> Unit) {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            HomeFixtureActivity::class.java,
        ).putExtra("state", state)
        ActivityScenario.launch<HomeFixtureActivity>(intent).use(block)
    }

    /**
     * Real key path: `activity.dispatchKeyEvent` down+up — the same route a
     * controller takes into the window (sendKeyDownUpSync drops BUTTON_A/B
     * keycodes from a non-gamepad source).
     */
    private fun sendKey(scenario: ActivityScenario<HomeFixtureActivity>, key: Int) {
        scenario.onActivity { a ->
            val now = android.os.SystemClock.uptimeMillis()
            a.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
            a.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
        }
        compose.waitForIdle()
    }

    /**
     * The keyboard is up on EITHER device mechanism: (a) the window keeps
     * its size and reports the IME inset, (b) the window resizes for the
     * IME and the inset reads 0 — then the window itself is shorter than
     * the pre-keyboard height.
     */
    private fun keyboardUp(scenario: ActivityScenario<HomeFixtureActivity>, baseHeightPx: Int): Boolean {
        var up = false
        scenario.onActivity { a ->
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)
            val insetUp = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
            up = insetUp || a.window.decorView.height < baseHeightPx - 100
        }
        return up
    }

    private fun windowHeightPx(scenario: ActivityScenario<HomeFixtureActivity>): Int {
        var h = 0
        scenario.onActivity { h = it.window.decorView.height }
        return h
    }

    /** The keyboard's top edge in window px (the window bottom when no inset). */
    private fun imeTopPx(scenario: ActivityScenario<HomeFixtureActivity>): Int {
        var top = Int.MAX_VALUE
        scenario.onActivity { a ->
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)
            val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            top = if (bottom > 0) a.window.decorView.height - bottom else a.window.decorView.height
        }
        return top
    }

    private fun ComposeTestRule.hasNode(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeTestRule.editorFocused(): Boolean =
        onAllNodes(editableNode).fetchSemanticsNodes()
            .firstOrNull()?.config?.getOrNull(SemanticsProperties.Focused) == true

    private fun ComposeTestRule.fieldText(): String =
        onAllNodes(editableNode).fetchSemanticsNodes()
            .firstOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

    private fun ComposeTestRule.intents(scenario: ActivityScenario<HomeFixtureActivity>): List<HomeIntent> {
        var out = emptyList<HomeIntent>()
        scenario.onActivity { out = it.observedIntents.toList() }
        return out
    }

    /** The selected `home-card-*` tag, or null. */
    private fun ComposeTestRule.selectedCardTag(): String? =
        onAllNodes(cardSelected).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .firstOrNull()

    /** The topmost-leftmost `home-card-*` tag: the first result. */
    private fun ComposeTestRule.firstCardTag(): String? =
        onAllNodes(cardNode).fetchSemanticsNodes()
            .minWithOrNull(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
            ?.config?.getOrNull(SemanticsProperties.TestTag)

    private fun SemanticsMatcher.withText(text: String): SemanticsMatcher =
        SemanticsMatcher("editable text '$text'") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == text
        }

    private fun cardTag(tag: String): SemanticsMatcher =
        SemanticsMatcher("card $tag") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag) == tag
        }

    private companion object {
        const val WAIT_MS = 15_000L

        val editableNode = SemanticsMatcher("editable text node") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText) != null
        }

        val cardNode = SemanticsMatcher("home-card-* node") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("home-card-") == true
        }

        val cardSelected = SemanticsMatcher("selected home-card-* node") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("home-card-") == true &&
                node.config.getOrNull(SemanticsProperties.Selected) == true
        }

        val scrollableNode = SemanticsMatcher("scrollable results list") { node ->
            node.config.getOrNull(SemanticsActions.ScrollBy) != null ||
                node.config.getOrNull(SemanticsActions.ScrollToIndex) != null ||
                node.config.getOrNull(SemanticsActions.ScrollByOffset) != null
        }
    }
}
