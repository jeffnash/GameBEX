package com.gamebex.client

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.GuestView
import com.gamebex.client.together.HostView
import com.gamebex.client.together.Problem
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.GameBexTheme
import com.gamebex.client.ui.LibSort
import com.gamebex.client.ui.LibraryViewModel
import com.gamebex.client.ui.home.HomeStore
import com.gamebex.client.ui.home.model.HomeIntent
import com.gamebex.client.ui.home.model.HomeMode
import com.gamebex.client.ui.together.TogetherUi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real AppNav graph with a NavController the test can read. */
@RunWith(AndroidJUnit4::class)
class AppNavTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var nav: NavHostController
    private lateinit var activity: ComponentActivity
    private val homePress = mutableLongStateOf(0L)

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GameBexApp

    @After
    fun cleanUp() {
        runCatching { publishSession(null, null) }
        TogetherUi.pendingOpen.value = false
        TogetherUi.pendingLink.value = null
        TogetherUi.hubMessage.value = null
        app.pendingRoute.value = null
        app.pendingLibrarySort.value = null
    }

    @Test
    fun systemTabFromAnEndedTogetherScreenLandsOnTheSystemRootAndLibraryKeepsItsSort() = launch {
        tapHomeNav("Library")
        awaitRoutes(listOf("library", "allgames"))
        // The Free Space path sets Library's sort; the Library pill itself clears any pending one.
        onMain { app.pendingLibrarySort.value = LibSort.Size }
        compose.waitUntil(WAIT_MS) { libraryVm().sort.value == LibSort.Size }

        tapRail("System")
        awaitRoutes(listOf("library", "emulators"))
        publishSession(null, GuestView(step = GuestStep.IN_GAME, hostName = "Sam"))
        onMain { nav.navigate("together") }
        onMain { nav.navigate("together/guest") }
        awaitRoutes(listOf("library", "emulators", "together", "together/guest"))
        publishSession(null, GuestView(step = GuestStep.ENDED, hostName = "Sam", message = TogetherCopy.ended))
        compose.waitForIdle()

        tapRail("System")
        awaitRoutes(listOf("library", "emulators"))
        tapRail("Library")
        awaitRoutes(listOf("library", "allgames"))
        assertEquals(LibSort.Size, libraryVm().sort.value)
        tapRail("System")
        awaitRoutes(listOf("library", "emulators"))
    }

    @Test
    fun aGamePageThenDownloadsThenLibraryLandsOnTheLibraryRoot() = launch {
        tapHomeNav("Library")
        awaitRoutes(listOf("library", "allgames"))
        onMain { nav.navigate("local/1") }
        awaitRoutes(listOf("library", "allgames", "local/{gameId}"))
        tapRail("Downloads")
        awaitRoutes(listOf("library", "downloads"))
        tapRail("Library")
        awaitRoutes(listOf("library", "allgames"))
        tapRail("Library")
        awaitRoutes(listOf("library", "allgames"))

        tapRail("Discover")
        awaitRoutes(listOf("library", "browse"))
        onMain { nav.navigate("search") }
        awaitRoutes(listOf("library", "browse", "search?initialQuery={initialQuery}"))
        tapRail("Discover")
        awaitRoutes(listOf("library", "browse"))
        tapRail("System")
        awaitRoutes(listOf("library", "emulators"))
        onMain { nav.navigate("sources") }
        awaitRoutes(listOf("library", "emulators", "sources"))
        tapRail("System")
        awaitRoutes(listOf("library", "emulators"))
    }

    @Test
    fun homeKeyFromAGamePageOpenedOnHomeResetsHome() = launch {
        val store = homeStore()
        onMain { store.onIntent(HomeIntent.Query("zelda")) }
        compose.waitUntil(WAIT_MS) { store.query.value == "zelda" }
        onMain { nav.navigate("local/1") }
        awaitRoutes(listOf("library", "local/{gameId}"))

        onMain { homePress.longValue += 1 }
        awaitRoutes(listOf("library"))
        compose.waitUntil(WAIT_MS) { store.query.value.isEmpty() }
        assertTrue(store.model.value.mode != HomeMode.SURPRISE)
    }

    @Test
    fun railHighlightsTheTabThatOwnsTheStackAndOpenSystemNeverDuplicatesIt() = launch {
        tapHomeNav("Library")
        awaitRoutes(listOf("library", "allgames"))
        onMain { nav.navigate("local/1") }
        awaitRoutes(listOf("library", "allgames", "local/{gameId}"))
        assertRailSelected("Library")

        tapRail("System")
        awaitRoutes(listOf("library", "emulators"))
        publishSession(
            null,
            GuestView(step = GuestStep.ACTION_NEEDED, hostName = "Sam", problem = Problem(TogetherCopy.needsEmulator("RetroArch"), TogetherAction.OPEN_SYSTEM)),
        )
        onMain { nav.navigate("together") }
        onMain { nav.navigate("together/guest") }
        awaitRoutes(listOf("library", "emulators", "together", "together/guest"))
        click(hasText(TogetherCopy.actionLabel(TogetherAction.OPEN_SYSTEM).uppercase()) and hasClickAction())
        awaitRoutes(listOf("library", "emulators"))
        assertRailSelected("System")

        tapRail("Library")
        awaitRoutes(listOf("library", "allgames"))
        onMain { nav.navigate("together/guest") }
        awaitRoutes(listOf("library", "allgames", "together/guest"))
        click(hasText(TogetherCopy.actionLabel(TogetherAction.OPEN_SYSTEM).uppercase()) and hasClickAction())
        awaitRoutes(listOf("library", "allgames", "together/guest", "emulators"))
        assertRailSelected("Library")
        onMain { nav.popBackStack() }
        awaitRoutes(listOf("library", "allgames", "together/guest"))
        assertRailSelected("Library")
    }

    @Test
    fun notificationOpenPopsBackToAGuestScreenAlreadyOnTheStack() = launch {
        publishSession(null, GuestView(step = GuestStep.IN_GAME, hostName = "Sam"))
        onMain { nav.navigate("together/guest") }
        onMain { nav.navigate("emulators") }
        awaitRoutes(listOf("library", "together/guest", "emulators"))
        onMain { TogetherUi.pendingOpen.value = true }
        awaitRoutes(listOf("library", "together/guest"))
    }

    @Test
    fun aFailedJoinLinkReusesTheHubAlreadyOnTheStack() = launch {
        tapHomeNav("System")
        awaitRoutes(listOf("library", "emulators"))
        onMain { nav.navigate("together") }
        awaitRoutes(listOf("library", "emulators", "together"))
        onMain { TogetherUi.pendingLink.value = FAILING_JOIN_LINK }
        compose.waitUntil(WAIT_MS) { TogetherUi.hubMessage.value != null }
        awaitRoutes(listOf("library", "emulators", "together"))
    }

    @Test
    fun aReadyNotificationForTheSameGameDoesNotStackASecondPage() = launch {
        onMain { app.pendingRoute.value = "local/7" }
        awaitRoutes(listOf("library", "local/{gameId}"))
        compose.waitUntil(WAIT_MS) { app.pendingRoute.value == null }
        compose.waitForIdle()
        onMain { app.pendingRoute.value = "local/7" }
        compose.waitUntil(WAIT_MS) { app.pendingRoute.value == null }
        compose.waitForIdle()
        assertEquals(listOf("library", "local/{gameId}"), routes())
    }

    @Test
    fun railKeepsTheOwningTabAfterRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(WAIT_MS) { compose.shows(hasText("Library") and hasClickAction()) }
            click(hasText("Library") and hasClickAction())
            compose.waitUntil(WAIT_MS) { compose.shows(railItem("Library") and isSelected()) }
            onMain { app.pendingRoute.value = "local/1" }
            compose.waitUntil(WAIT_MS) { app.pendingRoute.value == null }
            compose.waitForIdle()
            compose.onNode(railItem("Library") and isSelected()).assertExists()
            scenario.recreate()
            compose.waitUntil(WAIT_MS) { compose.shows(railItem("Library") and isSelected()) }
            compose.onNode(railItem("Home") and isSelected()).assertDoesNotExist()
        }
    }

    private fun launch(block: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                activity = a
                a.setContent {
                    val controller = rememberNavController()
                    nav = controller
                    GameBexTheme { AppNav(homePressToken = homePress.longValue, nav = controller) }
                }
            }
            compose.waitUntil(WAIT_MS) { runCatching { routes() }.getOrNull() == listOf("library") }
            block()
        }
    }

    private fun routes(): List<String?> {
        var out: List<String?> = emptyList()
        onMain { out = nav.currentBackStack.value.filter { it.destination !is NavGraph }.map { it.destination.route } }
        return out
    }

    private fun awaitRoutes(expected: List<String>) {
        runCatching { compose.waitUntil(WAIT_MS) { routes() == expected } }
        assertEquals(expected, routes())
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun railItem(label: String) = hasText(label) and hasClickAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected)

    private fun tapRail(label: String) {
        compose.waitUntil(WAIT_MS) { compose.shows(railItem(label)) }
        click(railItem(label))
        compose.waitForIdle()
    }

    /** Taps a Home top-bar pill; the rail is hidden on Home. */
    private fun tapHomeNav(label: String) {
        compose.waitUntil(WAIT_MS) { compose.shows(hasText(label) and hasClickAction()) }
        click(hasText(label) and hasClickAction())
        compose.waitForIdle()
    }

    /** A semantic click: the rail's hit boxes overlap on the small portrait test canvas, so a touch can land on a neighbour. */
    private fun click(matcher: SemanticsMatcher) {
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun assertRailSelected(label: String) {
        compose.waitUntil(WAIT_MS) { compose.shows(railItem(label) and isSelected()) }
        compose.onNode(railItem(label) and isSelected()).assertExists()
    }

    private fun ComposeTestRule.shows(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun libraryVm(): LibraryViewModel {
        var vm: LibraryViewModel? = null
        onMain { vm = ViewModelProvider(nav.currentBackStackEntry!!)[LibraryViewModel::class.java] }
        return vm!!
    }

    private fun homeStore(): HomeStore {
        var store: HomeStore? = null
        onMain { store = ViewModelProvider(activity)[HomeStore::class.java] }
        return store!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun publishSession(host: HostView?, guest: GuestView?) {
        onMain {
            val engine = app.together
            (field(field(engine, "hostEngine"), "_view") as MutableStateFlow<HostView?>).value = host
            (field(field(engine, "guestEngine"), "_view") as MutableStateFlow<GuestView?>).value = guest
        }
    }

    private fun field(target: Any, name: String): Any {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it.get(target) ?: error("$name was null")
            }
            type = type.superclass
        }
        error("Missing $name on ${target.javaClass.name}")
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val FAILING_JOIN_LINK = "gamebex://join?v=1&h=10.42.1.20&p=80&n=Sam"
    }
}
