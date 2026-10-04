package com.gamebex.client.ui.discover

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.GameBexTheme
import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.console.LocalConsoleGeometry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The system chip row picks by touch, marks the selection, and keeps the active chip in view. */
@RunWith(AndroidJUnit4::class)
class SystemChipRowTest {
    @get:Rule val rule = createComposeRule()

    private val chips = listOf(SystemChip(null, "Latest")) +
        (1..30).map { SystemChip("c$it", "System $it · $it") }

    /** Fixed 1:1 geometry and a narrow row, so most chips start off screen on any device. */
    private fun show(selected: () -> Int, focused: () -> Int? = { null }, onPick: (Int) -> Unit = {}) {
        rule.setContent {
            GameBexTheme {
                CompositionLocalProvider(LocalConsoleGeometry provides ConsoleGeometry(1f)) {
                    SystemChipRow(
                        chips = chips,
                        selected = selected(),
                        focused = focused(),
                        onPick = onPick,
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }
    }

    @Test fun tappingAChipReportsItsIndex() {
        var picked = -1
        show(selected = { 0 }, onPick = { picked = it })
        rule.onNodeWithText("System 1 · 1").performClick()
        rule.runOnIdle { assertEquals(1, picked) }
    }

    @Test fun selectedChipIsMarkedSelected() {
        show(selected = { 1 })
        rule.onNodeWithText("System 1 · 1").assertIsSelected()
        rule.onNodeWithText("Latest").assertIsNotSelected()
    }

    @Test fun selectionChangeScrollsTheChipIntoView() {
        val selected = mutableIntStateOf(0)
        show(selected = { selected.intValue })
        rule.onNodeWithText("System 30 · 30").assertDoesNotExist()
        rule.runOnIdle { selected.intValue = 30 }
        rule.onNodeWithText("System 30 · 30").assertIsDisplayed().assertIsSelected()
    }

    @Test fun dpadFocusLeadsTheScroll() {
        val focused = mutableStateOf<Int?>(null)
        show(selected = { 0 }, focused = { focused.value })
        rule.onNodeWithText("System 25 · 25").assertDoesNotExist()
        rule.runOnIdle { focused.value = 25 }
        rule.onNodeWithText("System 25 · 25").assertIsDisplayed().assertIsNotSelected()
    }
}
