package com.gamebex.client.ui.discover

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.GameBexTheme
import com.gamebex.client.ui.console.ConsoleGeometryRoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Discover's "LB / RB" switches the shelf's category by touch as well as by shoulder buttons. */
@RunWith(AndroidJUnit4::class)
class ShelfSwitchTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tappingLbAndRbSwitchesCategory() {
        var prev = 0
        var next = 0
        rule.setContent { GameBexTheme { ConsoleGeometryRoot { ShelfSwitch(onPrev = { prev++ }, onNext = { next++ }) } } }
        rule.onNodeWithText("LB").performClick()
        rule.onNodeWithText("RB").performClick()
        rule.onNodeWithText("RB").performClick()
        rule.runOnIdle {
            assertEquals(1, prev)
            assertEquals(2, next)
        }
    }
}
