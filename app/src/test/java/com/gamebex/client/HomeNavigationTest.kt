package com.gamebex.client

import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.HomeIntent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation gate drops only `Navigate` effects queued while Home was
 * not resumed. Fresh user intents — including taps during the enter
 * transition — arm the gate and their effects run. The Home press intent is
 * ACTION_MAIN + CATEGORY_HOME.
 */
class HomeNavGateTest {

    @Test
    fun aStaleEffectAtSessionStartIsDropped() {
        val gate = HomeNavGate()
        gate.onUserIntent(HomeIntent.Nav(HomeDestination.Downloads), "") // previous session
        gate.onSessionStart() // returning to Home: the queued effect is stale
        assertFalse(gate.shouldRunNavigate(homeIsTop = true))
    }

    @Test
    fun aTapDuringTheEnterTransitionRunsItsNavigation() {
        val gate = HomeNavGate()
        gate.onSessionStart()
        // The tap happens while the entry is still entering (not RESUMED);
        // the intent arms the gate.
        gate.onUserIntent(HomeIntent.Nav(HomeDestination.Discover), "")
        assertTrue(gate.shouldRunNavigate(homeIsTop = true))
    }

    @Test
    fun activateArmsTheGate() {
        val gate = HomeNavGate()
        gate.onSessionStart()
        gate.onUserIntent(HomeIntent.Activate(CardKey.local(1)), "")
        assertTrue(gate.shouldRunNavigate(homeIsTop = true))
    }

    @Test
    fun autoIntentsNeverArmTheGate() {
        val gate = HomeNavGate()
        gate.onSessionStart()
        gate.onUserIntent(HomeIntent.Entered, "")
        gate.onUserIntent(HomeIntent.DismissStatus, "")
        // The pre-filled query re-assert repeats the CURRENT query.
        gate.onUserIntent(HomeIntent.Query("sonic"), "sonic")
        assertFalse(gate.shouldRunNavigate(homeIsTop = true))
    }

    @Test
    fun userTypedQueryArmsTheGate() {
        val gate = HomeNavGate()
        gate.onSessionStart()
        gate.onUserIntent(HomeIntent.Query("zelda"), "sonic")
        assertTrue(gate.shouldRunNavigate(homeIsTop = true))
    }

    @Test
    fun effectsAfterLeavingHomeAreDropped() {
        val gate = HomeNavGate()
        gate.onSessionStart()
        gate.onUserIntent(HomeIntent.Nav(HomeDestination.Downloads), "")
        // The user already left Home before the slow effect landed.
        assertFalse(gate.shouldRunNavigate(homeIsTop = false))
    }

    @Test
    fun everyUserIntentTypeArmsExceptTheAutoOnes() {
        assertTrue(armsHomeNavigation(HomeIntent.Nav(HomeDestination.Downloads), ""))
        assertTrue(armsHomeNavigation(HomeIntent.Activate(CardKey.local(1)), ""))
        assertTrue(armsHomeNavigation(HomeIntent.Secondary(CardKey.local(1)), ""))
        assertTrue(armsHomeNavigation(HomeIntent.ClearSearch, "sonic"))
        assertTrue(armsHomeNavigation(HomeIntent.Surprise, ""))
        assertTrue(armsHomeNavigation(HomeIntent.ExitSurprise, ""))
        assertTrue(armsHomeNavigation(HomeIntent.RetryStatus, ""))
        assertFalse(armsHomeNavigation(HomeIntent.Entered, ""))
        assertFalse(armsHomeNavigation(HomeIntent.DismissStatus, ""))
        assertFalse(armsHomeNavigation(HomeIntent.Query(""), ""))
    }

    @Test
    fun homePressIsMainWithTheHomeCategory() {
        val main = "android.intent.action.MAIN"
        val home = "android.intent.category.HOME"
        val launcher = "android.intent.category.LAUNCHER"
        assertTrue(isHomePress(main, setOf(home)))
        assertTrue(isHomePress(main, setOf(home, "android.intent.category.DEFAULT")))
        // The launcher icon entry is MAIN + LAUNCHER: not a home press.
        assertFalse(isHomePress(main, setOf(launcher)))
        // A notification's route intent is not a home press.
        assertFalse(isHomePress("com.gamebex.ROUTE", setOf(home)))
        assertFalse(isHomePress(main, null))
        assertFalse(isHomePress(null, setOf(home)))
    }
}
