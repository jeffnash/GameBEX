package com.gamebex.client.ui.together

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.gamebex.client.together.GameChoice
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.debug.FakeTogetherController
import com.gamebex.client.debug.TogetherFixtures
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.Problem
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.game.CORE_INSTALL_MESSAGE_TAG
import com.gamebex.client.ui.game.CORE_INSTALL_PROGRESS_TAG
import com.gamebex.client.ui.game.CORE_INSTALL_SPINNER_TAG
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TogetherGuestScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pickingDownDownAChoosesThirdGame() {
        val fake = FakeTogetherController().also { it.setGuest(TogetherFixtures.guest("picking-game")) }
        rule.setTogetherContent { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.ButtonA)
        }
        assertEquals(3L, fake.lastPicked)
    }

    @Test fun downloadActionCallsAct() {
        val fake = FakeTogetherController().also { it.setGuest(TogetherFixtures.guest("action-needed")) }
        rule.setTogetherContent { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(com.gamebex.client.together.TogetherAction.DOWNLOAD, fake.lastAction)
    }

    @Test fun ownedCoreInstallShowsImmediateSpinnerAndRepeatedAOnlyReShowsProgress() {
        val fake = FakeTogetherController().also {
            it.setGuest(TogetherFixtures.guest("installing-core").copy(coreInstall = CoreInstallProgress("snes9x")))
        }
        var captured: ConsoleHints? = null
        rule.setTogetherContent { hints ->
            captured = hints
            TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {})
        }
        rule.onNodeWithTag(CORE_INSTALL_SPINNER_TAG).assertIsDisplayed()
        rule.onNodeWithTag(CORE_INSTALL_MESSAGE_TAG).assertIsDisplayed()
        rule.onNodeWithTag(CORE_INSTALL_PROGRESS_TAG).assertDoesNotExist()
        rule.onNodeWithText("Installing core…", ignoreCase = true).assertIsNotEnabled()
        rule.runOnIdle {
            assertTrue(captured!!.hints.none { it.glyph == ControllerGlyph.A })
            assertEquals(TogetherCopy.cancel, captured!!.hints.single { it.glyph == ControllerGlyph.B }.text)
        }
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA); pressKey(Key.ButtonA) }
        assertEquals(TogetherAction.INSTALL_CORE, fake.lastAction)
        rule.onNodeWithText(TogetherCopy.cancel, ignoreCase = true).performClick()
        assertEquals(TogetherAction.CANCEL_CORE_INSTALL, fake.lastAction)
        assertEquals(0, fake.ended)
    }

    @Test fun ownedCoreInstallShowsByteProgressThenInstallingSentinelWithoutOldPercentBar() {
        val progress = CoreInstallProgress("snes9x", read = 2_097_152L, total = 8_388_608L)
        val fake = FakeTogetherController().also {
            it.setGuest(TogetherFixtures.guest("installing-core").copy(coreInstall = progress, progress = 25))
        }
        rule.setTogetherContent { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onNodeWithText("Downloading 2.0 of 8.0 MB").assertIsDisplayed()
        val bar = rule.onNodeWithTag(CORE_INSTALL_PROGRESS_TAG).assertIsDisplayed().fetchSemanticsNode()
        assertEquals(0.25f, bar.config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        rule.runOnIdle { fake.setGuest(fake.guest.value!!.copy(coreInstall = progress.copy(installing = true), progress = null)) }
        rule.onNodeWithText("Installing in RetroArch…").assertIsDisplayed()
        rule.onNodeWithTag(CORE_INSTALL_SPINNER_TAG).assertIsDisplayed()
        rule.onNodeWithTag(CORE_INSTALL_PROGRESS_TAG).assertDoesNotExist()
    }

    @Test fun stalledCoreInstallRetryAndBRemainInsideSession() {
        val fake = FakeTogetherController().also {
            it.setGuest(TogetherFixtures.guest("installing-core").copy(coreInstall = CoreInstallProgress("snes9x", stalled = true)))
        }
        var backs = 0
        var captured: ConsoleHints? = null
        rule.setTogetherContent { hints ->
            captured = hints
            TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, { backs++ }, {}, {})
        }
        rule.onNodeWithText("Still working…").assertIsDisplayed()
        rule.onNodeWithText("Retry", ignoreCase = true).assertIsEnabled().performClick()
        assertEquals(TogetherAction.RETRY_CORE_INSTALL, fake.lastAction)
        rule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        assertEquals(TogetherAction.CANCEL_CORE_INSTALL, fake.lastAction)
        assertEquals(0, fake.ended)
        assertEquals(0, backs)
        rule.runOnIdle {
            assertEquals("Retry", captured!!.hints.single { it.glyph == ControllerGlyph.A }.text)
            assertEquals(TogetherCopy.cancel, captured!!.hints.single { it.glyph == ControllerGlyph.B }.text)
        }
    }

    @Test fun ownedCoreInstallStatusFitsAboveActionsAtEveryTextScale() {
        val fake = FakeTogetherController().also {
            it.setGuest(TogetherFixtures.guest("installing-core").copy(
                coreInstall = CoreInstallProgress("snes9x", read = 2_097_152L, total = 8_388_608L)))
        }
        val display = TestDisplay()
        rule.setTogetherContentAt(display) { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        for ((textScale, fontScale) in listOf(1f to 1f, 1.25f to 1f, 1.4f to 1f, 1.4f to 1.3f)) {
            rule.runOnIdle { display.textScale = textScale; display.fontScale = fontScale }
            val message = rule.onNodeWithTag(CORE_INSTALL_MESSAGE_TAG)
            assertFalse("$textScale x $fontScale: progress is cut", message.textIsCut())
            val action = rule.onNodeWithText("Installing core…", ignoreCase = true).box()
            assertTrue(message.box().bottom < action.top)
            assertTrue(rule.onNodeWithTag(CORE_INSTALL_PROGRESS_TAG).box().bottom < action.top)
        }
    }

    @Test fun openRetroArchUsesEngineActionInsteadOfCompatibilityCallback() {
        val fake = FakeTogetherController().also {
            it.setGuest(TogetherFixtures.guest("action-needed").copy(
                problem = Problem("Open RetroArch once.", TogetherAction.OPEN_RETROARCH)))
        }
        var opens = 0
        rule.setTogetherContent { hints ->
            TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}, onOpenRetroArch = { opens++ })
        }
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(TogetherAction.OPEN_RETROARCH, fake.lastAction)
        assertEquals(0, opens)
    }

    @Test fun endedWithMessageWaitsForBack() {
        val fake = FakeTogetherController().also { it.setGuest(TogetherFixtures.guest("guest-ready")) }
        var backs = 0
        rule.setTogetherContent { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, { backs++ }, {}, {}) }
        rule.runOnIdle { fake.setGuest(TogetherFixtures.guest("guest-ended")) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_100)
        rule.waitForIdle()
        assertEquals(0, backs)
        rule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        assertEquals(1, backs)
    }

    @Test fun endedWithoutMessagePopsExactlyOnce() {
        val fake = FakeTogetherController().also { it.setGuest(TogetherFixtures.guest("guest-ready")) }
        var backs = 0
        rule.setTogetherContent { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, { backs++ }, {}, {}) }
        rule.waitForIdle()
        assertEquals(0, backs)
        rule.runOnIdle { fake.setGuest(TogetherFixtures.guest("guest-ended").copy(message = null)) }
        rule.waitForIdle()
        assertEquals(1, backs)
        rule.runOnIdle { fake.setGuest(TogetherFixtures.guest("guest-ended").copy(message = null, countdown = 1)) }
        rule.waitForIdle()
        assertEquals(1, backs)
    }

    @Test fun staleEndedOrMissingSessionAtCompositionPopsImmediately() {
        // A restored or recreated guest screen: the session ended (with or without a
        // message) or is gone, and this composition never watched it live.
        val stale = listOf(TogetherFixtures.guest("guest-ended"), TogetherFixtures.guest("guest-ended").copy(message = null), null)
        val fakes = stale.map { view -> FakeTogetherController().also { it.setGuest(view) } }
        val backs = IntArray(stale.size)
        val index = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setTogetherContent { hints ->
            androidx.compose.runtime.key(index.intValue) {
                val i = index.intValue
                TogetherGuestScreen(fakes[i], TogetherUiTestSupport.rail, hints, { backs[i]++ }, {}, {})
            }
        }
        for (i in stale.indices) {
            rule.runOnIdle { index.intValue = i }
            rule.waitForIdle()
            assertEquals("case $i (${stale[i]?.step}, ${stale[i]?.message})", 1, backs[i])
        }
    }

    @Test fun noTwoHintsShareALabel() {
        val fake = FakeTogetherController()
        var captured: ConsoleHints? = null
        rule.setTogetherContent { hints -> captured = hints; TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        for (state in listOf("connecting", "waiting-offer", "guest-checking", "action-needed", "downloading", "core-sync",
            "installing-core", "picking-game", "guest-ready", "guest-launching", "guest-in-game", "guest-ended")) {
            rule.runOnIdle { fake.setGuest(TogetherFixtures.guest(state)) }
            rule.runOnIdle {
                val labels = captured!!.hints.map { it.text }
                assertEquals("$state: $labels", labels.distinct(), labels)
            }
        }
    }

    @Test fun pickingWindowKeepsSelectionOnScreen() {
        val choices = (1L..8L).map { GameChoice(it, "Choice $it", "gb") }
        val fake = FakeTogetherController().also { it.setGuest(TogetherFixtures.guest("picking-game").copy(choices = choices)) }
        rule.setTogetherContent { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onRoot().performKeyInput { repeat(6) { pressKey(Key.DirectionDown) } }
        rule.onNodeWithText("Choice 7").assertIsDisplayed()
        rule.onNodeWithText("Choice 1").assertDoesNotExist()
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(7L, fake.lastPicked)
    }

    @Test fun actionNeededAHintUsesActionLabel() {
        val view = TogetherFixtures.guest("action-needed")
        val action = view.problem?.action ?: error("action-needed fixture has no action")
        val fake = FakeTogetherController().also { it.setGuest(view) }
        var captured: ConsoleHints? = null
        rule.setTogetherContent { hints ->
            captured = hints
            TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {})
        }
        rule.waitForIdle()
        val aHint = captured!!.hints.single { it.glyph == ControllerGlyph.A }
        assertEquals(TogetherCopy.actionLabel(action), aHint.text)
        rule.onNodeWithText(TogetherCopy.actionLabel(action), ignoreCase = true).assertIsDisplayed()
    }

    @Test fun actionNeededNullActionHasNoAHint() {
        val view = TogetherFixtures.guest("action-needed")
        val action = view.problem?.action ?: error("action-needed fixture has no action")
        val fake = FakeTogetherController().also {
            it.setGuest(view.copy(problem = view.problem?.copy(action = null)))
        }
        var captured: ConsoleHints? = null
        rule.setTogetherContent { hints ->
            captured = hints
            TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {})
        }
        rule.waitForIdle()
        assertTrue(captured!!.hints.none { it.glyph == ControllerGlyph.A })
        rule.onNodeWithText(TogetherCopy.actionLabel(action), ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText(TogetherCopy.leave, ignoreCase = true).assertIsDisplayed()
    }

    @Test fun leaveShownExceptWhenEnded() {
        val fake = FakeTogetherController().also { it.setGuest(TogetherFixtures.guest("guest-checking")) }
        rule.setTogetherContent { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        val base = TogetherFixtures.guest("guest-checking")
        for (step in GuestStep.values()) {
            rule.runOnIdle {
                fake.setGuest(base.copy(
                    step = step,
                    message = if (step == GuestStep.ENDED) TogetherCopy.ended else null,
                    progress = null,
                    choices = emptyList(),
                    problem = null,
                ))
            }
            rule.waitForIdle()
            val leave = rule.onNodeWithText(TogetherCopy.leave, ignoreCase = true)
            if (step == GuestStep.ENDED) leave.assertDoesNotExist() else leave.assertIsDisplayed()
        }
    }

    @Test fun nullModeSubtitleOmitsSeparator() {
        val fake = FakeTogetherController().also {
            it.setGuest(TogetherFixtures.guest("guest-checking").copy(mode = null))
        }
        rule.setTogetherContent { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        val rendered = rule.onNodeWithText(TogetherCopy.withHost(TogetherCopy.otherPhone), substring = true)
            .fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
        assertFalse(rendered.contains(" \u00b7 "))
    }

    @Test fun progressBarSitsBelowHeadline() {
        val view = TogetherFixtures.guest("downloading")
        val fake = FakeTogetherController().also { it.setGuest(view) }
        rule.setTogetherContent { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        val headline = rule.onNodeWithText(TogetherCopy.downloading(view.gameTitle)).fetchSemanticsNode().boundsInRoot
        val bars = rule.onRoot().fetchSemanticsNode().layoutInfo.progressTracks()
        val shortest = bars.minOf { it.height }
        val progress = bars.filter { it.height == shortest }.maxBy { it.width }
        val top = progress.coordinates.positionInRoot().y
        assertTrue(top > headline.bottom)
    }

    @Test fun pickRowsShowBothLinesAtEveryTextSize() {
        val choices = listOf(GameChoice(1, "Blue", "gb"), GameChoice(2, "Red", "gbc"), GameChoice(3, "Yellow", "gba"))
        val fake = FakeTogetherController().also { it.setGuest(TogetherFixtures.guest("picking-game").copy(choices = choices)) }
        val display = TestDisplay()
        rule.setTogetherContentAt(display) { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        for ((textScale, fontScale) in listOf(1f to 1f, 1.25f to 1f, 1.4f to 1f, 1.4f to 1.3f)) {
            rule.runOnIdle { display.textScale = textScale; display.fontScale = fontScale }
            var previousBottom = Float.NEGATIVE_INFINITY
            for (choice in choices) {
                val row = rule.onNodeWithText(choice.title).box()
                val platform = rule.onNodeWithText(togetherPlatformName(choice.folder), useUnmergedTree = true)
                assertFalse("$textScale x $fontScale: ${choice.title} is cut", rule.onNodeWithText(choice.title, useUnmergedTree = true).textIsCut())
                assertFalse("$textScale x $fontScale: ${choice.folder} line is cut", platform.textIsCut())
                assertTrue("$textScale x $fontScale: platform line leaves its row", platform.box().bottom <= row.bottom + 0.5f)
                assertTrue("$textScale x $fontScale: rows overlap", row.top >= previousBottom - 0.5f)
                previousBottom = row.bottom
            }
        }
    }

    @Test fun pickRowsKeepSpecSpacingAtDefault() {
        val choices = listOf(GameChoice(1, "Blue", "gb"), GameChoice(2, "Red", "gbc"))
        val fake = FakeTogetherController().also { it.setGuest(TogetherFixtures.guest("picking-game").copy(choices = choices)) }
        rule.setTogetherContentAt(TestDisplay()) { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        val eyebrow = rule.onNodeWithText(TogetherCopy.eyebrow, useUnmergedTree = true).box()
        val first = rule.onNodeWithText("Blue").box()
        val second = rule.onNodeWithText("Red").box()
        // Rows at (760, 96), 84 tall, 8 apart; the eyebrow is at (84, 48).
        val unit = (first.left - eyebrow.left) / (760f - 84f)
        assertEquals(96f, (first.top - eyebrow.top) / unit + 48f, 1f)
        assertEquals(84f, first.height / unit, 1f)
        assertEquals(188f, (second.top - eyebrow.top) / unit + 48f, 1f)
    }

    @Test fun instructionsWrapInsteadOfBeingCutAtLarger() {
        val fake = FakeTogetherController().also { it.setGuest(TogetherFixtures.guest("guest-in-game")) }
        val display = TestDisplay(textScale = 1.4f)
        rule.setTogetherContentAt(display) { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        val items = fake.guest.value!!.instructions
        for (fontScale in listOf(1f, 1.3f)) {
            rule.runOnIdle { display.fontScale = fontScale }
            items.forEachIndexed { i, item ->
                assertFalse("font $fontScale: step ${i + 1} is cut", rule.onNodeWithText("${i + 1}. $item", useUnmergedTree = true).textIsCut())
            }
        }
    }

    @Test fun aSwipeReachesGamesPastTheFirstSix() {
        // Only six rows show; touch must reach the rest.
        val choices = (1..10).map { GameChoice(it.toLong(), "Game $it", "gb") }
        val fake = FakeTogetherController().also { it.setGuest(TogetherFixtures.guest("picking-game").copy(choices = choices)) }
        rule.setTogetherContentAt(TestDisplay()) { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
        rule.onNodeWithText("Game 9").assertDoesNotExist()
        rule.onNodeWithText("Game 5").performTouchInput {
            swipe(center, androidx.compose.ui.geometry.Offset(center.x, center.y - height * 12f), durationMillis = 800)
        }
        rule.onNodeWithText("Game 9").assertExists().performClick()
        rule.runOnIdle { assertEquals(9L, fake.lastPicked) }
    }

    @Test fun gbLinkInstructionsFitAboveTheHintsAtLarger() {
        val instructions = TogetherCopy.guestInstructions(TogetherMode.GB_LINK, TogetherCopy.otherPhone, "Y")
        val fake = FakeTogetherController().also {
            it.setGuest(TogetherFixtures.guest("guest-in-game").copy(folder = "gb", mode = TogetherMode.GB_LINK, instructions = instructions))
        }
        val display = TestDisplay(textScale = 1.4f)
        rule.setTogetherContentAt(display) { hints -> TogetherGuestScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, {}) }
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
}

private fun LayoutInfo.layoutChildren(): List<LayoutInfo> {
    val method = javaClass.methods.firstOrNull { it.name == "getChildren\$ui_release" } ?: return emptyList()
    val raw = method.invoke(this) as? List<*> ?: return emptyList()
    return raw.filterIsInstance<LayoutInfo>()
}

private fun LayoutInfo.progressTracks(): List<LayoutInfo> {
    val semantic = javaClass.methods.firstOrNull { it.name == "getCollapsedSemantics\$ui_release" }?.invoke(this)
    val self = if (isPlaced && isAttached && height > 0 && width >= height * 8 && semantic == null) listOf(this) else emptyList()
    return self + layoutChildren().flatMap { it.progressTracks() }
}
