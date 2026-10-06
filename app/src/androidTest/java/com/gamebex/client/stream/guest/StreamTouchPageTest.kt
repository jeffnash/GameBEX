package com.gamebex.client.stream.guest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.GameBexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The viewer Menu → Touch controls page. */
@RunWith(AndroidJUnit4::class)
class StreamTouchPageTest {
    @get:Rule val rule = createComposeRule()

    private fun watching() = ViewerUi(
        status = ViewerStatus.WATCHING,
        hostName = "J",
        width = 1600,
        height = 720,
    )

    private fun show(
        prefs: TouchPrefs = TouchPrefs(),
        onChange: (TouchPrefs) -> Unit = {},
        onReset: () -> Unit = {},
    ) {
        rule.setContent {
            GameBexTheme {
                StreamViewerScreen(
                    watching(), {}, {}, {}, {},
                    touchPrefs = prefs,
                    onTouchPrefsChange = onChange,
                    onResetTouchPrefs = onReset,
                )
            }
        }
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).performClick()
        rule.onNodeWithText("Touch controls").performClick()
        rule.onNodeWithTag("touch-controls-page", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun nodeText(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }

    @Test fun pageShowsCurrentValuesAndTogglesWriteThrough() {
        val changes = mutableListOf<TouchPrefs>()
        var resets = 0
        show(onChange = { changes += it }, onReset = { resets++ })
        // The test window may force the size clamp; the requested value leads either way.
        assertTrue(nodeText("touch-size-value").startsWith("Size: 100%"))
        rule.onNodeWithTag("touch-opacity-value", useUnmergedTree = true).assertTextEquals("Opacity: 55%")
        rule.onNodeWithTag("touch-swap", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("touch-haptics", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("touch-stick-mode", useUnmergedTree = true).performClick()
        rule.runOnIdle {
            assertEquals(3, changes.size)
            assertTrue(changes[0].swapSides)
            assertEquals(TouchHaptics.LIGHT, changes[1].haptics)
            assertEquals(TouchStickMode.FLOATING, changes[2].stickMode)
        }
        rule.onNodeWithTag("touch-reset", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(1, resets) }
        rule.onNodeWithTag("touch-close", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("touch-controls-page", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun sizeSliderWritesSteppedValues() {
        val changes = mutableListOf<TouchPrefs>()
        show(onChange = { changes += it })
        rule.onNodeWithTag("touch-size-slider", useUnmergedTree = true).performTouchInput { swipeRight() }
        rule.runOnIdle {
            assertTrue(changes.isNotEmpty())
            assertTrue(changes.all { it.sizePercent in TouchPrefs.SIZE_MIN..TouchPrefs.SIZE_MAX })
            assertEquals(changes.maxOf { it.sizePercent }, changes.last().sizePercent)
        }
    }

    @Test fun clampedNoteNamesTheEffectiveSize() {
        show(prefs = TouchPrefs(sizePercent = 125))
        val label = nodeText("touch-size-value")
        assertTrue(label, label.startsWith("Size: 125%"))
        if ("using" in label) {
            val eff = label.substringAfter("using ").substringBefore("%").toInt()
            assertTrue(label, eff in TouchPrefs.SIZE_MIN until 125)
            assertTrue(label, label.endsWith("— clamped to avoid overlap)"))
        }
    }
}
