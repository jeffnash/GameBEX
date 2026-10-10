package com.gamebex.client.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.stream.StreamFlagGroup
import com.gamebex.client.stream.StreamFlagStore
import com.gamebex.client.stream.StreamFlags
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.rememberConsoleFocusMemory
import com.gamebex.client.ui.together.TestDisplay
import com.gamebex.client.ui.together.TogetherUiTestSupport
import com.gamebex.client.ui.together.performKeyInput
import com.gamebex.client.ui.together.setTogetherContent
import com.gamebex.client.ui.together.setTogetherContentAt
import com.gamebex.client.ui.together.textIsCut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Stream flags screen (Settings/System → Device → Advanced → Stream flags). */
@RunWith(AndroidJUnit4::class)
class StreamFlagsScreenTest {
    @get:Rule val rule = createComposeRule()

    private class FakeFlagStore : StreamFlagStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) {
            map[key] = value
        }
        override fun resetAll() {
            map.clear()
        }
        override fun setPromising() {
            StreamFlags.ALL.filter { it.group == StreamFlagGroup.TRY }
                .forEach { map[it.key] = StreamFlags.promisingOn(it) }
        }
    }

    private fun show(store: FakeFlagStore, onBack: () -> Unit = {}): FakeFlagStore {
        rule.setTogetherContent { hints: ConsoleHints ->
            FlagsHarness(store, onBack, hints)
        }
        return store
    }

    @Composable
    private fun FlagsHarness(store: FakeFlagStore, onBack: () -> Unit, hints: ConsoleHints) {
        StreamFlagsScreen(onBack, TogetherUiTestSupport.rail, rememberConsoleFocusMemory(), hints, store)
    }

    /** Lazy rows outside the viewport aren't composed: scroll to them first. */
    private fun scrollToRow(key: String) {
        rule.onNodeWithTag("stream-flags-list", useUnmergedTree = true)
            .performScrollToNode(hasTestTag("flag-row-$key"))
    }

    @Test fun toggleRowFlipsOnTap() {
        val store = show(FakeFlagStore())
        scrollToRow("audio_regulator")
        rule.onNodeWithTag("flag-value-audio_regulator", useUnmergedTree = true).assertTextEquals("Off")
        rule.onNodeWithTag("flag-row-audio_regulator", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("flag-value-audio_regulator", useUnmergedTree = true).assertTextEquals("On")
        assertEquals("1", store.get("audio_regulator"))
        rule.onNodeWithTag("flag-row-audio_regulator", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("flag-value-audio_regulator", useUnmergedTree = true).assertTextEquals("Off")
        assertEquals("0", store.get("audio_regulator"))
    }

    @Test fun choiceRowCyclesOnTap() {
        show(FakeFlagStore())
        scrollToRow("start_level")
        rule.onNodeWithTag("flag-value-start_level", useUnmergedTree = true).assertTextEquals("Default (1280×576)")
        rule.onNodeWithTag("flag-row-start_level", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("flag-value-start_level", useUnmergedTree = true).assertTextEquals("1600×720")
        rule.onNodeWithTag("flag-row-start_level", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("flag-value-start_level", useUnmergedTree = true).assertTextEquals("1280×576")
    }

    @Test fun defaultsPresetClearsOverrides() {
        val store = show(FakeFlagStore())
        scrollToRow("audio_regulator")
        rule.onNodeWithTag("flag-row-audio_regulator", useUnmergedTree = true).performClick()
        scrollToRow("start_level")
        rule.onNodeWithTag("flag-row-start_level", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("flags-defaults", useUnmergedTree = true).performClick()
        scrollToRow("audio_regulator")
        rule.onNodeWithTag("flag-value-audio_regulator", useUnmergedTree = true).assertTextEquals("Off")
        scrollToRow("start_level")
        rule.onNodeWithTag("flag-value-start_level", useUnmergedTree = true).assertTextEquals("Default (1280×576)")
        assertTrue(store.map.isEmpty())
    }

    @Test fun promisingPresetTurnsOnTheTryGroupOnly() {
        val store = show(FakeFlagStore())
        rule.onNodeWithTag("flags-promising", useUnmergedTree = true).performClick()
        scrollToRow("audio_regulator")
        rule.onNodeWithTag("flag-value-audio_regulator", useUnmergedTree = true).assertTextEquals("On")
        scrollToRow("start_level")
        rule.onNodeWithTag("flag-value-start_level", useUnmergedTree = true).assertTextEquals("960×432")
        scrollToRow("audio_lead_frames")
        rule.onNodeWithTag("flag-value-audio_lead_frames", useUnmergedTree = true).assertTextEquals("512 (default)")
        scrollToRow("ra_poll")
        rule.onNodeWithTag("flag-value-ra_poll", useUnmergedTree = true).assertTextEquals("Late")
        assertEquals("1", store.get("audio_regulator"))
        assertEquals("2", store.get("start_level"))
        assertEquals(null, store.get("audio_lead_frames"))
        // On-by-default rows are not part of the preset.
        assertEquals(null, store.get("keyframes_on_request"))
        assertEquals(null, store.get("overlay_26b"))
    }

    @Test fun dpadMovesAndAChangesTheFocusedRowWhileBGoesBack() {
        var backs = 0
        show(FakeFlagStore()) { backs++ }
        // First row is the audio-lead choice: A steps 512 -> 768.
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        rule.onNodeWithTag("flag-value-audio_lead_frames", useUnmergedTree = true).assertTextEquals("768 (legacy)")
        // Down past the two on-by-default toggles and the P2 slot
        // workaround choice to the start-size choice,
        // A steps default -> 1600x720.
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        rule.onNodeWithTag("flag-value-start_level", useUnmergedTree = true).assertTextEquals("1600×720")
        rule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        rule.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun headerAndRowsStayWholeAtLargerText() {
        val display = TestDisplay(textScale = 1.4f)
        rule.setTogetherContentAt(display) { hints ->
            FlagsHarness(FakeFlagStore(), {}, hints)
        }
        for (fontScale in listOf(1f, 1.3f)) {
            rule.runOnIdle { display.fontScale = fontScale }
            assertFalse(
                "font $fontScale: header is cut",
                rule.onNodeWithText("STREAM FLAGS", useUnmergedTree = true).textIsCut(),
            )
            assertFalse(
                "font $fontScale: lead value is cut",
                rule.onNodeWithTag("flag-value-audio_lead_frames", useUnmergedTree = true).textIsCut(),
            )
        }
    }
}
