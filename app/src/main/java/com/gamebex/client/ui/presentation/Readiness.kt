package com.gamebex.client.ui.presentation

import com.gamebex.client.data.CoreNames
import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.PlayStates
import com.gamebex.client.data.Route

/** Semantic tone: maps to success/warning/error/gray. */
enum class Tone { Ready, Attention, Blocked, Neutral }

data class Readiness(val label: String, val tone: Tone)

/** One short readiness fact for a local game. No package IDs or paths. Pure. */
fun readinessOf(status: PlayStates.Status): Readiness = when (status) {
    is PlayStates.Status.Ready -> Readiness("Ready to play", Tone.Ready)
    is PlayStates.Status.Playing -> Readiness("Playing", Tone.Ready)
    is PlayStates.Status.Preparing -> Readiness("Preparing…", Tone.Neutral)
    is PlayStates.Status.NeedsRuntime -> Readiness("${status.emu.label} not installed", Tone.Attention)
    is PlayStates.Status.NeedsCore -> Readiness("Core not installed", Tone.Attention)
    is PlayStates.Status.NeedsBios -> Readiness("BIOS missing", Tone.Attention)
    is PlayStates.Status.NeedsManual -> Readiness("Needs a manual fix", Tone.Attention)
    is PlayStates.Status.NeedsSpace -> Readiness("Not enough space", Tone.Attention)
    is PlayStates.Status.GuidedImport -> Readiness("Import in ${status.emu.label}", Tone.Attention)
    is PlayStates.Status.NeedsVolume -> Readiness("Storage missing", Tone.Blocked)
    is PlayStates.Status.Blocked -> Readiness("Unsupported", Tone.Blocked)
    is PlayStates.Status.Available,
    is PlayStates.Status.Downloading,
    is PlayStates.Status.DownloadFailed,
    is PlayStates.Status.SourceUnavailable,
    -> Readiness("Not downloaded", Tone.Neutral)
}

/**
 * Emulator that will run [folder], honoring per-game overrides. Pure.
 * RetroArch cores read as "RetroArch · core"; unsupported or unknown systems read "None".
 */
fun emulatorLabel(folder: String?, emulatorOverride: String? = null, coreOverride: String? = null): String {
    emulatorOverride?.let { pkg -> Emu.entries.firstOrNull { it.pkg == pkg }?.let { return it.label } }
    return when (val route = folder?.let { PlatformRegistry.byFolder[it]?.route }) {
        is Route.RetroCore -> "RetroArch · ${CoreNames.display(coreOverride ?: route.core)}"
        is Route.Standalone -> route.emu.label
        is Route.GuidedInstall -> "${route.emu.label} (guided)"
        is Route.SideloadApp -> "Install as app"
        is Route.Blocked, null -> "None"
    }
}
