package com.gamebex.client.ui.together

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.debug.FakeTogetherController
import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.ui.game.CORE_INSTALL_MESSAGE_TAG
import com.gamebex.client.ui.game.CORE_INSTALL_PROGRESS_TAG
import com.gamebex.client.ui.game.CORE_INSTALL_SPINNER_TAG
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.GuestView
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.HostView
import com.gamebex.client.together.Problem
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
    fun coreInstallShowsSpinnerBeforeAnyBytesAndRepeatedADoesNotLaunch() {
        val progress = CoreInstallProgress("mupen64plus_next_gles3")
        val fake = FakeTogetherController().also {
            it.setHost(host(HostStep.NOT_READY).copy(coreInstall = progress))
        }
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onNodeWithTag(CORE_INSTALL_SPINNER_TAG).assertIsDisplayed()
        rule.onNodeWithTag(CORE_INSTALL_MESSAGE_TAG).assertIsDisplayed()
        rule.onNodeWithText("Installing the N64 core…").assertIsDisplayed()
        rule.onNodeWithTag(HOST_PRIMARY_TAG).assertIsNotEnabled()
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA); pressKey(Key.ButtonA) }
        assertEquals(TogetherAction.INSTALL_CORE, fake.lastAction)
        assertEquals(0, fake.hostStarts)
        assertEquals(0, fake.hostPlayAgains)
        rule.onNodeWithText("Installing the N64 core…").assertIsDisplayed()
    }

    @Test
    fun downloadHasAByteProgressBarAndCacheInstallReturnsToSpinner() {
        val progress = CoreInstallProgress("mupen64plus_next_gles3", read = 3_355_443, total = 6_501_171)
        val fake = FakeTogetherController().also {
            it.setHost(host(HostStep.NOT_READY).copy(coreInstall = progress))
        }
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onNodeWithText("Downloading 3.2 of 6.2 MB").assertIsDisplayed()
        val bar = rule.onNodeWithTag(CORE_INSTALL_PROGRESS_TAG).assertIsDisplayed().fetchSemanticsNode()
        assertEquals(ProgressBarRangeInfo(progress.fraction!!, 0f..1f), bar.config[SemanticsProperties.ProgressBarRangeInfo])
        rule.runOnIdle { fake.setHost(fake.host.value!!.copy(coreInstall = progress.copy(installing = true))) }
        rule.onNodeWithText("Installing in RetroArch…").assertIsDisplayed()
        rule.onNodeWithTag(CORE_INSTALL_SPINNER_TAG).assertIsDisplayed()
        rule.onNodeWithTag(CORE_INSTALL_PROGRESS_TAG).assertDoesNotExist()
        rule.onNodeWithTag(HOST_PRIMARY_TAG).assertIsNotEnabled()
    }

    @Test
    fun stalledInstallOffersRetryAndCancelWithoutEndingOrNavigating() {
        val fake = FakeTogetherController().also {
            it.setHost(host(HostStep.NOT_READY).copy(coreInstall = CoreInstallProgress("mupen64plus_next_gles3", stalled = true)))
        }
        var backs = 0
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, { backs++ }, {}, {}) }
        rule.onNodeWithText("Still working…").assertIsDisplayed()
        rule.onNodeWithText("Retry", ignoreCase = true).performClick()
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, fake.lastAction)
        rule.onNodeWithText(TogetherCopy.cancel, ignoreCase = true).performClick()
        assertEquals(TogetherAction.CANCEL_CORE_INSTALL, fake.lastAction)
        assertEquals(0, fake.ended)
        assertEquals(0, backs)
        rule.runOnIdle {
            fake.setHost(fake.host.value!!.copy(coreInstall = null, problem = Problem("Core installation cancelled.", TogetherAction.RETRY_CORE_INSTALL)))
        }
        rule.onNodeWithText("Core installation cancelled.").assertIsDisplayed()
        rule.onNodeWithTag(HOST_PRIMARY_TAG).performClick()
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, fake.lastAction)
        assertEquals(0, backs)
    }

    @Test
    fun installTimeoutExplainsTheErrorAndOpenRetroArchUsesTheEngineRecoveryAction() {
        val text = "RetroArch did not confirm installation. Open RetroArch and try again."
        val fake = FakeTogetherController().also {
            it.setHost(host(HostStep.NOT_READY).copy(problem = Problem(text, TogetherAction.OPEN_RETROARCH)))
        }
        var opened = 0
        rule.setTogetherContent { hints ->
            TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}, onOpenRetroArch = { opened++ })
        }
        rule.onNodeWithText(text).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.actionLabel(TogetherAction.OPEN_RETROARCH), ignoreCase = true).performClick()
        assertEquals(0, opened)
        assertEquals(TogetherAction.OPEN_RETROARCH, fake.lastAction)
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
