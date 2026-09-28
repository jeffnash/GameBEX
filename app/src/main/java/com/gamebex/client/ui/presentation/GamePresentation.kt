package com.gamebex.client.ui.presentation

import com.gamebex.client.data.PlayStates
import com.gamebex.client.data.PlayableGame

/**
 * Tier-1 UI projection. Thin view of the PlayableGame — the state machine
 * stays the single source of truth; this only shapes what console surfaces
 * render.
 */
data class GamePresentation(
    val stableId: String,
    val title: String,
    val platformLabel: String,
    val cover: Any?,
    val hero: Any?,
    val availability: GameAvailability,
    val primaryAction: GamePrimaryAction,
    val lastPlayedMs: Long? = null,
    val sizeBytes: Long? = null,
    val metaLine: String? = null,
    val folder: String? = null,
)

enum class GameAvailability {
    Local,
    Available,
    Downloading,
    Preparing,
    NeedsSetup,
    /** Storage volume holding the file is gone: Locate file. */
    Missing,
    /** System has no supported route: nothing to fix from the game. */
    Unsupported,
}

sealed interface GamePrimaryAction {
    data object Play : GamePrimaryAction
    data object Continue : GamePrimaryAction
    /** Catalog-only: the one extra step before Play. */
    data object Download : GamePrimaryAction
    /** Live download: progress display, primary press is a no-op. */
    data class Downloading(val pct: Int?) : GamePrimaryAction
    /** Live prep (extract, copy, fix): progress display, primary press is a no-op. */
    data object Preparing : GamePrimaryAction
    data object Resume : GamePrimaryAction
    data object Retry : GamePrimaryAction
    /** Cloudflare-challenged host: open the WebView assist. */
    data object Verify : GamePrimaryAction
    /** Owning source missing/disabled: open Sources. */
    data object ManageSources : GamePrimaryAction
    data object FinishSetup : GamePrimaryAction
    /** OUYA: the ROM is an installable app. */
    data object Install : GamePrimaryAction
    /** Guided runtimes: open the app, import inside it. */
    data class OpenApp(val appName: String) : GamePrimaryAction
}

/** Mapping sourced from PlayableGame status + CTA. */
fun PlayableGame.toPresentation(
    lastPlayedMs: Long? = null,
    sizeBytes: Long? = null,
    metaLine: String? = null,
    continued: Boolean = false,
    /** Cover fallback when no box art was fetched. */
    coverFallback: Any? = null,
): GamePresentation {
    val availability = when (status) {
        is PlayStates.Status.Ready,
        is PlayStates.Status.Playing,
        -> GameAvailability.Local
        is PlayStates.Status.Available -> GameAvailability.Available
        is PlayStates.Status.Downloading,
        is PlayStates.Status.DownloadFailed,
        -> GameAvailability.Downloading
        is PlayStates.Status.Preparing -> GameAvailability.Preparing
        is PlayStates.Status.NeedsBios,
        is PlayStates.Status.NeedsManual,
        is PlayStates.Status.NeedsRuntime,
        is PlayStates.Status.NeedsCore,
        is PlayStates.Status.NeedsSpace,
        is PlayStates.Status.GuidedImport,
        is PlayStates.Status.SourceUnavailable,
        -> GameAvailability.NeedsSetup
        is PlayStates.Status.NeedsVolume -> GameAvailability.Missing
        is PlayStates.Status.Blocked -> GameAvailability.Unsupported
    }
    val action = when (cta) {
        is PlayStates.Cta.Play -> if (continued) GamePrimaryAction.Continue else GamePrimaryAction.Play
        is PlayStates.Cta.InstallApp -> GamePrimaryAction.Install
        is PlayStates.Cta.OpenRuntime ->
            GamePrimaryAction.OpenApp((cta as PlayStates.Cta.OpenRuntime).emu.label)
        is PlayStates.Cta.Get -> GamePrimaryAction.Download
        is PlayStates.Cta.ResumeDownload -> GamePrimaryAction.Resume
        is PlayStates.Cta.RetryDownload -> GamePrimaryAction.Retry
        is PlayStates.Cta.OpenAssist -> GamePrimaryAction.Verify
        is PlayStates.Cta.ManageSources -> GamePrimaryAction.ManageSources
        is PlayStates.Cta.Prepare ->
            if (cap.prep == PlayStates.Prep.PREPARING) GamePrimaryAction.Preparing else GamePrimaryAction.FinishSetup
        is PlayStates.Cta.None -> when (val s = status) {
            is PlayStates.Status.Downloading ->
                GamePrimaryAction.Downloading(if (s.total > 0) (s.read * 100 / s.total).toInt() else null)
            else -> GamePrimaryAction.FinishSetup
        }
        else -> GamePrimaryAction.FinishSetup
    }
    return GamePresentation(
        stableId = key, title = title, platformLabel = systemLabel,
        cover = artBox ?: coverFallback, hero = artHero, availability = availability,
        primaryAction = action, lastPlayedMs = lastPlayedMs,
        sizeBytes = sizeBytes, metaLine = metaLine, folder = folder,
    )
}

fun GamePrimaryAction.label(): String = when (this) {
    GamePrimaryAction.Play -> "Play"
    GamePrimaryAction.Continue -> "Continue"
    GamePrimaryAction.Download -> "Download"
    is GamePrimaryAction.Downloading -> if (pct != null) "Downloading… $pct%" else "Downloading…"
    GamePrimaryAction.Preparing -> "Preparing…"
    GamePrimaryAction.Resume -> "Resume"
    GamePrimaryAction.Retry -> "Retry"
    GamePrimaryAction.Verify -> "Verify & continue"
    GamePrimaryAction.ManageSources -> "Manage sources"
    GamePrimaryAction.FinishSetup -> "Finish Setup"
    GamePrimaryAction.Install -> "Install"
    is GamePrimaryAction.OpenApp -> "Open $appName"
}

/**
 * CTA copy for surfaces that show availability: an unsupported system
 * says so (disabled, nothing to fix from the game); a missing storage
 * volume offers Locate file; everything else is the primary action.
 */
fun GamePresentation.ctaLabel(): String = when (availability) {
    GameAvailability.Unsupported -> "Unsupported"
    GameAvailability.Missing -> "Locate file"
    GameAvailability.Local,
    GameAvailability.Available,
    GameAvailability.Downloading,
    GameAvailability.Preparing,
    GameAvailability.NeedsSetup,
    -> primaryAction.label()
}

/** False when the CTA has nothing to run (unsupported system). */
val GamePresentation.ctaEnabled: Boolean
    get() = availability != GameAvailability.Unsupported
