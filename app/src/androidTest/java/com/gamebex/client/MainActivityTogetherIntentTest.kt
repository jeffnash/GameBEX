package com.gamebex.client

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.gamebex.client.ui.GameBexTheme
import androidx.compose.ui.test.SemanticsMatcher

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule

import androidx.compose.ui.test.onAllNodesWithText

import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.GuestView
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.HostView
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.console.HintBarEmphasizePrimary
import com.gamebex.client.ui.together.TogetherUi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTogetherIntentTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @After
    fun endTogetherSession() {
        TogetherUi.pendingLink.value = null
        TogetherUi.pendingOpen.value = false
        TogetherUi.hubMessage.value = null
        (app().applicationContext as GameBexApp).pendingRoute.value = null
        if (togetherAlreadyStarted()) endTogetherAndAssertIdle()
    }

    @Test
    fun launchingViewJoinLink_setsPendingLinkAndConsumesData() {
        val link = FAILING_JOIN_LINK
        TogetherUi.pendingLink.value = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            setClass(context, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = instrumentation.startActivitySync(view) as MainActivity
        try {
            instrumentation.runOnMainSync {
                assertEquals(null, activity.intent.data)
                val again = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                instrumentation.callActivityOnNewIntent(activity, again)
                assertEquals(link, TogetherUi.pendingLink.value)
                assertEquals(null, again.data)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
        endTogetherAndAssertIdle()
    }

    @Test
    fun historyJoinLinkIsNotConsumed() {
        val link = "gamebex://join?v=1&h=10.42.1.20&p=55440&n=Sam"
        TogetherUi.pendingLink.value = null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        }
        consumeTogetherIntent(intent)
        assertEquals(null, TogetherUi.pendingLink.value)
        assertEquals(link, intent.data.toString())
    }

    @Test
    fun restoredInstanceDoesNotConsumeJoinLink() {
        val link = "gamebex://join?v=1&h=10.42.1.20&p=55440&n=Sam"
        TogetherUi.pendingLink.value = null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        consumeTogetherIntent(intent, restoredInstance = true)
        assertEquals(null, TogetherUi.pendingLink.value)
        assertEquals(link, intent.data.toString())
    }

    @Test
    fun recreatedActivityDoesNotConsumeJoinLink() {
        val link = FAILING_JOIN_LINK
        TogetherUi.pendingLink.value = null
        TogetherUi.hubMessage.value = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val start = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val first = instrumentation.startActivitySync(start) as MainActivity
        val automation = instrumentation.uiAutomation
        fun shell(command: String): String =
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command),
            ).bufferedReader().use { it.readText() }
        fun awaitRecreated(timeoutMs: Long): MainActivity? {
            val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                val resumed = resumedMain(instrumentation)
                if (resumed != null && resumed !== first) return resumed
                Thread.sleep(100)
            }
            return null
        }
        try {
            instrumentation.runOnMainSync {
                stampJoinLink(first, link)
                TogetherUi.pendingLink.value = null
                first.recreate()
            }
            var activity = awaitRecreated(3_000)
            if (activity == null) {
                shell("wm density 240")
                activity = awaitRecreated(8_000)
            }
            val recreated = checkNotNull(activity) { "MainActivity was not recreated" }
            // A replayed link would fail to join and open the hub with linkInvalid;
            // the recreated activity must stay on Home.
            compose.waitForIdle()
            assertEquals(null, TogetherUi.hubMessage.value)
            compose.onNodeWithText(TogetherCopy.linkInvalid).assertDoesNotExist()
            compose.onNode(hasTestTag("home-tv")).assertExists()
            assertEquals(null, TogetherUi.pendingLink.value)
            assertEquals(link, recreated.intent.data?.toString())
        } finally {
            shell("wm density reset")
            instrumentation.waitForIdleSync()
            val finishing = resumedMain(instrumentation) ?: first
            instrumentation.runOnMainSync { finishing.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun openExtra_onNewIntentSetsPendingOpenAndConsumesExtra() {
        TogetherUi.pendingOpen.value = false
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val intent = Intent().putExtra("gamebex.together.open", true)
                instrumentation.callActivityOnNewIntent(activity, intent)
                assertTrue(TogetherUi.pendingOpen.value)
                assertFalse(intent.hasExtra("gamebex.together.open"))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun libraryRouteEmphasizesPrimaryAndOtherRoutesDoNot() {
        assertTrue(emphasizePrimaryFor("library"))
        assertFalse(emphasizePrimaryFor("browse"))
        assertFalse(emphasizePrimaryFor("emulators"))
        assertFalse(emphasizePrimaryFor("together"))
        assertFalse(emphasizePrimaryFor("together/host/{gameId}"))
        assertFalse(emphasizePrimaryFor("together/guest"))
        assertFalse(emphasizePrimaryFor("allgames"))
        assertFalse(emphasizePrimaryFor("downloads"))
        assertFalse(emphasizePrimaryFor("local/{gameId}"))
        assertFalse(emphasizePrimaryFor("detail/{entry}"))
        assertFalse(emphasizePrimaryFor("search"))
        assertFalse(emphasizePrimaryFor("search?initialQuery=zelda"))
        assertFalse(emphasizePrimaryFor(null))

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(WAIT_MS) { compose.shows(emphasize(true)) }
            (app().applicationContext as GameBexApp).pendingRoute.value = "emulators"
            compose.waitUntil(WAIT_MS) { compose.shows(emphasize(false)) }
            compose.onNode(emphasize(true), useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun endedHostWithLiveGuest_notificationFollowsGuestAndDoesNotCallHostGame() {
        publishSession(
            HostView(
                step = HostStep.ENDED,
                gameId = 42L,
                gameTitle = "",
                folder = "",
                mode = null,
                myName = "Me",
            ),
            GuestView(step = GuestStep.IN_GAME, hostName = "Sam"),
        )
        TogetherUi.pendingOpen.value = true
        launchAppNav { routes ->
            compose.waitUntil(WAIT_MS) { compose.showsText(TogetherCopy.playingWith("Sam")) }
            compose.waitForIdle()
            assertEquals(HostStep.ENDED, appTogether().host.value?.step)
            assertFalse(TogetherUi.pendingOpen.value)
            assertEquals("together/guest", routes().last())
            assertTrue(routes().none { it.startsWith("together/host") })
        }
    }

    @Test
    fun activeSession_pendingLinkDoesNotJoinOrNavigate() {
        publishSession(
            HostView(
                step = HostStep.CHECKING,
                gameId = 7L,
                gameTitle = "Game",
                folder = "snes",
                mode = null,
                myName = "Me",
            ),
            null,
        )
        TogetherUi.hubMessage.value = null
        TogetherUi.pendingLink.value = FAILING_JOIN_LINK
        launchAppNav { routes ->
            compose.waitUntil(WAIT_MS) { TogetherUi.pendingLink.value == null && routes().isNotEmpty() }
            compose.waitForIdle()
            assertEquals(listOf("library"), routes().distinct())
            assertEquals(null, TogetherUi.hubMessage.value)
            compose.onNodeWithText(TogetherCopy.joining).assertDoesNotExist()
            compose.onNodeWithText(TogetherCopy.linkInvalid).assertDoesNotExist()
            compose.onNode(hasTestTag("home-tv")).assertExists()
            assertEquals(HostStep.CHECKING, appTogether().host.value?.step)
            val guest = appTogether().guest.value
            assertTrue(guest == null || guest.step == GuestStep.ENDED)
        }
    }

    private fun emphasize(value: Boolean) =
        SemanticsMatcher.expectValue(HintBarEmphasizePrimary, value)

    private fun ComposeTestRule.shows(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeTestRule.showsText(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun resumedMain(instrumentation: android.app.Instrumentation): MainActivity? {
        var found: MainActivity? = null
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            found = monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
        }
        return found

    }

    private fun stampJoinLink(activity: android.app.Activity, link: String) {
        fun stamp(intent: Intent) {
            intent.action = Intent.ACTION_VIEW
            intent.data = Uri.parse(link)
            intent.flags = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY.inv()
        }
        stamp(activity.intent)
        runCatching {
            val thread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null) ?: return
            val activitiesField = thread.javaClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(thread) as? Map<*, *> ?: return
            for (record in activities.values) {
                if (record == null) continue
                val activityField = record.javaClass.getDeclaredField("activity")
                activityField.isAccessible = true
                if (activityField.get(record) !== activity) continue
                val intentField = record.javaClass.getDeclaredField("intent")
                intentField.isAccessible = true
                val recorded = intentField.get(record) as? Intent ?: return
                stamp(recorded)
                return
            }
        }
    }

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun appTogether() = (app().applicationContext as GameBexApp).together

    /**
     * Hosts the real [AppNav] with a NavController the test can watch; [block]
     * reads every destination it has shown, in order.
     */
    private fun launchAppNav(block: (routes: () -> List<String>) -> Unit) {
        val routes = java.util.Collections.synchronizedList(mutableListOf<String>())
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val nav = rememberNavController()
                    DisposableEffect(nav) {
                        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                            destination.route?.let { routes += it }
                        }
                        nav.addOnDestinationChangedListener(listener)
                        onDispose { nav.removeOnDestinationChangedListener(listener) }
                    }
                    GameBexTheme { AppNav(nav = nav) }
                }
            }
            block { synchronized(routes) { routes.toList() } }
        }
    }

    private fun togetherAlreadyStarted(): Boolean {
        val runtime = com.gamebex.client.together.platform.TogetherRuntime
        val field = runtime.javaClass.getDeclaredField("instance")
        field.isAccessible = true
        return field.get(runtime) != null
    }

    @Suppress("UNCHECKED_CAST")
    private fun publishSession(host: HostView?, guest: GuestView?) {
        val engine = appTogether()
        val hostFlow = field(field(engine, "hostEngine"), "_view") as MutableStateFlow<HostView?>
        val guestFlow = field(field(engine, "guestEngine"), "_view") as MutableStateFlow<GuestView?>
        hostFlow.value = host
        guestFlow.value = guest
    }

    private fun field(target: Any, name: String): Any {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val found = type.declaredFields.firstOrNull { it.name == name }
            if (found != null) {
                found.isAccessible = true
                return found.get(target) ?: error("$name was null")
            }
            type = type.superclass
        }
        error("Missing $name on ${target.javaClass.name}")
    }

    private fun publishRelaunchIntent(activity: android.app.Activity, intent: Intent) {
        activity.setIntent(intent)
        runCatching {
            val thread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null) ?: return@runCatching
            val activitiesField = thread.javaClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(thread) as? Map<*, *> ?: return@runCatching
            for (record in activities.values) {
                if (record == null) continue
                val activityField = record.javaClass.getDeclaredField("activity")
                activityField.isAccessible = true
                if (activityField.get(record) !== activity) continue
                val intentField = record.javaClass.getDeclaredField("intent")
                intentField.isAccessible = true
                val recorded = intentField.get(record) as? Intent
                if (recorded != null) {
                    recorded.action = intent.action
                    recorded.data = intent.data
                    recorded.flags = recorded.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY.inv()
                }
                return@runCatching
            }
        }
    }

    private fun endTogetherAndAssertIdle() {
        val controller = appTogether()
        controller.end()
        val deadline = android.os.SystemClock.uptimeMillis() + 3_000
        var host = controller.host.value
        var guest = controller.guest.value
        while (android.os.SystemClock.uptimeMillis() < deadline && sessionLive(host, guest)) {
            controller.end()
            Thread.sleep(40)
            host = controller.host.value
            guest = controller.guest.value
        }
        assertTrue(host == null || host.step == HostStep.ENDED)
        assertTrue(guest == null || guest.step == GuestStep.ENDED)
        runCatching { publishSession(null, null) }
    }

    private fun sessionLive(host: HostView?, guest: GuestView?) =
        (host != null && host.step != HostStep.ENDED) || (guest != null && guest.step != GuestStep.ENDED)

    private companion object {
        const val WAIT_MS = 15_000L

        /** Port 80 is below the JoinLink minimum: it parses at the intent, then fails before a socket opens. */
        const val FAILING_JOIN_LINK = "gamebex://join?v=1&h=10.42.1.20&p=80&n=Sam"
    }
}
