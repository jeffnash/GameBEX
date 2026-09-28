package com.gamebex.client.ui.console

import android.content.Intent
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.MainActivity
import com.gamebex.client.debug.HomeFixtureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The search field keeps a 48 dp tap target without trading away the editor's
 * copy/paste/selection semantics. On the `normal` fixture the editable node
 * is at least 48x48 dp and carries the full text action set: [SemanticsProperties.EditableText],
 * [SemanticsActions.SetText], [SemanticsActions.SetSelection],
 * [SemanticsActions.CopyText], [SemanticsActions.PasteText] and
 * [SemanticsActions.GetTextLayoutResult] — and the actions actually work
 * (replace, select, copy, paste the query).
 */
@RunWith(AndroidJUnit4::class)
class ConsoleSearchFieldSemanticsTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun homeNormal_searchField_exposesFullTextSemanticsOn48dpNode() {
        val density =
            InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val minPx = MIN_DP * density
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            HomeFixtureActivity::class.java,
        ).putExtra("state", "normal")
        ActivityScenario.launch<HomeFixtureActivity>(intent).use {
            compose.waitUntil(WAIT_MS) { compose.hasNode(homeTv) }
            compose.waitForIdle()

            val nodes = compose.onAllNodes(editableNode).fetchSemanticsNodes()
            assertTrue("no editable node on the search field", nodes.isNotEmpty())
            for (node in nodes) {
                // The row's node carries the click and IME actions too.
                assertTrue("missing OnClick", node.config.hasAction(SemanticsActions.OnClick))
                assertTrue("missing OnImeAction", node.config.hasAction(SemanticsActions.OnImeAction))
                assertTrue("missing SetText", node.config.hasAction(SemanticsActions.SetText))
                assertTrue("missing SetSelection", node.config.hasAction(SemanticsActions.SetSelection))
                assertTrue("missing Copy", node.config.hasAction(SemanticsActions.CopyText))
                assertTrue("missing Paste", node.config.hasAction(SemanticsActions.PasteText))
                assertTrue(
                    "missing GetTextLayoutResult",
                    node.config.hasAction(SemanticsActions.GetTextLayoutResult),
                )
                val w = node.size.width
                val h = node.size.height
                assertTrue(
                    "editable node is ${w / density}x${h / density} dp < ${MIN_DP}x$MIN_DP dp",
                    w >= minPx && h >= minPx,
                )
            }

            // SetText replaces the query through the caller — the
            // fixture routes Query("zelda") to its `search` state, whose query
            // is "sonic", so the replace round-trip is observable here.
            compose.onAllNodes(editableNode).onFirst()
                .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("zelda")) }
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode.withText("sonic")) }

            // Select + copy puts the selection on the clipboard.
            compose.onAllNodes(editableNode).onFirst()
                .performSemanticsAction(SemanticsActions.SetSelection) { it(0, 3, true) }
            compose.onAllNodes(editableNode).onFirst()
                .performSemanticsAction(SemanticsActions.CopyText) { it() }
            val clip = InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.text?.toString()
            assertEquals("son", clip)

            // Paste runs and accepts the clipboard content.
            var pasteOk = false
            compose.onAllNodes(editableNode).onFirst()
                .performSemanticsAction(SemanticsActions.PasteText) { pasteOk = it() }
            assertTrue("Paste action returned false", pasteOk)

            // Paste over a BACKWARDS selection must not crash
            // (replaceRange with start > end).
            compose.onAllNodes(editableNode).onFirst()
                .performSemanticsAction(SemanticsActions.SetSelection) { it(4, 1, true) }
            var pasteBackwardsOk = false
            compose.onAllNodes(editableNode).onFirst()
                .performSemanticsAction(SemanticsActions.PasteText) { pasteBackwardsOk = it() }
            assertTrue("Paste over a backwards selection returned false", pasteBackwardsOk)

            // InsertTextAtCursor over a backwards selection.
            var insertOk = false
            compose.onAllNodes(editableNode).onFirst()
                .performSemanticsAction(SemanticsActions.SetSelection) { it(3, 0, true) }
            compose.onAllNodes(editableNode).onFirst()
                .performSemanticsAction(SemanticsActions.InsertTextAtCursor) {
                    insertOk = it(AnnotatedString("Q"))
                }
            assertTrue("InsertTextAtCursor over a backwards selection returned false", insertOk)
        }
    }

    /**
     * The same 48 dp editor node with click + IME + paste semantics on
     * Library and Search (their call lines pass a 48 dp row).
     */
    @Test
    fun libraryAndSearchFields_exposeFullTextSemanticsOnA48dpRow() {
        val density =
            InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val minPx = MIN_DP * density
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(WAIT_MS) { compose.hasNode(homeTv) }
            compose.waitForIdle()

            // Home header pill -> Library (allgames).
            compose.onAllNodes(textNode("Library")).onFirst().performClick()
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode) }
            assertFieldContract(minPx, density, "Library")

            // Rail -> Discover, then X opens Search (the real route).
            compose.onAllNodes(textNode("Discover")).onFirst().performClick()
            compose.waitUntil(WAIT_MS) { !compose.hasNode(editableNode) } // Discover has no field
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
                android.view.KeyEvent.KEYCODE_BUTTON_X,
            )
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode) }
            assertFieldContract(minPx, density, "Search")
        }
    }

    private fun assertFieldContract(minPx: Float, density: Float, screen: String) {
        compose.waitForIdle()
        val nodes = compose.onAllNodes(editableNode).fetchSemanticsNodes()
        assertTrue("$screen: no editable node", nodes.isNotEmpty())
        for (node in nodes) {
            assertTrue("$screen: missing OnClick", node.config.hasAction(SemanticsActions.OnClick))
            assertTrue(
                "$screen: missing OnImeAction",
                node.config.hasAction(SemanticsActions.OnImeAction),
            )
            assertTrue("$screen: missing Paste", node.config.hasAction(SemanticsActions.PasteText))
            val w = node.size.width
            val h = node.size.height
            assertTrue(
                "$screen: editable node is ${w / density}x${h / density} dp < ${MIN_DP}x$MIN_DP dp",
                w >= minPx && h >= minPx,
            )
        }
    }

    private fun ComposeTestRule.hasNode(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsMatcher.withText(text: String): SemanticsMatcher =
        SemanticsMatcher("editable text '$text'") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == text
        }

    private fun textNode(text: String): SemanticsMatcher =
        SemanticsMatcher("text '$text'") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == text } == true
        }

    private companion object {
        const val MIN_DP = 48f
        const val WAIT_MS = 15_000L

        val homeTv = SemanticsMatcher("home-tv") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag) == "home-tv"
        }

        val editableNode = SemanticsMatcher("editable text node") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText) != null
        }

        fun <T : Function<Boolean>> androidx.compose.ui.semantics.SemanticsConfiguration.hasAction(
            key: SemanticsPropertyKey<AccessibilityAction<T>>,
        ): Boolean = getOrNull(key)?.action != null
    }
}
