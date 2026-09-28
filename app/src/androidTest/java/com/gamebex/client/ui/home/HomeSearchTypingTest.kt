package com.gamebex.client.ui.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The search field's `TextFieldValue` is the source of truth, so a slow
 * store echo can never re-order keystrokes. Types "mario" one character at
 * a time on the real Home and requires the field to read exactly "mario".
 */
@RunWith(AndroidJUnit4::class)
class HomeSearchTypingTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun typingOneCharacterAtATime_readsExactlyWhatWasTyped() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode) }
            compose.waitForIdle()

            for (ch in listOf("m", "a", "r", "i", "o")) {
                compose.onAllNodes(editableNode).onFirst().performTextInput(ch)
            }

            compose.waitUntil(WAIT_MS) { compose.hasNode(editableNode.withText("mario")) }
        }
    }

    private fun ComposeTestRule.hasNode(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsMatcher.withText(text: String): SemanticsMatcher =
        SemanticsMatcher("editable text '$text'") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == text
        }

    private companion object {
        const val WAIT_MS = 15_000L

        val editableNode = SemanticsMatcher("editable text node") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText) != null
        }
    }
}
