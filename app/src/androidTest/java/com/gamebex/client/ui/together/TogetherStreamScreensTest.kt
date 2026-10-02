package com.gamebex.client.ui.together

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.debug.FakeTogetherController
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.GuestView
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.HostView
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Play together screens' Stream parts. */
@RunWith(AndroidJUnit4::class)
class TogetherStreamScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun host(step: HostStep, alt: List<TogetherMode> = emptyList(), line: String? = null) = HostView(
        step = step,
        gameId = 1L,
        gameTitle = "Crash Team Racing",
        folder = "psx",
        mode = TogetherMode.RA_NETPLAY,
        myName = "J",
        myAddress = "10.42.1.20",
        port = 55440,
        qrText = "gamebex://join?v=1&h=10.42.1.20&p=55440&n=J",
        guestName = if (step == HostStep.IN_GAME) "B" else null,
        guestPhase = if (step == HostStep.IN_GAME) GuestPhase.PLAYING else null,
        altModes = alt,
        streamLine = line,
    )

    private fun streamGuest() = GuestView(GuestStep.IN_GAME, "J", "Mario Kart 64", "n64", TogetherMode.STREAM)

    @Test
    fun theHostCanTapToUseTheOtherMode() {
        val fake = FakeTogetherController().also { it.setHost(host(HostStep.WAITING, alt = listOf(TogetherMode.STREAM))) }
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onNodeWithText(TogetherCopy.useModeInstead(TogetherMode.STREAM), ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithTag(HOST_ALT_MODE_TAG).performClick()
        assertEquals(listOf(TogetherMode.STREAM), fake.switches)
    }

    @Test
    fun noOtherModeMeansNoSwitchButton() {
        val fake = FakeTogetherController().also { it.setHost(host(HostStep.WAITING)) }
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onNodeWithTag(HOST_ALT_MODE_TAG).assertDoesNotExist()
    }

    @Test
    fun onceAGuestJoinedTheSwitchIsGone() {
        val fake = FakeTogetherController().also {
            it.setHost(host(HostStep.JOINED, alt = listOf(TogetherMode.STREAM)).copy(guestName = "B", guestPhase = GuestPhase.READY))
        }
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onNodeWithTag(HOST_ALT_MODE_TAG).assertDoesNotExist()
    }

    @Test
    fun theHostSeesTheStreamLine() {
        val line = TogetherCopy.hostStreamLine("B", 60, 7_900_000)
        val fake = FakeTogetherController().also { it.setHost(host(HostStep.IN_GAME, line = line)) }
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onNodeWithText(line).assertIsDisplayed()
    }

    @Test
    fun aClosedViewerOffersOpenStream() {
        val fake = FakeTogetherController().also { it.setGuest(streamGuest()) }
        rule.setTogetherContent { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}, streamViewerOpen = false) }
        rule.onNodeWithText(TogetherCopy.streamClosed).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.openStream, ignoreCase = true).performClick()
        assertEquals(TogetherAction.OPEN_STREAM, fake.lastAction)
    }

    @Test
    fun anOpenViewerNeedsNoButton() {
        val fake = FakeTogetherController().also { it.setGuest(streamGuest()) }
        rule.setTogetherContent { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}, streamViewerOpen = true) }
        rule.onNodeWithText(TogetherCopy.openStream, ignoreCase = true).assertDoesNotExist()
        assertNull(fake.lastAction)
        assertTrue(fake.switches.isEmpty())
    }
}
