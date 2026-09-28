package com.gamebex.client.ui.home.tv

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvGlyph
import com.gamebex.client.ui.home.model.TvModel
import com.gamebex.client.ui.home.model.TvTone
import com.gamebex.client.ui.SpaceGrotesk
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.HomeCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Both TV banner lines are pinned to the mockup size and each stays on one
 * line. The facts line does not scale with the user's text-size preference
 * and never wraps — measured as node-box geometry (px heights).
 */
@RunWith(AndroidJUnit4::class)
class TvBannerLineGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    private val longFacts = buildString {
        append("GameCube · RetroArch · Dolphin · 3 saves. ")
        repeat(4) { append("Add sega_101.bin once and every Saturn game starts. ") }
    }

    private fun model(facts: String) = TvModel(
        content = TvContent.TestCard("NO GAME"),
        stateKey = "NEEDS THE SATURN BIOS",
        stateDetail = null,
        glyph = TvGlyph.WARN,
        factsLine = facts,
        tone = TvTone.WARN,
    )

    /** Height in px of the facts Text node in the unmerged tree. */
    private fun factsHeight(facts: String): Int =
        compose.onAllNodesWithText(facts, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single()
            .size
            .height

    @Test
    fun factsLineTypeIsPinnedToTheMockupSizeAcrossTextSizes() {
        val textScale = mutableStateOf(1f)
        compose.setContent {
            val g = ConsoleGeometry(1f, textScale.value)
            CompositionLocalProvider(LocalConsoleGeometry provides g) {
                TvScreen(
                    tv = model(longFacts),
                    channel = 1,
                    reducedMotion = true,
                    onTap = {},
                    modifier = Modifier.size(g.x(646), g.x(378)),
                )
            }
        }
        val atDefault = factsHeight(longFacts)
        textScale.value = 1.4f
        compose.waitForIdle()
        val atLarger = factsHeight(longFacts)
        assertEquals(
            "facts line must not scale with the text preference (default $atDefault px vs Larger $atLarger px)",
            atDefault.toFloat(),
            atLarger.toFloat(),
            1.5f,
        )
    }

    @Test
    fun factsLineNeverWrapsToTwoLinesHoweverLongTheCopy() {
        val facts = mutableStateOf("GameCube · 3 saves")
        compose.setContent {
            val g = ConsoleGeometry(1f, 1.4f)
            CompositionLocalProvider(LocalConsoleGeometry provides g) {
                TvScreen(
                    tv = model(facts.value),
                    channel = 1,
                    reducedMotion = true,
                    onTap = {},
                    modifier = Modifier.size(g.x(646), g.x(378)),
                )
            }
        }
        val short = factsHeight(facts.value)
        facts.value = longFacts
        compose.waitForIdle()
        val long = factsHeight(longFacts)
        assertEquals(
            "facts line must stay one line (short copy $short px vs long copy $long px)",
            short.toFloat(),
            long.toFloat(),
            1.5f,
        )
    }
    @Test
    fun noRoomFactsLineFitsWholeAtNormalTextSize() {
        val facts = HomeCopy.tvFacts(
            CardState.NoRoom((1.2 * HomeCopy.GB).toLong(), (0.6 * HomeCopy.GB).toLong()),
            system = null,
            freeBytes = null,
        )
        assertEquals(
            "Needs 1.2 GB, 614 MB free. Delete a game to make room.",
            facts,
        )
        val g = ConsoleGeometry(1f, 1f)
        lateinit var layout: androidx.compose.ui.text.TextLayoutResult
        compose.setContent {
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val width = with(androidx.compose.ui.platform.LocalDensity.current) {
                g.x(646 - 44).roundToPx()
            }
            layout = measurer.measure(
                text = facts!!,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = SpaceGrotesk,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = g.mockupType(21),
                ),
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = width),
                softWrap = false,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        assertFalse(
            "no-room facts ellipsized at normal text size (laid out ${layout.size.width}px)",
            layout.hasVisualOverflow || layout.isLineEllipsized(0),
        )
    }
}
