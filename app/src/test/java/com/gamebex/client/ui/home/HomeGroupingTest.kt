package com.gamebex.client.ui.home

import com.gamebex.client.data.PlayStates
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.HomeMode
import com.gamebex.client.ui.home.model.InviteKind
import com.gamebex.client.ui.home.model.LinkKind
import com.gamebex.client.ui.home.model.SaveInfo
import com.gamebex.client.ui.home.model.SizeInfo
import com.gamebex.source.RomEntry
import com.gamebex.source.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every grouping rule: membership, order, limits, All tiles, search and modes. */
class HomeGroupingTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L
    private val day = 24 * 60 * min
    private val gb = com.gamebex.client.ui.home.model.HomeCopy.GB

    private val factory = HomeCardFactory { PlayStates.Status.Ready }

    private fun game(
        id: Long,
        title: String = "Game $id",
        playCount: Int = 0,
        lastPlayedMs: Long = 0,
        addedAtMs: Long = 0,
        available: Boolean = true,
        deletedAtMs: Long = 0,
        sourceRefJson: String? = null,
        sourceId: String? = null,
        sourceItemKey: String? = null,
        folder: String = "gc",
    ) = GameEntity(
        id = id, title = title, folder = folder, volumeId = "vol",
        relPath = "gc/$id.iso", fileName = "$id.iso",
        sizeBytes = 1 * gb, mtimeMs = 0, ext = "iso",
        playCount = playCount, lastPlayedMs = lastPlayedMs, addedAtMs = addedAtMs,
        available = available, deletedAtMs = deletedAtMs,
        sourceRefJson = sourceRefJson, sourceId = sourceId, sourceItemKey = sourceItemKey,
    )

    private fun job(
        id: Long,
        status: String = DownloadEngine.ST_ACTIVE,
        transferKind: String = "ROM",
        title: String = "Job $id",
    ) = DownloadEngine.DlSnapshot(
        id = id, title = title, fileName = "j$id.zip", status = status,
        read = 0, total = 100, error = null, pageUrl = "https://acme/$id",
        transferKind = transferKind,
    )

    private fun record(id: Long, destRelPath: String = "gc/$id.iso", title: String = "Job $id") =
        DownloadRecord(
            id = id, pageUrl = "https://acme/$id", title = title,
            destRelPath = destRelPath, volumeId = "vol", bytes = 0,
            status = DownloadEngine.ST_ACTIVE,
        )

    private fun build(
        games: List<GameEntity> = emptyList(),
        saves: Map<Long, SaveInfo> = emptyMap(),
        jobs: Map<Long, DownloadEngine.DlSnapshot> = emptyMap(),
        records: List<DownloadRecord> = emptyList(),
        armed: Set<Long> = emptySet(),
        online: Boolean = true,
        free: Long = 28 * gb,
        siteName: String? = "ROMS-I-Own.com",
        query: String = "",
        site: SiteQuery = SiteQuery.None,
        surprise: GameEntity? = null,
        cards: HomeCardFactory = factory,
    ) = HomeGrouping.build(
        games = games, saves = saves, jobs = jobs, records = records, armed = armed,
        online = online, freeBytes = { free }, siteName = siteName, query = query,
        site = site, surprise = surprise, now = now, cards = cards,
    )

    private fun sectionsOf(result: HomeGrouping.Result) = result.sections.map { it.group }

    @Test fun firstRunWhenNoGamesNoTombstonesNoDownloads() {
        val r = build()
        assertEquals(HomeMode.FIRST_RUN, r.mode)
        assertEquals(listOf(HomeGroup.SETUP), sectionsOf(r))
        val cards = r.sections[0].cards
        assertEquals(
            listOf(InviteKind.ADD_PLUGIN, InviteKind.SCAN_PHONE, InviteKind.CHECK_SYSTEMS),
            cards.map { (it.key as CardKey).value.removePrefix("invite:") }.map { InviteKind.valueOf(it) },
        )
        assertEquals(CardKey.invite(InviteKind.ADD_PLUGIN), r.defaultKey)
    }

    @Test fun firstRunBlockedByJobOrTombstone() {
        val withJob = build(jobs = mapOf(1L to job(1)))
        assertEquals(HomeMode.HOME, withJob.mode)
        assertEquals(listOf(HomeGroup.DOWNLOADING), sectionsOf(withJob))

        val withTombstone = build(
            games = listOf(game(1, playCount = 1, deletedAtMs = now, sourceRefJson = "{}")),
        )
        assertEquals(HomeMode.HOME, withTombstone.mode)
    }

    @Test fun homeSectionOrderAndOmissions() {
        val games = listOf(
            game(1, playCount = 1, lastPlayedMs = now - day),
            game(2, addedAtMs = now - day),
        )
        val r = build(games = games, jobs = mapOf(9L to job(9)))
        assertEquals(listOf(HomeGroup.RESUME, HomeGroup.DOWNLOADING, HomeGroup.NEW), sectionsOf(r))

        // NEW omitted when empty; DOWNLOADING omitted without jobs.
        val playedOnly = build(games = listOf(game(1, playCount = 1)))
        assertEquals(listOf(HomeGroup.RESUME), sectionsOf(playedOnly))
    }

    @Test fun resumeMembership() {
        val ref = """{"sourceId":"acme","itemKey":"gc/3","pageUrl":"https://acme/3"}"""
        val games = listOf(
            game(1, playCount = 2),                       // in
            game(2, playCount = 0),                       // out: never played
            game(3, playCount = 1, available = false, deletedAtMs = now, sourceRefJson = ref), // in: tombstone
            game(4, playCount = 1, available = false, deletedAtMs = now), // out: tombstone without source ref
            game(5, playCount = 1, available = false),    // in: played, file gone (ejected SD card)
        )
        val r = build(games = games)
        val resume = r.sections.first { it.group == HomeGroup.RESUME }.cards.map { it.key }
        assertEquals(listOf(CardKey.local(1), CardKey.local(3), CardKey.local(5)), resume)
    }

    @Test fun resumeSortsByNewestActivity() {
        val games = listOf(
            game(1, playCount = 1, lastPlayedMs = now - 5 * day),
            game(2, playCount = 1, lastPlayedMs = now - day),
            game(3, playCount = 1, lastPlayedMs = now - 9 * day),
        )
        val saves = mapOf(3L to SaveInfo(now - min, 1, null)) // save wins over old play
        val r = build(games = games, saves = saves)
        val resume = r.sections.first { it.group == HomeGroup.RESUME }.cards.map { it.key }
        assertEquals(listOf(CardKey.local(3), CardKey.local(2), CardKey.local(1)), resume)
    }

    @Test fun resumeLimitsThreePlusAllTile() {
        val games = (1L..5L).map { game(it, playCount = 1, lastPlayedMs = now - it * min) }
        val r = build(games = games)
        val cards = r.sections.first { it.group == HomeGroup.RESUME }.cards
        assertEquals(4, cards.size) // 3 + All tile
        assertEquals(CardKind.ALL_TILE, cards.last().kind)
        assertEquals("All 5", cards.last().title)
        assertEquals("2 more ▸", cards.last().body)

        val four = build(games = games.take(4))
        val fourCards = four.sections.first { it.group == HomeGroup.RESUME }.cards
        assertEquals(4, fourCards.size)
        assertTrue(fourCards.none { it.kind == CardKind.ALL_TILE })
    }

    @Test fun newGroupFiltersSortsAndLimits() {
        val games = listOf(
            game(1, addedAtMs = now - min),
            game(2, addedAtMs = now - day),
            game(3, playCount = 1, addedAtMs = now),          // out: played
            game(4, addedAtMs = now - 3 * day, deletedAtMs = now), // out: tombstone
            game(5, addedAtMs = now - 2 * day, available = false), // out: not on phone
        )
        val r = build(games = games)
        val newCards = r.sections.first { it.group == HomeGroup.NEW }.cards
        assertEquals(listOf(CardKey.local(1), CardKey.local(2)), newCards.map { it.key })
    }

    @Test fun downloadingMembershipOrderAndLimits() {
        val jobs = mapOf(
            1L to job(1, DownloadEngine.ST_PAUSED),
            2L to job(2, DownloadEngine.ST_ACTIVE),
            3L to job(3, DownloadEngine.ST_QUEUED),
            4L to job(4, DownloadEngine.ST_DONE),      // out
            5L to job(5, DownloadEngine.ST_INGEST),    // out: file already complete
            6L to job(6, transferKind = "ART"),        // out: not a ROM
        )
        val r = build(jobs = jobs, records = listOf(record(2), record(3), record(1)))
        val section = r.sections.first { it.group == HomeGroup.DOWNLOADING }
        // 3 ROM jobs > 2 -> one card + All tile; active first.
        assertEquals(2, section.cards.size)
        assertEquals(CardKey.download(2), section.cards[0].key)
        assertEquals(CardKind.ALL_TILE, section.cards[1].kind)
        assertEquals("3 · from ROMS-I-Own.com", section.detail)

        val two = build(jobs = mapOf(1L to job(1, DownloadEngine.ST_ACTIVE), 2L to job(2, DownloadEngine.ST_QUEUED)))
        val twoCards = two.sections.first { it.group == HomeGroup.DOWNLOADING }.cards
        // The row always ends with the All downloads tile.
        assertEquals(
            listOf(CardKey.download(1), CardKey.download(2), CardKey.all(HomeGroup.DOWNLOADING)),
            twoCards.map { it.key },
        )
    }

    @Test fun downloadingProblemJobsSortLast() {
        val jobs = mapOf(
            1L to job(1, DownloadEngine.ST_FAILED),
            2L to job(2, DownloadEngine.ST_PAUSED),
            3L to job(3, DownloadEngine.ST_ACTIVE),
        )
        // Queue order is independent of the visibility limit. Paused and
        // problem jobs share the last bucket; ties break on id.
        assertEquals(
            listOf(3L, 1L, 2L),
            HomeGrouping.downloadQueue(jobs).map { it.id },
        )
    }

    @Test fun jobCardsCarryTheLandingGame() {
        val games = listOf(game(7))
        val jobs = mapOf(9L to job(9))
        val r = build(games = games, jobs = jobs, records = listOf(record(9, destRelPath = "gc/7.iso")))
        val card = r.sections.first { it.group == HomeGroup.DOWNLOADING }.cards[0]
        assertEquals(7L, card.gameId)
        assertEquals(9L, card.downloadId)
    }

    @Test fun searchRanksPrefixThenWordStartThenSubstring() {
        val games = listOf(
            game(1, "Sonic Adventure 2", addedAtMs = now - day),
            game(2, "Amazing Sonic", addedAtMs = now),
            game(3, "Sonic", addedAtMs = now),
            game(4, "Knuckles", addedAtMs = now),
        )
        val r = build(games = games, query = "sonic")
        assertEquals(HomeMode.SEARCH, r.mode)
        val yours = r.sections.first { it.group == HomeGroup.SEARCH_YOURS }.cards.map { it.key }
        // Prefix: 3 then 1 (recency), then word/substring: 2.
        assertEquals(listOf(CardKey.local(3), CardKey.local(1), CardKey.local(2)), yours)
    }

    @Test fun searchNormalizationStripsPunctuation() {
        val games = listOf(game(1, "The Simpsons: Hit & Run"))
        val r = build(games = games, query = "simpsons hit run")
        assertEquals(listOf(CardKey.local(1)), r.sections.first { it.group == HomeGroup.SEARCH_YOURS }.cards.map { it.key })
    }

    @Test fun searchYoursIncludesTombstonesAndCapsAtSix() {
        val games = (1L..8L).map { game(it, "Sonic $it", deletedAtMs = if (it == 8L) now else 0) } +
            game(9, "Sonic 9", deletedAtMs = now, sourceRefJson = "{}")
        val r = build(games = games, query = "sonic")
        val yours = r.sections.first { it.group == HomeGroup.SEARCH_YOURS }.cards
        assertEquals(6, yours.size)
    }

    @Test fun searchSiteDedupesLibraryEntries() {
        val games = listOf(game(1, "Sonic Adventure 2", sourceId = "acme", sourceItemKey = "gc/sa2"))
        val entries = listOf(
            hit("gc/sa2", "Sonic Adventure 2"), // in the library -> dropped
            hit("md/s2", "Sonic 2"),
        )
        val r = build(games = games, query = "sonic", site = SiteQuery.Found(entries))
        val site = r.sections.first { it.group == HomeGroup.SEARCH_SITE }.cards
        assertEquals(
            listOf(CardKey.site("acme", "md/s2"), CardKey.link(LinkKind.SEE_ALL_ON_SITE)),
            site.map { it.key },
        )
    }

    @Test fun searchSiteLinks() {
        val q = "sonic"
        fun siteCards(site: SiteQuery) = build(query = q, site = site)
            .sections.first { it.group == HomeGroup.SEARCH_SITE }.cards.map { it.key }

        assertEquals(listOf(CardKey.link(LinkKind.SEARCHING)), siteCards(SiteQuery.Searching))
        assertEquals(listOf(CardKey.link(LinkKind.SEARCHING)), siteCards(SiteQuery.None))
        assertEquals(listOf(CardKey.link(LinkKind.NO_PLUGIN_HINT)), siteCards(SiteQuery.NoPlugin))
        assertEquals(listOf(CardKey.link(LinkKind.OFFLINE_NOTE)), siteCards(SiteQuery.Offline))
        assertEquals(listOf(CardKey.link(LinkKind.SEARCH_FAILED)), siteCards(SiteQuery.Failed("boom")))
        assertEquals(listOf(CardKey.link(LinkKind.NOTHING_FOUND)), siteCards(SiteQuery.Found(emptyList())))

        // Page 1 all in the library -> just the "see all" link, not "nothing found".
        val games = listOf(game(1, sourceId = "acme", sourceItemKey = "gc/sa2"))
        val allOwned = SiteQuery.Found(listOf(hit("gc/sa2", "Sonic Adventure 2")))
        val cards = build(games = games, query = q, site = allOwned)
            .sections.first { it.group == HomeGroup.SEARCH_SITE }.cards
        assertEquals(listOf(CardKey.link(LinkKind.SEE_ALL_ON_SITE)), cards.map { it.key })
    }

    @Test fun searchSiteCapsAtEightWithSeeAll() {
        val entries = (1..10).map { hit("gc/$it", "Sonic $it") }
        val r = build(query = "sonic", site = SiteQuery.Found(entries))
        val cards = r.sections.first { it.group == HomeGroup.SEARCH_SITE }.cards
        assertEquals(9, cards.size) // 8 + SEE_ALL_ON_SITE
        assertEquals(CardKey.link(LinkKind.SEE_ALL_ON_SITE), cards.last().key)
    }

    @Test fun searchYoursSectionOmittedWhenEmpty() {
        val r = build(games = listOf(game(1, "Mario")), query = "sonic", site = SiteQuery.Found(listOf(hit("gc/1", "Sonic"))))
        assertEquals(listOf(HomeGroup.SEARCH_SITE), sectionsOf(r))
    }

    @Test fun surpriseModeHoldsOneGameAndRollAgain() {
        val pick = game(4, "Galaga", addedAtMs = now - day)
        val r = build(
            games = listOf(game(1, playCount = 1), game(2, addedAtMs = now - min), pick),
            jobs = mapOf(9L to job(9)),
            surprise = pick,
        )
        assertEquals(HomeMode.SURPRISE, r.mode)
        // The normal HOME sections render below the SURPRISE row.
        assertEquals(
            listOf(HomeGroup.SURPRISE, HomeGroup.RESUME, HomeGroup.DOWNLOADING, HomeGroup.NEW),
            sectionsOf(r),
        )
        val cards = r.sections[0].cards
        // The picked game leads (the TV shows it) and Roll again is
        // a half-width card beside it.
        assertEquals(listOf(CardKey.local(4), CardKey.invite(InviteKind.ROLL_AGAIN)), cards.map { it.key })
        assertEquals(1, cards[1].span)
        assertEquals(CardKey.local(4), r.defaultKey)
        // The pick is excluded from the sections below: grid keys stay unique.
        assertEquals(1, r.sections.flatMap { it.cards }.count { it.key == CardKey.local(4) })
    }

    @Test fun pickSurprisePrefersUnplayedAndSkipsLast() {
        val games = listOf(
            game(1, playCount = 3),
            game(2, playCount = 0),
            game(3, playCount = 0),
        )
        // Deterministic: exclude everything until the preferred pool is empty.
        val pick = HomeGrouping.pickSurprise(games, isReady = { true }, excludeId = null, random = seeded())
        assertTrue(pick!!.playCount == 0)

        val onlyLast = HomeGrouping.pickSurprise(games, isReady = { it.id == 2L }, excludeId = 2, random = seeded())
        assertNull(onlyLast)
    }

    @Test fun pickSurpriseNeedsReadiness() {
        val games = listOf(game(1), game(2))
        assertNull(HomeGrouping.pickSurprise(games, isReady = { false }, excludeId = null, random = seeded()))
    }

    @Test fun pickSurpriseProbesAtMostTwelve() {
        var probes = 0
        val games = (1L..30L).map { game(it) }
        val pick = HomeGrouping.pickSurprise(games, isReady = { probes++; false }, excludeId = null, random = seeded())
        assertNull(pick)
        assertEquals(12, probes)
    }

    @Test fun defaultKeyPrefersResumeThenDownloadingThenNewThenInvite() {
        val games = listOf(
            game(1, playCount = 1, lastPlayedMs = now - day),
            game(2, addedAtMs = now - day),
        )
        val both = build(games = games, jobs = mapOf(9L to job(9)))
        assertEquals(CardKey.local(1), both.defaultKey)

        val noResume = build(games = listOf(game(2, addedAtMs = now - day)), jobs = mapOf(9L to job(9)))
        assertEquals(CardKey.download(9), noResume.defaultKey)

        val onlyNew = build(games = listOf(game(2, addedAtMs = now - day)))
        assertEquals(CardKey.local(2), onlyNew.defaultKey)
    }

    @Test fun jobStateMapping() {
        val armed = emptySet<Long>()
        assertEquals(
            CardState.Queued(armed = false),
            factory.jobState(job(1, DownloadEngine.ST_QUEUED), armed = false, etaSec = null),
        )
        assertEquals(
            CardState.Downloading(0, 100, 5, paused = false, armed = true),
            factory.jobState(job(1, DownloadEngine.ST_ACTIVE), armed = true, etaSec = 5),
        )
        assertEquals(
            CardState.Downloading(0, 100, null, paused = true, armed = false),
            factory.jobState(job(1, DownloadEngine.ST_PARKED_SD), armed = false, etaSec = 5),
        )
        val failed = factory.jobState(
            job(1, DownloadEngine.ST_FAILED).copy(error = "Connection lost."),
            armed = false, etaSec = null,
        )
        assertEquals(CardState.DownloadProblem("Connection lost.", verify = false), failed)
        val assist = factory.jobState(job(1, DownloadEngine.ST_NEEDS_ASSIST), armed = false, etaSec = null)
        assertEquals(true, (assist as CardState.DownloadProblem).verify)
        val gone = factory.jobState(job(1, DownloadEngine.ST_SOURCE_UNAVAILABLE), armed = false, etaSec = null)
        assertEquals(
            CardState.DownloadProblem(com.gamebex.client.ui.home.model.HomeCopy.sourceGone, verify = false),
            gone,
        )
    }

    @Test fun offlineTombstoneBecomesWaitingForConnection() {
        val g = game(1, playCount = 1, deletedAtMs = now, sourceRefJson = "{}")
        val r = build(games = listOf(g), online = false)
        val card = r.sections.first { it.group == HomeGroup.RESUME }.cards[0]
        assertEquals(CardState.WaitingForConnection, card.state)
    }

    @Test fun firstRunIgnoresFinishedCleanIngestAndArtJobs() {
        val jobs = mapOf(
            1L to job(1, DownloadEngine.ST_DONE),   // finished
            2L to job(2, DownloadEngine.ST_INGEST), // file complete, arriving
            3L to job(3, transferKind = "ART"),     // not a ROM
        )
        val r = build(jobs = jobs)
        assertEquals(HomeMode.FIRST_RUN, r.mode)
    }

    @Test fun emptyHomeFallsBackToTheSetupInvites() {
        // A tombstone without a source ref can never come back: HOME would
        // have no sections. It must still show the SETUP invites.
        val r = build(games = listOf(game(1, playCount = 1, available = false, deletedAtMs = now)))
        assertEquals(HomeMode.HOME, r.mode)
        assertEquals(listOf(HomeGroup.SETUP), sectionsOf(r))
        assertEquals(CardKey.invite(InviteKind.ADD_PLUGIN), r.defaultKey)
    }

    @Test fun searchFindsAvailableRedownloadableTombstonesAndEjectedSdGames() {
        val ref = """{"sourceId":"acme","itemKey":"gc/3","pageUrl":"https://acme/3"}"""
        val games = listOf(
            game(1, "Sonic One"),                                                    // in: available
            game(2, "Sonic Two", available = false, deletedAtMs = now),              // out: dead tombstone
            game(3, "Sonic Three", available = false, deletedAtMs = now, sourceRefJson = ref), // in
            game(4, "Sonic Four", available = false),                                // out: never played, not on phone
            game(5, "Sonic Five", playCount = 1, available = false),                 // in: played, ejected SD card
        )
        val r = build(games = games, query = "sonic")
        val yours = r.sections.first { it.group == HomeGroup.SEARCH_YOURS }.cards.map { it.key }
        assertEquals(listOf(CardKey.local(1), CardKey.local(3), CardKey.local(5)), yours)
    }

    @Test fun searchNormalizesAccents() {
        val games = listOf(game(1, "Pokémon Snap"), game(2, "Pokemon Stadium"))
        // "pokemon" matches "Pokémon Snap" (NFD + strip accents).
        assertEquals(
            listOf(CardKey.local(1)),
            build(games = games, query = "pokemon snap").sections
                .first { it.group == HomeGroup.SEARCH_YOURS }.cards.map { it.key },
        )
        assertEquals(
            2,
            build(games = games, query = "pokemon").sections
                .first { it.group == HomeGroup.SEARCH_YOURS }.cards.size,
        )
    }

    @Test fun failedSiteSearchShowsTheErrorLinkNotNothingFound() {
        val r = build(query = "sonic", site = SiteQuery.Failed("The site timed out."))
        val section = r.sections.first { it.group == HomeGroup.SEARCH_SITE }
        // No "first page · not on your phone" for a failed search.
        assertNull(section.detail)
        val card = section.cards.single()
        assertEquals(CardKey.link(LinkKind.SEARCH_FAILED), card.key)
        assertEquals("Couldn't search ROMS-I-Own.com", card.title)
        assertEquals("The site timed out.", card.body)
    }

    @Test fun noPluginSiteDetailDoesNotClaimFirstPage() {
        val r = build(query = "sonic", site = SiteQuery.NoPlugin)
        val section = r.sections.first { it.group == HomeGroup.SEARCH_SITE }
        assertNull(section.detail)
    }

    @Test fun ingestFailureIsAProblemJob() {
        val failed = job(1, DownloadEngine.ST_INGEST)
            .copy(error = "Saved, waiting for library retry.")
        // The failure shows in the queue as a DownloadProblem.
        assertEquals(listOf(1L), HomeGrouping.downloadQueue(mapOf(1L to failed)).map { it.id })
        assertEquals(
            CardState.DownloadProblem("Saved, waiting for library retry.", verify = false),
            factory.jobState(failed, armed = false, etaSec = null),
        )
        // A clean ingest is invisible: the file is complete.
        assertTrue(HomeGrouping.downloadQueue(mapOf(2L to job(2, DownloadEngine.ST_INGEST))).isEmpty())
    }

    @Test fun newGroupSortsBlockedRouteGamesLastAndNeverLeads() {
        // "Can't start" comes from the platform route (ps3 is
        // Route.Blocked, psvita Route.GuidedInstall). Readiness stays Ready
        // for every game and the sort still moves them last — the sort never
        // depends on readiness.
        val counting = HomeCardFactory { PlayStates.Status.Ready }
        val games = listOf(
            game(1, addedAtMs = now - day),
            game(2, addedAtMs = now, folder = "ps3"),              // newest, but Route.Blocked
            game(3, addedAtMs = now - min),
            game(4, addedAtMs = now - min / 2, folder = "psvita"), // Route.GuidedInstall
        )
        val r = build(games = games, cards = counting)
        val newCards = r.sections.first { it.group == HomeGroup.NEW }.cards
        assertEquals(
            listOf(CardKey.local(3), CardKey.local(1), CardKey.local(2), CardKey.local(4)),
            newCards.map { it.key },
        )
        assertEquals(CardKey.local(3), r.defaultKey)
    }

    @Test fun groupingCallsReadinessOnlyForShownCards() {
        var probes = 0
        val counting = HomeCardFactory { probes++; PlayStates.Status.Ready }
        val games = (1L..3000L).map { game(it, addedAtMs = now - it) }
        val r = build(games = games, cards = counting)
        val shownGameCards = r.sections.flatMap { it.cards }.count { it.kind == CardKind.GAME }
        // Every readiness call belongs to a card that is actually shown.
        assertEquals(shownGameCards, probes)
        assertTrue("readiness probes=$probes for shown=$shownGameCards", probes <= 12)
    }

    @Test fun playedGameOnAnEjectedSdCardStaysInResumeAsUnavailable() {
        val sd = game(7, "On removed SD", playCount = 1, lastPlayedMs = now - day, available = false)
        val r = build(games = listOf(sd))
        assertEquals(HomeMode.HOME, r.mode)
        val card = r.sections.first { it.group == HomeGroup.RESUME }.cards
            .first { it.key == CardKey.local(7) }
        assertEquals(CardState.Unavailable(HomeCopy.needsVolume), card.state)
        assertEquals(HomeCopy.needsVolume, card.cardLine)
        assertEquals(HomeAction.Go(HomeDestination.Game(7)), card.primary)
        assertEquals("Details", card.primaryLabel)
    }

    @Test fun futureTimestampsSortAsNowNotAfterIt() {
        // A future timestamp reads "just now" and sorts as now,
        // never above a game at now by however far ahead it points.
        val resumeLib = listOf(
            game(1, playCount = 1, lastPlayedMs = now),
            game(2, playCount = 1, lastPlayedMs = now + 3 * day),
        )
        assertEquals(
            listOf(CardKey.local(1), CardKey.local(2)),
            build(games = resumeLib).sections.first { it.group == HomeGroup.RESUME }.cards.map { it.key },
        )
        val newLib = listOf(
            game(3, addedAtMs = now),
            game(4, addedAtMs = now + 400 * day),
        )
        assertEquals(
            listOf(CardKey.local(3), CardKey.local(4)),
            build(games = newLib).sections.first { it.group == HomeGroup.NEW }.cards.map { it.key },
        )
    }

    @Test fun searchUsesTheStoresSearchKeysAndFallsBackPerGame() {
        // Keystrokes match against the store's cached normalized
        // titles (SearchKeys), never re-normalize the library.
        val games = listOf(game(1, "Sonic One"), game(2, "Knuckles Two"))
        val keys = mapOf(1L to SearchKeys(shown = "cached hit", raw = "cached hit"))
        assertEquals(
            listOf(1L),
            HomeGrouping.searchLocal(games, emptyMap(), "cached hit", searchKeys = keys).map { it.id },
        )
        // A game the cache doesn't know is normalized on the spot.
        assertEquals(
            listOf(2L),
            HomeGrouping.searchLocal(games, emptyMap(), "knuckles", searchKeys = keys).map { it.id },
        )
    }

    @Test fun newGroupFillsTheScreenWhenResumeIsEmpty() {
        val games = (1L..9L).map { game(it, addedAtMs = now - it) }
        val cards = build(games = games).sections.first { it.group == HomeGroup.NEW }.cards
        assertEquals(8, cards.size) // up to 7 cards plus All
        assertEquals(CardKind.ALL_TILE, cards.last().kind)
        assertEquals("All 9", cards.last().title)

        // A small library shows every game with no All tile.
        val fewCards = build(games = games.take(3)).sections.first { it.group == HomeGroup.NEW }.cards
        assertEquals(3, fewCards.size)
        assertTrue(fewCards.none { it.kind == CardKind.ALL_TILE })

        // With RESUME present the classic 3 + All rule stays.
        val withResume = build(
            games = listOf(game(10, playCount = 1, lastPlayedMs = now)) + games,
        ).sections.first { it.group == HomeGroup.NEW }.cards
        assertEquals(4, withResume.size)
        assertEquals(CardKind.ALL_TILE, withResume.last().kind)
    }

    @Test fun downloadingAlwaysEndsWithTheAllDownloadsTile() {
        val cards = build(jobs = mapOf(1L to job(1)))
            .sections.first { it.group == HomeGroup.DOWNLOADING }.cards
        assertEquals(
            listOf(CardKey.download(1), CardKey.all(HomeGroup.DOWNLOADING)),
            cards.map { it.key },
        )
        assertEquals("All downloads", cards.last().title)
    }

    @Test fun onlyTombstonesLibraryShowsResume() {
        val ref = """{"sourceId":"acme","itemKey":"gc/1","pageUrl":"https://acme/1"}"""
        val r = build(games = listOf(game(1, playCount = 1, available = false, deletedAtMs = now, sourceRefJson = ref)))
        assertEquals(listOf(HomeGroup.RESUME), sectionsOf(r))
        val card = r.sections[0].cards[0]
        assertTrue("tombstone shows as OffPhone", card.state is CardState.OffPhone)
        assertEquals(CardKey.local(1), r.defaultKey)
    }

    @Test fun onlyDownloadsLibraryShowsTheQueue() {
        val r = build(jobs = mapOf(9L to job(9)))
        assertEquals(listOf(HomeGroup.DOWNLOADING), sectionsOf(r))
        assertEquals(CardKey.download(9), r.defaultKey)
    }

    @Test fun oneGameLibraryIsFocusable() {
        val r = build(games = listOf(game(1, addedAtMs = now)))
        assertEquals(listOf(HomeGroup.NEW), sectionsOf(r))
        assertEquals(listOf(CardKey.local(1)), r.sections[0].cards.map { it.key })
        assertEquals(CardKey.local(1), r.defaultKey)
    }

    @Test fun duplicateSiteEntriesCollapseToUniqueKeys() {
        val entries = listOf(hit("gc/a", "Sonic A"), hit("gc/b", "Sonic B"), hit("gc/a", "Sonic A"))
        val r = build(query = "sonic", site = SiteQuery.Found(entries))
        val keys = r.sections.first { it.group == HomeGroup.SEARCH_SITE }.cards.map { it.key }
        assertEquals(keys.size, keys.toSet().size) // duplicate keys crash the grid
        assertEquals(
            listOf(CardKey.site("acme", "gc/a"), CardKey.site("acme", "gc/b"), CardKey.link(LinkKind.SEE_ALL_ON_SITE)),
            keys,
        )
    }

    private fun hit(itemKey: String, title: String) = SiteHit(
        entry = RomEntry(
            ref = SourceRef("acme", itemKey, "https://acme/$itemKey"),
            title = title,
            platformId = "gc",
        ),
        size = SizeInfo(gb, "1.0 GB"),
    )

    private fun seeded() = kotlin.random.Random(42)
}
