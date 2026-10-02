package com.gamebex.client.stream.guest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.GameBexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The viewer's overlays and actions, without a real stream. */
@RunWith(AndroidJUnit4::class)
class StreamViewerScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun ui(status: ViewerStatus, message: String? = null, stats: Boolean = false) = ViewerUi(
        status = status,
        hostName = "J",
        width = 1600,
        height = 720,
        showStats = stats,
        stats = if (stats) listOf("Video 38 ms (p95 61) · 60 fps · 7.8 Mbps") else emptyList(),
        message = message,
    )

    @Test
    fun connectingNamesTheHostAndHintsTheWayOut() {
        rule.setContent { GameBexTheme { StreamViewerScreen(ui(ViewerStatus.CONNECTING), {}, {}, {}, {}) } }
        rule.onNodeWithText(TogetherCopy.connecting("J")).assertIsDisplayed()
        rule.onNodeWithText("${TogetherCopy.leaveStreamHint} · ${TogetherCopy.tapForMenu}").assertIsDisplayed()
    }

    @Test
    fun aTapShowsLeaveAndStatsAndLeaveCallsBack() {
        var leaves = 0
        var toggles = 0
        rule.setContent { GameBexTheme { StreamViewerScreen(ui(ViewerStatus.WATCHING), {}, { leaves++ }, { toggles++ }, {}) } }
        rule.onNodeWithTag(TAG_ROOT).performClick()
        rule.onNodeWithText(TogetherCopy.showStats).performClick()
        assertEquals(1, toggles)
        rule.onNodeWithTag(TAG_ROOT).performClick()
        rule.onNodeWithText(TogetherCopy.leaveStream).performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun statsShowWhenOn() {
        var state by mutableStateOf(ui(ViewerStatus.WATCHING))
        rule.setContent { GameBexTheme { StreamViewerScreen(state, {}, {}, {}, {}) } }
        rule.runOnIdle { state = ui(ViewerStatus.WATCHING, stats = true) }
        rule.onNodeWithTag(TAG_STATS, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Video 38 ms (p95 61) · 60 fps · 7.8 Mbps").assertIsDisplayed()
    }

    @Test
    fun aDroppedLinkWarnsAtOnce() {
        val state = ui(ViewerStatus.WATCHING).copy(linkLost = true)
        rule.setContent { GameBexTheme { StreamViewerScreen(state, {}, {}, {}, {}) } }
        rule.onNodeWithTag(TAG_LINK_LOST, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.streamLinkLostStillGuest("J")).assertIsDisplayed()
    }

    @Test
    fun aDroppedLinkWhileReconnectingSaysItIsTryingToKeepTheStream() {
        val state = ui(ViewerStatus.RECONNECTING).copy(linkLost = true)
        rule.setContent { GameBexTheme { StreamViewerScreen(state, {}, {}, {}, {}) } }
        rule.onNodeWithText(TogetherCopy.streamLinkLostGuest("J")).assertIsDisplayed()
    }

    @Test
    fun reconnectingSaysSo() {
        rule.setContent { GameBexTheme { StreamViewerScreen(ui(ViewerStatus.RECONNECTING), {}, {}, {}, {}) } }
        rule.onNodeWithText(TogetherCopy.streamReconnecting).assertIsDisplayed()
    }

    @Test
    fun anEndedStreamExplainsAndBackCloses() {
        var backs = 0
        rule.setContent { GameBexTheme { StreamViewerScreen(ui(ViewerStatus.ENDED, TogetherCopy.streamEnded("J")), {}, {}, {}, { backs++ }) } }
        rule.onNodeWithText(TogetherCopy.streamEnded("J")).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.back).performClick()
        assertEquals(1, backs)
    }
}
