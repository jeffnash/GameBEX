package com.gamebex.client.ui.home

import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.Route
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.ui.home.model.ArtRef
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.HomeMode
import com.gamebex.client.ui.home.model.HomeScreenModel
import com.gamebex.client.ui.home.model.HomeSection
import com.gamebex.client.ui.home.model.InviteKind
import com.gamebex.client.ui.home.model.LinkKind
import com.gamebex.client.ui.home.model.SaveInfo
import com.gamebex.client.ui.home.model.SizeInfo
import com.gamebex.source.RomEntry
import kotlin.random.Random

/** One site search hit plus its default-download size (null until it lands). */
data class SiteHit(val entry: RomEntry, val size: SizeInfo?)

/**
 * Pre-normalized search strings for one game: [shown] is the
 * normalized display title, [raw] the normalized raw title. The store caches
 * these per game-list version so keystrokes never re-normalize the library.
 */
data class SearchKeys(val shown: String, val raw: String)

/** The state of the site side of SEARCH. */
sealed interface SiteQuery {
    data object None : SiteQuery
    data object Searching : SiteQuery
    data object NoPlugin : SiteQuery
    data object Offline : SiteQuery
    data class Found(val entries: List<SiteHit>) : SiteQuery
    data class Failed(val message: String) : SiteQuery
}

/**
 * The pure Home grouping. No Android types:
 * fully JVM-testable. [build] turns library rows, saves, engine jobs and
 * site results into the sections, mode and default key of
 * [HomeScreenModel]; card building is delegated to [HomeCardFactory].
 */
object HomeGrouping {

    /** At most this many SEARCH_YOURS cards. */
    const val SEARCH_YOURS_LIMIT = 6

    /** At most this many SEARCH_SITE cards. */
    const val SEARCH_SITE_LIMIT = 8

    /** How many cards a group shows before its All tile takes over. */
    private const val GAME_VISIBLE = 3
    private const val GAME_ALL_THRESHOLD = 4
    private const val JOB_VISIBLE = 1
    private const val JOB_ALL_THRESHOLD = 2

    /** With RESUME empty, NEW fills the screen (up to 7 cards + All). */
    private const val GAME_FILL = 7
    private const val GAME_FILL_THRESHOLD = 8

    private const val TRANSFER_ROM = "ROM"

    data class Result(
        val mode: HomeMode,
        val sections: List<HomeSection>,
        val defaultKey: CardKey?,
    )

    /**
     * Inputs: games, a save map, jobs, records,
     * armed ids, online, free bytes, site name, query, site search state,
     * the surprise pick and `now`. [etaSec] resolves a job's ETA.
     */
    fun build(
        games: List<GameEntity>,
        saves: Map<Long, SaveInfo>,
        jobs: Map<Long, DownloadEngine.DlSnapshot>,
        records: List<DownloadRecord>,
        armed: Set<Long>,
        online: Boolean,
        freeBytes: (String?) -> Long?,
        siteName: String?,
        query: String,
        site: SiteQuery,
        surprise: GameEntity?,
        now: Long,
        cards: HomeCardFactory,
        etaSec: (DownloadEngine.DlSnapshot) -> Long? = { null },
        /** Systems that have games and are not Ready (CHECK_SYSTEMS notice). */
        systemWarnings: Int = 0,
        /**
         * Normalized titles per game id, cached by the store
         * per game-list version (rebuilt on every games emission). Missing
         * ids fall back to normalizing on the spot.
         */
        searchKeys: Map<Long, SearchKeys> = emptyMap(),
    ): Result {
        val q = query.trim()
        // A played game whose file is gone (ejected SD card) is still
        // the user's library — the phone is not in first run.
        val firstRun = downloadQueue(jobs).isEmpty() &&
            games.none { it.available || it.deletedAtMs > 0 || it.playCount > 0 }
        val mode = when {
            q.isNotBlank() -> HomeMode.SEARCH
            surprise != null -> HomeMode.SURPRISE
            firstRun -> HomeMode.FIRST_RUN
            else -> HomeMode.HOME
        }
        var sections = when (mode) {
            HomeMode.FIRST_RUN -> setupSection(siteName, cards, systemWarnings)
            HomeMode.SURPRISE -> {
                // SURPRISE first (the pick leads and owns the default), then
                // the normal HOME sections below it. The pick is excluded
                // from those to keep every CardKey unique.
                val pick = surprise!!
                listOf(surpriseSection(pick, saves, online, freeBytes, siteName, now, cards)) +
                    homeSections(
                        games.filter { it.id != pick.id }, saves, jobs, records, armed,
                        online, freeBytes, siteName, now, cards, etaSec,
                    )
            }
            HomeMode.SEARCH ->
                searchSections(games, saves, q, site, online, freeBytes, siteName, now, cards, searchKeys)
            HomeMode.HOME ->
                homeSections(games, saves, jobs, records, armed, online, freeBytes, siteName, now, cards, etaSec)
        }
        // HOME must never be empty or unfocusable: no sections -> SETUP invites.
        if (sections.isEmpty()) sections = setupSection(siteName, cards, systemWarnings)
        return Result(mode, positioned(sections), defaultKeyOf(sections))
    }

    /** Flat 0-based position for every card, in section order (TV channel = position + 1). */
    private fun positioned(sections: List<HomeSection>): List<HomeSection> {
        var next = 0
        return sections.map { s -> s.copy(cards = s.cards.map { it.copy(position = next++) }) }
    }

    /** Default: first game card in section order, else first invite. */
    fun defaultKeyOf(sections: List<HomeSection>): CardKey? {
        val cards = sections.flatMap { it.cards }
        return cards.firstOrNull { it.kind == CardKind.GAME }?.key
            ?: cards.firstOrNull { it.kind == CardKind.INVITE }?.key
            ?: cards.firstOrNull()?.key
    }

    private fun homeSections(
        games: List<GameEntity>,
        saves: Map<Long, SaveInfo>,
        jobs: Map<Long, DownloadEngine.DlSnapshot>,
        records: List<DownloadRecord>,
        armed: Set<Long>,
        online: Boolean,
        freeBytes: (String?) -> Long?,
        siteName: String?,
        now: Long,
        cards: HomeCardFactory,
        etaSec: (DownloadEngine.DlSnapshot) -> Long?,
    ): List<HomeSection> {
        val resume = resumeGames(games, saves, now)
        // Games that can't start sort last and never lead the group; the
        // rank is the platform route, computed once per game so the sort
        // never runs readiness.
        val news = games.filter { it.available && it.playCount == 0 && it.deletedAtMs == 0L }
            .map { it to if (isUnsupported(it)) 1 else 0 }
            .sortedWith(
                compareBy<Pair<GameEntity, Int>> { it.second }
                    .thenByDescending { clampToNow(it.first.addedAtMs, now) },
            )
            .map { it.first }
        val queue = downloadQueue(jobs)
        val out = mutableListOf<HomeSection>()

        if (resume.isNotEmpty()) {
            val (shown, hidden) = limited(resume, GAME_ALL_THRESHOLD, GAME_VISIBLE)
            out += gameSection(
                HomeGroup.RESUME, resume.size, shown, hidden, saves, online, freeBytes,
                siteName, now, cards,
            )
        }
        if (queue.isNotEmpty()) {
            val byId = records.associateBy { it.id }
            val gameFor = jobGameIndex(games, records)
            val (shown, hidden) = limited(queue, JOB_ALL_THRESHOLD, JOB_VISIBLE)
            val stack = hidden.mapNotNull { snap -> byId[snap.id]?.let { cards.jobArt(it) } }
                .ifEmpty { hidden.map { ArtRef.None } }
            out += HomeSection(
                group = HomeGroup.DOWNLOADING,
                label = HomeCopy.groupLabel(HomeGroup.DOWNLOADING, siteName),
                detail = HomeCopy.groupDetail(HomeGroup.DOWNLOADING, queue.size, siteName),
                cards = shown.map { snap ->
                    val rec = byId[snap.id]
                    cards.jobCard(
                        snapshot = snap,
                        record = rec,
                        gameId = gameFor[snap.id],
                        armed = snap.id in armed,
                        etaSec = etaSec(snap),
                        siteName = siteName,
                        nowMs = now,
                        online = online,
                    )
                    // The row always ends with the All downloads tile.
                } + allTiles(cards, HomeGroup.DOWNLOADING, queue.size, hidden.size, stack, always = true),
            )
        }
        if (news.isNotEmpty()) {
            // When RESUME is empty, NEW fills the screen (up to 7 + All).
            val (shown, hidden) = if (resume.isEmpty()) {
                limited(news, GAME_FILL_THRESHOLD, GAME_FILL)
            } else {
                limited(news, GAME_ALL_THRESHOLD, GAME_VISIBLE)
            }
            out += gameSection(
                HomeGroup.NEW, news.size, shown, hidden, saves, online, freeBytes,
                siteName, now, cards,
            )
        }
        return out
    }

    /**
     * "Can't start on this phone" comes from the platform
     * route alone — Route.Blocked or Route.GuidedInstall, exactly what
     * `PlayStates.derive` reports as Blocked/GuidedImport — with no
     * presenter, PackageManager or StatFs calls, so the NEW sort never
     * probes readiness. A per-game emulator override never reroutes those
     * two routes (`EmuRouter.playInner`), so it cannot change the verdict.
     */
    private fun isUnsupported(g: GameEntity): Boolean =
        when (PlatformRegistry.byFolder[g.folder]?.route ?: Route.Blocked("Unknown system")) {
            is Route.Blocked, is Route.GuidedInstall -> true
            else -> false
        }

    /** A future timestamp reads as "just now", so it sorts as now. */
    private fun clampToNow(ms: Long, now: Long): Long = if (ms > now) now else ms

    /**
     * RESUME membership and order. A played game whose file is gone
     * (ejected SD card: available = false, deletedAtMs == 0) stays in
     * RESUME. A future timestamp sorts as [now], never after.
     */
    fun resumeGames(games: List<GameEntity>, saves: Map<Long, SaveInfo>, now: Long): List<GameEntity> =
        games.filter { g ->
            g.playCount > 0 &&
                if (g.deletedAtMs > 0) g.sourceRefJson != null else true
        }.sortedByDescending { g ->
            clampToNow(maxOf(saves[g.id]?.newestMs ?: 0L, g.lastPlayedMs), now)
        }

    /**
     * DOWNLOADING membership and order: active, queued, paused/problem.
     * Finished and ART jobs are ignored; a failed ST_INGEST (error set) is a
     * problem job and stays.
     */
    fun downloadQueue(jobs: Map<Long, DownloadEngine.DlSnapshot>): List<DownloadEngine.DlSnapshot> =
        jobs.values
            .filter {
                it.transferKind == TRANSFER_ROM &&
                    it.status != DownloadEngine.ST_DONE &&
                    (it.status != DownloadEngine.ST_INGEST || !it.error.isNullOrBlank())
            }
            .sortedWith(compareBy({ jobRank(it.status) }, { it.id }))

    private fun jobRank(status: String): Int = when (status) {
        DownloadEngine.ST_ACTIVE -> 0
        DownloadEngine.ST_QUEUED -> 1
        else -> 2
    }

    /** Job id -> the library row it will land on (path first, source key). */
    private fun jobGameIndex(
        games: List<GameEntity>,
        records: List<DownloadRecord>,
    ): Map<Long, Long> {
        val byPath = games.associateBy { it.volumeId to it.relPath }
        val bySource = games.filter { it.sourceId != null && it.sourceItemKey != null }
            .associateBy { it.sourceId!! to it.sourceItemKey!! }
        return records.mapNotNull { rec ->
            val byKey = rec.sourceId?.let { s -> rec.sourceItemKey?.let { k -> bySource[s to k] } }
            val game = byPath[rec.volumeId to rec.destRelPath] ?: byKey ?: return@mapNotNull null
            rec.id to game.id
        }.toMap()
    }

    private fun searchSections(
        games: List<GameEntity>,
        saves: Map<Long, SaveInfo>,
        query: String,
        site: SiteQuery,
        online: Boolean,
        freeBytes: (String?) -> Long?,
        siteName: String?,
        now: Long,
        cards: HomeCardFactory,
        searchKeys: Map<Long, SearchKeys> = emptyMap(),
    ): List<HomeSection> {
        val yours = searchLocal(games, saves, query, searchKeys = searchKeys)
        val out = mutableListOf<HomeSection>()
        if (yours.isNotEmpty()) {
            out += gameSection(
                HomeGroup.SEARCH_YOURS, yours.size, yours, emptyList(), saves, online,
                freeBytes, siteName, now, cards,
            )
        }
        val siteCards = searchSiteCards(games, query, site, online, freeBytes, siteName, now, cards)
        out += HomeSection(
            group = HomeGroup.SEARCH_SITE,
            label = HomeCopy.groupLabel(HomeGroup.SEARCH_SITE, siteName),
            // "First page · not on your phone" only when the site
            // actually returned results (not for no-plugin / failure rows).
            detail = if (site is SiteQuery.Found && site.entries.isNotEmpty()) {
                HomeCopy.groupDetail(HomeGroup.SEARCH_SITE, siteCards.size, siteName)
            } else {
                null
            },
            cards = siteCards,
        )
        return out
    }

    /** SEARCH_SITE cards: results minus library dupes, or one link. */
    private fun searchSiteCards(
        games: List<GameEntity>,
        query: String,
        site: SiteQuery,
        online: Boolean,
        freeBytes: (String?) -> Long?,
        siteName: String?,
        now: Long,
        cards: HomeCardFactory,
    ) = when (site) {
        SiteQuery.None, SiteQuery.Searching -> listOf(cards.link(LinkKind.SEARCHING, query, siteName))
        SiteQuery.NoPlugin -> listOf(cards.link(LinkKind.NO_PLUGIN_HINT, query, siteName))
        SiteQuery.Offline -> listOf(cards.link(LinkKind.OFFLINE_NOTE, query, siteName))
        // A failed search shows the error, not "nothing found".
        is SiteQuery.Failed ->
            listOf(cards.link(LinkKind.SEARCH_FAILED, query, siteName, message = site.message))
        is SiteQuery.Found -> {
            val owned = librarySourceKeys(games)
            // Duplicates never reach the grid: CardKeys must stay unique.
            val hits = site.entries
                .distinctBy { it.entry.ref.sourceId to it.entry.ref.itemKey }
                .filter { (it.entry.ref.sourceId to it.entry.ref.itemKey) !in owned }
                .take(SEARCH_SITE_LIMIT)
            when {
                hits.isNotEmpty() ->
                    hits.map { hit ->
                        cards.siteCard(
                            hit.entry, hit.size, online,
                            freeBytes(hit.entry.platformId), siteName, now,
                        )
                    } + cards.link(LinkKind.SEE_ALL_ON_SITE, query, siteName)
                // Everything page 1 offers is already in the library.
                site.entries.isNotEmpty() -> listOf(cards.link(LinkKind.SEE_ALL_ON_SITE, query, siteName))
                else -> listOf(cards.link(LinkKind.NOTHING_FOUND, query, siteName))
            }
        }
    }

    /** Library-owned (sourceId, itemKey) pairs, tombstones included. */
    fun librarySourceKeys(games: List<GameEntity>): Set<Pair<String, String>> =
        games.mapNotNull { g ->
            val s = g.sourceId ?: return@mapNotNull null
            val k = g.sourceItemKey ?: return@mapNotNull null
            s to k
        }.toSet()

    /**
     * SEARCH_YOURS: available games, redownloadable tombstones
     * (`deletedAtMs > 0 && sourceRefJson != null`) and played games whose
     * file is gone (an ejected SD card stays findable). Matching normalizes
     * both sides (NFD, accents stripped, punctuation to spaces, collapsed)
     * against the display title and the raw
     * title; prefix matches rank first, then word-start matches, then
     * substring matches.
     */
    fun searchLocal(
        games: List<GameEntity>,
        saves: Map<Long, SaveInfo>,
        query: String,
        limit: Int = SEARCH_YOURS_LIMIT,
        searchKeys: Map<Long, SearchKeys> = emptyMap(),
    ): List<GameEntity> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return games.filter {
            it.available || (it.deletedAtMs > 0 && it.sourceRefJson != null) ||
                (it.deletedAtMs == 0L && it.playCount > 0)
        }
            .mapNotNull { g ->
                // The store's per-list cache wins; a game the
                // cache doesn't know (fresh insert) is normalized on the spot.
                val keys = searchKeys[g.id] ?: SearchKeys(
                    normalize(HomeCopy.displayTitle(g.title)),
                    normalize(g.title),
                )
                val tier = listOfNotNull(tierOf(keys.shown, q), tierOf(keys.raw, q)).minOrNull()
                    ?: return@mapNotNull null
                tier to g
            }.sortedWith(
                compareBy<Pair<Int, GameEntity>> { it.first }
                    .thenByDescending { activityOf(it.second, saves) },
            ).map { it.second }.take(limit)
    }

    private fun tierOf(title: String, q: String): Int? = when {
        title.startsWith(q) -> 0
        title.contains(" $q") -> 1
        title.contains(q) -> 2
        else -> null
    }

    /** NFD, accents stripped, lowercase, punctuation -> spaces, collapsed. */
    fun normalize(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(COMBINING, "")
            .lowercase()
            .replace(NON_ALNUM, " ")
            .trim()
            .replace(SPACE_RUN, " ")

    private val COMBINING = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val SPACE_RUN = Regex(" +")

    private fun activityOf(g: GameEntity, saves: Map<Long, SaveInfo>): Long =
        maxOf(saves[g.id]?.newestMs ?: 0L, g.lastPlayedMs, g.addedAtMs)

    /**
     * SURPRISE: a random available game whose readiness is Ready, unplayed
     * games preferred, never [excludeId] twice in a row. Readiness is probed
     * for at most [probeLimit] candidates — never the whole library. Pure:
     * randomness is injected.
     */
    fun pickSurprise(
        games: List<GameEntity>,
        isReady: (GameEntity) -> Boolean,
        excludeId: Long?,
        random: Random = Random.Default,
        probeLimit: Int = 12,
    ): GameEntity? {
        val pool = games.filter { it.available && it.deletedAtMs == 0L && it.id != excludeId }
        if (pool.isEmpty()) return null
        val (unplayed, played) = pool.partition { it.playCount == 0 }
        val ordered = unplayed.shuffled(random) + played.shuffled(random)
        return ordered.asSequence().take(probeLimit).firstOrNull(isReady)
    }

    private fun setupSection(
        siteName: String?,
        cards: HomeCardFactory,
        systemWarnings: Int = 0,
    ): List<HomeSection> = listOf(
        HomeSection(
            group = HomeGroup.SETUP,
            label = HomeCopy.groupLabel(HomeGroup.SETUP, siteName),
            detail = HomeCopy.groupDetail(HomeGroup.SETUP, 3, siteName),
            cards = listOf(
                cards.invite(InviteKind.ADD_PLUGIN),
                cards.invite(InviteKind.SCAN_PHONE),
                cards.invite(InviteKind.CHECK_SYSTEMS, systemWarnings),
            ),
        ),
    )

    private fun surpriseSection(
        pick: GameEntity,
        saves: Map<Long, SaveInfo>,
        online: Boolean,
        freeBytes: (String?) -> Long?,
        siteName: String?,
        now: Long,
        cards: HomeCardFactory,
    ): HomeSection = HomeSection(
        group = HomeGroup.SURPRISE,
        label = HomeCopy.groupLabel(HomeGroup.SURPRISE, siteName),
        detail = HomeCopy.groupDetail(HomeGroup.SURPRISE, 2, siteName),
        // The picked game leads (default selection, TV shows it);
        // Roll again is a half-width card beside it.
        cards = listOf(
            cards.localCard(HomeGroup.SURPRISE, pick, saves[pick.id], online, freeBytes(pick.folder), siteName, now),
            cards.invite(InviteKind.ROLL_AGAIN),
        ),
    )

    private fun <T> limited(all: List<T>, threshold: Int, keep: Int): Pair<List<T>, List<T>> =
        if (all.size > threshold) all.take(keep) to all.drop(keep) else all to emptyList()

    private fun gameSection(
        group: HomeGroup,
        total: Int,
        shown: List<GameEntity>,
        hidden: List<GameEntity>,
        saves: Map<Long, SaveInfo>,
        online: Boolean,
        freeBytes: (String?) -> Long?,
        siteName: String?,
        now: Long,
        cards: HomeCardFactory,
    ): HomeSection {
        val stack = hidden.take(3).map { cards.coverArt(it) }
        return HomeSection(
            group = group,
            label = HomeCopy.groupLabel(group, siteName),
            detail = HomeCopy.groupDetail(group, total, siteName),
            cards = shown.map { g ->
                cards.localCard(group, g, saves[g.id], online, freeBytes(g.folder), siteName, now)
            } + allTiles(cards, group, total, hidden.size, stack),
        )
    }

    private fun allTiles(
        cards: HomeCardFactory,
        group: HomeGroup,
        total: Int,
        more: Int,
        stack: List<ArtRef>,
        always: Boolean = false,
    ) = if (more > 0 || always) listOf(cards.allTile(group, total, more, stack)) else emptyList()
}
