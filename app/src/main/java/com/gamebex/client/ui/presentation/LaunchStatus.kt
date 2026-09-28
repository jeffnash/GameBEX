package com.gamebex.client.ui.presentation

import com.gamebex.client.data.Emu
import com.gamebex.client.launch.EmuRouter

/** The one recovery a quick-play status panel offers on A. */
sealed interface LaunchAction {
    data object OpenDetails : LaunchAction
    data class Install(val emu: Emu) : LaunchAction
    data object Retry : LaunchAction
}

fun LaunchAction.label(): String = when (this) {
    LaunchAction.OpenDetails -> "Open details"
    is LaunchAction.Install -> "Install"
    LaunchAction.Retry -> "Retry"
}

/**
 * Quick-play (Home, Library) status for one game. [busy] states are neutral
 * progress ("Launching…") and never capture A/B; the rest carry a tone and
 * at most one [action], with B to dismiss.
 */
data class LaunchStatus(
    val gameId: Long,
    val text: String,
    val tone: Tone,
    val action: LaunchAction? = null,
    val busy: Boolean = false,
    /** Retry replays the same launch mode (a fresh start stays fresh). */
    val fresh: Boolean = false,
) {
    companion object {
        fun progress(gameId: Long, text: String) =
            LaunchStatus(gameId, text, Tone.Neutral, busy = true)
    }
}

/**
 * Router outcome → quick-play panel. Launched clears the panel (the boot
 * is counted on resume). Pure.
 */
fun launchStatusFor(gameId: Long, out: EmuRouter.Outcome, fresh: Boolean = false): LaunchStatus? =
    when (out) {
        is EmuRouter.Outcome.Launched -> null
        is EmuRouter.Outcome.MissingEmu -> LaunchStatus(
            gameId, "${out.emu.label} isn't installed.", Tone.Attention,
            LaunchAction.Install(out.emu), fresh = fresh,
        )
        is EmuRouter.Outcome.Blocked ->
            LaunchStatus(gameId, out.reason, Tone.Blocked, LaunchAction.OpenDetails, fresh = fresh)
        is EmuRouter.Outcome.NeedsSetup ->
            LaunchStatus(gameId, out.text, Tone.Attention, LaunchAction.OpenDetails, fresh = fresh)
        is EmuRouter.Outcome.Failed ->
            LaunchStatus(gameId, out.msg, Tone.Attention, LaunchAction.Retry, fresh = fresh)
    }
