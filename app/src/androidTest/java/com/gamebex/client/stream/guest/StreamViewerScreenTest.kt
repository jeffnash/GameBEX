package com.gamebex.client.stream.guest

import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.stream.shell.RetroArchActivityProbe
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.GameBexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun hudOffStillShowsGuestAudioFailure() {
        val message = "Guest audio is unavailable (write). Host sound is unchanged."
        rule.setContent {
            GameBexTheme { StreamViewerScreen(ui(ViewerStatus.WATCHING, message), {}, {}, {}, {}, hudEnabled = false) }
        }
        rule.onNodeWithText(message).assertIsDisplayed()
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun hudOffKeepsTouchPadMenuAndLeave() {
        var leaves = 0
        rule.setContent {
            GameBexTheme { StreamViewerScreen(ui(ViewerStatus.WATCHING, stats = true), {}, { leaves++ }, {}, {}, hudEnabled = false) }
        }
        rule.onNodeWithTag(TAG_STATS, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).performClick()
        rule.onNodeWithText(TogetherCopy.leaveStream).performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun hiddenPadKeepsHudAndMenu() {
        rule.setContent {
            GameBexTheme { StreamViewerScreen(ui(ViewerStatus.WATCHING, stats = true), {}, {}, {}, {}, touchPadHidden = true) }
        }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(TAG_STATS, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun physicalControllerAtMountHidesPadButKeepsMenuAndLeave() {
        var leaves = 0
        rule.setContent {
            GameBexTheme {
                StreamViewerScreen(ui(ViewerStatus.WATCHING, stats = true), {}, { leaves++ }, {}, {},
                    physicalControllerConnected = true,
                )
            }
        }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("GameBEX controls", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(TAG_STATS, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(TAG_VIDEO, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).performClick()
        rule.onNodeWithText(TogetherCopy.leaveStream).performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun controllerPresenceHidesAndRestoresPadWithTouchOnlyReleaseOnBothChanges() {
        var connected by mutableStateOf(false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        val physical = PadMapper()
        val touch = PadMapper()
        var releases = 0
        var leaves = 0
        val releaseTouch = {
            releases++
            touch.reset()
        }
        rule.runOnIdle {
            physical.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 1)
            physical.onAxes(0.5f, -0.5f, 0f, 0.75f, 1f, 0f, -1f, 0f)
        }
        val heldPhysical = physical.state()
        rule.setContent {
            GameBexTheme {
                StreamViewerScreen(ui(ViewerStatus.WATCHING), {}, { leaves++ }, {}, {},
                    onTouchKey = { key, down, atNs ->
                        events.add(key to down)
                        touch.onKey(key, down, atNs)
                    },
                    onTouchAxes = { x, y, _ -> touch.onAxes(x, y, 0f, 0f, 0f, 0f, 0f, 0f) },
                    onReleaseTouchInput = releaseTouch,
                    physicalControllerConnected = connected,
                )
            }
        }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("GameBEX controls", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(touchKeyTag(KeyEvent.KEYCODE_BUTTON_R1), useUnmergedTree = true).performTouchInput { down(center) }
        rule.runOnIdle {
            assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_R1 to true), events)
            connected = updateViewerControllerPresence(connected, true, releaseTouch)
        }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(TAG_VIDEO, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).assertIsDisplayed()
        var hiddenReleases = 0
        rule.runOnIdle {
            assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_R1 to true, KeyEvent.KEYCODE_BUTTON_R1 to false), events)
            assertTrue(releases > 0)
            assertTrue(touch.state().isNeutral)
            assertEquals(heldPhysical, mergePadStates(physical.state(), touch.state()))
            hiddenReleases = releases
        }
        rule.onNodeWithTag(TAG_ROOT, useUnmergedTree = true).performTouchInput { cancel() }
        rule.runOnIdle { connected = updateViewerControllerPresence(connected, false, releaseTouch) }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("GameBEX controls", useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(hiddenReleases + 1, releases)
            assertEquals(heldPhysical, mergePadStates(physical.state(), touch.state()))
        }
        rule.onNodeWithTag(touchKeyTag(KeyEvent.KEYCODE_BUTTON_R1), useUnmergedTree = true).performTouchInput { down(center); up() }
        rule.runOnIdle {
            assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_R1 to true, KeyEvent.KEYCODE_BUTTON_R1 to false,
                KeyEvent.KEYCODE_BUTTON_R1 to true, KeyEvent.KEYCODE_BUTTON_R1 to false), events)
            assertEquals(heldPhysical, mergePadStates(physical.state(), touch.state()))
        }
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).performClick()
        rule.onNodeWithText(TogetherCopy.leaveStream).performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun disconnectKeepsDebugHiddenPadHidden() {
        var connected by mutableStateOf(true)
        rule.setContent {
            GameBexTheme {
                StreamViewerScreen(ui(ViewerStatus.WATCHING), {}, {}, {}, {},
                    touchPadHidden = true,
                    physicalControllerConnected = connected,
                )
            }
        }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { connected = false }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).performClick()
        rule.onNodeWithText(TogetherCopy.leaveStream).assertIsDisplayed()
    }

    @Test
    fun hudOffKeepsWaitingLeaveAndTerminalMessage() {
        var state by mutableStateOf(ui(ViewerStatus.WATCHING).copy(waitingForVideo = true))
        rule.setContent {
            GameBexTheme { StreamViewerScreen(state, {}, {}, {}, {}, hudEnabled = false, touchPadHidden = true) }
        }
        rule.onNodeWithTag(TAG_LOADING, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.leaveStream).assertIsDisplayed()
        rule.runOnIdle { state = ui(ViewerStatus.ENDED, "The host stopped sharing.") }
        rule.onNodeWithTag(TAG_LOADING, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("The host stopped sharing.").assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.back).assertIsDisplayed()
    }

    @Test
    fun retroArchLifecycleProbeCompilesAndParsesOnAndroid() {
        fun snapshot(exiting: Boolean): String {
            val component = "com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture"
            val suffix = if (exiting) " f} isExiting}" else "}"
            val process = if (exiting) "null" else "ProcessRecord{abc 42:com.retroarch.aarch64/u0a496}"
            val state = if (exiting) "DESTROYED" else "STOPPED"
            return """
                ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
                Display #0 (activities from top to bottom):
                  * Hist #0: ActivityRecord{abc u0 $component t42$suffix
                    app=$process
                    mActivityComponent=$component
                    state=$state delayedResume=false finishing=$exiting
                ActivityTaskSupervisor state:
                  mNoHistoryActivities=[]
            """.trimIndent() + "\n"
        }
        RetroArchActivityProbe()
        assertEquals(true, RetroArchActivityProbe.parseSnapshot(snapshot(false)))
        assertEquals(false, RetroArchActivityProbe.parseSnapshot(snapshot(true)))
    }

    @Test
    fun connectingNamesTheHostAndHintsTheWayOut() {
        rule.setContent { GameBexTheme { StreamViewerScreen(ui(ViewerStatus.CONNECTING), {}, {}, {}, {}) } }
        rule.onNodeWithText(TogetherCopy.connecting("J")).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.leaveStreamHint).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.leaveStream).assertIsDisplayed()
    }

    @Test
    fun menuShowsLeaveAndStatsAndLeaveCallsBack() {
        var leaves = 0
        var toggles = 0
        rule.setContent { GameBexTheme { StreamViewerScreen(ui(ViewerStatus.WATCHING), {}, { leaves++ }, { toggles++ }, {}) } }
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).performClick()
        rule.onNodeWithText(TogetherCopy.showStats).performClick()
        assertEquals(1, toggles)
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).performClick()
        rule.onNodeWithText(TogetherCopy.leaveStream).performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun touchControlsOnlyAppearAfterVideoWhileWatching() {
        var state by mutableStateOf(ui(ViewerStatus.CONNECTING))
        rule.setContent { GameBexTheme { StreamViewerScreen(state, {}, {}, {}, {}) } }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { state = ui(ViewerStatus.WATCHING).copy(waitingForVideo = true) }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { state = state.copy(waitingForVideo = false) }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("GameBEX controls", useUnmergedTree = true).assertDoesNotExist()
        listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        ).forEach { rule.onNodeWithTag(touchKeyTag(it), useUnmergedTree = true).assertIsDisplayed() }
        rule.onNodeWithTag(TAG_VIDEO, useUnmergedTree = true).assertExists()
        rule.runOnIdle { state = ui(ViewerStatus.RECONNECTING) }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { state = ui(ViewerStatus.ENDED) }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun buttonDownStaysHeldUntilUpAndDoesNotOpenMenu() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val times = mutableListOf<Long>()
        rule.setContent {
            GameBexTheme {
                StreamViewerScreen(ui(ViewerStatus.WATCHING), {}, {}, {}, {}, onTouchKey = { key, down, at ->
                    events.add(key to down)
                    times.add(at)
                })
            }
        }
        rule.onNodeWithTag(touchKeyTag(KeyEvent.KEYCODE_BUTTON_A), useUnmergedTree = true).performTouchInput { down(center) }
        rule.runOnIdle { assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_A to true), events) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.runOnIdle { assertEquals(1, events.size) }
        rule.onNodeWithText(TogetherCopy.leaveStream).assertDoesNotExist()
        rule.onNodeWithTag(touchKeyTag(KeyEvent.KEYCODE_BUTTON_A), useUnmergedTree = true).performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_A to true, KeyEvent.KEYCODE_BUTTON_A to false), events)
            assertTrue(times[1] >= times[0])
        }
        rule.onNodeWithText(TogetherCopy.leaveStream).assertDoesNotExist()
    }

    @Test
    fun dpadAndFaceButtonHoldIndependentlyAndCancelReleasesTheRemainingFinger() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        rule.setContent {
            GameBexTheme {
                StreamViewerScreen(ui(ViewerStatus.WATCHING), {}, {}, {}, {}, onTouchKey = { key, down, _ -> events.add(key to down) })
            }
        }
        val root = rule.onNodeWithTag(TAG_ROOT, useUnmergedTree = true)
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val left = rule.onNodeWithTag(touchKeyTag(KeyEvent.KEYCODE_DPAD_LEFT), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center - origin
        val a = rule.onNodeWithTag(touchKeyTag(KeyEvent.KEYCODE_BUTTON_A), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center - origin
        root.performTouchInput {
            down(0, left)
            down(1, a)
        }
        rule.runOnIdle {
            assertEquals(setOf(KeyEvent.KEYCODE_DPAD_LEFT to true, KeyEvent.KEYCODE_BUTTON_A to true), events.toSet())
        }
        root.performTouchInput { up(0) }
        rule.runOnIdle {
            assertTrue((KeyEvent.KEYCODE_DPAD_LEFT to false) in events)
            assertTrue((KeyEvent.KEYCODE_BUTTON_A to false) !in events)
        }
        root.performTouchInput { cancel() }
        rule.runOnIdle {
            assertEquals(4, events.size)
            assertEquals(KeyEvent.KEYCODE_BUTTON_A to false, events.last())
        }
        rule.onNodeWithText(TogetherCopy.leaveStream).assertDoesNotExist()
    }

    @Test
    fun hidingControlsDuringAHoldReleasesTouchInput() {
        var state by mutableStateOf(ui(ViewerStatus.WATCHING))
        val events = mutableListOf<Pair<Int, Boolean>>()
        var releases = 0
        rule.setContent {
            GameBexTheme {
                StreamViewerScreen(state, {}, {}, {}, {},
                    onTouchKey = { key, down, _ -> events.add(key to down) },
                    onReleaseTouchInput = { releases++ },
                )
            }
        }
        rule.onNodeWithTag(touchKeyTag(KeyEvent.KEYCODE_BUTTON_R1), useUnmergedTree = true).performTouchInput { down(center) }
        rule.runOnIdle { state = state.copy(waitingForVideo = true) }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_R1 to true, KeyEvent.KEYCODE_BUTTON_R1 to false), events)
            assertTrue(releases > 0)
        }
        rule.onNodeWithTag(TAG_ROOT, useUnmergedTree = true).performTouchInput { cancel() }
    }

    @Test
    fun pausingAndRemovingThePadReleaseHeldPointers() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        var mounted by mutableStateOf(true)
        val events = mutableListOf<Pair<Int, Boolean>>()
        var releases = 0
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                GameBexTheme {
                    if (mounted) StreamViewerScreen(ui(ViewerStatus.WATCHING), {}, {}, {}, {},
                        onTouchKey = { key, down, _ -> events.add(key to down) },
                        onReleaseTouchInput = { releases++ },
                    )
                }
            }
        }
        rule.onNodeWithTag(touchKeyTag(KeyEvent.KEYCODE_BUTTON_B), useUnmergedTree = true).performTouchInput { down(center) }
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        rule.runOnIdle { assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_B to true, KeyEvent.KEYCODE_BUTTON_B to false), events) }
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(TAG_ROOT, useUnmergedTree = true).performTouchInput { cancel() }
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.onNodeWithTag(touchKeyTag(KeyEvent.KEYCODE_BUTTON_B), useUnmergedTree = true).performTouchInput { down(center) }
        rule.runOnIdle { mounted = false }
        rule.runOnIdle {
            assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_B to true, KeyEvent.KEYCODE_BUTTON_B to false, KeyEvent.KEYCODE_BUTTON_B to true, KeyEvent.KEYCODE_BUTTON_B to false), events)
            assertTrue(releases >= 2)
        }
    }

    @Test
    fun tappingTheVideoDoesNotOpenMenu() {
        rule.setContent { GameBexTheme { StreamViewerScreen(ui(ViewerStatus.WATCHING), {}, {}, {}, {}) } }
        rule.onNodeWithTag(TAG_ROOT, useUnmergedTree = true).performTouchInput { down(center); up() }
        rule.onNodeWithText(TogetherCopy.leaveStream).assertDoesNotExist()
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).performClick()
        rule.onNodeWithText(TogetherCopy.leaveStream).assertIsDisplayed()
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
        rule.onNodeWithTag(TAG_LOADING, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText(TogetherCopy.back).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun connectingShowsTheBrandAndAccessibleLoadingWhileKeepingTheVideoMounted() {
        rule.setContent { GameBexTheme { StreamViewerScreen(ui(ViewerStatus.CONNECTING), {}, {}, {}, {}) } }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(TAG_LOADING, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("GameBEX", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.streamWaitingForHost).assertIsDisplayed()
        rule.onNodeWithTag(TAG_VIDEO, useUnmergedTree = true).assertExists()
        rule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1_920)
        rule.onNodeWithTag(TAG_LOADING, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun watchingStillWaitsUntilTheFirstVideoFrame() {
        val state = ui(ViewerStatus.WATCHING).copy(waitingForVideo = true)
        rule.setContent { GameBexTheme { StreamViewerScreen(state, {}, {}, {}, {}) } }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(TAG_LOADING, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.connecting("J")).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.streamWaitingForHost).assertIsDisplayed()
        rule.onNodeWithTag(TAG_VIDEO, useUnmergedTree = true).assertExists()
    }

    @Test
    fun leaveStaysAvailableWhileWaitingEvenAfterTheHintExpires() {
        var leaves = 0
        val state = ui(ViewerStatus.WATCHING).copy(waitingForVideo = true)
        rule.setContent { GameBexTheme { StreamViewerScreen(state, {}, { leaves++ }, {}, {}) } }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(6_000)
        rule.onNodeWithText(TogetherCopy.leaveStream).assertIsDisplayed().performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun theFirstVideoFrameRemovesLoadingWithoutRemountingTheSurface() {
        var state by mutableStateOf(ui(ViewerStatus.WATCHING).copy(waitingForVideo = true))
        var surfaces = 0
        rule.setContent {
            GameBexTheme {
                StreamViewerScreen(state, { if (it != null) surfaces++ }, {}, {}, {})
            }
        }
        rule.onNodeWithTag(TAG_LOADING, useUnmergedTree = true).assertIsDisplayed()
        rule.waitUntil(timeoutMillis = 5_000) { surfaces > 0 }
        val attached = surfaces
        rule.runOnIdle { state = state.copy(waitingForVideo = false) }
        rule.onNodeWithTag(TAG_LOADING, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(TAG_VIDEO, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(TogetherCopy.streamWaitingForHost).assertDoesNotExist()
        assertEquals(attached, surfaces)
        rule.onNodeWithTag(TAG_MENU, useUnmergedTree = true).performClick()
        rule.onNodeWithText(TogetherCopy.showStats).assertIsDisplayed()
    }

    @Test
    fun waitingKeepsLinkAndWifiWarningsVisibleWithoutBlockingLeave() {
        var leaves = 0
        val state = ui(ViewerStatus.WATCHING).copy(waitingForVideo = true, linkLost = true, weakWifi = true)
        rule.setContent { GameBexTheme { StreamViewerScreen(state, {}, { leaves++ }, {}, {}) } }
        rule.onNodeWithTag(TAG_LOADING, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(TAG_LINK_LOST, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.streamLinkLostStillGuest("J")).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.weakWifi).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.leaveStream).assertIsDisplayed().performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun reconnectingKeepsLeaveAvailableAndEndsWithoutAnInfiniteLoader() {
        var state by mutableStateOf(ui(ViewerStatus.RECONNECTING).copy(waitingForVideo = true))
        var leaves = 0
        var backs = 0
        rule.setContent { GameBexTheme { StreamViewerScreen(state, {}, { leaves++ }, {}, { backs++ }) } }
        rule.onNodeWithTag(TAG_LOADING, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.streamReconnecting).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.leaveStream).performClick()
        assertEquals(1, leaves)
        rule.runOnIdle { state = state.copy(status = ViewerStatus.ENDED, message = TogetherCopy.streamEnded("J")) }
        rule.onNodeWithTag(TAG_LOADING, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(TAG_VIDEO, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText(TogetherCopy.streamEnded("J")).assertIsDisplayed()
        rule.onNodeWithText(TogetherCopy.back).performClick()
        assertEquals(1, backs)
    }
}
