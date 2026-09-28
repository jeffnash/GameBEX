package com.gamebex.client.ui.presentation

import com.gamebex.client.data.BiosTable
import com.gamebex.client.data.CoreNames
import com.gamebex.client.data.Emu
import com.gamebex.client.data.EmuCatalog
import com.gamebex.client.data.Platform
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.Route
import com.gamebex.client.ui.UserErrors

/**
 * System readiness first, emulator identity secondary. Pure projection
 * over route + probes; the screen maps it to rows.
 */
data class SystemReadinessItem(
    val platformId: String,
    val displayName: String,
    val folder: String,
    val state: SystemReadinessState,
    /** One-line state detail (reason, versions, core name). */
    val detail: String?,
    val primaryAction: SystemAction?,
    /** "RetroArch · gambatte" / "NetherSX2 1.0" / null. */
    val runtimeLabel: String?,
    /** "4 games · 1 needs BIOS" / null when no local games. */
    val gamesLine: String?,
    /** BIOS placement guidance (text only, never a download). */
    val biosGuide: String?,
    /** One-time touch-overlay hide path for the route's emulator. */
    val overlayGuide: String?,
    /** Graphics defaults for the 1600x720 handheld. */
    val graphicsGuide: String?,
    /** Gamepad mapping path for the route's emulator. */
    val controllerGuide: String?,
)

enum class SystemReadinessState {
    Ready,
    NeedsSetup,
    MissingRuntime,
    UpdateAvailable,
    LimitedSupport,
}

sealed interface SystemAction {
    data class Install(val pkg: String) : SystemAction
    data class Open(val pkg: String) : SystemAction
    /** Missing core: open RetroArch for its Online Updater. */
    data object OpenCoreUpdater : SystemAction
    /** Core system-file pack missing: GameBEX fetches it from the buildbot. */
    data class FetchSystemFiles(val core: String) : SystemAction
}

/** Prep breakdown of one folder's local games. */
data class PrepSummary(
    val total: Int,
    val ready: Int,
    val needsBios: Int,
    val needsManual: Int,
    val failed: Int,
)

/**
 * Pure derivation: route + runtime probe + core probe + reviewed pin +
 * prep counts -> readiness. Every non-Ready state names its evidence;
 * nothing is hardcoded.
 */
fun deriveSystemReadiness(
    platform: Platform,
    emuInstalled: Boolean,
    emuVersionName: String?,
    emuVersionCode: Long,
    pinnedVersionCode: Long,
    cores: Set<String>?,
    prep: PrepSummary?,
    /** Cores whose system-file pack is missing (see CoreSystemFiles). */
    missingSystemFiles: Set<String> = emptySet(),
): SystemReadinessItem {
    val needsBios = prep?.needsBios ?: 0
    val gamesLine = prep?.takeIf { it.total > 0 }?.let {
        buildString {
            append(countOf(it.total, "game"))
            val flags = listOf(
                it.needsBios to verb(it.needsBios, "needs", "need") + " BIOS",
                it.needsManual to verb(it.needsManual, "needs", "need") + " files",
                it.failed to "failed",
            ).filter { (n, _) -> n > 0 }
            if (flags.isNotEmpty()) {
                append(flags.joinToString(", ", " · ") { (n, w) -> "$n $w" })
            }
        }
    }
    val biosGuide = if (needsBios > 0) biosGuideFor(platform.folder) else null
    val routeEmu = when (val r = platform.route) {
        is Route.RetroCore -> Emu.RETROARCH
        is Route.Standalone -> r.emu
        is Route.GuidedInstall -> r.emu
        else -> null
    }
    val overlayGuide = routeEmu?.let(::overlayGuideFor)
    val graphicsGuide = routeEmu?.let(::graphicsGuideFor)
    val controllerGuide = routeEmu?.let(::controllerGuideFor)
    fun base(
        state: SystemReadinessState,
        detail: String?,
        action: SystemAction?,
        runtime: String?,
    ) = SystemReadinessItem(
        platformId = platform.slug, displayName = platform.display,
        folder = platform.folder, state = state, detail = detail,
        primaryAction = action, runtimeLabel = runtime,
        gamesLine = gamesLine, biosGuide = biosGuide,
        overlayGuide = overlayGuide, graphicsGuide = graphicsGuide,
        controllerGuide = controllerGuide,
    )
    return when (val r = platform.route) {
        // The route's reason is internal (roadmap notes); callers log it.
        is Route.Blocked ->
            base(SystemReadinessState.LimitedSupport, UserErrors.unsupportedSystem(platform.display), null, null)
        is Route.SideloadApp ->
            base(SystemReadinessState.LimitedSupport, "ROMs install as Android apps.", null, null)
        is Route.RetroCore -> {
            val coreName = CoreNames.display(r.core)
            val runtime = "RetroArch · $coreName"
            when {
                !emuInstalled -> base(
                    SystemReadinessState.MissingRuntime,
                    "RetroArch is not installed.", SystemAction.Install(Emu.RETROARCH.pkg),
                    "RetroArch",
                )
                cores == null -> base(
                    SystemReadinessState.NeedsSetup,
                    "Core check unavailable — verify in RetroArch.",
                    SystemAction.OpenCoreUpdater, runtime,
                )
                !cores.contains(r.core) -> base(
                    SystemReadinessState.NeedsSetup,
                    "Install the $coreName core (Online Updater).",
                    SystemAction.OpenCoreUpdater, runtime,
                )
                r.core in missingSystemFiles -> base(
                    SystemReadinessState.NeedsSetup,
                    "$coreName system files missing — press A to download them.",
                    SystemAction.FetchSystemFiles(r.core), runtime,
                )
                needsBios > 0 -> base(
                    SystemReadinessState.NeedsSetup,
                    "${countOf(needsBios, "game")} ${verb(needsBios, "needs", "need")} BIOS files.",
                    null, runtime,
                )
                else -> base(
                    SystemReadinessState.Ready,
                    "RetroArch${emuVersionName?.let { " $it" } ?: ""} · $coreName core ready.",
                    SystemAction.Open(Emu.RETROARCH.pkg), runtime,
                )
            }
        }
        is Route.Standalone, is Route.GuidedInstall -> {
            val emu = if (r is Route.Standalone) r.emu else (r as Route.GuidedInstall).emu
            val guided = r is Route.GuidedInstall
            val runtime = emu.label + (emuVersionName?.let { " $it" } ?: "")
            when {
                !emuInstalled -> base(
                    SystemReadinessState.MissingRuntime,
                    "${emu.label} is not installed.", SystemAction.Install(emu.pkg),
                    emu.label,
                )
                pinnedVersionCode > 0 && emuVersionCode < pinnedVersionCode -> base(
                    SystemReadinessState.UpdateAvailable,
                    "A reviewed ${emu.label} build is available.",
                    SystemAction.Install(emu.pkg), runtime,
                )
                needsBios > 0 -> base(
                    SystemReadinessState.NeedsSetup,
                    "${countOf(needsBios, "game")} ${verb(needsBios, "needs", "need")} BIOS files.",
                    null, runtime,
                )
                else -> base(
                    SystemReadinessState.Ready,
                    runtime + if (guided) " (guided import)" else "",
                    SystemAction.Open(emu.pkg), runtime,
                )
            }
        }
    }
}

/** PackageManager-style probe of one emulator package (readiness needs no more). */
data class EmuSnap(val installed: Boolean, val versionName: String?, val versionCode: Long)

/**
 * The System screen's per-system item derivation, shared by the System
 * screen and the header warning count (DeviceStatus) so both always
 * agree. [cores] is null until the core probe has run or while it is
 * unavailable: unknown never reads as "core missing". [probe] resolves an
 * emulator package's installed snapshot. Pure projection (one log
 * breadcrumb); registry order preserved.
 */
fun systemReadinessItems(
    cores: Set<String>?,
    prepMap: Map<String, PrepSummary>,
    missingSystemFiles: Set<String>,
    probe: (String) -> EmuSnap,
): List<SystemReadinessItem> =
    systemPlatforms().map { p ->
        (p.route as? Route.Blocked)?.takeIf { (prepMap[p.folder]?.total ?: 0) > 0 }?.let { r ->
            // Internal route note stays in logs; the row shows user copy.
            runCatching { android.util.Log.d("GameBEXSystem", "${p.slug} limited: ${r.reason}") }
        }
        val pkg = when (val r = p.route) {
            is Route.RetroCore -> Emu.RETROARCH.pkg
            is Route.Standalone -> r.emu.pkg
            is Route.GuidedInstall -> r.emu.pkg
            else -> null
        }
        val snap = pkg?.let(probe)
        val pin = pkg?.let { EmuCatalog.byPkg[it] }
        deriveSystemReadiness(
            platform = p,
            emuInstalled = snap?.installed == true,
            emuVersionName = snap?.versionName,
            emuVersionCode = snap?.versionCode ?: 0,
            pinnedVersionCode = pin?.pinnedVersionCode ?: 0,
            cores = cores,
            prep = prepMap[p.folder],
            missingSystemFiles = missingSystemFiles,
        )
    }

/** "1 game" / "3 games". Pure. */
fun countOf(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"

/** Present-tense verb agreeing with [n]: verb(1, "needs", "need") = "needs". */
private fun verb(n: Int, singular: String, plural: String): String = if (n == 1) singular else plural

/**
 * One-time touch-overlay hide path for an emulator. Toggle labels come
 * from each installed build's own strings: prefer the
 * auto-hide-when-controller-connected switch where one exists so game
 * graphics fill the screen as soon as a BT controller connects.
 */
fun overlayGuideFor(emu: Emu): String = when (emu) {
    Emu.RETROARCH ->
        "Settings → On-Screen Display → On-Screen Overlay → turn ON Hide Overlay When Controller Is Connected."
    Emu.PPSSPP ->
        "Settings → Controls → turn OFF On-Screen Touch Controls."
    Emu.MELONDS ->
        "Settings → Input → turn ON Hide console buttons when a controller is connected."
    Emu.AZAHAR ->
        "In-game menu → turn OFF Show Controller Overlay."
    Emu.DOLPHIN ->
        "In-game menu → Overlay Controls → turn the overlay off."
    Emu.NETHER ->
        "Settings → Touchscreen → turn ON Hide With External Controller."
    // ARMSX2 labels read from the 2.7 APK's strings.
    Emu.ARMSX2 ->
        "Settings → On-Screen Controls → turn them off (or lower Overlay opacity)."
    Emu.VITA3K ->
        "Settings → turn OFF Show On-Screen Controls."
    Emu.SCUMMVM ->
        "Options → turn OFF Show On-screen control."
    Emu.MAME4DROID ->
        "Options → Input → turn ON Hide touch controls in-game."
    Emu.FLYCAST ->
        "Settings → General → turn OFF Virtual Gamepad."
    Emu.J2MELOADER ->
        "Settings → turn ON Hide buttons."
}

/**
 * Graphics defaults for the 1600x720 handheld (Dimensity MT6835). Labels
 * were read from each shipped build's own strings; values favor a full-
 * height picture at a resolution this chip holds at 60 fps. RetroArch is
 * the only managed one (a config is passed on every launch); the rest are
 * one-time manual settings, since Android blocks cross-app config writes.
 */
fun graphicsGuideFor(emu: Emu): String = when (emu) {
    Emu.RETROARCH ->
        "Managed automatically: fills screen height, smooth upscale, overlay auto-hide. Tweaks live in Settings → Video → Scaling."
    Emu.PPSSPP ->
        "Settings → Graphics → Rendering Resolution 2x (crisp on 720p, cheap on this chip)."
    Emu.MELONDS ->
        "Settings → Video → Internal resolution 2x; Screen layout → a landscape split."
    Emu.AZAHAR ->
        "Settings → Graphics → Internal Resolution 2x; Integer Scaling OFF; Layout → Large Screen."
    Emu.DOLPHIN ->
        "Graphics → General → Aspect Ratio: Stretch; Enhancements → Internal Resolution 2x."
    Emu.NETHER ->
        "Settings → Graphics → GPU Renderer → Vulkan; Upscale Multiplier 2x (native on entry-level phones); Aspect Ratio Stretch; Enable Frame Limit ON."
    Emu.ARMSX2 ->
        "Settings → Renderer → Vulkan; Internal Resolution native on entry-level phones; turn ON Performance Cores."
    Emu.VITA3K ->
        "Settings → GPU: Resolution multiplier 1x (this chip); stretch the display area ON."
    Emu.SCUMMVM ->
        "Options → Graphics: 2x/3x scaler; Aspect ratio correction ON."
    Emu.MAME4DROID ->
        "Options → Display → Scale: fit to screen; leave Effect shader stock."
    Emu.FLYCAST ->
        "Settings → Video → Internal Resolution 1280x720; Integer Scaling OFF; Widescreen ON."
    Emu.J2MELOADER ->
        "Settings → Display → Scale type to fill the screen; filtering OFF if slow."
}

/**
 * Gamepad mapping path per emulator. Standalone emulators scan inputs at
 * boot, so every guide leads with: connect the pad BEFORE launching.
 * Kept to path + rule (no button maps: those vary per pad/mode).
 */
fun controllerGuideFor(emu: Emu): String = when (emu) {
    Emu.RETROARCH ->
        "Connect the pad BEFORE launching. Auto-maps standard pads; Settings → Input → Port 1 Binds to remap. If your pad has input modes, use its Xbox/X-input mode."
    Emu.PPSSPP ->
        "Connect the pad BEFORE launching. Auto-maps sticks/D-pad; Settings → Controls → Control Mapping to remap."
    Emu.MELONDS ->
        "Connect the pad BEFORE launching. Settings → Input → Player 1 → tap each button to map it."
    Emu.AZAHAR ->
        "Connect the pad BEFORE launching. Settings → Controls → map buttons to the pad."
    Emu.DOLPHIN ->
        "Connect the pad BEFORE launching — Dolphin scans inputs at boot, reconnects don't register. GameCube: Settings → GameCube Input → Port 1 → pick your pad (not Emulated)."
    Emu.NETHER ->
        "Connect the pad BEFORE launching. Settings → Controller → Port 1 → pick your pad, tap entries to remap."
    Emu.ARMSX2 ->
        "Connect the pad BEFORE launching. Controller Settings → Controller mapping → map to your pad."
    Emu.VITA3K ->
        "Connect the pad BEFORE launching. Settings → Controls → map to the pad."
    Emu.SCUMMVM ->
        "Gamepads send key events: Options → Controls maps them like a keyboard. Connect before launching."
    Emu.MAME4DROID ->
        "Connect the pad BEFORE launching. Options → Input → define keys on the pad."
    Emu.FLYCAST ->
        "Connect the pad BEFORE launching. Settings → Controls → map the pad per port."
    Emu.J2MELOADER ->
        "Connect the pad BEFORE launching. Settings → Controls → remap phone keys to pad buttons."
}

/** Placement guidance for a folder's required BIOS files, or null. */
fun biosGuideFor(folder: String): String? {
    val needs = BiosTable.TABLE[folder]?.filter { it.required } ?: return null
    if (needs.isEmpty()) return null
    return needs.joinToString("\n") { n ->
        val names = n.files.joinToString(if (n.anyOf) " or " else ", ") { it.name }
        "Copy $names to ${n.dirHint}"
    }
}

/**
 * A system only "needs attention" when the player has games for it: an
 * unconfigured system with an empty folder is optional, not a problem. Pure.
 */
fun needsAttention(state: SystemReadinessState, gameCount: Int): Boolean =
    gameCount > 0 && when (state) {
        SystemReadinessState.NeedsSetup, SystemReadinessState.MissingRuntime,
        SystemReadinessState.UpdateAvailable -> true
        SystemReadinessState.Ready, SystemReadinessState.LimitedSupport -> false
    }

/** Prep counts rolled up per folder, the shared System/DeviceStatus shape. Pure. */
fun prepSummaries(counts: List<com.gamebex.client.data.local.PrepCount>): Map<String, PrepSummary> =
    counts.groupBy { it.folder }.mapValues { (_, rows) ->
        PrepSummary(
            total = rows.sumOf { it.n },
            ready = rows.filter { it.prepState == "READY" }.sumOf { it.n },
            needsBios = rows.filter { it.prepState == "NEEDS_BIOS" }.sumOf { it.n },
            needsManual = rows.filter { it.prepState == "NEEDS_MANUAL" }.sumOf { it.n },
            failed = rows.filter { it.prepState == "FAILED" }.sumOf { it.n },
        )
    }

/** Indices of the rows the System screen counts as "needs attention". Pure. */
fun attentionIndices(items: List<SystemReadinessItem>, prepMap: Map<String, PrepSummary>): List<Int> =
    items.indices.filter { needsAttention(items[it].state, prepMap[items[it].folder]?.total ?: 0) }

/** How many systems have games and are not Ready (header ⚠ count). Pure. */
fun attentionCount(items: List<SystemReadinessItem>, prepMap: Map<String, PrepSummary>): Int =
    attentionIndices(items, prepMap).size

/**
 * One System row per ROM folder: registry entries sharing a folder
 * (MSX/MSX2, Neo Geo/Neo Geo AES) merge under one display name, so the
 * folder's games and attention count once. The first entry keeps its
 * slug + route. Pure; registry order preserved.
 */
fun systemPlatforms(all: List<Platform> = PlatformRegistry.all): List<Platform> =
    all.groupBy { it.folder }.values.map { group ->
        val first = group.first()
        if (group.size == 1) first else first.copy(display = mergedDisplayName(group.map { it.display }))
    }

/** "MSX" + "MSX2" → "MSX / MSX2"; "Neo Geo" + "Neo Geo AES" → "Neo Geo" (word-prefix variants fold). */
internal fun mergedDisplayName(names: List<String>): String {
    val head = names.first()
    val extra = names.drop(1).filterNot { it == head || it.startsWith("$head ") }.distinct()
    return (listOf(head) + extra).joinToString(" / ")
}

/**
 * System header status: counts only systems the player has games for
 * ("5 of 6 systems ready"); an empty library says so. Pure.
 */
fun systemsSubtitle(
    systemsWithGames: Int,
    readyWithGames: Int,
    controllerLine: String,
    limitedWithGames: Int = 0,
): String {
    if (systemsWithGames == 0) return "No games yet · $controllerLine"
    // Limited-support systems can never be "ready"; count them apart so
    // "ALL SYSTEMS READY" never sits above "9 of 10 systems ready".
    val settable = systemsWithGames - limitedWithGames
    val limited = if (limitedWithGames > 0) " · $limitedWithGames limited" else ""
    return "$readyWithGames of ${countOf(settable, "system")} ready$limited · $controllerLine"
}
