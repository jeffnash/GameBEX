package com.gamebex.client.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.console.ConsoleGeometryRoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A recent search can be removed by touch without picking it first. */
@RunWith(AndroidJUnit4::class)
class RecentRowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tappingTheCrossRemovesWithoutSearching() {
        var picked = 0
        var removed = 0
        rule.setContent {
            GameBexTheme { ConsoleGeometryRoot { RecentRow("zelda", focused = false, onClick = { picked++ }, onRemove = { removed++ }) } }
        }
        rule.onNodeWithContentDescription("Remove zelda").performClick()
        rule.runOnIdle {
            assertEquals(1, removed)
            assertEquals(0, picked)
        }
    }

    @Test fun focusedRowShowsXRemoveInsteadOfTheCross() {
        var removed = 0
        rule.setContent {
            GameBexTheme { ConsoleGeometryRoot { RecentRow("zelda", focused = true, onClick = {}, onRemove = { removed++ }) } }
        }
        rule.onAllNodesWithContentDescription("Remove zelda").assertCountEquals(0)
        rule.onNodeWithText("Remove").performClick()
        rule.runOnIdle { assertEquals(1, removed) }
    }
}
