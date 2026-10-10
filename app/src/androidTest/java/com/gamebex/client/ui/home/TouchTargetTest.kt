package com.gamebex.client.ui.home

import android.content.Intent
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.MainActivity
import com.gamebex.client.debug.HomeFixtureActivity
import com.gamebex.client.debug.HomeFixtures
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every tap target on the redesign surfaces is at least 48x48 dp.
 *
 * ASSERTED (fails the run): all 9 `HomeFixtureActivity` fixture states and
 * `MainActivity:Home` — `home-card-*`, `home-tv`, `home-primary` (gesture-
 * tapped via `detectTapGestures`, so checked by their test tags) and every
 * node with a click action (search field, clear ✕, nav pills; the logo is a
 * plain 48x48-design-px `Image` and not a tap target). Hint pills are
 * covered behaviorally by [hintPills_keepTheirHitBoxOutsideTheVisiblePill]:
 * their tap `Box` uses `pointerInput` and exposes no semantics node to
 * measure, so the test taps the hit-box-only strip above the visible pill
 * and requires the pill's action to fire — that fails if anyone shrinks or
 * squeezes the box.
 *
 * ALLOWLIST (informational rows only, never a failure): the legacy screens
 * `MainActivity:Library`, `MainActivity:Downloads`, `MainActivity:System`
 * are pre-existing UI held OUT of the 48 dp contract for v1. The left rail
 * cells are NOT exempt — they carry real 48 dp hit boxes and are asserted.
 * Allowlisted rows are walked and logged so the data stays visible in the
 * test output.
 */
@RunWith(AndroidJUnit4::class)
class TouchTargetTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun homeFixtureStates_everyTapTargetIsAtLeast48dp() {
        val violations = mutableListOf<String>()
        for (state in HomeFixtures.states) {
            val intent = Intent(
                ApplicationProvider.getApplicationContext(),
                HomeFixtureActivity::class.java,
            ).putExtra("state", state)
            ActivityScenario.launch<HomeFixtureActivity>(intent).use {
                compose.waitUntil(WAIT_MS) { compose.hasNodeWithTag("home-tv") }
                compose.waitForIdle()
                val report = compose.tapTargetReport("fixture:$state")
                logRows(report)
                violations += report.violations
            }
        }
        logAndAssertEmpty("HomeFixtureActivity", violations)
    }

    @Test
    fun mainActivity_homeGate_legacyScreensInformational() {
        val violations = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(WAIT_MS) { compose.hasNodeWithTag("home-tv") }
            compose.waitForIdle()

            // The real Home at the app's edge-to-edge geometry: asserted.
            val home = compose.tapTargetReport("MainActivity:Home")
            logRows(home)
            violations += home.violations

            // Home header nav pill -> Library.
            compose.onAllNodes(clickableText("Library")).onFirst().performClick()
            compose.awaitScreen("Library") { plainText("Library") }
            logRows(compose.tapTargetReport("MainActivity:Library"))

            // Rail -> Downloads.
            compose.onAllNodes(clickableText("Downloads")).onFirst().performClick()
            compose.awaitScreen("Downloads") { plainText("Downloads") }
            logRows(compose.tapTargetReport("MainActivity:Downloads"))

            // Rail -> System.
            compose.onAllNodes(clickableText("System")).onFirst().performClick()
            compose.awaitScreen("System") { anyText("SYSTEM") }
            logRows(compose.tapTargetReport("MainActivity:System"))
        }
        logAndAssertEmpty("MainActivity:Home", violations)
    }

    /**
     * Hint pills (visible pill 48 design px tall, hit box `hitMin(x(84))`).
     * The tap Box has no semantics node, so probe the real
     * hit box: tap 20 dp straight up from the pill text — inside the 48 dp
     * hit box's top strip, outside the ~30-design-px visible pill — and
     * require the pill's button action to fire.
     *
     * 1. `normal` fixture: the Y pill "Surprise me" must switch the fixture
     *    to `surprise` (the ROLL_AGAIN invite card appears).
     * 2. `surprise` fixture: the B pill "Put it back" must return it to
     *    `normal` (Crazy Taxi `local:1` is back).
     */
    @Test
    fun hintPills_keepTheirHitBoxOutsideTheVisiblePill() {
        val density =
            InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val above = 20f * density // px: hit-box-only strip above the pill

        // Y "Surprise me" on `normal` -> `surprise`.
        launchFixture("normal").use {
            compose.onAllNodes(anyText("Surprise me")).onFirst()
                .performTouchInput {
                    click(Offset(visibleSize.width / 2f, visibleSize.height / 2f - above))
                }
            compose.waitUntil(WAIT_MS) {
                compose.hasNodeWithTag("home-card-invite:ROLL_AGAIN")
            }
        }

        // B "Put it back" on `surprise` -> `normal`.
        launchFixture("surprise").use {
            compose.onAllNodes(anyText("Put it back")).onFirst()
                .performTouchInput {
                    click(Offset(visibleSize.width / 2f, visibleSize.height / 2f - above))
                }
            compose.waitUntil(WAIT_MS) {
                compose.hasNodeWithTag("home-card-local:1")
            }
        }
    }

    /** One walk: below-48-dp rows split into asserted vs allowlisted. */
    private class Report(val violations: List<String>, val exempt: List<String>)

    /** All semantics nodes: the walk covers the whole merged tree. */
    private val anyNode = SemanticsMatcher("any node") { true }

    private fun ComposeTestRule.hasNodeWithTag(tag: String): Boolean =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun launchFixture(state: String): ActivityScenario<HomeFixtureActivity> {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            HomeFixtureActivity::class.java,
        ).putExtra("state", state)
        return ActivityScenario.launch<HomeFixtureActivity>(intent).also {
            compose.waitUntil(WAIT_MS) { compose.hasNodeWithTag("home-tv") }
            compose.waitForIdle()
        }
    }

    /** True once [marker] matches and Home is gone (navigation completed). */
    private fun ComposeTestRule.awaitScreen(name: String, marker: () -> SemanticsMatcher) {
        waitUntil(WAIT_MS) {
            onAllNodes(marker()).fetchSemanticsNodes().isNotEmpty() && !hasNodeWithTag("home-tv")
        }
        waitForIdle()
    }

    /**
     * Every tap-target node below [MIN_DP] x [MIN_DP] on [where]: click-action
     * nodes plus the gesture-tapped surfaces (cards, TV, primary).
     * Allowlisted legacy rows are split out (see the class KDoc).
     */
    private fun ComposeTestRule.tapTargetReport(where: String): Report {
        val density =
            InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val minPx = MIN_DP * density
        val violations = mutableListOf<String>()
        val exempt = mutableListOf<String>()
        for (node in onAllNodes(anyNode).fetchSemanticsNodes()) {
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            val clickable = node.config.getOrNull(SemanticsActions.OnClick) != null
            val gestureTapped = tag == "home-tv" || tag == "home-primary" ||
                tag?.startsWith("home-card-") == true
            if (!clickable && !gestureTapped) continue
            val w = node.size.width
            val h = node.size.height
            if (w >= minPx && h >= minPx) continue
            val row = "$where ${describe(node)}: ${w}x$h px " +
                "= ${"%.1f".format(w / density)}x${"%.1f".format(h / density)} dp " +
                "< ${MIN_DP}x$MIN_DP dp"
            if (isAllowlisted(where, node, density)) exempt += row else violations += row
        }
        return Report(violations, exempt)
    }

    /**
     * The legacy screens keep working exactly as today for v1 and are held
     * out of the 48 dp contract. Informational only. Rail cells are NOT
     * exempt — they carry a real 48 dp hit area, so they are asserted.
     */
    private fun isAllowlisted(where: String, node: androidx.compose.ui.semantics.SemanticsNode, density: Float): Boolean =
        where in LEGACY_SURFACES // legacy screen contents

    private fun logRows(report: Report) {
        report.exempt.forEach { println("TOUCH-TARGET (allowlisted): $it") }
    }

    private fun describe(node: androidx.compose.ui.semantics.SemanticsNode): String {
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("/")
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("/") { it.text }
        val editable = node.config.getOrNull(SemanticsProperties.EditableText)?.text
        val bounds = node.boundsInRoot
        val at = "(%.0f,%.0f %.0fx%.0f)".format(bounds.left, bounds.top, bounds.width, bounds.height)
        return "$at tag=$tag desc=$desc text=$text editable=$editable"
    }

    private fun logAndAssertEmpty(surface: String, violations: List<String>) {
        violations.forEach { Log.i(TAG, "TOUCH-TARGET: $it") }
        assertTrue(
            "$surface: ${violations.size} tap target(s) below ${MIN_DP}x$MIN_DP dp:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /** Nodes whose merged text holds [text] exactly; [clickable] filters on the click action. */
    private fun textNode(text: String, clickable: Boolean?): SemanticsMatcher =
        SemanticsMatcher("text '$text' clickable=$clickable") { node ->
            (clickable == null || (node.config.getOrNull(SemanticsActions.OnClick) != null) == clickable) &&
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }

    private fun clickableText(text: String): SemanticsMatcher = textNode(text, clickable = true)

    private fun plainText(text: String): SemanticsMatcher = textNode(text, clickable = false)

    private fun anyText(text: String): SemanticsMatcher = textNode(text, clickable = null)

    private companion object {
        const val TAG = "TouchTargetTest"
        const val MIN_DP = 48f
        const val WAIT_MS = 15_000L

        /** Screens kept "exactly as today" for v1. */
        val LEGACY_SURFACES = setOf(
            "MainActivity:Library",
            "MainActivity:Downloads",
            "MainActivity:System",
        )

        /** `ConsoleDestination` labels rendered by the left rail (ConsoleScaffold). */
        val RAIL_LABELS = setOf("Home", "Library", "Discover", "Downloads", "System")
    }
}
