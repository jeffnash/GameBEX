package com.gamebex.client.ui.presentation

import com.gamebex.client.data.Emu
import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.launch.RetroCoreFetcher

/** Recovery actions shared by Home and Library quick-play. */
sealed interface LaunchAction {
    data object OpenDetails : LaunchAction
    data class Install(val emu: Emu) : LaunchAction
    data object Retry : LaunchAction
    data object OpenRetroArch : LaunchAction
}

fun LaunchAction.label(): String = when (this) {
    LaunchAction.OpenDetails -> "Open details"
    is LaunchAction.Install -> "Install"
    LaunchAction.Retry -> "Retry"
    LaunchAction.OpenRetroArch -> "Open RetroArch"
}

/**
 * Quick-play (Home, Library) status for one game. Core progress owns B to
 * cancel the real launch job, and offers Retry after a stall. Other busy
 * states do not capture A/B. Settled states offer recovery and B to dismiss.
 */
data class LaunchStatus(
    val gameId: Long,
    val text: String,
    val tone: Tone,
    val action: LaunchAction? = null,
    val busy: Boolean = false,
    /** Retry replays the same launch mode (a fresh start stays fresh). */
    val fresh: Boolean = false,
    val coreProgress: CoreInstallProgress? = null,
    val secondaryAction: LaunchAction? = null,
) {
    val canCancel: Boolean get() = busy && coreProgress != null

    companion object {
        fun progress(gameId: Long, text: String, fresh: Boolean = false) =
            LaunchStatus(gameId, text, Tone.Neutral, busy = true, fresh = fresh)

        fun coreInstall(gameId: Long, progress: CoreInstallProgress, fresh: Boolean = false) =
            LaunchStatus(
                gameId, progress.text, Tone.Neutral,
                action = LaunchAction.Retry.takeIf { progress.stalled },
                busy = true, fresh = fresh, coreProgress = progress,
            )

        fun cancelled(gameId: Long, fresh: Boolean) = LaunchStatus(
            gameId, "Core installation cancelled. Retry when you're ready.", Tone.Attention,
            LaunchAction.Retry, fresh = fresh,
        )
    }
}

/**
 * Router outcome → quick-play panel. Launched clears the panel (the boot
 * is counted on resume). Pure.
 */
fun launchStatusFor(
    gameId: Long,
    out: EmuRouter.Outcome,
    fresh: Boolean = false,
    retroCore: Boolean = false,
): LaunchStatus? =
    when (out) {
        is EmuRouter.Outcome.Launched -> null
        is EmuRouter.Outcome.MissingEmu -> LaunchStatus(
            gameId, "${out.emu.label} isn't installed.", Tone.Attention,
            LaunchAction.Install(out.emu), fresh = fresh,
        )
        is EmuRouter.Outcome.Blocked ->
            LaunchStatus(gameId, out.reason, Tone.Blocked, LaunchAction.OpenDetails, fresh = fresh)
        is EmuRouter.Outcome.NeedsSetup -> LaunchStatus(
            gameId, out.text, Tone.Attention,
            action = if (retroCore && out.text == RetroCoreFetcher.INSTALL_SETUP_GUIDANCE)
                LaunchAction.OpenRetroArch else LaunchAction.OpenDetails,
            fresh = fresh,
            secondaryAction = LaunchAction.Retry.takeIf { retroCore },
        )
        is EmuRouter.Outcome.Failed ->
            LaunchStatus(gameId, out.msg, Tone.Attention, LaunchAction.Retry, fresh = fresh)
    }
