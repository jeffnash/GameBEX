package com.gamebex.client.debug

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.gamebex.client.ui.GameBexTheme
import com.gamebex.client.ui.console.ConsoleDestination
import com.gamebex.client.ui.console.ConsoleGeometryRoot
import com.gamebex.client.ui.console.ConsoleScaffold
import com.gamebex.client.ui.console.ControllerHintBar
import com.gamebex.client.ui.console.rememberConsoleFocusMemory
import com.gamebex.client.ui.console.rememberConsoleHints
import com.gamebex.client.ui.home.HomeRoot
import com.gamebex.client.ui.home.model.HomeIntent

/**
 * The Query-vs-current-query decision, pure so it is unit-testable. A
 * `Query` equal to the current fixture's own query is the
 * search field's initial re-assert of a pre-filled query (e.g.
 * `searchnoplugin`'s "sonic") — never user typing — and must not swap
 * fixtures, which is what makes `searchnoplugin` reachable. A different
 * non-blank query is typing → `search`; blank → `normal`. Null = stay.
 */
fun fixtureSwitchFor(typed: String, currentQuery: String): String? = when {
    typed == currentQuery -> null
    typed.isBlank() -> "normal"
    else -> "search"
}

/**
 * Debug-only Home fixture host. Renders [HomeFixtures.states] full-bleed,
 * rail-less, so the fixture can be compared with the mockup shots on the
 * phone without touching the user's library:
 *
 *     adb shell am start -n com.gamebex.client/.debug.HomeFixtureActivity --es state normal
 *
 * Home intents are handled locally: search switches fixtures (via
 * [fixtureSwitchFor]), and everything else is logged and toasted (there is no
 * real store here).
 */
class HomeFixtureActivity : ComponentActivity() {

    /** Current fixture; `am start --es state X` switches it without a restart. */
    private val fixture = mutableStateOf("normal")

    /**
     * Test hook: every intent seen by the fixture, so instrumented tests
     * can assert what the pad/a11y paths dispatched (e.g. "the card's
     * accessibility onClick runs the primary action").
     */
    val observedIntents: MutableList<HomeIntent> =
        java.util.Collections.synchronizedList(mutableListOf<HomeIntent>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixture.value = fixtureFrom(intent)
        Log.i(TAG, "fixture ${fixture.value} (${HomeFixtures.states.joinToString()})")
        // Match MainActivity's window setup exactly, or the fixture renders
        // inset (consoleScale 0.503 vs the app's 0.571) and every
        // fixture-vs-mockup comparison comes out ~12% small.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Hide the system bars like MainActivity's immersive effect, or the
        // fixture screenshots carry status/nav bars the mockups don't have.
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val state by fixture
            val model = remember(state) { HomeFixtures.model(state) }
            val focusMemory = rememberConsoleFocusMemory()
            val hints = rememberConsoleHints()
            GameBexTheme {
                ConsoleGeometryRoot {
                    // Home is full-width with the hint strip visible; the
                    // fixture shows the HOME hint look.
                    ConsoleScaffold(
                        activeDestination = ConsoleDestination.Home,
                        onDestinationSelected = { },
                        focusMemory = focusMemory,
                        showRail = false,
                        controllerHints = { ControllerHintBar(hints.hints, emphasizePrimary = true) },
                    ) {
                        // The fixture hosts `HomeRoot` exactly as the real
                        // screen does: one HomeRoot instance for the whole
                        // session, fed a new model when the state changes. It
                        // stays out of `key(state)`: re-creating the whole Home
                        // subtree on every state swap throws away the editor's
                        // real focus and the editing state mid-keystroke (A /
                        // Enter / Down then fall through to the card grid
                        // instead of submitting the search). The field still
                        // starts each state on that state's own query: the
                        // external-query adoption in `HomeRoot`
                        // (`fieldAfterExternalQuery`) syncs it.
                        HomeRoot(
                            model = model,
                            onIntent = { i ->
                                applyIntent(i, model.query) { next -> fixture.value = next }
                            },
                            focusMemory = focusMemory,
                            hints = hints,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fixture.value = fixtureFrom(intent)
    }

    private fun fixtureFrom(intent: Intent?): String =
        intent?.getStringExtra(EXTRA_STATE)?.takeIf { it in HomeFixtures.states } ?: "normal"

    /** Fixture-local intent handling; [setState] takes the next fixture name. */
    private fun applyIntent(intent: HomeIntent, currentQuery: String, setState: (String) -> Unit) {
        observedIntents += intent
        when (intent) {
            is HomeIntent.Query -> fixtureSwitchFor(intent.text, currentQuery)?.let(setState)
            HomeIntent.ClearSearch -> setState("normal")
            HomeIntent.Surprise -> setState("surprise")
            HomeIntent.ExitSurprise -> setState("normal")
            else -> {
                Log.i(TAG, "intent: $intent")
                Toast.makeText(this, "$intent", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val TAG = "HomeFixture"
        const val EXTRA_STATE = "state"
    }
}
