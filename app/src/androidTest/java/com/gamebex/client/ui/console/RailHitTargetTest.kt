package com.gamebex.client.ui.console

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every rail cell's click node is a real 48 dp tap target. The visual cell
 * sits centred inside an outer hit Box sized hitMin(x(72)); the scaffold is
 * composed at the handheld geometry (~0.573) where x(72) = 41.3 dp.
 *
 * The guard pins the visual stack: the label nodes prove the cells stay
 * exactly x(72) apart (first cell at y 104 design px) and each label keeps
 * its x — centred in its rail-wide cell, so the x derives per label width
 * ("Home" and "Library" differ).
 */
@RunWith(AndroidJUnit4::class)
class RailHitTargetTest {

    @get:Rule
    val compose = createComposeRule()

    private val g = ConsoleGeometry(917f / 1600f)

    @Test
    fun railCellClickNodes_areAtLeast48dpTapTargets() {
        compose.setScaffold()
        val minPx = with(displayDensity) { TouchMinTarget.roundToPx() }
        for (dest in ConsoleDestination.entries) {
            val nodes = compose.onAllNodes(railCell(dest.label)).fetchSemanticsNodes()
            assertEquals("one clickable rail cell for ${dest.label}", 1, nodes.size)
            val node = nodes.single()
            assertTrue(
                "rail cell ${dest.label} tap target is ${node.size.width}x${node.size.height} px " +
                    "= ${node.size.width / displayDensity.density}x" +
                    "${node.size.height / displayDensity.density} dp, must be at least 48x48 dp",
                node.size.width >= minPx && node.size.height >= minPx,
            )
        }
    }

    @Test
    fun railVisualCells_keepThePlainStackExactlyX72ApartAtTheirRound1Positions() {
        compose.setScaffold()
        val d = displayDensity
        val slotPx = with(d) { g.x(72).roundToPx() }
        val railPx = with(d) { g.railWidth.roundToPx() }
        // The stack: 20 pad + 56 brand + 28 gap = the cell slot at y 104
        // design px; the label sits x(10)+x(24)+x(8) below the cell top and
        // is horizontally centred in the rail-wide cell (Column
        // CenterHorizontally = Math.round((space - size) / 2f), verified in
        // Compose 1.7.0 BiasAlignment$Horizontal). Labels have different
        // widths, so each label's x derives from its own width.
        var expectedTop = with(d) {
            g.x(20).roundToPx() + g.x(56).roundToPx() + g.x(28).roundToPx() +
                g.x(10).roundToPx() + g.x(24).roundToPx() + g.x(8).roundToPx()
        }
        for (dest in ConsoleDestination.entries) {
            val nodes = compose
                .onAllNodes(labelText(dest.label), useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertEquals("one label for ${dest.label}", 1, nodes.size)
            val node = nodes.single()
            val bounds = node.boundsInRoot
            val expectedLeft = Math.round((railPx - node.size.width) / 2f)
            assertEquals("${dest.label} label keeps its x position", expectedLeft, Math.round(bounds.left))
            assertEquals("${dest.label} label keeps its y position", expectedTop, Math.round(bounds.top))
            expectedTop += slotPx
        }
    }

    private val displayDensity: Density
        get() = Density(
            InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density,
        )

    /** The real [ConsoleScaffold] at handheld geometry, all slots empty. */
    private fun ComposeContentTestRule.setScaffold() {
        setContent {
            CompositionLocalProvider(LocalConsoleGeometry provides g) {
                ConsoleScaffold(
                    activeDestination = ConsoleDestination.Home,
                    onDestinationSelected = {},
                    focusMemory = ConsoleFocusMemory(),
                    content = {},
                )
            }
        }
    }

    /** Merged click node carrying [label] (merged label text or description). */
    private fun railCell(label: String): SemanticsMatcher =
        SemanticsMatcher("clickable rail cell labeled $label") { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                (
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
                    )
        }

    /** The visible label node (unmerged tree: the label is inside the click). */
    private fun labelText(label: String): SemanticsMatcher =
        SemanticsMatcher("label text $label") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }
}
