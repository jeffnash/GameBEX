package com.gamebex.client.debug

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fails without the [fixtureSwitchFor] equal-query guard: HomeRoot's initial re-assert of
 * "sonic" would swap `searchnoplugin` to `search` and drop the no-plugin row.
 */
@RunWith(AndroidJUnit4::class)
class HomeFixtureQueryGuardTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun searchNoPluginKeepsTheNoPluginRowThroughTheInitialQueryReassert_andTypingAQuerySwapsToSearch() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            HomeFixtureActivity::class.java,
        ).putExtra("state", "searchnoplugin")
        ActivityScenario.launch<HomeFixtureActivity>(intent).use {
            compose.waitUntil(WAIT_MS) {
                compose.hasNode(noPluginRow) && compose.hasNode(editableNode)
            }
            // Let HomeRoot's initial Query re-assert ("sonic") run its course.
            repeat(REASSERT_CYCLES) { compose.waitForIdle() }
            assertTrue(
                "the No ROM site plugin row must survive the initial Query re-assert",
                compose.hasNode(noPluginRow),
            )
            assertFalse(
                "the initial Query re-assert swapped the fixture to search",
                compose.hasNode(siteRows),
            )

            compose.onAllNodes(editableNode).onFirst().performTextInput("zelda")
            compose.waitUntil(WAIT_MS) { compose.hasNode(siteRows) }
            assertTrue(
                "the search fixture must show the ROMS-I-OWN.COM rows",
                compose.hasNode(siteRows),
            )
            assertFalse(
                "the search fixture must drop the No ROM site plugin row",
                compose.hasNode(noPluginRow),
            )
        }
    }

    private fun ComposeTestRule.hasNode(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNode.spokenTexts(): List<String> =
        config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private val noPluginRow = SemanticsMatcher("row reading 'No ROM site plugin'") { node ->
        node.spokenTexts().any { it.contains("No ROM site plugin") }
    }

    private val siteRows = SemanticsMatcher("ROMS-I-OWN.COM rows") { node ->
        node.spokenTexts().any { it.contains("ROMS-I-OWN.COM") }
    }

    private val editableNode = SemanticsMatcher("editable text node") { node ->
        node.config.getOrNull(SemanticsProperties.EditableText) != null
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val REASSERT_CYCLES = 5
    }
}
