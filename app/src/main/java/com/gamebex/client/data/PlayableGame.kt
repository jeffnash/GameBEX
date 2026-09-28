package com.gamebex.client.data

import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.GameEntity

/**
 * PlayableGame: one unified game object for catalog entries and local
 * ROMs, with status derived from independent capabilities and every CTA
 * the console-OS UI can offer. Pure Kotlin — no Android — so the whole
 * matrix is JVM-testable. The Get&Play orchestrator and every shelf CTA
 * read this; the download/prep engines feed the capabilities.
 *
 * Two faces:
 * - [derive]: capabilities -> status (priority chain; first match wins).
 * - [Capabilities.apply]: event -> new capabilities, then re-derive.
 * Playing is the only non-derived status: the UI sets it on launch and
 * clears it on return (events [Event.Launched]/[Event.Returned]).
 */
object PlayStates {
    /** Prep outcomes, plus the transient PREPARING. */
    enum class Prep { NONE, PREPARING, READY, NEEDS_BIOS, NEEDS_MANUAL, FAILED }

    /** A live or failed download job attached to this game. */
    data class Dl(val status: String, val read: Long, val total: Long, val sourceId: String? = null)

    /**
     * Independent capabilities. rowExists=false means catalog-only
     * (Available); local=false with a row means the volume went away
     * (NeedsVolume) — unless the row is a delete tombstone ([deleted]),
     * which is "not on your phone, download again" (Available). biosMissing
     * names only matter when !biosReady.
     */
    data class Capabilities(
        val route: Route,
        val rowExists: Boolean,
        val local: Boolean,
        val prep: Prep,
        val biosReady: Boolean,
        val biosMissing: List<String> = emptyList(),
        val prepNote: String = "",
        val runtimeInstalled: Boolean,
        val coreReady: Boolean,
        val spaceOk: Boolean,
        val dl: Dl? = null,
        val playing: Boolean = false,
        /** User-deleted source game kept for "Download again" (tombstone). */
        val deleted: Boolean = false,
    )

    sealed interface Status {
        /** Catalog-only: nothing local, no job. */
        data object Available : Status
        data class Downloading(val read: Long, val total: Long, val paused: Boolean, val assist: Boolean) : Status
        data class DownloadFailed(val error: String?) : Status
        data object Preparing : Status
        data object Ready : Status
        data object Playing : Status
        data class NeedsBios(val names: List<String>) : Status
        data class NeedsManual(val note: String) : Status
        data class NeedsRuntime(val emu: Emu) : Status
        data class NeedsCore(val core: String) : Status
        data object NeedsSpace : Status
        /** Row exists but the file is gone (SD ejected). */
        data object NeedsVolume : Status
        data class Blocked(val reason: String) : Status
        /** Runtime installed but needs in-app import (Vita3K); never direct Play. */
        data class GuidedImport(val emu: Emu) : Status
        /** Suspended network job: owning source missing/disabled/stale. */
        data class SourceUnavailable(val sourceId: String?) : Status
    }

    /** The one primary action for a status. None = sit tight (transient). */
    sealed interface Cta {
        data object Get : Cta
        data object ResumeDownload : Cta
        data object RetryDownload : Cta
        data object OpenAssist : Cta
        data object Prepare : Cta
        data object FixBios : Cta
        data class InstallRuntime(val emu: Emu) : Cta
        data class InstallCore(val core: String) : Cta
        data object InstallApp : Cta
        data class OpenRuntime(val emu: Emu) : Cta
        data object Play : Cta
        data object FreeSpace : Cta
        data object RemountVolume : Cta
        data object Details : Cta
        data object ManageSources : Cta
        data object None : Cta
    }

    /** Priority chain: route -> download -> presence -> space -> prep -> bios -> runtime -> core. */
    fun derive(cap: Capabilities): Status {
        if (cap.playing) return Status.Playing
        val route = cap.route
        if (route is Route.Blocked) return Status.Blocked(route.reason)
        cap.dl?.let { dl ->
            when (dl.status) {
                com.gamebex.client.net.DownloadEngine.ST_FAILED ->
                    return Status.DownloadFailed(null)
                com.gamebex.client.net.DownloadEngine.ST_DONE -> {}
                com.gamebex.client.net.DownloadEngine.ST_SOURCE_UNAVAILABLE -> {
                    // A suspended replacement must not hide Play for a
                    // valid downloaded file.
                    if (cap.rowExists && cap.local) return@let
                    return Status.SourceUnavailable(dl.sourceId)
                }
                com.gamebex.client.net.DownloadEngine.ST_NEEDS_ASSIST ->
                    return Status.Downloading(dl.read, dl.total, paused = true, assist = true)
                com.gamebex.client.net.DownloadEngine.ST_PAUSED ->
                    return Status.Downloading(dl.read, dl.total, paused = true, assist = false)
                com.gamebex.client.net.DownloadEngine.ST_PARKED_SD ->
                    return Status.Downloading(dl.read, dl.total, paused = true, assist = false)
                else -> return Status.Downloading(dl.read, dl.total, paused = false, assist = false)
            }
        }
        if (!cap.rowExists) return Status.Available
        // Tombstone: the row is kept for "Download again"; the file is
        // gone by the user's own hand, never by a missing volume.
        if (cap.deleted) return Status.Available
        if (!cap.local) return Status.NeedsVolume
        if (!cap.spaceOk) return Status.NeedsSpace
        when (cap.prep) {
            Prep.NONE, Prep.PREPARING -> return Status.Preparing
            Prep.FAILED -> return Status.NeedsManual(cap.prepNote.ifBlank { "Preparation failed." })
            Prep.NEEDS_MANUAL -> return Status.NeedsManual(cap.prepNote.ifBlank { "Needs a manual fix." })
            Prep.NEEDS_BIOS -> return Status.NeedsBios(cap.biosMissing)
            Prep.READY -> {}
        }
        if (!cap.biosReady) return Status.NeedsBios(cap.biosMissing)
        val needEmu: Emu? = when (route) {
            is Route.Standalone -> route.emu
            is Route.GuidedInstall -> route.emu
            is Route.RetroCore -> Emu.RETROARCH
            is Route.SideloadApp, is Route.Blocked -> null
        }
        if (needEmu != null && !cap.runtimeInstalled) return Status.NeedsRuntime(needEmu)
        if (route is Route.RetroCore && !cap.coreReady) return Status.NeedsCore(route.core)
        if (route is Route.GuidedInstall) return Status.GuidedImport(route.emu)
        return Status.Ready
    }

    fun ctaFor(status: Status, route: Route): Cta = when (status) {
        is Status.Available -> Cta.Get
        is Status.Downloading -> when {
            status.assist -> Cta.OpenAssist
            status.paused -> Cta.ResumeDownload
            else -> Cta.None // live download: secondary Pause/Cancel live elsewhere
        }
        is Status.DownloadFailed -> Cta.RetryDownload
        is Status.Preparing -> Cta.Prepare
        is Status.Ready -> if (route is Route.SideloadApp) Cta.InstallApp else Cta.Play
        is Status.GuidedImport -> Cta.OpenRuntime(status.emu)
        is Status.Playing -> Cta.None
        is Status.NeedsBios -> Cta.FixBios
        is Status.NeedsManual -> Cta.Details
        is Status.NeedsRuntime -> Cta.InstallRuntime(status.emu)
        is Status.NeedsCore -> Cta.InstallCore(status.core)
        is Status.NeedsSpace -> Cta.FreeSpace
        is Status.NeedsVolume -> Cta.RemountVolume
        is Status.Blocked -> Cta.Details
        is Status.SourceUnavailable -> Cta.ManageSources
    }

    sealed interface Event {
        data object DownloadStarted : Event
        data class DownloadProgress(val read: Long, val total: Long) : Event
        data object DownloadPaused : Event
        data object DownloadResumed : Event
        data object DownloadStalled : Event
        data object AssistOpened : Event
        data object DownloadDone : Event
        data class DownloadFailed(val error: String?) : Event
        data object RomAppeared : Event
        data object RomMissing : Event
        data object PrepStarted : Event
        data class PrepDone(
            val prep: Prep,
            val biosReady: Boolean,
            val biosMissing: List<String> = emptyList(),
            val note: String = "",
        ) : Event
        data object BiosFixed : Event
        data object RuntimeInstalled : Event
        data object CoreInstalled : Event
        data object SpaceFreed : Event
        data object SpaceLow : Event
        data object Launched : Event
        data object Returned : Event
    }

    /** Event -> new capabilities. Callers re-derive. All transitions total. */
    fun Capabilities.apply(event: Event): Capabilities = when (event) {
        is Event.DownloadStarted ->
            copy(dl = Dl(com.gamebex.client.net.DownloadEngine.ST_ACTIVE, 0, 0))
        is Event.DownloadProgress ->
            copy(dl = (dl ?: Dl(com.gamebex.client.net.DownloadEngine.ST_ACTIVE, 0, 0))
                .copy(status = com.gamebex.client.net.DownloadEngine.ST_ACTIVE,
                    read = event.read, total = event.total))
        is Event.DownloadPaused ->
            copy(dl = dl?.copy(status = com.gamebex.client.net.DownloadEngine.ST_PAUSED))
        is Event.DownloadResumed ->
            copy(dl = dl?.copy(status = com.gamebex.client.net.DownloadEngine.ST_QUEUED))
        is Event.DownloadStalled ->
            copy(dl = dl?.copy(status = com.gamebex.client.net.DownloadEngine.ST_PARKED_SD))
        is Event.AssistOpened ->
            copy(dl = dl?.copy(status = com.gamebex.client.net.DownloadEngine.ST_NEEDS_ASSIST))
        is Event.DownloadDone -> copy(dl = null, rowExists = true, local = true)
        is Event.DownloadFailed ->
            copy(dl = dl?.copy(status = com.gamebex.client.net.DownloadEngine.ST_FAILED))
        is Event.RomAppeared -> copy(rowExists = true, local = true)
        is Event.RomMissing -> copy(local = false)
        is Event.PrepStarted -> copy(prep = Prep.PREPARING)
        is Event.PrepDone -> copy(
            prep = event.prep, biosReady = event.biosReady,
            biosMissing = event.biosMissing, prepNote = event.note,
        )
        is Event.BiosFixed -> copy(
            biosReady = true, biosMissing = emptyList(),
            prep = if (prep == Prep.NEEDS_BIOS) Prep.READY else prep,
        )
        is Event.RuntimeInstalled -> copy(runtimeInstalled = true)
        is Event.CoreInstalled -> copy(coreReady = true)
        is Event.SpaceFreed -> copy(spaceOk = true)
        is Event.SpaceLow -> copy(spaceOk = false)
        is Event.Launched -> copy(playing = true)
        is Event.Returned -> copy(playing = false)
    }
}

/**
 * The unified game object. Factories translate storage rows; [status] and
 * [cta] always derive from [cap] so they can't drift.
 */
data class PlayableGame(
    val key: String,
    val title: String,
    val systemLabel: String,
    val folder: String?,
    val artBox: String?,
    val artHero: String?,
    val artLogo: String?,
    val artShot: String?,
    val cap: PlayStates.Capabilities,
) {
    val status: PlayStates.Status get() = PlayStates.derive(cap)
    val cta: PlayStates.Cta get() = PlayStates.ctaFor(status, cap.route)

    companion object {
        fun prepOf(state: String): PlayStates.Prep = when (state) {
            "READY" -> PlayStates.Prep.READY
            "NEEDS_BIOS" -> PlayStates.Prep.NEEDS_BIOS
            "NEEDS_MANUAL" -> PlayStates.Prep.NEEDS_MANUAL
            "FAILED" -> PlayStates.Prep.FAILED
            else -> PlayStates.Prep.NONE
        }

        fun fromLocal(
            game: GameEntity,
            dl: DownloadRecord?,
            biosReady: Boolean,
            biosMissing: List<String> = emptyList(),
            runtimeInstalled: Boolean,
            coreReady: Boolean,
            spaceOk: Boolean,
            preparing: Boolean = false,
        ): PlayableGame {
            val platform = game.folder.let { PlatformRegistry.byFolder[it] }
            val route = platform?.route ?: Route.Blocked("Unknown system ${game.folder}")
            // Cartridge folders need no preparation: an un-prepped cartridge
            // row is Ready, not Preparing. Disc/arcade rows must run prep.
            val rawPrep = prepOf(game.prepState)
            val prep = when {
                preparing -> PlayStates.Prep.PREPARING
                rawPrep == PlayStates.Prep.NONE &&
                    game.folder !in BiosTable.DISC_FOLDERS &&
                    game.folder !in BiosTable.ARCADE_FOLDERS -> PlayStates.Prep.READY
                else -> rawPrep
            }
            return PlayableGame(
                key = "local:${game.id}", title = game.title,
                systemLabel = platform?.display ?: game.folder, folder = game.folder,
                artBox = game.artBox, artHero = game.artHero,
                artLogo = game.artLogo, artShot = game.artShot,
                cap = PlayStates.Capabilities(
                    route = route, rowExists = true, local = game.available,
                    prep = prep, biosReady = biosReady, biosMissing = biosMissing,
                    prepNote = game.prepNote.orEmpty(),
                    runtimeInstalled = runtimeInstalled, coreReady = coreReady,
                    spaceOk = spaceOk, deleted = game.deletedAtMs > 0,
                    dl = dl?.takeIf {
                        it.status != com.gamebex.client.net.DownloadEngine.ST_DONE
                    }?.let { PlayStates.Dl(it.status, it.bytes, it.totalBytes, sourceId = it.sourceId) },
                ),
            )
        }

        fun fromCatalog(
            entry: RomEntry,
            folder: String?,
            dl: DownloadRecord?,
            spaceOk: Boolean,
        ): PlayableGame {
            val route = folder?.let { PlatformRegistry.byFolder[it]?.route }
                ?: Route.Blocked("No system mapped yet")
            return PlayableGame(
                key = entry.key.toStableId(), title = entry.title,
                systemLabel = entry.consoleName ?: folder ?: "?",
                folder = folder,
                artBox = entry.coverUrl, artHero = null, artLogo = null, artShot = null,
                cap = PlayStates.Capabilities(
                    route = route, rowExists = false, local = false,
                    prep = PlayStates.Prep.NONE, biosReady = true,
                    runtimeInstalled = false, coreReady = false,
                    spaceOk = spaceOk,
                    dl = dl?.takeIf {
                        it.status != com.gamebex.client.net.DownloadEngine.ST_DONE
                    }?.let { PlayStates.Dl(it.status, it.bytes, it.totalBytes, sourceId = it.sourceId) },
                ),
            )
        }
    }
}
