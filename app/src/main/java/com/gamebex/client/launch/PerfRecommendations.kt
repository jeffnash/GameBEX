package com.gamebex.client.launch

import com.gamebex.client.data.Emu
import com.gamebex.client.data.Platform
import com.gamebex.client.data.Route

/** How a recommendation reaches the emulator. */
enum class RecKind {
    /** GameBEX writes it ("Apply recommended settings"). */
    AUTO,
    /** Already in the config GameBEX passes on every launch. */
    MANAGED,
    /** The user sets it inside the emulator (its settings aren't writable from outside). */
    MANUAL,
    /** Only if a game runs slow: has a trade-off, set inside the emulator. */
    TRY_IF_SLOW,
    /** Expectation, not a setting. */
    NOTE,
}

/**
 * One row: [setting] is the emulator's own UI label (or an upstream-
 * documented name), [value] what to pick, [why] the reason in one line.
 * [key] names the writer for [RecKind.AUTO] rows (see [PerfTuner]).
 */
data class PerfRec(
    val setting: String,
    val value: String,
    val why: String,
    val kind: RecKind,
    val key: String? = null,
)

/** Recommendations for one emulator (+ RetroArch core) on one tier. */
data class PerfPlan(val emu: Emu, val core: String?, val tier: DeviceTier, val recs: List<PerfRec>) {
    val applicable: Boolean get() = recs.any { it.kind == RecKind.AUTO }
}

/**
 * Device-aware settings table. Setting names are the ones shown in each
 * emulator's own UI (NetherSX2's list was read on-device) or
 * documented upstream (PCSX2, Dolphin, PPSSPP, libretro core options);
 * nothing is invented. Only Dolphin's two keys are written by GameBEX —
 * everything else is private to the emulator or not worth the risk.
 * Pure: unit-tested per tier.
 */
object PerfRecommendations {
    const val DOLPHIN_BACKEND = "dolphin.backend"
    const val DOLPHIN_RES = "dolphin.res"

    /** Plans for a system: its route emulator first, then its alternates. */
    fun forPlatform(platform: Platform, tier: DeviceTier): List<PerfPlan> {
        val primary = when (val r = platform.route) {
            is Route.RetroCore -> forEmu(Emu.RETROARCH, r.core, tier)
            is Route.Standalone -> forEmu(r.emu, null, tier)
            is Route.GuidedInstall -> forEmu(r.emu, null, tier)
            else -> null
        }
        val alts = platform.altEmus.mapNotNull { forEmu(it, null, tier) }
        return listOfNotNull(primary) + alts
    }

    /** Null when the emulator needs nothing on this tier. */
    fun forEmu(emu: Emu, core: String?, tier: DeviceTier): PerfPlan? {
        val low = tier == DeviceTier.LOW
        val recs: List<PerfRec> = when (emu) {
            Emu.RETROARCH -> retroArch(core, tier)
            Emu.NETHER -> nether(tier)
            Emu.ARMSX2 -> armsx2(tier)
            Emu.DOLPHIN -> listOfNotNull(
                PerfRec("Video Backend", "Vulkan", "Usually the faster backend on phone GPUs.", RecKind.AUTO, DOLPHIN_BACKEND),
                if (low) {
                    PerfRec("Internal Resolution", "Native (1x)", "Upscaling costs GPU time a low-end GPU doesn't have.", RecKind.AUTO, DOLPHIN_RES)
                } else {
                    PerfRec("Internal Resolution", "2x", "Sharp on a 720p screen; drop to 1x if it stutters.", RecKind.MANUAL)
                },
                PerfRec("Enable Dual Core", "On", "Runs the GPU thread on a second fast core.", RecKind.MANUAL),
                if (low) PerfRec("Shader Compilation Mode", "Asynchronous (Skip Drawing)", "Cuts shader-compile stutter; may skip a few effects the first time they appear.", RecKind.TRY_IF_SLOW) else null,
                if (low) PerfRec("Full speed", "Not guaranteed", "Heavier GameCube/Wii games need more fast cores than this phone has.", RecKind.NOTE) else null,
            )
            Emu.PPSSPP -> listOfNotNull(
                PerfRec("Rendering Resolution", if (low) "1x PSP" else "2x PSP", if (low) "Keeps the GPU at full speed." else "Crisp on 720p, cheap on this chip.", RecKind.MANUAL),
                if (low) PerfRec("Frame Skipping", "Off (try 1 if slow)", "Frame skip hides slowdown but makes motion choppy.", RecKind.TRY_IF_SLOW) else null,
            )
            Emu.AZAHAR -> listOfNotNull(
                PerfRec("Internal Resolution", if (tier == DeviceTier.HIGH) "2x" else "1x (Native)", "3DS rendering above native is GPU-heavy.", RecKind.MANUAL),
                if (low) PerfRec("Full speed", "Not guaranteed", "3DS emulation leans on fast cores; many 3D games will run slow here.", RecKind.NOTE) else null,
            )
            Emu.MELONDS -> listOf(
                PerfRec("Internal resolution", if (low) "1x" else "2x", if (low) "DS 3D upscaling costs CPU on this chip." else "Sharp on 720p.", RecKind.MANUAL),
            )
            Emu.FLYCAST -> listOfNotNull(
                PerfRec("Internal Resolution", if (low) "640x480" else "1280x720", if (low) "Native Dreamcast resolution holds 60 fps." else "Matches the 720p screen.", RecKind.MANUAL),
            )
            Emu.VITA3K -> listOfNotNull(
                PerfRec("Resolution multiplier", "1x", "Vita rendering is heavy even at native.", RecKind.MANUAL),
                if (tier != DeviceTier.HIGH) PerfRec("Full speed", "Not guaranteed", "Vita3K needs strong cores; expect many games to run slow.", RecKind.NOTE) else null,
            )
            Emu.SCUMMVM, Emu.MAME4DROID, Emu.J2MELOADER -> emptyList()
        }
        return if (recs.isEmpty()) null else PerfPlan(emu, core, tier, recs)
    }

    /**
     * NetherSX2 global settings are private (not writable from outside);
     * per-game INI writing is skipped (see [PerfTuner]). Everything here is
     * a manual step with the label NetherSX2 itself shows.
     */
    /** ARMSX2 2.7 labels (read from the APK's strings). */
    private fun armsx2(tier: DeviceTier): List<PerfRec> {
        val low = tier == DeviceTier.LOW
        return listOfNotNull(
            PerfRec("Renderer", "Vulkan", "Lowest driver overhead on phone GPUs; its Mali fast path needs it.", RecKind.MANUAL),
            PerfRec("Internal Resolution", if (low) "Native" else "2x", if (low) "Upscaling costs GPU time the phone doesn't have." else "Sharp on a 720p screen.", RecKind.MANUAL),
            PerfRec("Performance Cores", "On", "Pins the heavy PS2 threads to the fast cores.", RecKind.MANUAL),
            if (low) PerfRec("Full speed", "Not guaranteed", "Below ARMSX2's stated minimum (Dimensity 8300 class); heavy games will dip.", RecKind.NOTE) else null,
        )
    }

    private fun nether(tier: DeviceTier): List<PerfRec> {
        val low = tier == DeviceTier.LOW
        return listOfNotNull(
            PerfRec("Enable Frame Limit", "On", "Off lets games run uncapped: uneven speed and broken audio pacing.", RecKind.MANUAL),
            PerfRec("GPU Renderer", "Vulkan", "Lowest driver overhead on phone GPUs.", RecKind.MANUAL),
            PerfRec("Upscale Multiplier", if (low) "Native (1x)" else "2x", if (low) "Upscaling costs GPU time the phone doesn't have." else "Sharp on a 720p screen.", RecKind.MANUAL),
            PerfRec("MTVU", "On", "Moves VU1 to its own thread; keep it on.", RecKind.MANUAL),
            if (low) PerfRec("EE Cycle Rate", "-1 (75%)", "Lighter emulated CPU; some games then run slower in-game or glitch — set back to 0 if so.", RecKind.TRY_IF_SLOW) else null,
            if (low) PerfRec("Hardware Download Mode", "Unsynchronized (or Disabled)", "Skips GPU readback waits; some effects may flicker or vanish.", RecKind.TRY_IF_SLOW) else null,
            if (low) PerfRec("Full speed", "Not guaranteed", "PS2 needs ~3 busy threads; with 2 fast cores heavy games will dip.", RecKind.NOTE) else null,
        )
    }

    /**
     * RetroArch: the managed CONFIGFILE already carries the CPU-friendly
     * frontend settings (GraphicsTuner); video_threaded stays off because
     * it crashed the N64 core on-device. Heavy cores add core-option hints
     * (labels from the libretro cores' option lists).
     */
    private fun retroArch(core: String?, tier: DeviceTier): List<PerfRec> {
        val low = tier == DeviceTier.LOW
        val managed = listOf(
            PerfRec("Rewind", "Off", "Rewind snapshots cost CPU every frame.", RecKind.MANAGED),
            PerfRec("Threaded Video", "Off", "Crashed the N64 core at boot in on-device testing.", RecKind.MANAGED),
        )
        val perCore: List<PerfRec> = when (core) {
            "mednafen_saturn" -> listOf(
                PerfRec("Full speed", if (low) "Not expected" else "Not guaranteed", "Beetle Saturn is accuracy-first and very CPU-heavy.", RecKind.NOTE),
            )
            "mednafen_psx" -> listOf(
                PerfRec("Internal GPU Resolution", "1x (native)", "Software upscaling multiplies CPU cost.", RecKind.MANUAL),
            )
            "ppsspp" -> listOf(
                PerfRec("Internal Resolution", if (low) "480x272" else "960x544", "Native PSP resolution is cheapest.", RecKind.MANUAL),
            )
            "flycast" -> listOf(
                PerfRec("Internal Resolution", if (low) "640x480" else "1280x960", "Native Dreamcast resolution holds 60 fps.", RecKind.MANUAL),
            )
            "citra" -> listOfNotNull(
                PerfRec("Resolution scale factor", "1x", "3DS upscaling is GPU-heavy.", RecKind.MANUAL),
                if (low) PerfRec("Full speed", "Not guaranteed", "3DS emulation leans on fast cores.", RecKind.NOTE) else null,
            )
            "dolphin" -> listOfNotNull(
                PerfRec("Internal Resolution", if (low) "1x (Native)" else "2x", "GameCube/Wii upscaling is GPU-heavy.", RecKind.MANUAL),
                if (low) PerfRec("Full speed", "Not guaranteed", "Heavier games need more fast cores than this phone has.", RecKind.NOTE) else null,
            )
            else -> emptyList()
        }
        return (if (low) perCore + managed else perCore + managed.take(1)).take(5)
    }
}
