package com.gamebex.client.ui.home

import com.gamebex.client.MainDispatcherRule
import com.gamebex.client.awaitTrue
import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlayStates
import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.launch.RetroCoreFetcher
import com.gamebex.client.ui.presentation.LaunchAction
import com.gamebex.client.ui.presentation.LaunchStatus
import com.gamebex.client.ui.presentation.launchStatusFor
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.FakeLibraryDb
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.DownloadStart
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.HomeEffect
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.HomeIntent
import com.gamebex.client.ui.home.model.HomeMode
import com.gamebex.client.ui.home.model.InviteKind
import com.gamebex.client.ui.home.model.LinkKind
import com.gamebex.client.ui.home.model.DeviceStatus
import com.gamebex.client.ui.home.model.HomeDownloads
import com.gamebex.client.ui.home.model.SaveIndex
import com.gamebex.client.ui.home.model.SaveInfo
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.SiteSearch
import com.gamebex.client.ui.home.model.SiteResult
import com.gamebex.client.ui.home.model.SizeInfo
import com.gamebex.source.RomEntry
import com.gamebex.source.SourceRef
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeStoreTest {

    @get:Rule val main = MainDispatcherRule()

    private val gb = HomeCopy.GB

    private class FakeSaveIndex : SaveIndex {
        val map = mutableMapOf<Long, SaveInfo>()
        var invalidations = 0
        var lookups = 0
        override fun lookup(game: GameEntity): SaveInfo? {
            lookups++
            return map[game.id]
        }

        override fun invalidate() {
            invalidations++
        }
    }

    private class FakeSiteSearch : SiteSearch {
        override val pluginName: StateFlow<String?> = MutableStateFlow<String?>(null)
        var result: (String) -> SiteResult = { SiteResult.NoPlugin }
        val searchCalls = mutableListOf<String>()
        val sizeCalls = mutableListOf<String>()
        val sizeCompleted = mutableListOf<String>()
        var sizeDelayMs = 0L
        override suspend fun search(query: String): SiteResult {
            searchCalls += query
            return result(query)
        }

        override suspend fun size(entry: RomEntry): SizeInfo? {
            sizeCalls += entry.ref.itemKey
            if (sizeDelayMs > 0) delay(sizeDelayMs)
            sizeCompleted += entry.ref.itemKey
            return SizeInfo(1 * HomeCopy.GB, "1.0 GB")
        }
    }

    private class FakeHomeDownloads : HomeDownloads {
        override val jobs = MutableStateFlow<Map<Long, DownloadEngine.DlSnapshot>>(emptyMap())
        override val armed = MutableStateFlow<Set<Long>>(emptySet())
        var start: DownloadStart = DownloadStart.Failed("boom")
        val calls = mutableListOf<String>()
        override suspend fun downloadAgain(gameId: Long): DownloadStart {
            calls += "again:$gameId"
            return start
        }

        override suspend fun downloadFromSite(entry: RomEntry): DownloadStart {
            calls += "site:${entry.ref.itemKey}"
            return start
        }

        override fun togglePlayWhenReady(downloadId: Long) {
            calls += "toggle:$downloadId"
        }

        override fun pause(downloadId: Long) {
            calls += "pause:$downloadId"
        }

        override fun resume(downloadId: Long) {
            calls += "resume:$downloadId"
        }
    }

    private class FakeDeviceStatus : DeviceStatus {
        override val online = MutableStateFlow(true)
        var free: Long? = 28 * HomeCopy.GB
        var warnings = 2
        var warningsThrows = false
        var freeCalls = 0
        override fun freeBytes(folder: String?): Long? {
            freeCalls++
            return free
        }

        override suspend fun systemWarnings(): Int {
            if (warningsThrows) throw IllegalStateException("warnings failed")
            return warnings
        }
    }

    private class Harness(
        games: List<GameEntity> = emptyList(),
        records: List<DownloadRecord> = emptyList(),
        ready: PlayStates.Status = PlayStates.Status.Ready,
        scope: CoroutineScope? = null,
        io: CoroutineDispatcher = Dispatchers.IO,
        searchKeys: SearchKeysCache = SearchKeysCache(),
    ) {
        val db = FakeLibraryDb(games.toMutableList(), records.toMutableList())
        val saves = FakeSaveIndex()
        val site = FakeSiteSearch()
        val downloads = FakeHomeDownloads()
        val device = FakeDeviceStatus()
        val launched = mutableListOf<Long>()
        val launchModes = mutableListOf<Boolean>()
        val installed = mutableListOf<Emu>()
        var runtimeOpens = 0
        var launchBehavior: suspend (Long, Boolean, (LaunchStatus?) -> Unit) -> Unit = { id, fresh, report ->
            report(LaunchStatus.progress(id, "Launching…", fresh))
        }
        val rescans = mutableListOf<Boolean>()
        var status: PlayStates.Status = ready
        var readinessCalls = 0
        var readinessError = false
        val store = HomeStore(
            saves = saves,
            site = site,
            downloads = downloads,
            device = device,
            db = db,
            readiness = {
                readinessCalls++
                if (readinessError) throw IllegalStateException("probe failed")
                status
            },
            launcher = { id, fresh, onStatus ->
                launched += id
                launchModes += fresh
                launchBehavior(id, fresh, onStatus)
            },
            beginInstall = { installed += it },
            openRetroArch = { runtimeOpens++; null },
            rescan = { rescans += it },
            scope = scope,
            io = io,
            searchKeys = searchKeys,
        )
    }

    private fun game(
        id: Long,
        title: String = "Game $id",
        playCount: Int = 0,
        lastPlayedMs: Long = 0,
        addedAtMs: Long = 0,
        deletedAtMs: Long = 0,
        sourceRefJson: String? = null,
    ) = GameEntity(
        id = id, title = title, folder = "gc", volumeId = "vol",
        relPath = "gc/$id.iso", fileName = "$id.iso",
        sizeBytes = 1 * gb, mtimeMs = 0, ext = "iso",
        playCount = playCount, lastPlayedMs = lastPlayedMs, addedAtMs = addedAtMs,
        deletedAtMs = deletedAtMs, sourceRefJson = sourceRefJson,
    )

    private fun entry(itemKey: String = "gc/sa2", title: String = "Sonic Adventure 2") = RomEntry(
        ref = SourceRef("acme", itemKey, "https://acme/$itemKey"),
        title = title,
        platformId = "gc",
    )

    private fun job(id: Long, status: String = DownloadEngine.ST_ACTIVE, read: Long = 0) = DownloadEngine.DlSnapshot(
        id = id, title = "Job $id", fileName = "j.zip", status = status,
        read = read, total = 100, error = null, pageUrl = "https://acme/$id",
        transferKind = "ROM",
    )

    @Test fun buildsHomeFromLibrary(): Unit = runBlocking {
        val h = Harness(games = listOf(
            game(1, "Crazy Taxi", playCount = 2, lastPlayedMs = System.currentTimeMillis()),
            game(2, "Metroid Prime", addedAtMs = System.currentTimeMillis()),
        ))
        awaitTrue { h.store.model.value.sections.isNotEmpty() }
        val model = h.store.model.value
        assertEquals(HomeMode.HOME, model.mode)
        assertEquals(listOf(HomeGroup.RESUME, HomeGroup.NEW), model.sections.map { it.group })
        assertEquals(CardKey.local(1), model.defaultKey)
        assertEquals("Search your games", model.header.searchPlaceholder) // no plugin yet
        assertEquals(2, model.header.systemWarnings)
    }

    @Test fun querySwitchesToSearchAndClearReturnsHome(): Unit = runBlocking {
        val h = Harness(games = listOf(game(1, "Sonic Adventure 2")))
        awaitTrue { h.store.model.value.sections.isNotEmpty() }
        h.site.result = { SiteResult.Found(listOf(entry())) }

        h.store.onIntent(HomeIntent.Query("sonic"))
        awaitTrue { h.store.model.value.mode == HomeMode.SEARCH }
        awaitTrue { h.site.searchCalls.isNotEmpty() }
        assertEquals(listOf("sonic"), h.site.searchCalls)
        val sections = h.store.model.value.sections
        assertEquals(listOf(HomeGroup.SEARCH_YOURS, HomeGroup.SEARCH_SITE), sections.map { it.group })

        h.store.onIntent(HomeIntent.ClearSearch)
        awaitTrue { h.store.model.value.mode == HomeMode.HOME }
    }

    @Test fun siteSizesFillInPlace(): Unit = runBlocking {
        val h = Harness(games = listOf(game(1, "Mario")))
        awaitTrue { h.store.model.value.sections.isNotEmpty() }
        h.site.result = { SiteResult.Found(listOf(entry())) }
        h.store.onIntent(HomeIntent.Query("sonic"))

        fun siteCardState(): CardState? = h.store.model.value.sections
            .firstOrNull { it.group == HomeGroup.SEARCH_SITE }
            ?.cards?.firstOrNull { it.key == CardKey.site("acme", "gc/sa2") }?.state

        awaitTrue { siteCardState() != null }
        awaitTrue { siteCardState() is CardState.OnSite && (siteCardState() as CardState.OnSite).size != null }
        // The size lands on the same card (update in place), no new key.
        assertEquals(CardState.OnSite(SizeInfo(1 * gb, "1.0 GB")), siteCardState())
        assertEquals(listOf("gc/sa2"), h.site.sizeCalls)
    }

    @Test fun searchSiteCardDownloadsOnActivate(): Unit = runBlocking {
        val h = Harness(games = listOf(game(1, "Mario")))
        awaitTrue { h.store.model.value.sections.isNotEmpty() }
        h.site.result = { SiteResult.Found(listOf(entry())) }
        h.downloads.start = DownloadStart.Started(55)
        h.store.onIntent(HomeIntent.Query("sonic"))
        awaitTrue {
            h.store.model.value.card(CardKey.site("acme", "gc/sa2")) != null
        }
        h.store.onIntent(HomeIntent.Activate(CardKey.site("acme", "gc/sa2")))
        awaitTrue { h.downloads.calls.isNotEmpty() }
        assertEquals(listOf("site:gc/sa2"), h.downloads.calls)
    }

    @Test fun downloadFromSiteChoiceNavigatesToEntry(): Unit = runBlocking {
        val h = Harness()
        val effects = collect(h)
        h.site.result = { SiteResult.Found(listOf(entry())) }
        h.store.onIntent(HomeIntent.Query("sonic"))
        awaitTrue { h.store.model.value.card(CardKey.site("acme", "gc/sa2")) != null }
        h.downloads.start = DownloadStart.NeedsChoice

        h.store.onIntent(HomeIntent.Activate(CardKey.site("acme", "gc/sa2")))
        awaitTrue { effects.isNotEmpty() }
        val dest = (effects.last() as HomeEffect.Navigate).dest
        assertTrue(dest is HomeDestination.SiteEntry)
        assertEquals("gc/sa2", (dest as HomeDestination.SiteEntry).entry.ref.itemKey)
    }

    @Test fun playLaunchesTheGame(): Unit = runBlocking {
        val h = Harness(games = listOf(game(1, playCount = 1, lastPlayedMs = System.currentTimeMillis())))
        awaitTrue { h.store.model.value.sections.isNotEmpty() }
        h.store.onIntent(HomeIntent.Activate(CardKey.local(1)))
        awaitTrue { h.launched.isNotEmpty() }
        assertEquals(listOf(1L), h.launched)
        awaitTrue { h.store.model.value.status != null }
    }

    @Test fun downloadAgainToastsOnStarted(): Unit = runBlocking {
        val ref = """{"sourceId":"acme","itemKey":"gc/mgs","pageUrl":"https://acme/mgs"}"""
        val h = Harness(games = listOf(
            game(3, "Metal Gear Solid", playCount = 4, lastPlayedMs = System.currentTimeMillis(), deletedAtMs = 1L, sourceRefJson = ref),
        ))
        val effects = collect(h)
        awaitTrue { h.store.model.value.sections.isNotEmpty() }
        val key = CardKey.local(3)
        assertEquals(CardState.OffPhone(SizeInfo(1 * gb, "1.0 GB"), 0), h.store.model.value.card(key)!!.state)

        h.downloads.start = DownloadStart.Started(9)
        h.store.onIntent(HomeIntent.Activate(key))
        awaitTrue { effects.isNotEmpty() }
        assertEquals(listOf("again:3"), h.downloads.calls)
        assertEquals(HomeEffect.Toast(HomeCopy.downloadingToast("Metal Gear Solid", SizeInfo(1 * gb, "1.0 GB"))), effects.last())
    }

    @Test fun downloadingCardTogglesPlayWhenReady(): Unit = runBlocking {
        val h = Harness()
        h.downloads.jobs.value = mapOf(9L to job(9))
        awaitTrue { h.store.model.value.card(CardKey.download(9)) != null }
        h.store.onIntent(HomeIntent.Activate(CardKey.download(9)))
        awaitTrue { h.downloads.calls.isNotEmpty() }
        assertEquals(listOf("toggle:9"), h.downloads.calls)
    }

    @Test fun surpriseRollsAndToastsWhenNothingQualifies(): Unit = runBlocking {
        val ready = Harness(games = listOf(game(2, "Galaga")))
        awaitTrue { ready.store.model.value.sections.isNotEmpty() }
        ready.store.onIntent(HomeIntent.Surprise)
        awaitTrue { ready.store.model.value.mode == HomeMode.SURPRISE }
        assertEquals(
            listOf(HomeGroup.SURPRISE),
            ready.store.model.value.sections.map { it.group },
        )

        val empty = Harness()
        val effects = collect(empty)
        awaitTrue { empty.store.model.value.sections.isEmpty() }
        empty.store.onIntent(HomeIntent.Surprise)
        awaitTrue { effects.isNotEmpty() }
        assertEquals(HomeEffect.Toast(HomeCopy.surpriseNothing), effects.last())
        assertEquals(HomeMode.HOME, empty.store.model.value.mode)
    }

    @Test fun enteredInvalidatesSavesAndScans(): Unit = runBlocking {
        val h = Harness()
        h.store.onIntent(HomeIntent.Entered)
        awaitTrue { h.saves.invalidations > 0 && h.rescans.isNotEmpty() }
        assertEquals(listOf(true), h.rescans)
    }

    @Test fun togetherPillNavigatesToTheHub(): Unit = runBlocking {
        val h = Harness()
        val effects = collect(h)
        h.store.onIntent(HomeIntent.Nav(HomeDestination.Together))
        awaitTrue { effects.isNotEmpty() }
        assertEquals(HomeEffect.Navigate(HomeDestination.Together), effects.last())
    }

    @Test fun navEmitsNavigateAndStatusDismisses(): Unit = runBlocking {
        val h = Harness(games = listOf(game(1, playCount = 1, lastPlayedMs = System.currentTimeMillis())))
        val effects = collect(h)
        awaitTrue { h.store.model.value.sections.isNotEmpty() }

        h.store.onIntent(HomeIntent.Nav(HomeDestination.Downloads))
        awaitTrue { effects.isNotEmpty() }
        assertEquals(HomeEffect.Navigate(HomeDestination.Downloads), effects.last())

        h.launchBehavior = { id, fresh, report ->
            report(launchStatusFor(id, EmuRouter.Outcome.Failed("Try again."), fresh))
        }
        h.store.onIntent(HomeIntent.Activate(CardKey.local(1)))
        awaitTrue { h.store.model.value.status?.busy == false }
        h.store.onIntent(HomeIntent.DismissStatus)
        awaitTrue { h.store.model.value.status == null }
    }

    @Test fun offlineFlipsTheDownloadsBadge(): Unit = runBlocking {
        val h = Harness(games = listOf(game(1, playCount = 1)))
        awaitTrue { h.store.model.value.sections.isNotEmpty() }
        assertNull(h.store.model.value.header.downloadsBadge)
        h.device.online.value = false
        awaitTrue { h.store.model.value.header.downloadsBadge == "offline" }
    }

    private fun TestScope.vh(
        games: List<GameEntity> = emptyList(),
        records: List<DownloadRecord> = emptyList(),
        ready: PlayStates.Status = PlayStates.Status.Ready,
        searchKeys: SearchKeysCache = SearchKeysCache(),
    ) = Harness(
        games = games,
        records = records,
        ready = ready,
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        searchKeys = searchKeys,
    )

    /**
     * Drives virtual time past debounce timers and pending work.
     * `advanceUntilIdle` cannot replace this: coroutines-test 1.8 stops it
     * while only background-scope events remain.
     */
    private fun TestScope.spin(millis: Long = 60_000) {
        testScheduler.advanceTimeBy(millis)
        testScheduler.runCurrent()
    }

    /** A save added while Home is open shows up on Entered. */
    @Test fun enteredRebuildsAndShowsANewSave() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1, lastPlayedMs = 1_000)))
        spin()
        val key = CardKey.local(1)
        assertEquals(CardState.Played(1_000), h.store.model.value.card(key)!!.state)

        h.saves.map[1] = SaveInfo(newestMs = 5_000, count = 1, thumbnail = null)
        h.store.onIntent(HomeIntent.Entered)
        spin()
        assertEquals(CardState.Saved(5_000, 1), h.store.model.value.card(key)!!.state)
    }

    /** The pair is keyed on the trimmed query; compare trimmed. */
    @Test fun trailingSpacesStillShowSiteResults() = runTest {
        val h = vh(games = listOf(game(1, "Sonic")))
        spin()
        h.site.result = { SiteResult.Found(listOf(entry())) }
        h.store.onIntent(HomeIntent.Query("sonic "))
        spin()
        assertEquals(listOf("sonic"), h.site.searchCalls)
        assertNotNull(h.store.model.value.card(CardKey.site("acme", "gc/sa2")))
    }

    /** The old query's size lookups stop when a new query starts. */
    @Test fun switchingQueriesCancelsTheOldSizeLookups() = runTest {
        val h = vh(games = listOf(game(1, "Mario")))
        spin()
        h.site.result = { q ->
            if (q == "a") SiteResult.Found(listOf(entry("g1"), entry("g2"), entry("g3")))
            else SiteResult.NoPlugin
        }
        h.site.sizeDelayMs = 10_000
        h.store.onIntent(HomeIntent.Query("a"))
        testScheduler.advanceTimeBy(500) // 400 ms debounce, then the lookups start
        testScheduler.runCurrent()
        assertEquals(2, h.site.sizeCalls.size) // at most 2 in flight
        assertEquals(emptyList<String>(), h.site.sizeCompleted)

        h.store.onIntent(HomeIntent.Query("b"))
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(11_000) // the old lookups would land at +10 s if still alive
        testScheduler.runCurrent()
        assertEquals(emptyList<String>(), h.site.sizeCompleted)
    }

    /** Connectivity and plugin changes re-run a non-blank search. */
    @Test fun siteSearchRerunsWhenOnlineOrPluginChanges() = runTest {
        val h = vh(games = listOf(game(1, "Sonic")))
        spin()
        h.site.result = { SiteResult.Found(listOf(entry())) }
        h.store.onIntent(HomeIntent.Query("sonic"))
        spin()
        assertEquals(listOf("sonic"), h.site.searchCalls)

        @Suppress("UNCHECKED_CAST")
        (h.site.pluginName as MutableStateFlow<String?>).value = "ROMS-I-Own.com"
        spin()
        assertEquals(listOf("sonic", "sonic"), h.site.searchCalls)

        h.device.online.value = false
        spin()
        assertEquals(listOf("sonic", "sonic", "sonic"), h.site.searchCalls)
    }

    /** A failed search reaches grouping as a failure, not as empty. */
    @Test fun aFailingSiteSearchReachesGroupingAsAFailure() = runTest {
        val h = vh(games = listOf(game(1, "Sonic")))
        spin()
        h.site.result = { throw IllegalStateException("site exploded") }
        h.store.onIntent(HomeIntent.Query("sonic"))
        spin()
        assertEquals(listOf("sonic"), h.site.searchCalls)
        val section = h.store.model.value.sections.first { it.group == HomeGroup.SEARCH_SITE }
        val link = section.cards.single()
        assertEquals(CardKind.QUIET_LINK, link.kind)
        assertNotEquals(CardKey.link(LinkKind.SEARCHING), link.key)
    }

    /** The query flow updates synchronously, before any rebuild. */
    @Test fun queryUpdatesSynchronously() = runTest {
        val h = vh()
        spin()
        h.store.onIntent(HomeIntent.Query("mario"))
        assertEquals("mario", h.store.query.value)
        h.store.onIntent(HomeIntent.ClearSearch)
        assertEquals("", h.store.query.value)
    }

    /** A does nothing while a launch is in progress. */
    @Test fun activateIsIgnoredWhileALaunchIsBusy() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1, lastPlayedMs = 1)))
        h.launchBehavior = { id, fresh, report ->
            report(LaunchStatus.coreInstall(id, CoreInstallProgress("snes9x"), fresh))
            awaitCancellation()
        }
        spin()
        h.store.onIntent(HomeIntent.Activate(CardKey.local(1)))
        spin()
        assertEquals(listOf(1L), h.launched)

        h.store.onIntent(HomeIntent.Activate(CardKey.local(1)))
        spin()
        assertEquals(listOf(1L), h.launched) // busy: ignored

        h.store.onIntent(HomeIntent.DismissStatus)
        h.store.onIntent(HomeIntent.Activate(CardKey.local(1)))
        spin()
        assertEquals(listOf(1L, 1L), h.launched)
    }

    @Test fun pressShowsProgressBeforeDispatchAndRejectsRapidDoubleResume() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1, lastPlayedMs = 1)))
        spin()
        h.store.onIntent(HomeIntent.Activate(CardKey.local(1)))
        val status = h.store.model.value.status!!
        assertEquals("Launching…", status.text)
        assertTrue(status.busy)
        h.store.onIntent(HomeIntent.Activate(CardKey.local(1)))
        testScheduler.runCurrent()
        assertEquals(listOf(1L), h.launched)
    }

    @Test fun homeCancelCancelsTheRealJobAndRetryPreservesResumeMode() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1, lastPlayedMs = 1)))
        var cancelled = 0
        h.launchBehavior = { id, fresh, report ->
            report(LaunchStatus.coreInstall(id, CoreInstallProgress("snes9x", 1_048_576, 4_194_304), fresh))
            try { awaitCancellation() } finally { cancelled++ }
        }
        spin()
        h.store.onIntent(HomeIntent.Activate(CardKey.local(1)))
        testScheduler.runCurrent()
        assertEquals("Downloading 1.0 of 4.0 MB", h.store.model.value.status!!.text)
        assertTrue(h.store.model.value.status!!.canCancel)
        h.store.onIntent(HomeIntent.DismissStatus)
        testScheduler.runCurrent()
        assertEquals(1, cancelled)
        assertEquals(LaunchAction.Retry, h.store.model.value.status!!.action)
        h.store.onIntent(HomeIntent.RetryStatus)
        testScheduler.runCurrent()
        assertEquals(listOf(1L, 1L), h.launched)
        assertEquals(listOf(false, false), h.launchModes)
        h.store.onIntent(HomeIntent.DismissStatus)
        testScheduler.runCurrent()
    }

    @Test fun setupGuidanceOpensRetroArchOnceWithoutInstallingOrSilentlyReplayingResume() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1, lastPlayedMs = 1)))
        h.launchBehavior = { id, fresh, report ->
            report(launchStatusFor(id, EmuRouter.Outcome.NeedsSetup(RetroCoreFetcher.INSTALL_SETUP_GUIDANCE), fresh, retroCore = true))
        }
        spin()
        h.store.onIntent(HomeIntent.Activate(CardKey.local(1)))
        testScheduler.runCurrent()
        assertEquals(RetroCoreFetcher.INSTALL_SETUP_GUIDANCE, h.store.model.value.status!!.text)
        h.store.onIntent(HomeIntent.RetryStatus) // A is the advertised Open RetroArch action.
        testScheduler.runCurrent()
        assertEquals(1, h.runtimeOpens)
        assertTrue(h.installed.isEmpty())
        assertEquals(listOf(1L), h.launched)
        h.store.onIntent(HomeIntent.SecondaryStatus)
        testScheduler.runCurrent()
        assertEquals(listOf(1L, 1L), h.launched)
        assertEquals(listOf(false, false), h.launchModes)
    }

    @Test fun homeStatusActionsRouteDetailsAndInstallationInsteadOfRetrying() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1, lastPlayedMs = 1)))
        val effects = mutableListOf<HomeEffect>()
        backgroundScope.launch { h.store.effects.collect { effects += it } }
        h.launchBehavior = { id, fresh, report ->
            report(launchStatusFor(id, EmuRouter.Outcome.NeedsSetup("Import it first."), fresh))
        }
        spin()
        h.store.onIntent(HomeIntent.Activate(CardKey.local(1)))
        testScheduler.runCurrent()
        h.store.onIntent(HomeIntent.RetryStatus)
        testScheduler.runCurrent()
        assertTrue(effects.contains(HomeEffect.Navigate(HomeDestination.Game(1))))
        assertEquals(listOf(1L), h.launched)
        h.launchBehavior = { id, fresh, report -> report(launchStatusFor(id, EmuRouter.Outcome.MissingEmu(Emu.RETROARCH), fresh)) }
        h.store.onIntent(HomeIntent.Activate(CardKey.local(1)))
        testScheduler.runCurrent()
        h.store.onIntent(HomeIntent.RetryStatus)
        testScheduler.runCurrent()
        assertEquals(listOf(Emu.RETROARCH), h.installed)
        assertTrue(effects.contains(HomeEffect.Navigate(HomeDestination.System)))
        assertEquals(listOf(1L, 1L), h.launched)
    }

    /** SaveInfo is cached per game until saves.invalidate(). */
    @Test fun savesAreLookedUpOnceUntilInvalidated() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1)))
        spin()
        assertEquals(1, h.saves.lookups)
        h.device.online.value = false
        spin()
        assertEquals(1, h.saves.lookups) // cached across rebuilds
        h.store.onIntent(HomeIntent.Entered)
        spin()
        assertEquals(2, h.saves.lookups) // invalidate() refreshes
    }

    /** Readiness is cached per shown game until Entered. */
    @Test fun readinessIsProbedOncePerGameUntilEntered() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1)))
        spin()
        assertEquals(1, h.readinessCalls)
        h.device.online.value = false
        spin()
        assertEquals(1, h.readinessCalls) // cached across rebuilds
        h.store.onIntent(HomeIntent.Entered)
        spin()
        assertEquals(2, h.readinessCalls) // Entered refreshes
    }

    /** The readiness cache follows the game's DB row. */
    @Test fun readinessCacheTracksTheDbRow() {
        var calls = 0
        val cache = ReadinessCache {
            calls++
            PlayStates.Status.Ready
        }
        val g = game(1)
        assertEquals(PlayStates.Status.Ready, cache(g))
        assertEquals(PlayStates.Status.Ready, cache(g))
        assertEquals(1, calls)
        cache(g.copy(playCount = 3)) // row changed: re-probe
        assertEquals(2, calls)
        cache.markStale()
        cache(g.copy(playCount = 3)) // stale: re-checked when shown again
        assertEquals(3, calls)
    }

    /** A broken probe falls back per card, never killing the model. */
    @Test fun aBrokenReadinessProbeFallsBackPerCard() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1)))
        h.readinessError = true
        spin()
        val card = h.store.model.value.card(CardKey.local(1))
        assertNotNull(card)
        assertTrue(card!!.state is CardState.NeedsFix) // the "couldn't check" fallback
    }

    /** A failing probe keeps the game's last known readiness. */
    @Test fun readinessCacheFallsBackToTheLastKnownStatus() {
        var calls = 0
        val cache = ReadinessCache {
            calls++
            if (calls == 1) PlayStates.Status.NeedsSpace else throw IllegalStateException("boom")
        }
        val g = game(1)
        assertEquals(PlayStates.Status.NeedsSpace, cache(g))
        assertEquals(PlayStates.Status.NeedsSpace, cache(g.copy(playCount = 2)))
        assertEquals(2, calls) // the failed probe was not cached
    }

    /** The save cache follows the game's DB row too. */
    @Test fun cachingSaveIndexTracksTheDbRow() {
        val inner = FakeSaveIndex()
        inner.map[1] = SaveInfo(newestMs = 5_000, count = 1, thumbnail = null)
        val cache = CachingSaveIndex(inner)
        val g = game(1)
        assertEquals(SaveInfo(5_000, 1, null), cache.lookup(g))
        assertEquals(SaveInfo(5_000, 1, null), cache.lookup(g))
        assertEquals(1, inner.lookups)
        cache.lookup(g.copy(playCount = 3))
        assertEquals(2, inner.lookups)
        cache.invalidate()
        cache.lookup(g.copy(playCount = 3))
        assertEquals(3, inner.lookups)
        assertEquals(1, inner.invalidations)
    }

    /**
     * Download byte ticks patch the job cards in place — no save
     * stat, no readiness probe, no StatFs — while the visible progress
     * text and the TV channel (position) stay intact.
     */
    @Test fun downloadProgressTicksSkipSavesReadinessAndStatFs() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1)))
        h.downloads.jobs.value = mapOf(9L to job(9))
        spin()
        val key = CardKey.download(9)
        val card0 = h.store.model.value.card(key)!!
        assertEquals(0L, (card0.state as CardState.Downloading).read)
        val saves0 = h.saves.lookups
        val ready0 = h.readinessCalls
        val free0 = h.device.freeCalls
        assertTrue(saves0 > 0 && ready0 > 0 && free0 > 0)

        h.downloads.jobs.value = mapOf(9L to job(9, read = 50))
        spin()
        val card = h.store.model.value.card(key)!!
        val state = card.state as CardState.Downloading
        assertEquals(50L, state.read)
        assertEquals(100L, state.total)
        assertEquals(card0.position, card.position) // TV channel must not collapse
        assertTrue(card.cardLine.startsWith("50")) // progress text updated in place
        assertEquals(saves0, h.saves.lookups)
        assertEquals(ready0, h.readinessCalls)
        assertEquals(free0, h.device.freeCalls)
    }

    /** An upstream pipeline failure keeps the last good model. */
    @Test fun aFailingPipelineKeepsTheLastGoodModel() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1)))
        spin()
        val before = h.store.model.value
        assertTrue(before.sections.isNotEmpty())
        h.device.warningsThrows = true
        h.store.onIntent(HomeIntent.Entered) // the tick recomputes warnings → throws
        spin()
        assertEquals(before.sections, h.store.model.value.sections)
        assertEquals(HomeMode.HOME, h.store.model.value.mode)
    }

    /**
     * One failing warnings probe must not end the pipeline.
     * After the failure, Entered (a save that landed while away) and going
     * offline still change the model.
     */
    @Test fun afterAWarningsFailureEnteredAndOfflineStillChangeTheModel() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1, lastPlayedMs = 1_000)))
        spin()
        assertEquals(CardState.Played(1_000), h.store.model.value.card(CardKey.local(1))!!.state)
        h.device.warningsThrows = true
        h.store.onIntent(HomeIntent.Entered) // the tick's systemWarnings() throws
        spin()

        h.saves.map[1] = SaveInfo(newestMs = 5_000, count = 1, thumbnail = null)
        h.store.onIntent(HomeIntent.Entered)
        spin()
        assertEquals(CardState.Saved(5_000, 1), h.store.model.value.card(CardKey.local(1))!!.state)

        h.device.online.value = false
        spin()
        assertEquals("offline", h.store.model.value.header.downloadsBadge)
    }

    /**
     * The pipeline keeps emitting after an error — the last warning
     * count is kept across the failure, a later tick refreshes it,
     * and download progress patches still land in place.
     */
    @Test fun theModelKeepsEmittingAfterAWarningProbeError() = runTest {
        val h = vh()
        spin()
        assertEquals(2, h.store.model.value.header.systemWarnings)
        h.device.warningsThrows = true
        h.store.onIntent(HomeIntent.Entered)
        spin()
        assertEquals(2, h.store.model.value.header.systemWarnings) // last good count kept

        h.device.warningsThrows = false
        h.device.warnings = 5
        h.store.onIntent(HomeIntent.Entered)
        spin()
        assertEquals(5, h.store.model.value.header.systemWarnings)

        // the progress collector is alive too: a byte patch lands in place.
        h.downloads.jobs.value = mapOf(9L to job(9))
        spin()
        h.downloads.jobs.value = mapOf(9L to job(9, read = 50))
        spin()
        val state = h.store.model.value.card(CardKey.download(9))!!.state as CardState.Downloading
        assertEquals(50L, state.read)
    }

    /**
     * Entered re-checks the shown cards' readiness without
     * clearing the whole cache — a failed refresh keeps the game's last
     * known status instead of degrading the card to "couldn't check".
     */
    @Test fun enteredKeepsTheLastKnownReadinessWhenTheRefreshFails() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1, lastPlayedMs = 1_000)))
        spin()
        assertEquals(CardState.Played(1_000), h.store.model.value.card(CardKey.local(1))!!.state)
        h.readinessError = true
        h.store.onIntent(HomeIntent.Entered)
        spin()
        assertEquals(CardState.Played(1_000), h.store.model.value.card(CardKey.local(1))!!.state)
        assertTrue(h.readinessCalls >= 2) // the shown card was re-checked
    }

    /**
     * With a 3,000-game library a rebuild costs at most 12 readiness
     * calls — the shown cards only. Entered marks entries stale; the
     * rebuild after it re-checks just the shown cards, and a warm
     * rebuild probes nothing.
     */
    @Test fun threeThousandGamesCostAtMostTwelveReadinessCallsPerRebuild() = runTest {
        val now = 1_790_000_000_000L
        val games = (1..3_000).map { i ->
            game(
                i.toLong(),
                title = "Game $i ${"X".repeat(64)}",
                playCount = if (i % 10 == 0) 1 else 0,
                lastPlayedMs = if (i % 10 == 0) now - i else 0,
                addedAtMs = now - i,
            )
        }
        val h = vh(games = games)
        spin()
        assertTrue("first build: ${h.readinessCalls}", h.readinessCalls <= 12)

        var before = h.readinessCalls
        h.store.onIntent(HomeIntent.Entered)
        spin()
        assertTrue("after Entered: ${h.readinessCalls - before}", h.readinessCalls - before <= 12)

        before = h.readinessCalls
        h.device.online.value = false
        spin()
        assertEquals("warm rebuild must probe nothing", 0, h.readinessCalls - before)
    }

    /**
     * Every Roll again and every mode reset bumps `selectionToken`,
     * so the cursor and the TV reconcile to the new pick
     * (HomeScreenModel.selectionToken contract).
     */
    @Test fun rollsAndModeResetsBumpTheSelectionToken() = runTest {
        val h = vh(games = listOf(
            game(1, "Crazy Taxi", playCount = 1, lastPlayedMs = 1_000),
            game(2, "Metroid Prime", addedAtMs = 2_000),
        ))
        spin()
        val t0 = h.store.model.value.selectionToken

        h.store.onIntent(HomeIntent.Surprise) // roll
        spin()
        assertEquals(HomeMode.SURPRISE, h.store.model.value.mode)
        val t1 = h.store.model.value.selectionToken
        assertTrue("a roll must bump the selection token", t1 > t0)

        h.store.onIntent(HomeIntent.Activate(CardKey.invite(InviteKind.ROLL_AGAIN))) // Roll again
        spin()
        assertEquals(HomeMode.SURPRISE, h.store.model.value.mode)
        val t2 = h.store.model.value.selectionToken
        assertTrue("Roll again must bump the selection token", t2 > t1)

        h.store.onIntent(HomeIntent.ExitSurprise) // mode reset to HOME
        spin()
        assertEquals(HomeMode.HOME, h.store.model.value.mode)
        assertTrue("a mode reset must bump the selection token", h.store.model.value.selectionToken > t2)
    }

    /**
     * Ordinary rebuilds (progress, connectivity) keep the token
     * stable — the cursor must not jump on every model update.
     */
    @Test fun ordinaryRebuildsKeepTheSelectionTokenStable() = runTest {
        val h = vh(games = listOf(game(1, playCount = 1, lastPlayedMs = 1)))
        spin()
        val t0 = h.store.model.value.selectionToken
        h.downloads.jobs.value = mapOf(9L to job(9))
        spin()
        h.downloads.jobs.value = mapOf(9L to job(9, read = 50))
        spin()
        h.device.online.value = false
        spin()
        h.store.onIntent(HomeIntent.Entered)
        spin()
        assertEquals(t0, h.store.model.value.selectionToken)
    }

    /**
     * Typing must not re-normalize the library — 3,000 games are
     * normalized twice each, once per game list.
     */
    @Test fun keystrokesDoNotRenormalizeTheLibrary() = runTest {
        var normalized = 0
        val keys = SearchKeysCache { text -> normalized++; HomeGrouping.normalize(text) }
        val games = (1..3_000).map { game(it.toLong(), title = "Game $it ${"X".repeat(64)}") }
        val h = vh(games = games, searchKeys = keys)
        spin()
        assertEquals(2 * 3_000, normalized) // shown + raw key per game, once

        val query = "game x"
        for (i in 1..query.length) {
            h.store.onIntent(HomeIntent.Query(query.take(i)))
            spin()
        }
        assertEquals("keystrokes must not re-normalize the library", 2 * 3_000, normalized)
    }

    /**
     * The store's cached keys actually reach grouping's search — a
     * cached key can match a query its title never contains.
     * Passing no map makes this fail.
     */
    @Test fun theStoresSearchKeysReachGroupingSearch() = runTest {
        val keys = SearchKeysCache { "needle" } // deliberately diverges from the titles
        val h = vh(games = listOf(game(1, "Crazy Taxi")), searchKeys = keys)
        spin()
        h.store.onIntent(HomeIntent.Query("needle"))
        spin()
        val yours = h.store.model.value.sections.first { it.group == HomeGroup.SEARCH_YOURS }
        assertEquals(listOf("Crazy Taxi"), yours.cards.map { it.title })
    }

    /** The default cache keys still match real titles. */
    @Test fun theDefaultSearchKeysStillMatchRealTitles() = runTest {
        val h = vh(games = listOf(game(1, "Crazy Taxi")))
        spin()
        h.store.onIntent(HomeIntent.Query("crazy"))
        spin()
        val yours = h.store.model.value.sections.first { it.group == HomeGroup.SEARCH_YOURS }
        assertEquals(listOf("Crazy Taxi"), yours.cards.map { it.title })
    }

    /** The CHECK_SYSTEMS invite reads the live count. */
    @Test fun theCheckSystemsInviteReadsTheWarningCount() = runTest {
        val h = vh() // no games: the SETUP invites
        spin()
        val invite = h.store.model.value.card(CardKey.invite(InviteKind.CHECK_SYSTEMS))
        assertNotNull(invite)
        val tv = invite!!.tv.content
        assertTrue(tv is TvContent.Notice)
        assertEquals("2 SYSTEMS NEED SETUP", (tv as TvContent.Notice).label)
    }

    /**
     * Progress patches must never step backwards — an out-of-order
     * snapshot keeps the max read until the job's status changes
     * (a genuine restart).
     */
    @Test fun progressPatchesNeverStepBackwardsUntilTheStatusChanges() = runTest {
        val h = vh()
        h.downloads.jobs.value = mapOf(9L to job(9, read = 1_000))
        spin()
        assertEquals(1_000L, (h.store.model.value.card(CardKey.download(9))!!.state as CardState.Downloading).read)

        // an out-of-order patch: same status, fewer bytes read.
        h.downloads.jobs.value = mapOf(9L to job(9, read = 400))
        spin()
        assertEquals(
            1_000L,
            (h.store.model.value.card(CardKey.download(9))!!.state as CardState.Downloading).read,
        )

        // a status change re-seeds: a restart may genuinely rewind.
        h.downloads.jobs.value = mapOf(9L to job(9, status = DownloadEngine.ST_DONE, read = 100))
        spin()
        h.downloads.jobs.value = mapOf(9L to job(9, read = 200))
        spin()
        assertEquals(
            200L,
            (h.store.model.value.card(CardKey.download(9))!!.state as CardState.Downloading).read,
        )
    }
    private fun collect(h: Harness): MutableList<HomeEffect> {
        val out = mutableListOf<HomeEffect>()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        scope.launch { h.store.effects.collect { out += it } }
        return out
    }
}
