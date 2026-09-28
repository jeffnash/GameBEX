package com.gamebex.client.ui.console

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.GameBexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rail focus never outlives the rail or the screen it was entered on. */
@RunWith(AndroidJUnit4::class)
class ConsoleScaffoldRailFocusTest {
    @get:Rule val rule = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test fun railFocusClearsWhenTheRailHidesOrTheRouteChanges() {
        val showRail = mutableStateOf(true)
        val route = mutableStateOf("allgames")
        var picked = 0
        var host: RailHost? = null
        val focus = FocusRequester()
        rule.setContent {
            GameBexTheme {
                ConsoleGeometryRoot {
                    ConsoleScaffold(
                        activeDestination = ConsoleDestination.Library,
                        onDestinationSelected = { picked++ },
                        focusMemory = rememberConsoleFocusMemory(),
                        showRail = showRail.value,
                        railResetKey = route.value,
                    ) { rail ->
                        host = rail
                        Box(Modifier.fillMaxSize().focusRequester(focus).focusable())
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    }
                }
            }
        }
        fun pressA() {
            val down = AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_BUTTON_A)
            rule.onRoot().performKeyPress(KeyEvent(down))
            rule.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent.changeAction(down, AndroidKeyEvent.ACTION_UP)))
        }

        // Home key / notification / invite: navigation elsewhere hides the rail.
        rule.runOnIdle { host!!.enterRail() }
        rule.runOnIdle { showRail.value = false }
        rule.runOnIdle { showRail.value = true }
        pressA()
        rule.runOnIdle { assertEquals(0, picked) }

        // The route changes while the rail still shows.
        rule.runOnIdle { host!!.enterRail() }
        rule.runOnIdle { route.value = "downloads" }
        pressA()
        rule.runOnIdle { assertEquals(0, picked) }

        // Without a reset, A on the rail picks a tab.
        rule.runOnIdle { host!!.enterRail() }
        pressA()
        rule.runOnIdle { assertEquals(1, picked) }
    }
}
