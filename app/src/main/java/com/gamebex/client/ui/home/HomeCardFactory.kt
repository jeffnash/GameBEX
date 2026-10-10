package com.gamebex.client.ui.home

import com.gamebex.client.data.CoreNames
import com.gamebex.client.data.PlayStates
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.PrepProgress
import com.gamebex.client.data.local.PrepProgressBus
import com.gamebex.client.data.local.label
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.ui.LibSort
import com.gamebex.client.ui.home.model.ArtKind
import com.gamebex.client.ui.home.model.ArtRef
import com.gamebex.client.ui.home.model.ButtonTone
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.InviteKind
import com.gamebex.client.ui.home.model.LinkKind
import com.gamebex.client.ui.home.model.SaveInfo
import com.gamebex.client.ui.home.model.SizeInfo
import com.gamebex.client.ui.home.model.SystemTag
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvGlyph
import com.gamebex.client.ui.home.model.TvModel
import com.gamebex.client.ui.home.model.TvTone
import com.gamebex.client.ui.presentation.emulatorLabel
import com.gamebex.source.RomEntry
import java.io.File

/**
 * Game + readiness + save + job -> [HomeCard], every string
 * from [HomeCopy]. Readiness is injected as a function so tests fake it;
 * production calls `LocalGamePresenter.present` for the shown cards only
 * (the grouping builds at most the visible cards, never the library).
 */
class HomeCardFactory(
    private val readiness: (GameEntity) -> PlayStates.Status,
) {

    /**
     * State for a library game: the group's normal state
     * (Saved / Played / Added / OffPhone), overridden by the readiness
     * problem. Tombstones skip readiness entirely — the file is gone by
     * design, so `OffPhone` always wins.
     */
    fun localState(
        group: HomeGroup,
        game: GameEntity,
        save: SaveInfo?,
        freeBytes: Long?,
        online: Boolean,
    ): CardState {
        val base = baseState(group, game, save)
        if (game.deletedAtMs > 0) return overrideSpace(base, freeBytes, online)
        // The row exists but its file is gone (ejected SD card).
        // NeedsVolume maps to Unavailable — decided from the row
        // alone, so no readiness probe is spent on it.
        if (!game.available) {
            return overrideSpace(CardState.Unavailable(HomeCopy.needsVolume), freeBytes, online)
        }
        return overrideSpace(problemFor(readiness(game), base), freeBytes, online)
    }

    /** The group's normal state. */
    private fun baseState(group: HomeGroup, game: GameEntity, save: SaveInfo?): CardState = when {
        game.deletedAtMs > 0 -> CardState.OffPhone(sizeOf(game.sizeBytes), save?.count ?: 0)
        group == HomeGroup.NEW -> CardState.Added(game.addedAtMs)
        save != null -> CardState.Saved(save.newestMs, save.count)
        game.playCount > 0 -> CardState.Played(game.lastPlayedMs)
        else -> CardState.Added(game.addedAtMs)
    }

    /** Readiness mapping. Ready/Playing keep the group's normal state. */
    private fun problemFor(status: PlayStates.Status, base: CardState): CardState = when (status) {
        is PlayStates.Status.Ready,
        is PlayStates.Status.Playing,
        is PlayStates.Status.Available,
        is PlayStates.Status.Downloading,
        is PlayStates.Status.DownloadFailed,
        -> base
        is PlayStates.Status.NeedsBios -> CardState.NeedsBios(status.names)
        is PlayStates.Status.NeedsRuntime -> CardState.NeedsEmulator(status.emu.label)
        is PlayStates.Status.NeedsCore ->
            CardState.NeedsEmulator("the RetroArch ${CoreNames.display(status.core)} core")
        is PlayStates.Status.NeedsManual -> CardState.NeedsFix(status.note)
        is PlayStates.Status.Preparing -> CardState.Preparing()
        is PlayStates.Status.NeedsSpace -> CardState.NeedsFix(HomeCopy.needsSpace)
        is PlayStates.Status.NeedsVolume -> CardState.Unavailable(HomeCopy.needsVolume)
        is PlayStates.Status.Blocked -> CardState.Unsupported(status.reason)
        is PlayStates.Status.GuidedImport -> CardState.Unsupported("${HomeCopy.importIn}${status.emu.label}")
        is PlayStates.Status.SourceUnavailable -> CardState.NeedsFix(HomeCopy.sourceGone)
    }

    /** State overrides for OnSite / OffPhone. */
    private fun overrideSpace(state: CardState, freeBytes: Long?, online: Boolean): CardState {
        val need = when (state) {
            is CardState.OnSite -> state.size?.bytes
            is CardState.OffPhone -> state.size?.bytes
            else -> return state
        }
        if (!online) return CardState.WaitingForConnection
        return if (need != null && freeBytes != null && need > freeBytes) {
            CardState.NoRoom(need, freeBytes)
        } else {
            state
        }
    }

    /** DOWNLOADING states from an engine snapshot. */
    fun jobState(snapshot: DownloadEngine.DlSnapshot, armed: Boolean, etaSec: Long?): CardState =
        when (snapshot.status) {
            DownloadEngine.ST_ACTIVE ->
                CardState.Downloading(snapshot.read, snapshot.total, etaSec, paused = false, armed = armed)
            DownloadEngine.ST_QUEUED -> CardState.Queued(armed)
            DownloadEngine.ST_PAUSED, DownloadEngine.ST_PARKED_SD ->
                CardState.Downloading(snapshot.read, snapshot.total, null, paused = true, armed = armed)
            DownloadEngine.ST_NEEDS_ASSIST ->
                CardState.DownloadProblem(problemText(snapshot, HomeCopy.verifyDownload), verify = true)
            DownloadEngine.ST_SOURCE_UNAVAILABLE ->
                CardState.DownloadProblem(problemText(snapshot, HomeCopy.sourceGone), verify = false)
            // An ingest failure is a DownloadProblem; a clean ingest
            // is the file arriving (Preparing).
            DownloadEngine.ST_INGEST ->
                if (snapshot.error.isNullOrBlank()) CardState.Preparing()
                else CardState.DownloadProblem(problemText(snapshot, HomeCopy.downloadStopped), verify = false)
            else ->
                CardState.DownloadProblem(problemText(snapshot, HomeCopy.downloadStopped), verify = false)
        }

    private fun problemText(snapshot: DownloadEngine.DlSnapshot, fallback: String): String =
        snapshot.error?.takeIf { it.isNotBlank() }
            ?: snapshot.note?.takeIf { it.isNotBlank() }
            ?: fallback

    /**
     * Overlay a live prep snapshot onto a Preparing card, in
     * place — same pattern as the download byte-tick patch. A prep that
     * finished (absent from the bus) keeps its card; the debounced
     * rebuild re-derives the real state from the DB.
     */
    fun prepPatch(card: HomeCard, snap: PrepProgress, nowMs: Long = System.currentTimeMillis()): HomeCard {
        val state = CardState.Preparing(
            read = snap.bytesWritten,
            total = snap.totalBytes,
            phase = snap.phase.label,
            stalled = PrepProgressBus.isStalled(snap, nowMs),
        )
        return card.copy(
            state = state,
            cardLine = HomeCopy.cardLine(state, card.system, nowMs),
            primaryLabel = HomeCopy.primaryLabel(state),
            tv = card.tv.copy(
                stateKey = HomeCopy.tvStateKey(state, null, nowMs, card.system),
                stateDetail = HomeCopy.tvStateDetail(state, null, nowMs),
                factsLine = HomeCopy.tvFacts(state, card.system, null, nowMs),
            ),
        )
    }

    /**
     * A Preparing card with no live prep run for 3
     * ticks (never started, or died with the process) becomes NeedsFix —
     * same state a FAILED prep derives, so the card says "Needs setup",
     * the button says "Fix setup", and Detail offers Retry. Unlike
     * [prepPatch] the actions change too: the button must work.
     */
    fun prepStale(card: HomeCard, note: String, nowMs: Long = System.currentTimeMillis()): HomeCard {
        val state = CardState.NeedsFix(note)
        return card.copy(
            state = state,
            cardLine = HomeCopy.cardLine(state, card.system, nowMs),
            primary = primaryFor(state, card.gameId, card.downloadId, entry = null),
            primaryLabel = HomeCopy.primaryLabel(state),
            primaryTone = HomeCopy.buttonTone(state),
            secondary = secondaryFor(state, card.gameId, card.downloadId, entry = null),
            secondaryLabel = secondaryLabelFor(state),
            tv = card.tv.copy(
                stateKey = HomeCopy.tvStateKey(state, null, nowMs, card.system),
                stateDetail = HomeCopy.tvStateDetail(state, null, nowMs),
                factsLine = HomeCopy.tvFacts(state, card.system, null, nowMs),
                glyph = HomeCopy.tvGlyph(state),
                tone = HomeCopy.tvTone(state),
            ),
        )
    }

    /** A library game card (RESUME / NEW / SEARCH_YOURS / SURPRISE). */
    fun localCard(
        group: HomeGroup,
        game: GameEntity,
        save: SaveInfo?,
        online: Boolean,
        freeBytes: Long?,
        siteName: String?,
        nowMs: Long,
    ): HomeCard {
        val state = localState(group, game, save, freeBytes, online)
        return gameCard(
            key = CardKey.local(game.id),
            title = game.title,
            system = systemOf(game),
            state = state,
            nowMs = nowMs,
            freeBytes = freeBytes,
            siteName = siteName,
            sizeBytes = game.sizeBytes.takeIf { it > 0 },
            gameId = game.id,
            thumb = thumbArt(game, save),
        )
    }

    /** A ROM-site search hit (SEARCH_SITE). */
    fun siteCard(
        entry: RomEntry,
        size: SizeInfo?,
        online: Boolean,
        freeBytes: Long?,
        siteName: String?,
        nowMs: Long,
    ): HomeCard {
        val system = systemOfFolder(entry.platformId)
        val state = overrideSpace(CardState.OnSite(size), freeBytes, online)
        return gameCard(
            key = CardKey.site(entry.ref.sourceId, entry.ref.itemKey),
            title = entry.title,
            system = system,
            state = state,
            nowMs = nowMs,
            freeBytes = freeBytes,
            siteName = siteName,
            sizeBytes = size?.bytes,
            entry = entry,
            thumb = siteArt(entry),
        )
    }

    /** A DOWNLOADING engine job card. */
    fun jobCard(
        snapshot: DownloadEngine.DlSnapshot,
        record: DownloadRecord?,
        gameId: Long?,
        armed: Boolean,
        etaSec: Long?,
        siteName: String?,
        nowMs: Long,
        online: Boolean = true,
    ): HomeCard {
        val state = jobState(snapshot, armed, etaSec)
        return gameCard(
            key = CardKey.download(snapshot.id),
            title = record?.title?.takeIf { it.isNotBlank() } ?: snapshot.title,
            system = systemOfFolder(record?.platformFolder),
            state = state,
            nowMs = nowMs,
            freeBytes = null,
            siteName = siteName,
            downloadId = snapshot.id,
            gameId = gameId,
            thumb = jobArt(record),
            online = online,
        )
    }

    /** The one game + wiring behind every row. */
    fun gameCard(
        key: CardKey,
        title: String,
        system: SystemTag?,
        state: CardState,
        nowMs: Long,
        freeBytes: Long?,
        siteName: String?,
        sizeBytes: Long? = null,
        gameId: Long? = null,
        downloadId: Long? = null,
        entry: RomEntry? = null,
        thumb: ArtRef = ArtRef.None,
        online: Boolean = true,
    ): HomeCard = HomeCard(
        key = key,
        kind = CardKind.GAME,
        title = HomeCopy.displayTitle(title),
        cardLine = HomeCopy.cardLine(state, system, nowMs, online),
        system = system,
        thumb = thumb,
        state = state,
        primary = primaryFor(state, gameId, downloadId, entry),
        primaryLabel = HomeCopy.primaryLabel(state),
        primaryTone = HomeCopy.buttonTone(state),
        secondary = secondaryFor(state, gameId, downloadId, entry),
        secondaryLabel = secondaryLabelFor(state),
        tv = tvFor(state, system, nowMs, freeBytes, siteName, sizeBytes, thumb),
        position = 0, // flat index assigned by HomeGrouping at assembly
        gameId = gameId,
        downloadId = downloadId,
    )

    /** ALL_TILE: "All {N}", a stack of hidden covers, group action. */
    fun allTile(group: HomeGroup, total: Int, more: Int, stack: List<ArtRef>): HomeCard = HomeCard(
        key = CardKey.all(group),
        kind = CardKind.ALL_TILE,
        title = if (group == HomeGroup.DOWNLOADING) HomeCopy.allDownloads else HomeCopy.allTitle(total),
        cardLine = HomeCopy.allBody(group, more),
        body = HomeCopy.allBody(group, more),
        stack = stack,
        primary = when (group) {
            HomeGroup.RESUME -> HomeAction.Go(HomeDestination.Library(LibSort.Played))
            HomeGroup.NEW -> HomeAction.Go(HomeDestination.Library(LibSort.Recent))
            else -> HomeAction.Go(HomeDestination.Downloads)
        },
        primaryLabel = when (group) {
            HomeGroup.DOWNLOADING -> HomeCopy.openDownloads
            else -> HomeCopy.openLibrary
        },
        primaryTone = ButtonTone.PLAY,
        tv = TvModel(
            content = TvContent.Mosaic(stack),
            stateKey = "ALL $total",
            stateDetail = "$more MORE",
            glyph = null,
            factsLine = HomeCopy.allFacts(group),
        ),
        position = 0, // flat index assigned by HomeGrouping at assembly
    )

    /**
     * INVITE. Each invite previews a [TvContent.Notice] — plug, folder,
     * warn or dice glyph with the banner label.
     * [systemWarnings] feeds the CHECK_SYSTEMS label.
     */
    fun invite(kind: InviteKind, systemWarnings: Int = 0): HomeCard {
        val (glyph, label, tone) = noticeOf(kind, systemWarnings)
        return HomeCard(
            key = CardKey.invite(kind),
            kind = CardKind.INVITE,
            title = HomeCopy.inviteTitle(kind),
            cardLine = HomeCopy.inviteBody(kind),
            body = HomeCopy.inviteBody(kind),
            primary = when (kind) {
                InviteKind.ADD_PLUGIN -> HomeAction.Go(HomeDestination.Sources)
                InviteKind.SCAN_PHONE -> HomeAction.RescanPhone
                InviteKind.CHECK_SYSTEMS -> HomeAction.Go(HomeDestination.System)
                InviteKind.ROLL_AGAIN -> HomeAction.RollAgain
            },
            primaryLabel = HomeCopy.inviteLabel(kind),
            primaryTone = ButtonTone.PLAY,
            tv = TvModel(
                content = TvContent.Notice(glyph, label, tone),
                stateKey = label,
                stateDetail = null,
                glyph = glyph,
                factsLine = null,
                tone = tone,
            ),
            // Roll again sits beside the picked game; the other
            // invites span both columns.
            span = if (kind == InviteKind.ROLL_AGAIN) 1 else 2,
            position = 0, // flat index assigned by HomeGrouping at assembly
        )
    }

    private fun noticeOf(kind: InviteKind, systemWarnings: Int): Triple<TvGlyph, String, TvTone> =
        when (kind) {
            InviteKind.ADD_PLUGIN -> Triple(TvGlyph.PLUG, HomeCopy.noPluginLabel, TvTone.NORMAL)
            InviteKind.SCAN_PHONE -> Triple(TvGlyph.FOLDER, HomeCopy.findYourGames, TvTone.NORMAL)
            InviteKind.CHECK_SYSTEMS -> Triple(
                TvGlyph.WARN,
                if (systemWarnings > 0) HomeCopy.systemsNeedSetup(systemWarnings)
                else HomeCopy.allSystemsReady,
                TvTone.WARN,
            )
            InviteKind.ROLL_AGAIN -> Triple(TvGlyph.DICE, HomeCopy.rollAgainTv, TvTone.NORMAL)
        }

    /**
     * The "no game" TV (nothing selected / first run fallback): test-card
     * plate "NO GAME" and the banner "NO GAME INSERTED".
     */
    fun noGameTv(): TvModel = TvModel(
        content = TvContent.TestCard(HomeCopy.noGameLabel),
        stateKey = HomeCopy.noGameInserted,
        stateDetail = null,
        glyph = null,
        factsLine = null,
        tone = TvTone.MUTED,
    )

    /** QUIET_LINK. */
    fun link(kind: LinkKind, query: String, siteName: String?, message: String? = null): HomeCard = HomeCard(
        key = CardKey.link(kind),
        kind = CardKind.QUIET_LINK,
        title = HomeCopy.linkTitle(kind, query, siteName),
        cardLine = HomeCopy.linkBody(kind, siteName, message).orEmpty(),
        body = HomeCopy.linkBody(kind, siteName, message),
        primary = when (kind) {
            LinkKind.NO_PLUGIN_HINT -> HomeAction.Go(HomeDestination.Sources)
            LinkKind.SEE_ALL_ON_SITE -> HomeAction.Go(HomeDestination.SiteSearch(query))
            else -> HomeAction.None
        },
        primaryLabel = HomeCopy.linkLabel(kind).orEmpty(),
        primaryTone = ButtonTone.PLAY,
        tv = linkTv(kind, siteName),
        span = 2,
        position = 0, // flat index assigned by HomeGrouping at assembly
    )

    private fun linkTv(kind: LinkKind, siteName: String?): TvModel = when (kind) {
        LinkKind.NO_PLUGIN_HINT -> TvModel(
            content = TvContent.PlugCard(HomeCopy.noPluginLabel),
            stateKey = HomeCopy.noPluginLabel,
            stateDetail = null,
            glyph = TvGlyph.WARN,
            factsLine = null,
            tone = TvTone.MUTED,
        )
        LinkKind.SEE_ALL_ON_SITE -> TvModel(
            content = TvContent.Mosaic(List(2) { ArtRef.None }),
            stateKey = "${HomeCopy.moreOn} ${(siteName ?: "").uppercase()}",
            stateDetail = null,
            glyph = null,
            factsLine = null,
        )
        LinkKind.OFFLINE_NOTE -> TvModel(
            TvContent.TestCard(HomeCopy.offlineLabel), HomeCopy.offlineLabel, null,
            TvGlyph.OFFLINE, null, TvTone.MUTED,
        )
        LinkKind.NOTHING_FOUND -> TvModel(
            TvContent.TestCard(HomeCopy.notFoundLabel), HomeCopy.notFoundLabel, null,
            null, null, TvTone.MUTED,
        )
        LinkKind.SEARCH_FAILED -> TvModel(
            TvContent.TestCard(HomeCopy.searchFailedLabel), HomeCopy.searchFailedLabel, null,
            TvGlyph.WARN, null, TvTone.WARN,
        )
        LinkKind.SEARCHING -> TvModel(
            TvContent.TestCard(HomeCopy.searchingLabel), HomeCopy.searchingLabel, null,
            null, null, TvTone.MUTED,
        )
    }

    private fun primaryFor(
        state: CardState,
        gameId: Long?,
        downloadId: Long?,
        entry: RomEntry?,
    ): HomeAction = when (state) {
        is CardState.Saved, is CardState.Played, is CardState.Added ->
            HomeAction.Play(gameId ?: 0)
        is CardState.NeedsBios, is CardState.NeedsEmulator, is CardState.NeedsFix,
        is CardState.Unsupported, is CardState.Unavailable,
        -> HomeAction.Go(HomeDestination.Game(gameId ?: 0))
        is CardState.Preparing, CardState.WaitingForConnection -> HomeAction.None
        is CardState.Downloading ->
            if (state.paused) HomeAction.ResumeDownload(downloadId ?: 0)
            else HomeAction.TogglePlayWhenReady(downloadId ?: 0)
        is CardState.Queued -> HomeAction.TogglePlayWhenReady(downloadId ?: 0)
        is CardState.DownloadProblem ->
            if (state.verify) HomeAction.Go(HomeDestination.Downloads)
            else HomeAction.ResumeDownload(downloadId ?: 0)
        is CardState.OffPhone -> HomeAction.DownloadAgain(gameId ?: 0)
        is CardState.OnSite ->
            entry?.let { HomeAction.DownloadFromSite(it) } ?: HomeAction.None
        is CardState.NoRoom -> HomeAction.Go(HomeDestination.Library(LibSort.Size))
        CardState.None -> HomeAction.None
    }

    private fun secondaryFor(
        state: CardState,
        gameId: Long?,
        downloadId: Long?,
        entry: RomEntry?,
    ): HomeAction? = when (state) {
        is CardState.Downloading ->
            if (state.paused) HomeAction.Go(HomeDestination.Downloads)
            else HomeAction.PauseDownload(downloadId ?: 0)
        is CardState.Queued -> HomeAction.PauseDownload(downloadId ?: 0)
        is CardState.DownloadProblem -> HomeAction.Go(HomeDestination.Downloads)
        is CardState.Unsupported, is CardState.Unavailable -> null
        is CardState.OnSite ->
            entry?.let { HomeAction.Go(HomeDestination.SiteEntry(it)) } ?: HomeAction.Go(HomeDestination.Game(gameId ?: 0))
        else ->
            if (entry != null) HomeAction.Go(HomeDestination.SiteEntry(entry))
            else HomeAction.Go(HomeDestination.Game(gameId ?: 0))
    }

    private fun secondaryLabelFor(state: CardState): String? = when (state) {
        is CardState.Downloading -> if (state.paused) HomeCopy.details else HomeCopy.pause
        is CardState.Queued -> HomeCopy.pause
        is CardState.Unsupported, is CardState.Unavailable -> null
        else -> HomeCopy.details
    }

    private fun tvFor(
        state: CardState,
        system: SystemTag?,
        nowMs: Long,
        freeBytes: Long?,
        siteName: String?,
        sizeBytes: Long?,
        thumb: ArtRef,
    ): TvModel {
        val dim = state is CardState.OffPhone || state is CardState.OnSite ||
            state is CardState.WaitingForConnection || state is CardState.NoRoom
        val progress = (state as? CardState.Downloading)
            ?.takeIf { it.total > 0 }
            ?.let { it.read.coerceIn(0, it.total).toFloat() / it.total }
        return TvModel(
            content = TvContent.Picture(thumb, dim = dim, progress = progress),
            stateKey = HomeCopy.tvStateKey(state, siteName, nowMs, system),
            stateDetail = HomeCopy.tvStateDetail(state, siteName, nowMs),
            glyph = HomeCopy.tvGlyph(state),
            factsLine = HomeCopy.tvFacts(state, system, freeBytes, sizeBytes),
            tone = HomeCopy.tvTone(state),
        )
    }

    /**
     * Card thumbnail: a save shot fills it, else the cover, else no art for a
     * generated case. File existence is resolved here (on the IO pipeline),
     * never in composables: kind is COVER/SAVE_THUMB only when the
     * File exists or the model is a URL, else NONE with systemFolder/title.
     */
    fun thumbArt(game: GameEntity, save: SaveInfo?): ArtRef {
        save?.thumbnail?.takeIf { it.isFile }?.let { f ->
            return ArtRef(
                f, ArtKind.SAVE_THUMB,
                revision = f.lastModified(),
                systemFolder = game.folder,
                title = HomeCopy.displayTitle(game.title),
            )
        }
        return coverArt(game)
    }

    /** Cover art: ArtStore box/local cover when the file exists, then the plugin/SS cover URL. */
    fun coverArt(game: GameEntity): ArtRef {
        val title = HomeCopy.displayTitle(game.title)
        val path = game.artBox ?: game.coverPath
        val file = path?.let { File(it) }
        if (file != null && file.isFile) {
            return ArtRef(file, ArtKind.COVER, systemFolder = game.folder, title = title)
        }
        val url = game.coverUrl?.takeIf { it.isNotBlank() }
        return if (url != null) {
            ArtRef(url, ArtKind.COVER, sourceId = game.sourceId, systemFolder = game.folder, title = title)
        } else {
            ArtRef(null, ArtKind.NONE, systemFolder = game.folder, title = title)
        }
    }

    fun siteArt(entry: RomEntry): ArtRef {
        val title = HomeCopy.displayTitle(entry.title)
        val url = entry.coverUrl?.takeIf { it.isNotBlank() }
        return if (url != null) {
            ArtRef(url, ArtKind.COVER, sourceId = entry.ref.sourceId, systemFolder = entry.platformId, title = title)
        } else {
            ArtRef(null, ArtKind.NONE, systemFolder = entry.platformId, title = title)
        }
    }

    fun jobArt(record: DownloadRecord?): ArtRef {
        val title = record?.title?.takeIf { it.isNotBlank() }?.let { HomeCopy.displayTitle(it) }
        val url = record?.coverUrl?.takeIf { it.isNotBlank() }
            ?: return ArtRef(null, ArtKind.NONE, systemFolder = record?.platformFolder, title = title)
        return ArtRef(url, ArtKind.COVER, sourceId = record.sourceId, systemFolder = record.platformFolder, title = title)
    }

    fun systemOf(game: GameEntity): SystemTag? = systemTag(
        game.folder,
        emulatorLabel(game.folder, game.emulatorOverride, game.coreOverride),
    )

    fun systemOfFolder(folder: String?): SystemTag? =
        folder?.let { systemTag(it, emulatorLabel(it)) }

    private fun systemTag(folder: String, emulator: String): SystemTag? {
        if (folder.isBlank()) return null
        return SystemTag(
            folder = folder,
            short = HomeCopy.systemShort(folder),
            name = PlatformRegistry.byFolder[folder]?.display ?: folder,
            emulator = emulator,
        )
    }

    private fun sizeOf(bytes: Long): SizeInfo? =
        bytes.takeIf { it > 0 }?.let { SizeInfo(it, HomeCopy.size(it)) }
}
