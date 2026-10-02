package com.gamebex.client.together

import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.Route
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.RetroCoreFetcher
import com.gamebex.client.launch.ShizukuFiles
import com.gamebex.client.stream.StreamCaps

/** Which games support which mode. */
object TogetherSupport {
    val GB_FOLDERS: Set<String> = setOf("gb", "gbc")
    const val PSP_FOLDER: String = "psp"
    private val CORE_NAME = Regex("^[a-z0-9_]+$")
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

 /**
 * Every mode [game] can be played in, best first: [nativeModeFor], then hardware-verified native modes
 * for its folder ([TogetherCaps.VERIFIED_NATIVE]), then STREAM. Empty when the game can't be offered.
 */
    fun modesFor(game: GameEntity): List<TogetherMode> {
        if (!game.available || game.deletedAtMs > 0) return emptyList()
        if (PlatformRegistry.byFolder[game.folder] == null) return emptyList()
        val out = LinkedHashSet<TogetherMode>()
        nativeModeFor(game)?.let { out += it }
        TogetherCaps.VERIFIED_NATIVE[game.folder]?.let { out += it }
        if (game.folder in StreamCaps.FOLDERS) out += TogetherMode.STREAM
        return out.toList()
    }

 /** The default mode: the first of [modesFor], or null. */
    fun modeFor(game: GameEntity): TogetherMode? = modesFor(game).firstOrNull()

 /** The native two-phone mode proven before Stream existed, or null. */
    fun nativeModeFor(game: GameEntity): TogetherMode? {
        if (!game.available || game.deletedAtMs > 0) return null
        val platform = PlatformRegistry.byFolder[game.folder] ?: return null
        if (game.folder == PSP_FOLDER) return TogetherMode.PSP_ADHOC
        val route = platform.route as? Route.RetroCore ?: return null
        val core = game.coreOverride ?: route.core
        if (game.folder in GB_FOLDERS) return if (core == "gambatte") TogetherMode.GB_LINK else null
        return if (core in TogetherCaps.RA_NETPLAY_CORES) TogetherMode.RA_NETPLAY else null
    }

 /**
 * Whether RetroArch has [core]. The probe decides when it answers. When it can't (null), only a core
 * GameBEX verified in its ledger counts, so a session never boots RetroArch on an unconfirmed core file.
 */
    fun coreConfirmed(core: String, probed: Collection<String>?, ledgerVerified: () -> Boolean): Boolean =
        if (probed != null) probed.any { it.equals(core, ignoreCase = true) } else ledgerVerified()

 /**
 * The RetroArch modes need Shizuku running and allowed so a session launch can really restart RetroArch.
 * Null when it may go on (or for PSP, whose Shizuku gate is its own).
 */
    fun raShizukuGate(mode: TogetherMode, state: ShizukuFiles.State): Readiness? {
        if (mode == TogetherMode.PSP_ADHOC || !TogetherCaps.RA_RESTART_NEEDS_SHIZUKU) return null
        return when (state) {
            ShizukuFiles.State.NOT_RUNNING -> Readiness.NeedsShizuku(false)
            ShizukuFiles.State.NOT_GRANTED -> Readiness.NeedsShizuku(true)
            ShizukuFiles.State.OUTDATED, ShizukuFiles.State.UNAVAILABLE -> Readiness.Blocked(TogetherCopy.shizukuRequiredRa)
            ShizukuFiles.State.READY -> null
        }
    }

 /**
 * RA_NETPLAY/GB_LINK readiness after the install and Shizuku gates: RetroArch's first run,
 * then preflight ([preflightStop] is its text), then the core ([coreConfirmed]).
 */
    fun retroArchReadiness(
        needsFirstRun: Boolean,
        preflightStop: String?,
        core: String?,
        probed: Collection<String>?,
        knownBuildRequired: Boolean = false,
        ledgerVerified: () -> Boolean,
    ): Readiness = when {
        needsFirstRun -> Readiness.NeedsOpenOnce(Emu.RETROARCH)
        preflightStop != null -> Readiness.Blocked(preflightStop)
        core != null && !coreConfirmed(core, probed, ledgerVerified) -> Readiness.NeedsCore(core)
 // The probe can't say which build is installed; only the ledger can.
        core != null && knownBuildRequired && !ledgerVerified() -> Readiness.NeedsCore(core)
        else -> Readiness.Ready
    }

 /** Null when [mode] may go on with this RetroArch [versionCode] (null = unknown). */
    fun raNetplayBuildGate(mode: TogetherMode, versionCode: Long?): Readiness? =
        if (mode == TogetherMode.RA_NETPLAY && versionCode != null && versionCode < TogetherCaps.RA_NETPLAY_MIN_VERSION_CODE) {
            Readiness.NeedsUpdate(Emu.RETROARCH)
        } else {
            null
        }

 /** The RetroArch core this phone's copy of [game] runs in [mode]; null for PSP_ADHOC. */
    fun coreFor(game: GameEntity, mode: TogetherMode): String? = when (mode) {
 // STREAM guests never run the game, so the offer carries no core.
        TogetherMode.PSP_ADHOC, TogetherMode.STREAM -> null
        TogetherMode.GB_LINK -> "gambatte"
        TogetherMode.RA_NETPLAY ->
            game.coreOverride ?: (PlatformRegistry.byFolder[game.folder]?.route as? Route.RetroCore)?.core
    }

 /** Folders whose games can be the guest's copy of a [folder] game in [mode]. */
    fun matchFolders(mode: TogetherMode, folder: String): Set<String> = when (mode) {
        TogetherMode.PSP_ADHOC -> setOf(PSP_FOLDER)
        TogetherMode.STREAM -> setOf(folder)
        TogetherMode.GB_LINK -> GB_FOLDERS
        TogetherMode.RA_NETPLAY -> {
            val core = (PlatformRegistry.byFolder[folder]?.route as? Route.RetroCore)?.core
            if (core == null) setOf(folder)
            else PlatformRegistry.all
                .filter { (it.route as? Route.RetroCore)?.core == core }
                .map { it.folder }.toSet()
        }
    }

 /**
 * A core build received over the link is used only when its name is a plain core id equal to
 * [expectedCore], its hash is 64 lower-case hex, and its size is 1..MAX_CORE_ZIP_BYTES. It guards file paths.
 */
    fun isSafeCoreBuild(b: CoreBuild, expectedCore: String): Boolean =
        b.core == expectedCore && CORE_NAME.matches(b.core) && SHA256_HEX.matches(b.sha256) &&
            b.sizeBytes in 1..RetroCoreFetcher.MAX_CORE_ZIP_BYTES
}
