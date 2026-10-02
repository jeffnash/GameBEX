package com.gamebex.client

import android.view.KeyEvent
import com.gamebex.client.ui.console.ControllerPrefs
import com.gamebex.client.ui.console.remapGamepadKey
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gamebex.client.together.platform.TogetherRuntime
import com.gamebex.client.together.platform.TogetherPlatformImpl
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.GuestView
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.HostView
import com.gamebex.client.ui.together.TogetherUi
import com.gamebex.client.sources.CatalogRouteResult
import com.gamebex.client.sources.decodeCatalogPayload
import com.gamebex.client.sources.encodeCatalogPayload
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDestination
import com.gamebex.client.ui.console.ConsoleGeometryRoot
import com.gamebex.client.ui.console.ConsoleScaffold
import com.gamebex.client.ui.console.ControllerHintBar
import com.gamebex.client.ui.console.rememberConsoleFocusMemory
import com.gamebex.client.ui.console.rememberConsoleHints
import com.gamebex.client.ui.discover.DiscoverScreen
import com.gamebex.client.ui.game.GameDetailScreen
import com.gamebex.client.ui.home.HomeRoot
import com.gamebex.client.ui.home.HomeStore
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.HomeEffect
import com.gamebex.client.ui.home.model.HomeIntent
import com.gamebex.client.ui.system.SystemScreen
import com.gamebex.client.ui.together.TogetherInviteDialog
import com.gamebex.client.ui.together.TogetherHubScreen
import com.gamebex.client.ui.together.TogetherHostScreen
import com.gamebex.client.ui.together.TogetherGuestScreen
import com.gamebex.client.stream.guest.StreamGuestRuntime
import com.gamebex.client.ui.BrowseViewModel
import com.gamebex.client.ui.ConsoleDownloadsScreen
import com.gamebex.client.ui.sources.SourcesScreen
import com.gamebex.client.ui.sources.SourcesViewModel
import com.gamebex.client.ui.DownloadsViewModel
import com.gamebex.client.ui.EmulatorsViewModel
import com.gamebex.client.ui.ConsoleLibraryScreen
import com.gamebex.client.ui.LibraryViewModel
import com.gamebex.client.ui.LocalDetailViewModel
import com.gamebex.client.ui.GameBexTheme
import com.gamebex.client.ui.SearchViewModel
import java.net.URLEncoder
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    /** Best-effort grant: without it downloads simply run unprotected. */
    private val notifAsk = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Bumped on every Home-button intent while GameBEX is the
     * launcher. AppNav returns to `library` and HomeRoot resets (query,
     * Surprise, selection) when it changes.
     */
    val homePressToken = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeTogetherIntent(intent, savedInstanceState != null)
        (application as GameBexApp).requestNotifications = {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { notifAsk.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
        // Console surfaces draw edge-to-edge; system bars hide in landscape.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // Full-bleed under the camera cutout: the default mode letterboxes
        // landscape to x70+ (70px notch), breaking the 1600-wide contract.
        // The notch lands on the dark rail, never on focusable content.
        // API-gated: the field doesn't exist below 28 (minSdk 26).
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // Handheld stays awake only while a download is active or a launch
        // is in progress — as the home app, the phone must
        // be able to sleep. See KeepScreenOnEffect.
        setContent {
            GameBexTheme {
                Box(Modifier.fillMaxSize().background(ConsoleColors.Background)) {
                    AppNav(homePressToken = homePressToken.value)
                    com.gamebex.client.ui.system.ControllerGuideHost()
                }
            }
        }
    }

    /**
     * Confirm/back swap for Nintendo-habit players, applied before Compose
     * and the system fallbacks see the key, so both agree on the mapping.
     * (RestrictedApi: lint false positive on overriding the public
     * Activity method through androidx.core's ComponentActivity.)
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = remapGamepadKey(event.keyCode, ControllerPrefs.swapConfirm)
        if (code == event.keyCode) return super.dispatchKeyEvent(event)
        return super.dispatchKeyEvent(
            KeyEvent(
                event.downTime, event.eventTime, event.action, code, event.repeatCount,
                event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
            ),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeTogetherIntent(intent)
        // The system Home button while GameBEX is the
        // launcher returns to `library` fresh (clears search, exits
        // Surprise, resets the selection) — never a route deep link.
        if (isHomePressIntent(intent)) {
            homePressToken.value += 1
            return
        }
        (application as GameBexApp).pendingRoute.value = consumeRouteIntent(this, intent)
    }
    override fun onResume() {
        super.onResume()
        (application as GameBexApp).confirmPendingPlay()
        // Play when ready auto-launches only while GameBEX is foreground.
        com.gamebex.client.net.PlayWhenReady.foreground = true
        TogetherRuntime.setForeground(true)
        (application as GameBexApp).together.startBrowsing()
        (application as GameBexApp).together.onForeground()
    }

    override fun onPause() {
        com.gamebex.client.net.PlayWhenReady.foreground = false
        (application as GameBexApp).together.stopBrowsing()
        TogetherRuntime.setForeground(false)
        super.onPause()
    }
}

/** Consumes Play together deep links and notification opens exactly once. */
internal fun consumeTogetherIntent(intent: Intent?, restoredInstance: Boolean = false) {
    if (intent == null) return
    val replay = joinLinkDeliveryIsReplay(restoredInstance, intent.flags)
    if (!replay && intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "gamebex" && intent.data?.host == "join") {
        TogetherUi.pendingLink.value = intent.data.toString()
        intent.data = null
    }
    if (intent.getBooleanExtra(TogetherPlatformImpl.EXTRA_OPEN, false)) {
        TogetherUi.pendingOpen.value = true
        intent.removeExtra(TogetherPlatformImpl.EXTRA_OPEN)
    }
}

/** A recreated activity or Recents launch must not join again. */
internal fun joinLinkDeliveryIsReplay(restoredInstance: Boolean, flags: Int): Boolean =
    restoredInstance || flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

/** True while a host or guest session is still running. No copy describes that case, so callers stay quiet. */
internal fun togetherSessionActive(host: HostView?, guest: GuestView?): Boolean =
    (host != null && host.step != HostStep.ENDED) || (guest != null && guest.step != GuestStep.ENDED)

/**
 * Notification-open destination. ENDED host and guest views are not the
 * active session; opening the host route would call hostGame.
 */
internal fun togetherOpenRoute(host: HostView?, guest: GuestView?): String? = when {
    host != null && host.step != HostStep.ENDED -> "together/host/${host.gameId}"
    guest != null && guest.step != GuestStep.ENDED -> "together/guest"
    else -> null
}

/**
 * Notification routing: validates the route extra (top-level or
 * `local/<id>`), arms autoplay only for our own token-carrying Play
 * action, and strips the extras so recreation never replays them.
 */
internal fun consumeRouteIntent(ctx: android.content.Context, intent: Intent?): String? {
    if (intent == null) return null
    val app = ctx.applicationContext as GameBexApp
    val raw = intent.getStringExtra(com.gamebex.client.net.DownloadService.EXTRA_ROUTE)
    val target = com.gamebex.client.net.GameRoutes.parse(
        raw,
        play = intent.getBooleanExtra(com.gamebex.client.net.ReadyNotifier.EXTRA_PLAY, false),
        token = intent.getStringExtra(com.gamebex.client.net.ReadyNotifier.EXTRA_TOKEN),
        expectedToken = runCatching { com.gamebex.client.net.ReadyNotifier.token(ctx) }.getOrNull(),
    )
    intent.removeExtra(com.gamebex.client.net.DownloadService.EXTRA_ROUTE)
    intent.removeExtra(com.gamebex.client.net.ReadyNotifier.EXTRA_PLAY)
    intent.removeExtra(com.gamebex.client.net.ReadyNotifier.EXTRA_TOKEN)
    return when (target) {
        null -> null
        is com.gamebex.client.net.GameRoutes.Target.Top -> target.route
        is com.gamebex.client.net.GameRoutes.Target.Game -> {
            if (target.autoPlay) {
                app.pendingAutoPlay.value = target.gameId
                com.gamebex.client.net.ReadyNotifier.cancel(ctx, target.gameId)
            }
            com.gamebex.client.net.GameRoutes.local(target.gameId)
        }
    }
}

/**
 * Detail autoplay (ready-notification Play action): once the routed game's
 * row loads, start it exactly as the Play button would. One-shot: the
 * pending id is consumed before launching.
 */
@Composable
private fun AutoPlayEffect(app: GameBexApp, gameId: Long, vm: LocalDetailViewModel, context: android.content.Context) {
    val pendingPlay by app.pendingAutoPlay.collectAsState()
    LaunchedEffect(gameId, pendingPlay) {
        if (pendingPlay != gameId) return@LaunchedEffect
        app.pendingAutoPlay.value = null
        val loaded = kotlinx.coroutines.withTimeoutOrNull(5_000) {
            vm.game.first { it?.id == gameId }
        }
        if (loaded != null) vm.play(context)
    }
}

/**
 * Single navigation construction site: the codec payload, URI-encoded
 * once. Form-encoding emits "+" for spaces but Navigation never decodes
 * "+" in path args, so spaces ride as %20 (detail titles/labels with
 * spaces corrupted otherwise).
 */
internal fun detailRouteFor(entry: com.gamebex.client.data.RomEntry): String =
    "detail/${URLEncoder.encode(encodeCatalogPayload(entry), "UTF-8").replace("+", "%20")}"

/** Factory for ViewModels that take app args plus a SavedStateHandle. */
private inline fun <reified VM : ViewModel> savedFactory(crossinline make: (SavedStateHandle) -> VM) =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            make(extras.createSavedStateHandle()) as T
    }

@Composable
fun AppNav(
    homePressToken: Long = 0,
    nav: androidx.navigation.NavHostController = rememberNavController(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as GameBexApp
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    // The tab that owns the back stack. goHome sets it (every tab change goes
    // through goHome) and it's saved, so it survives restores and recreation. It
    // stands while that tab's root is still on the stack; otherwise Home owns it.
    var ownerTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("library") }
    fun currentTab(): String = tabOwning(ownerTab) { runCatching { nav.getBackStackEntry(it) }.isSuccess }

    // One Home store for the screen and shell-level observability. The
    // `library` entry is the start destination and never leaves the
    // backstack, so this activity scope matches the old entry scope.
    val homeStore: HomeStore = viewModel {
        HomeStore(
            context.applicationContext,
            app.saveIndex, app.siteSearch, app.homeDownloads, app.device,
            com.gamebex.client.data.local.LibraryDb.get(context),
        )
    }

    KeepScreenOnEffect(app, homeStore)

    /**
     * A tab tap (rail, Home top bar, the Home key) lands on [dest]'s ROOT.
     * Pages pushed above the current tab's root (game pages, search, sources,
     * Play together) are dropped first, so a tab saves only its root (filters,
     * scroll). Home is never restored: a non-inclusive popUpTo("library") with
     * saveState also files the popped stack under `library`. Anything a restore
     * brings back above [dest]'s root goes too.
     */
    fun goHome(dest: String) {
        nav.popBackStack(currentTab(), inclusive = false)
        nav.navigate(dest) {
            popUpTo("library") { saveState = true }
            launchSingleTop = true
            restoreState = dest != "library"
        }
        nav.popBackStack(dest, inclusive = false)
        ownerTab = dest
    }

    /** Go to [route], or pop back to it when it's already on the stack (never a second copy). */
    fun openOnce(route: String) = nav.openOnce(route)

    // A Home-button press returns to `library` fresh —
    // clears the search, exits Surprise, resets the selection (HomeRoot
    // resets the cursor through homePressToken).
    LaunchedEffect(homePressToken) {
        if (homePressToken == 0L) return@LaunchedEffect
        homeStore.onIntent(HomeIntent.ClearSearch)
        homeStore.onIntent(HomeIntent.ExitSurprise)
        goHome("library")
    }

    // Notification tap routing (cold start + singleTop re-delivery).
    // Top-level destinations and local/<id> game details are honored
    // (validated by GameRoutes); anything else is dropped.
    fun routeTo(r: String?) {
        when (val t = com.gamebex.client.net.GameRoutes.parse(r)) {
            is com.gamebex.client.net.GameRoutes.Target.Top -> goHome(t.route)
            is com.gamebex.client.net.GameRoutes.Target.Game ->
                openOnce(com.gamebex.client.net.GameRoutes.local(t.gameId))
            null -> Unit
        }
    }
    LaunchedEffect(Unit) {
        routeTo(consumeRouteIntent(context, (context as? android.app.Activity)?.intent))
    }
    val pending by app.pendingRoute.collectAsState()
    LaunchedEffect(pending) {
        if (pending != null) {
            routeTo(pending)
            app.pendingRoute.value = null
        }
    }
    val hostView by app.together.host.collectAsState()
    val guestView by app.together.guest.collectAsState()
    val pendingTogetherLink by TogetherUi.pendingLink.collectAsState()
    LaunchedEffect(pendingTogetherLink, hostView, guestView, backStack) {
        val link = pendingTogetherLink ?: return@LaunchedEffect
        if (nav.currentDestination == null) return@LaunchedEffect
        TogetherUi.pendingLink.value = null
        if (togetherSessionActive(hostView, guestView)) return@LaunchedEffect
        val error = app.together.joinLink(link)
        if (error == null) openOnce("together/guest")
        else {
            TogetherUi.hubMessage.value = error
            openOnce("together")
        }
    }
    val pendingTogetherOpen by TogetherUi.pendingOpen.collectAsState()
    LaunchedEffect(pendingTogetherOpen, hostView, guestView, backStack) {
        if (!pendingTogetherOpen) return@LaunchedEffect
        if (nav.currentDestination == null) return@LaunchedEffect
        TogetherUi.pendingOpen.value = false
        togetherOpenRoute(hostView, guestView)?.let(::openOnce)
    }

    // Immersive handheld mode. GameBEX is landscape-only (manifest
    // sensorLandscape), so the system bars always hide.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val win = (context as? android.app.Activity)?.window
        if (win != null) {
            val controller = androidx.core.view.WindowInsetsControllerCompat(win, win.decorView)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {}
    }

    // The console shell is the only UI. Resized or
    // multi-window bounds letterbox inside ConsoleGeometryRoot's canvas.
    ConsoleGeometryRoot {
        val focusMemory = rememberConsoleFocusMemory()
        val hintState = rememberConsoleHints()
        // The lit tab is the one that owns the back stack (a game page opened
        // from Library stays "Library"), read from the stack itself so it survives
        // restores and recreation.
        val dest = consoleDestinationFor(currentTab())
        ConsoleScaffold(
            activeDestination = dest,
            onDestinationSelected = { goHome(it.route) },
            focusMemory = focusMemory,
            // The rail is hidden on Home only; Home has its own header nav.
            showRail = route != "library",
            railResetKey = route,
            controllerHints = {
                when (route) {
                    // Screens write live hints (a quick-play status panel
                    // re-binds A/B while it shows; System hides Y when clean).
                    // Only Home emphasizes the primary A.
                    "library", "allgames", "downloads", "local/{gameId}", "detail/{entry}",
                    "browse", "emulators", "together", "together/host/{gameId}", "together/guest" ->
                        ControllerHintBar(hintState.hints, emphasizePrimary = emphasizePrimaryFor(route))
                    else -> if (isSearchRoute(route)) {
                        ControllerHintBar(hintState.hints, emphasizePrimary = false)
                    }
                }
            },
        ) { rail ->
            ConsoleNavHost(nav, app, context, focusMemory, rail, hintState, ::goHome, homeStore, homePressToken)
        }
        TogetherInviteDialog(app.together, route) { openOnce("together/guest") }
    }
}

/** The tab roots that can sit directly above `library` (Home is `library` itself). */
private val TAB_ROOTS = setOf("allgames", "browse", "downloads", "emulators")

/**
 * The tab that owns the back stack: the tab root last reached through a tab
 * change ([owner]) while it's still on the stack ([onStack]), else Home. Pushed
 * pages (game pages, search, sources, Play together, a System page opened from a
 * session) belong to it, as does everything above it.
 */
internal fun tabOwning(owner: String, onStack: (String) -> Boolean): String =
    if (owner in TAB_ROOTS && onStack(owner)) owner else "library"

/** Pops every Play together route on top, so leaving a session screen leaves nothing together-related behind (N1). */
private fun androidx.navigation.NavController.leaveTogether() {
    while (currentBackStackEntry?.destination?.route?.startsWith("together") == true && popBackStack()) Unit
}

/**
 * Navigate to [route], or pop back to it when it's already on the stack. A
 * concrete route matches its entry's arguments (`local/7` is not `local/8`).
 * popBackStack alone can't tell "already on top" from "absent", so check first.
 */
private fun androidx.navigation.NavController.openOnce(route: String) {
    if (runCatching { getBackStackEntry(route) }.isSuccess) popBackStack(route, inclusive = false) else navigate(route)
}

/** System from a session screen pops back to a System already on the stack instead of pushing a second one. */
private fun androidx.navigation.NavController.openSystem() = openOnce("emulators")

/**
 * Search route with the optional pre-fill query: the
 * optional query lives in the route pattern, so `searchRouteFor` navigation
 * from "See every result" actually lands `initialQuery` in SearchViewModel's
 * SavedStateHandle. A bare "search" (Discover) still matches.
 */
internal const val SEARCH_ROUTE = "search?initialQuery={initialQuery}"
internal const val SEARCH_ARG_QUERY = "initialQuery"

/** The one construction site for a pre-filled Search navigation. */
internal fun searchRouteFor(query: String): String =
    "search?initialQuery=${android.net.Uri.encode(query)}"

/** True for the search destination's pattern or any pre-filled instance. */
internal fun isSearchRoute(route: String?): Boolean = route?.startsWith("search") == true

/** Home emphasizes the primary A; every other route leaves it off. */
internal fun emphasizePrimaryFor(route: String?): Boolean = route == "library"

/**
 * The console navigation graph (the app's only one).
 */
@Composable
private fun ConsoleNavHost(
    nav: androidx.navigation.NavHostController,
    app: GameBexApp,
    context: android.content.Context,
    focusMemory: com.gamebex.client.ui.console.ConsoleFocusMemory,
    rail: com.gamebex.client.ui.console.RailHost,
    hints: com.gamebex.client.ui.console.ConsoleHints,
    goHome: (String) -> Unit,
    homeStore: HomeStore,
    homePressToken: Long,
) {
    NavHost(
        navController = nav,
        startDestination = "library",
        // Console firmware cuts instantly; Material fades also race focus.
        enterTransition = { androidx.compose.animation.EnterTransition.None },
        exitTransition = { androidx.compose.animation.ExitTransition.None },
        popEnterTransition = { androidx.compose.animation.EnterTransition.None },
        popExitTransition = { androidx.compose.animation.ExitTransition.None },
    ) {
        composable("library") {
            val store = homeStore
            val model by store.model.collectAsState()
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            // One-shot effects: navigation plus a transient line
            // at the bottom, the same toast treatment Discover uses.
            var toast by remember { mutableStateOf<String?>(null) }
            // Only Navigate effects QUEUED while Home wasn't
            // resumed (the stale replay buffer on return to Home) are
            // dropped. Fresh taps — including taps during the enter
            // transition — arm the gate and must run.
            val navGate = remember { HomeNavGate() }
            LaunchedEffect(store) {
                navGate.onSessionStart()
                store.effects.collect { eff ->
                    when (eff) {
                        is HomeEffect.Toast -> toast = eff.text
                        is HomeEffect.Navigate -> {
                            if (navGate.shouldRunNavigate(
                                    homeIsTop = nav.currentBackStackEntry?.destination?.route == "library",
                                )
                            ) {
                                when (val d = eff.dest) {
                                    is HomeDestination.Library -> {
                                        app.pendingLibrarySort.value = d.sort
                                        goHome("allgames")
                                    }
                                    HomeDestination.Downloads -> goHome("downloads")
                                    HomeDestination.Discover -> goHome("browse")
                                    is HomeDestination.SiteSearch ->
                                        nav.navigate(searchRouteFor(d.query))
                                    HomeDestination.Sources -> nav.navigate("sources")
                                    HomeDestination.System -> goHome("emulators")
                                    is HomeDestination.Game -> nav.navigate("local/${d.gameId}")
                                    is HomeDestination.SiteEntry -> nav.navigate(detailRouteFor(d.entry))
                                }
                            }
                        }
                    }
                }
            }
            LaunchedEffect(toast) {
                if (toast != null) {
                    kotlinx.coroutines.delay(5000)
                    toast = null
                }
            }
            // Entered on resume: save-index cache drop + staleness refresh.
            DisposableEffect(lifecycle, store) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
                    if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        store.onIntent(HomeIntent.Entered)
                    }
                }
                lifecycle.addObserver(obs)
                onDispose { lifecycle.removeObserver(obs) }
            }
            Box(Modifier.fillMaxSize()) {
                // External query changes come from the store's synchronous
                // query flow, adopted echo-safely by the field; the default
                // (model.query) covers other callers.
                HomeRoot(
                    model = model,
                    onIntent = { intent ->
                        // A user intent arms navigation for this Home
                        // session (auto intents — Entered, auto-dismiss, the
                        // pre-filled query re-assert — don't).
                        navGate.onUserIntent(intent, model.query)
                        store.onIntent(intent)
                    },
                    focusMemory = focusMemory,
                    hints = hints,
                    externalQuery = store.query.collectAsState().value,
                    homeResetToken = homePressToken,
                )
                val g = com.gamebex.client.ui.console.LocalConsoleGeometry.current
                toast?.let { msg ->
                    androidx.compose.material3.Text(
                        msg,
                        color = ConsoleColors.Warning, fontSize = g.t(16),
                        lineHeight = g.t(18),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = g.x(52)),
                    )
                }
            }
        }
        composable("allgames") {
            val libVm: LibraryViewModel = viewModel { LibraryViewModel(context.applicationContext) }
            val pendingSort by app.pendingLibrarySort.collectAsState()
            LaunchedEffect(pendingSort) {
                pendingSort?.let {
                    libVm.onSort(it)
                    app.pendingLibrarySort.value = null
                }
            }
            ConsoleLibraryScreen(
                libVm, rail, focusMemory, hints,
                onOpen = { nav.navigate("local/$it") },
                onBrowse = { goHome("browse") },
                onInstallEmu = { goHome("emulators") },
            )
        }
        composable(
            "local/{gameId}",
            arguments = listOf(navArgument("gameId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("gameId") ?: 0L
            val vm: LocalDetailViewModel = viewModel { LocalDetailViewModel(context.applicationContext) }
            LaunchedEffect(id) { vm.load(id) }
            AutoPlayEffect(app, id, vm, context)
            GameDetailScreen(
                vm, rail, hints,
                onBack = { nav.popBackStack() },
                onInstallEmu = { goHome("emulators") },
                onManageSources = { nav.navigate("sources") },
                onFreeSpace = {
                    // Free Space: Library, biggest games first; Select deletes.
                    app.pendingLibrarySort.value = com.gamebex.client.ui.LibSort.Size
                    goHome("allgames")
                },
                onPlayTogether = { nav.navigate("together/host/$it") },
            )
        }
        composable("browse") {
            val vm: BrowseViewModel = viewModel(factory = savedFactory { h -> BrowseViewModel(app.sources, context.applicationContext, h) })
            DiscoverScreen(
                vm, focusMemory, rail, hints,
                onOpen = { nav.navigate(detailRouteFor(it)) },
                onOpenLocal = { nav.navigate("local/$it") },
                onSearch = { nav.navigate("search") },
                onManageSources = { nav.navigate("sources") },
            )
        }
        composable(
            SEARCH_ROUTE,
            // The Home "See every result" link lands here with
            // the query pre-filled through SearchViewModel's SavedStateHandle.
            arguments = listOf(
                navArgument(SEARCH_ARG_QUERY) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            val vm: SearchViewModel = viewModel(factory = savedFactory { h -> SearchViewModel(app.sources, h) })
            com.gamebex.client.ui.ConsoleSearchScreen(
                vm, rail, hints,
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(detailRouteFor(it)) },
                onSources = { nav.navigate("sources") },
            )
        }
        composable(
            "detail/{entry}",
            arguments = listOf(navArgument("entry") { type = NavType.StringType }),
        ) { backStackEntry ->
            // Unified detail: catalog entries open the same screen as
            // library games; Download flips to Play when ingest lands.
            val result = remember {
                decodeCatalogPayload(backStackEntry.arguments?.getString("entry").orEmpty())
            }
            val vm: LocalDetailViewModel = viewModel { LocalDetailViewModel(context.applicationContext) }
            LaunchedEffect(result) {
                when (result) {
                    is CatalogRouteResult.Current -> vm.loadCatalog(result.entry)
                    // Invalid: the destination shows its not-found state.
                    is CatalogRouteResult.Invalid -> Unit
                }
            }
            GameDetailScreen(
                vm, rail, hints,
                onBack = { nav.popBackStack() },
                onInstallEmu = { goHome("emulators") },
                onManageSources = { nav.navigate("sources") },
                onPlayTogether = { nav.navigate("together/host/$it") },
            )
        }
        composable("sources") {
            val vm: SourcesViewModel = viewModel {
                SourcesViewModel(app.sources, app.engine, context.applicationContext)
            }
            SourcesScreen(vm, rail, focusMemory, onBack = { nav.popBackStack() })
        }
        composable("downloads") {
            val vm: DownloadsViewModel = viewModel {
                DownloadsViewModel(
                    app.engine,
                    com.gamebex.client.ui.DownloadDismissals(
                        context.applicationContext.getSharedPreferences(
                            com.gamebex.client.ui.DownloadDismissals.PREFS, android.content.Context.MODE_PRIVATE,
                        ),
                    ),
                )
            }
            val installed by app.sources.list().collectAsState()
            val sourceNames = remember(installed) { installed.associate { it.id to it.name.ifBlank { it.id } } }
            ConsoleDownloadsScreen(
                vm, onBack = { nav.popBackStack() }, railHost = rail, focusMemory = focusMemory,
                onOpen = { nav.navigate("local/$it") },
                sourceNames = sourceNames,
                hints = hints,
                onBrowse = { goHome("browse") },
            )
        }
        composable("emulators") {
            val vm: EmulatorsViewModel = viewModel { EmulatorsViewModel(context.applicationContext) }
            SystemScreen(vm, focusMemory, rail, onSources = { nav.navigate("sources") }, onTogether = { nav.navigate("together") }, hints = hints)
        }
        composable("together") {
            TogetherHubScreen(app.together, rail, hints, onBack = { nav.popBackStack() }, onJoined = { nav.navigate("together/guest") })
        }
        composable("together/host/{gameId}", arguments = listOf(navArgument("gameId") { type = NavType.LongType })) { entry ->
            val gameId = entry.arguments?.getLong("gameId") ?: 0L
            TogetherHostScreen(app.together, gameId, rail, hints, onBack = { nav.leaveTogether() }, onOpenSystem = { nav.openSystem() }, onOpenPpsspp = {
                context.packageManager.getLaunchIntentForPackage("org.ppsspp.ppsspp")?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, onOpenRetroArch = { openRetroArchMenu(context) })
        }
        composable("together/guest") {
            val streamViewerOpen by StreamGuestRuntime.viewerOpen.collectAsState()
            TogetherGuestScreen(app.together, rail, hints, onBack = { nav.leaveTogether() }, onOpenSystem = { nav.openSystem() }, onOpenPpsspp = {
                context.packageManager.getLaunchIntentForPackage("org.ppsspp.ppsspp")?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, onOpenRetroArch = { openRetroArchMenu(context) }, streamViewerOpen = streamViewerOpen)
        }
    }
}

/**
 * RetroArch's launcher activity (MainMenuActivity) runs its first-run setup
 * and file-access prompt; the session screens open it for NeedsOpenOnce(RETROARCH).
 */
private fun openRetroArchMenu(context: android.content.Context) {
    context.packageManager.getLaunchIntentForPackage("com.retroarch.aarch64")
        ?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/** A system Home-button press while GameBEX is the launcher. */
internal fun isHomePressIntent(intent: Intent?): Boolean =
    isHomePress(intent?.action, intent?.categories)

/** Pure [isHomePressIntent] core (String constants inline; unit-testable). */
internal fun isHomePress(action: String?, categories: Set<String>?): Boolean =
    action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_HOME) == true

/**
 * Which intents may arm navigation. Auto intents (lifecycle
 * `Entered`, the quick-play auto-dismiss, and the pre-filled query re-assert
 * which repeats the current query) never arm; every user intent does.
 */
internal fun armsHomeNavigation(intent: HomeIntent, currentQuery: String): Boolean =
    when (intent) {
        HomeIntent.Entered, HomeIntent.DismissStatus -> false
        is HomeIntent.Query -> intent.text != currentQuery
        else -> true
    }

/**
 * The navigation gate. Effects QUEUED while Home wasn't
 * resumed — the channel's stale replay buffer, drained at the start of every
 * Home collection session — are dropped. Fresh user intents (including taps
 * during the enter transition) arm the gate and their effects run.
 */
internal class HomeNavGate {
    private var armed = false

    /** A new Home collection session: whatever was queued before is stale. */
    fun onSessionStart() {
        armed = false
    }

    fun onUserIntent(intent: HomeIntent, currentQuery: String) {
        if (armsHomeNavigation(intent, currentQuery)) armed = true
    }

    fun shouldRunNavigate(homeIsTop: Boolean): Boolean = armed && homeIsTop
}

/**
 * The handheld may sleep. The keep-screen-on flag is held
 * only while a download is active (any screen) or a launch is in progress
 * (the Home quick-play status), never unconditionally.
 */
@Composable
private fun KeepScreenOnEffect(app: GameBexApp, homeStore: HomeStore) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val jobs by app.homeDownloads.jobs.collectAsState()
    val model by homeStore.model.collectAsState()
    val downloading = jobs.values.any {
        it.status == com.gamebex.client.net.DownloadEngine.ST_ACTIVE ||
            it.status == com.gamebex.client.net.DownloadEngine.ST_QUEUED
    }
    val launching = model.status?.busy == true
    val keep = downloading || launching
    LaunchedEffect(activity, keep) {
        val win = activity?.window ?: return@LaunchedEffect
        if (keep) {
            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** Rail destination for a tab route (the owning tab comes from [tabOwning]). */
internal fun consoleDestinationFor(route: String?): ConsoleDestination = when {
    route == null || route == "library" || route.startsWith("local/") -> ConsoleDestination.Home
    route == "browse" || isSearchRoute(route) || route.startsWith("detail/") -> ConsoleDestination.Discover
    route == "downloads" -> ConsoleDestination.Downloads
    route == "allgames" -> ConsoleDestination.Library
    else -> ConsoleDestination.System
}
