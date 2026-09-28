package com.gamebex.client.ui.together

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.debug.FakeTogetherController
import com.gamebex.client.together.HostStep
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.debug.TogetherFixtures
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

import com.gamebex.client.together.Problem
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TogetherHostScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun readyHostAStarts() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("joined")) }
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(1, fake.hostStarts)
    }

    @Test fun checkingHostADoesNothing() {
        val fake = FakeTogetherController().also {
            it.setHost(TogetherFixtures.host("joined").copy(guestPhase = GuestPhase.CHECKING))
        }
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(0, fake.hostStarts)
        assertEquals(null, fake.lastAction)
        rule.runOnIdle { fake.setHost(TogetherFixtures.host("joined").copy(guestPhase = GuestPhase.READY)) }
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(1, fake.hostStarts)
    }

    @Test fun openSystemActionUsesCallback() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("not-ready")) }
        var opened = 0
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, { opened++ }, {}) }
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(1, opened)
        assertEquals(null, fake.lastAction)
    }

    @Test fun staleEndedViewTransitionsToCheckingWithoutPopping() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("ended")) }
        var backs = 0
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, { backs++ }, {}, {}) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(HostStep.CHECKING, fake.host.value?.step); assertEquals(0, backs) }
    }

    @Test fun endedAfterLiveViewPopsExactlyOnce() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("joined")) }
        var backs = 0
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, { backs++ }, {}, {}) }
        rule.runOnIdle { fake.setHost(TogetherFixtures.host("ended")) }
        rule.waitForIdle()
        rule.runOnIdle { fake.setHost(TogetherFixtures.host("ended").copy(message = "again")) }
        rule.waitForIdle()
        assertEquals(1, backs)
    }

    @Test fun yRemovesGuest() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("joined")) }
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onRoot().performKeyInput { pressKey(Key.ButtonY) }
        assertEquals(1, fake.hostRemoves)
    }

    @Test fun bEndsWithoutPoppingUntilEnded() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("joined")) }
        var backs = 0
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, { backs++ }, {}, {}) }
        rule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        rule.runOnIdle { assertEquals(1, fake.ended); assertEquals(0, backs); fake.setHost(TogetherFixtures.host("ended")) }
        rule.waitForIdle()
        assertEquals(1, backs)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun waitingRightColumnStaysOnCanvas() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("waiting")) }
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        val rootWidth = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val caption = rule.onNodeWithText(TogetherCopy.qrCaption).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(caption.left < rootWidth * 0.65f)
        assertTrue(caption.right <= rootWidth + 1f)
    }

    @Test fun notReadyNullActionHidesPrimary() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("not-ready")) }
        var captured: ConsoleHints? = null
        rule.setTogetherContent { hints ->
            captured = hints
            TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {})
        }
        rule.onNodeWithTag(HOST_PRIMARY_TAG).assertExists()
        rule.runOnIdle {
            fake.setHost(TogetherFixtures.host("not-ready").copy(problem = Problem(TogetherCopy.notSupported, null)))
        }
        rule.waitForIdle()
        rule.onNodeWithTag(HOST_PRIMARY_TAG).assertDoesNotExist()
        assertTrue(captured!!.hints.none { it.glyph == ControllerGlyph.A })
        rule.onNodeWithText(TogetherCopy.actionLabel(TogetherAction.OPEN_SYSTEM), ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText(TogetherCopy.cancel, ignoreCase = true).assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(0, fake.hostStarts)
        assertEquals(0, fake.hostPlayAgains)
        assertEquals(null, fake.lastAction)
    }

    @Test fun inGameLinkLostHidesPrimary() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("in-game")) }
        var captured: ConsoleHints? = null
        rule.setTogetherContent { hints ->
            captured = hints
            TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {})
        }
        rule.onNodeWithTag(HOST_PRIMARY_TAG).assertExists()
        rule.runOnIdle { fake.setHost(TogetherFixtures.host("in-game").copy(linkLost = true)) }
        rule.waitForIdle()
        rule.onNodeWithTag(HOST_PRIMARY_TAG).assertDoesNotExist()
        assertTrue(captured!!.hints.none { it.glyph == ControllerGlyph.A })
        rule.onNodeWithText(TogetherCopy.playAgain, ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText(TogetherCopy.end, ignoreCase = true).assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(0, fake.hostPlayAgains)
        assertEquals(0, fake.hostStarts)
    }

    @Test fun titleSitsBelowTheEyebrowAtFontScale13() {
        val title = "Super Mario World: The Lost Levels Championship Edition"
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("waiting").copy(gameTitle = title)) }
        rule.setTogetherContent(fontScale = 1.3f) { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        val eyebrow = rule.onNodeWithText(TogetherCopy.eyebrow, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val heading = rule.onNodeWithText(title.uppercase(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("title top ${heading.top} is above the eyebrow bottom ${eyebrow.bottom}", heading.top >= eyebrow.bottom)
    }

    @Test fun hostKeepsSpecSpacingAtDefault() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("waiting")) }
        rule.setTogetherContentAt(TestDisplay()) { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        val view = fake.host.value!!
        val eyebrow = rule.onNodeWithText(TogetherCopy.eyebrow, useUnmergedTree = true).box()
        val caption = rule.onNodeWithText(TogetherCopy.qrCaption, useUnmergedTree = true).box()
        // Left column x 84, right column x 760; the asserted y values below.
        val unit = (caption.left - eyebrow.left) / (760f - 84f)
        fun y(node: androidx.compose.ui.test.SemanticsNodeInteraction) = (node.box().top - eyebrow.top) / unit + 48f
        assertEquals(76f, y(rule.onNodeWithText(view.gameTitle.uppercase(), useUnmergedTree = true)), 1f)
        assertEquals(190f, y(rule.onNodeWithText(togetherSubtitle(view.folder, view.mode)!!, useUnmergedTree = true)), 1f)
        assertEquals(236f, y(rule.onNodeWithText(TogetherCopy.hostWaitingTitle, useUnmergedTree = true)), 1f)
        assertEquals(524f, y(rule.onNodeWithTag(HOST_PRIMARY_TAG)), 1f)
        assertEquals(412f, (caption.top - eyebrow.top) / unit + 48f, 1f)
        assertEquals(444f, y(rule.onNodeWithText(TogetherCopy.thisPhone(view.myAddress!!), useUnmergedTree = true)), 1f)

        rule.runOnIdle { fake.setHost(TogetherFixtures.host("joined").copy(guestPhase = GuestPhase.CORE_SYNC, guestProgress = 40)) }
        // The guest panel is at y 380 with 20 padding, so its name starts at 400.
        assertEquals(400f, y(rule.onNodeWithText(TogetherCopy.otherPhone, useUnmergedTree = true)), 1f)
        assertEquals(524f, y(rule.onNodeWithTag(HOST_PRIMARY_TAG)), 1f)
    }

    @Test fun headerSubtitleAndStatusStackAtLarger() {
        val title = "Super Mario World: The Lost Levels Championship Edition"
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("waiting").copy(gameTitle = title)) }
        val display = TestDisplay(textScale = 1.4f)
        rule.setTogetherContentAt(display) { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        val view = fake.host.value!!
        for (fontScale in listOf(1f, 1.3f)) {
            rule.runOnIdle { display.fontScale = fontScale }
            val eyebrow = rule.onNodeWithText(TogetherCopy.eyebrow, useUnmergedTree = true).box()
            val heading = rule.onNodeWithText(title.uppercase(), useUnmergedTree = true).box()
            val subtitle = rule.onNodeWithText(togetherSubtitle(view.folder, view.mode)!!, useUnmergedTree = true).box()
            val headline = rule.onNodeWithText(TogetherCopy.hostWaitingTitle, useUnmergedTree = true).box()
            assertTrue("font $fontScale: eyebrow overlaps title", eyebrow.bottom <= heading.top)
            assertTrue("font $fontScale: title $heading overlaps subtitle $subtitle", heading.bottom <= subtitle.top)
            assertTrue("font $fontScale: subtitle $subtitle overlaps status $headline", subtitle.bottom <= headline.top)
        }
    }

    @Test fun qrCaptionSitsAboveTheAddressAtLarger() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("waiting")) }
        val display = TestDisplay(textScale = 1.4f)
        rule.setTogetherContentAt(display) { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        for (fontScale in listOf(1f, 1.3f)) {
            rule.runOnIdle { display.fontScale = fontScale }
            val caption = rule.onNodeWithText(TogetherCopy.qrCaption, useUnmergedTree = true).box()
            val address = rule.onNodeWithText(TogetherCopy.thisPhone(fake.host.value!!.myAddress!!), useUnmergedTree = true).box()
            assertTrue("font $fontScale: caption $caption overlaps address $address", caption.bottom <= address.top)
        }
    }

    @Test fun guestPanelKeepsItsPhaseLineAtLarger() {
        val fake = FakeTogetherController().also {
            it.setHost(TogetherFixtures.host("joined").copy(guestPhase = GuestPhase.CORE_SYNC, guestProgress = 40))
        }
        val display = TestDisplay(textScale = 1.4f)
        rule.setTogetherContentAt(display) { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        val phaseText = TogetherCopy.phaseLine(GuestPhase.CORE_SYNC, null, 40)
        for (fontScale in listOf(1f, 1.3f)) {
            rule.runOnIdle { display.fontScale = fontScale }
            val phase = rule.onNodeWithText(phaseText, useUnmergedTree = true)
            assertFalse("font $fontScale: phase line is cut", phase.textIsCut())
            assertTrue("font $fontScale: panel runs into the buttons", phase.box().bottom <= rule.onNodeWithTag(HOST_PRIMARY_TAG).box().top)
        }
    }

    @Test fun gbLinkInstructionsFitAboveTheHintsAtLarger() {
        val instructions = TogetherCopy.hostInstructions(TogetherMode.GB_LINK)
        val fake = FakeTogetherController().also {
            it.setHost(TogetherFixtures.host("in-game").copy(folder = "gb", mode = TogetherMode.GB_LINK, instructions = instructions))
        }
        val display = TestDisplay(textScale = 1.4f)
        rule.setTogetherContentAt(display) { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        for (fontScale in listOf(1f, 1.3f)) {
            rule.runOnIdle { display.fontScale = fontScale }
            // The eyebrow is at (84, 48), the instructions column at x 760; the hint bar starts at HINT_TOP.
            val eyebrow = rule.onNodeWithText(TogetherCopy.eyebrow, useUnmergedTree = true).box()
            val unit = (rule.onNodeWithText(TogetherCopy.inTheGame, useUnmergedTree = true).box().left - eyebrow.left) / (760f - 84f)
            val hintTop = eyebrow.top + (ConsoleDesign.HINT_TOP - 48f) * unit
            instructions.forEachIndexed { i, item ->
                val line = rule.onNodeWithText("${i + 1}. $item", useUnmergedTree = true)
                assertFalse("font $fontScale: step ${i + 1} is cut", line.textIsCut())
                assertTrue("font $fontScale: step ${i + 1} runs into the hints", line.box().bottom <= hintTop)
            }
        }
    }

    @Test fun restoredEntryAfterEndDoesNotStartANewSession() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("joined")) }
        var backs = 0
        val restorer = StateRestorationTester(rule)
        restorer.setContent { TogetherTestFrame { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, { backs++ }, {}, {}) } }
        rule.waitForIdle()
        assertEquals(0, fake.hostGames)
        rule.runOnIdle { fake.setHost(TogetherFixtures.host("ended")) }
        rule.waitForIdle()
        val before = backs
        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(0, fake.hostGames)
        // The restored entry pops itself instead of hosting again.
        assertEquals(before + 1, backs)
    }

    @Test fun processDeathRecreationDoesNotStartANewSession() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("waiting")) }
        var backs = 0
        val restorer = StateRestorationTester(rule)
        restorer.setContent { TogetherTestFrame { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, { backs++ }, {}, {}) } }
        rule.waitForIdle()
        // The process died: the engine comes back with no host session.
        rule.runOnIdle { fake.setHost(null) }
        rule.waitForIdle()
        val before = backs
        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(0, fake.hostGames)
        assertEquals(before + 1, backs)
    }

    @Test fun freshEntryFromThe2PButtonStartsTheSession() {
        val fake = FakeTogetherController()
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.waitForIdle()
        assertEquals(1, fake.hostGames)
    }

    @Test fun noTwoHintsShareALabel() {
        val fake = FakeTogetherController().also { it.setHost(TogetherFixtures.host("checking")) }
        var captured: ConsoleHints? = null
        rule.setTogetherContent { hints -> captured = hints; TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        for (state in listOf("checking", "not-ready", "waiting", "joined", "launching", "in-game", "ended")) {
            rule.runOnIdle { fake.setHost(TogetherFixtures.host(state)) }
            rule.runOnIdle {
                val labels = captured!!.hints.map { it.text }
                assertEquals("$state: $labels", labels.distinct(), labels)
            }
        }
    }

    @Test fun nullModeSubtitleOmitsSeparator() {
        val fake = FakeTogetherController().also {
            it.setHost(TogetherFixtures.host("checking").copy(mode = null))
        }
        rule.setTogetherContent { hints -> TogetherHostScreen(fake, 1L, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        val subtitle = togetherSubtitle(fake.host.value!!.folder, null)!!
        val rendered = rule.onNodeWithText(subtitle, substring = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
        assertFalse(rendered.contains(" \u00b7 "))
    }
}
