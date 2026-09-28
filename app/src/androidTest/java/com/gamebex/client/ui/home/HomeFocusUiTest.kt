package com.gamebex.client.ui.home

import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.debug.HomeFixtureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D-pad focus on the `normal` fixture drives the card semantics — exactly
 * one `home-card-*` node carries `selected = true`, and the `home-tv`
 * content description ("Preview: {stateLine}") changes whenever the cursor
 * lands on a different card. Keys go through real key events
 * (`sendKeyDownUpSync`), the same path a controller takes into `HomeRoot`'s
 * `consoleKeys` router.
 *
 * The expected cursor path derives from the fixture layout and the grid map
 * (each section starts a new row, cards fill two at a time): `normal`
 * renders RESUME [Crazy Taxi, Simpsons, Metal Gear Solid, All tile] /
 * DOWNLOADING [the Kingdom Hearts II job, All-downloads tile] / NEW
 * [Metroid Prime, Nights, Pikmin, All tile], so the rows are [1,2]
 * [3,all:RESUME] [dl:101,all:DOWNLOADING] [5,6] [7,all:NEW] — the fixtures
 * are built through the real `HomeGrouping.build`, so the DOWNLOADING row
 * is a job card plus the "All downloads" tile, never a plain game row.
 */
@RunWith(AndroidJUnit4::class)
class HomeFocusUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun dpadMovesSelection_updatesSelectedSemantics_andTvPreview() {
        // (key event to press first, expected selected card tag after it).
        val steps = listOf(
            null to "home-card-local:1", // entry: defaultKey = Crazy Taxi
            KeyEvent.KEYCODE_DPAD_RIGHT to "home-card-local:2", // -> Simpsons
            KeyEvent.KEYCODE_DPAD_DOWN to "home-card-all:RESUME", // row 1, col 1
            KeyEvent.KEYCODE_DPAD_LEFT to "home-card-local:3", // -> Metal Gear Solid
            KeyEvent.KEYCODE_DPAD_DOWN to "home-card-dl:101", // -> the KH II job
            KeyEvent.KEYCODE_DPAD_DOWN to "home-card-local:5", // -> Metroid Prime
            KeyEvent.KEYCODE_DPAD_RIGHT to "home-card-local:6", // -> Nights
            KeyEvent.KEYCODE_DPAD_UP to "home-card-all:DOWNLOADING", // row 2, col min(1,1)
        )

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            HomeFixtureActivity::class.java,
        ).putExtra("state", "normal")
        ActivityScenario.launch<HomeFixtureActivity>(intent).use { scenario ->
            compose.waitUntil(WAIT_MS) {
                compose.selectedCardTags().singleOrNull() == steps.first().second
            }
            var previousTv = compose.tvPreview()
            assertTrue(
                "home-tv must carry a Preview: contentDescription",
                previousTv.startsWith("Preview: "),
            )

            for ((key, expectedTag) in steps) {
                if (key != null) {
                    // Real key events into the app's window, then (only if
                    // injection lost the view focus) a plain dispatch through
                    // the activity — the same dispatchKeyEvent path the phone
                    // uses. Both end in HomeRoot's consoleKeys router.
                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
                    compose.waitForIdle()
                    var path = "sendKeyDownUpSync"
                    if (compose.selectedCardTags().singleOrNull() != expectedTag) {
                        path = "activity.dispatchKeyEvent"
                        scenario.onActivity { a ->
                            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                        }
                        compose.waitForIdle()
                    }
                    Log.i(TAG, "step ${keyName(key)} -> $expectedTag via $path")
                    try {
                        compose.waitUntil(WAIT_MS) {
                            compose.selectedCardTags().singleOrNull() == expectedTag
                        }
                    } catch (t: Throwable) {
                        throw AssertionError(
                            "after ${keyName(key)} expected selected=$expectedTag; " +
                                "selected=${compose.selectedCardTags()} " +
                                "cards=${compose.allCardTags()} tv=${compose.tvPreview()}",
                            t,
                        )
                    }
                }
                val selected = compose.selectedCardTags()
                assertEquals(
                    "exactly one selected home-card-* node after ${keyName(key)}",
                    1, selected.size,
                )
                assertEquals(
                    "selected home-card-* tag after ${keyName(key)}",
                    expectedTag, selected.single(),
                )
                val tv = compose.tvPreview()
                assertTrue("home-tv must carry a Preview: contentDescription", tv.startsWith("Preview: "))
                if (key != null) {
                    assertNotEquals(
                        "home-tv must change when the cursor moves (after ${keyName(key)})",
                        previousTv, tv,
                    )
                }
                previousTv = tv
            }
        }
    }

    /** Tags of the `home-card-*` nodes with `selected == true`. */
    private fun ComposeTestRule.selectedCardTags(): List<String> =
        onAllNodes(anyCard).fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    /** Tags of all composed `home-card-*` nodes (diagnostics). */
    private fun ComposeTestRule.allCardTags(): List<String> =
        onAllNodes(anyCard).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    /** `home-tv` content description ("Preview: {stateLine}"). */
    private fun ComposeTestRule.tvPreview(): String {
        val nodes = onAllNodes(hasTestTag("home-tv")).fetchSemanticsNodes()
        assertEquals("exactly one home-tv node", 1, nodes.size)
        return nodes.single().config.getOrNull(SemanticsProperties.ContentDescription)
            ?.singleOrNull() ?: ""
    }

    private fun keyName(key: Int?): String =
        if (key == null) "entry" else KeyEvent.keyCodeToString(key)

    private val anyCard = SemanticsMatcher("home-card-*") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("home-card-") == true
    }

    private companion object {
        const val TAG = "HomeFocusUiTest"
        const val WAIT_MS = 15_000L
    }
}
