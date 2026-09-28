package com.gamebex.client.debug

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.together.TogetherCopy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `am start --es state X` on a running fixture must switch it to X (singleTop redelivery). */
@RunWith(AndroidJUnit4::class)
class TogetherFixtureActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun newIntentWithAStateSwitchesTheRunningFixture() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val start = Intent(context, TogetherFixtureActivity::class.java).putExtra("state", "waiting")
        ActivityScenario.launch<TogetherFixtureActivity>(start).use { scenario ->
            compose.waitUntil(WAIT_MS) { compose.shows(TogetherCopy.hostWaitingTitle) }
            scenario.onActivity { activity ->
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(
                    activity,
                    Intent(context, TogetherFixtureActivity::class.java).putExtra("state", "joined"),
                )
            }
            compose.waitUntil(WAIT_MS) { compose.shows(TogetherCopy.guestJoined(TogetherCopy.otherPhone)) }
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.shows(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
