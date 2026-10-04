package com.gamebex.client.launch

import android.os.Environment
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.Route
import com.gamebex.client.data.BiosTable
import com.gamebex.client.data.local.ArchiveHub
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.PrepPipeline
import com.gamebex.client.data.local.RomStore
import com.gamebex.client.net.BiosFetcher
import com.gamebex.client.ui.UserErrors
import com.gamebex.client.together.TogetherLaunch
import com.gamebex.client.stream.StreamEmus
import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherSupport
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherCaps
import com.gamebex.client.together.emu.InstalledCoreLedger
import com.gamebex.client.together.emu.PpssppNetConfig
import com.gamebex.client.together.emu.SessionStop
import com.gamebex.client.together.emu.TogetherRaConfig
import java.util.concurrent.ConcurrentHashMap

/** Per-system launch router. Implements VERIFIED contracts only; unverified
 *  emulators surface as explicit Blocked states, never silent fallbacks. */
object EmuRouter {
    private const val TAG = "GameBEXPlay"
    private const val RA_PKG = "com.retroarch.aarch64"
    private const val PERM_READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    private const val PERM_READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    private const val PERM_READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    private const val PERM_READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    private const val PERM_MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"
    /** AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE, which the public SDK doesn't expose. */
    private const val OP_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage"
    private const val RA_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"
    /** In-flight launches by game id: repeated Play presses can't double-fire. */
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    sealed interface Outcome {
        data class Launched(val emuLabel: String, val emuPkg: String, val coreFetched: Boolean = false) : Outcome
        data class MissingEmu(val emu: Emu) : Outcome
        data class Blocked(val reason: String) : Outcome
        data class NeedsSetup(val text: String) : Outcome
        data class Failed(val msg: String) : Outcome
    }

    fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Fetch-or-boot decision. A null probe (RA stopped/silent) fetches
     * rather than risking the ANR-on-missing-core hang; without RA
     * installed there's nothing to fetch for (MissingEmu path instead).
     */
    /**
     * "Start fresh" (skip the savestate auto-load) is only honorable where
     * we own the launch config: the RetroArch route via its CONFIGFILE
     * extra. Standalone emulators expose no per-launch switch, so they
     * never offer it. Pure.
     */
    fun canStartFresh(folder: String, emulatorOverride: String?): Boolean {
        if (PlatformRegistry.byFolder[folder]?.route !is Route.RetroCore) return false
        return emulatorOverride == null || emulatorOverride == Emu.RETROARCH.pkg
    }

    /** Package a route (plus per-game override) boots, or null when no emulator runs. Pure. */
    fun launchTarget(route: Route, emuOverride: Emu?): String? = when (route) {
        is Route.RetroCore -> if (emuOverride != null && emuOverride != Emu.RETROARCH) emuOverride.pkg else RA_PKG
        is Route.Standalone -> (emuOverride ?: route.emu).pkg
        is Route.GuidedInstall, is Route.SideloadApp, is Route.Blocked -> null
    }

    fun shouldFetchCore(probe: Set<String>?, core: String, raInstalled: Boolean): Boolean =
        raInstalled && (probe == null || probe.none { it.equals(core, ignoreCase = true) })

    /**
     * Launch gate: an unprepared disc-folder archive must go through prep
     * first — handing it to an emulator raw is a guaranteed failed boot.
     * Pure (the extension/archive/folder matrix has a regression test).
     */
    fun needsArchivePrep(folder: String, ext: String, prepState: String): Boolean =
        prepState == PrepPipeline.PREP_NONE &&
            ext.lowercase() in ArchiveHub.ROUTE_EXTS &&
            folder in BiosTable.DISC_FOLDERS

    /** True on first call per key: one-time in-app setup guides. */
    private fun guidedOnce(ctx: Context, key: String): Boolean {
        val p = ctx.getSharedPreferences("emu_guides", Context.MODE_PRIVATE)
        if (p.getBoolean(key, false)) return false
        p.edit().putBoolean(key, true).apply()
        return true
    }

    /**
     * Suspend: a RetroCore launch first probes installed cores, and when the
     * probe positively reports the core missing it auto-fetches it (never on
     * probe failure — unknown means launch normally). [onCoreFetch] reports
     * download progress for the fetching UI.
     */
    sealed interface Preflight {
        data class Ok(val game: GameEntity) : Preflight
        data class Stop(val outcome: Outcome) : Preflight
    }

    /** The package facts [retroArchNeedsFirstRun] reads; a seam so JVM tests can fake PackageManager. */
    internal interface PackageFacts {
        /** Null when [pkg] isn't installed. */
        fun requestedPermissions(pkg: String): List<String>?
        fun targetSdk(pkg: String): Int
        fun isGranted(permission: String, pkg: String): Boolean
        /** [pkg]'s All Files Access; null when this phone can't tell. */
        fun allFilesAccess(pkg: String): Boolean? = null
    }

    private class AndroidPackageFacts(
        private val pm: PackageManager,
        private val appOps: android.app.AppOpsManager?,
    ) : PackageFacts {
        override fun requestedPermissions(pkg: String): List<String>? =
            runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()
                ?.let { it.requestedPermissions?.toList().orEmpty() }
        override fun targetSdk(pkg: String): Int =
            runCatching { pm.getApplicationInfo(pkg, 0).targetSdkVersion }.getOrDefault(0)
        override fun isGranted(permission: String, pkg: String): Boolean =
            pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED
        override fun allFilesAccess(pkg: String): Boolean? {
            if (android.os.Build.VERSION.SDK_INT < 30) return null
            val ops = appOps ?: return null
            val uid = runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: return null
            val mode = runCatching { ops.unsafeCheckOpNoThrow(OP_MANAGE_EXTERNAL_STORAGE, uid, pkg) }.getOrNull()
            return allFilesAccessFromMode(mode) { isGranted(PERM_MANAGE_EXTERNAL_STORAGE, pkg) }
        }
    }

    /**
     * A RetroArch that was never opened hasn't run MainMenuActivity's first run
     * (the file-access prompt and asset extraction). Launched directly,
     * RetroActivityFuture sits on a black screen and ANRs. True while RetroArch
     * still lacks the storage permission its targetSdk uses on this device.
     */
    /**
     * All Files Access from its app-op [mode], the rule the system applies: the
     * Settings switch writes ALLOWED or ERRORED, and DEFAULT (never touched) falls back
     * to the permission. Null [mode] = couldn't check. Pure.
     */
    internal fun allFilesAccessFromMode(mode: Int?, permissionGranted: () -> Boolean): Boolean? = when (mode) {
        null -> null
        android.app.AppOpsManager.MODE_ALLOWED -> true
        android.app.AppOpsManager.MODE_DEFAULT -> permissionGranted()
        else -> false
    }

    internal fun retroArchNeedsFirstRun(facts: PackageFacts, deviceSdk: Int): Boolean {
        val requested = facts.requestedPermissions(RA_PKG) ?: return false
        // A build targeting Android 11+ that asks for All Files Access reads ROMs
        // only with it. Unknown (null) never blocks.
        if (deviceSdk >= 30 && facts.targetSdk(RA_PKG) >= 30 && PERM_MANAGE_EXTERNAL_STORAGE in requested) {
            return facts.allFilesAccess(RA_PKG) == false
        }
        val storage = if (deviceSdk >= 33 && facts.targetSdk(RA_PKG) >= 33) {
            listOf(PERM_READ_MEDIA_IMAGES, PERM_READ_MEDIA_VIDEO, PERM_READ_MEDIA_AUDIO)
        } else {
            listOf(PERM_READ_EXTERNAL_STORAGE)
        }
        val checked = storage.filter { it in requested }
        return checked.isNotEmpty() && checked.none { facts.isGranted(it, RA_PKG) }
    }

    fun retroArchNeedsFirstRun(ctx: Context): Boolean = retroArchNeedsFirstRun(
        AndroidPackageFacts(ctx.packageManager, ctx.getSystemService(android.app.AppOpsManager::class.java)),
        android.os.Build.VERSION.SDK_INT,
    ) || RetroCoreProbe.setupPathsMissing(ctx)

    /** RetroArch's own paths, the way its launcher (MainMenuActivity) derives them; null when unknown. */
    data class RaPaths(val dataDir: String?, val apk: String?, val sdcard: String?, val ime: String?)

    /**
     * The extras RetroArch's own launcher passes. RetroArch derives its assets, cores,
     * core-info, autoconfig and overlay folders from DATADIR only, and its saves, remaps and
     * playlists folder from SDCARD. Pure.
     */
    internal fun retroArchExtras(rom: String, coreSo: String, cfg: String?, paths: RaPaths): Map<String, String> =
        buildMap {
            put("ROM", rom)
            put("LIBRETRO", coreSo)
            if (cfg != null) put("CONFIGFILE", cfg)
            paths.ime?.let { put("IME", it) }
            paths.dataDir?.let { put("DATADIR", it) }
            paths.apk?.let { put("APK", it) }
            paths.sdcard?.let {
                put("SDCARD", it)
                put("EXTERNAL", "$it/Android/data/$RA_PKG/files")
            }
        }

    private fun raPaths(ctx: Context): RaPaths {
        val info = runCatching { ctx.packageManager.getApplicationInfo(RA_PKG, 0) }.getOrNull()
        return RaPaths(
            dataDir = info?.dataDir,
            apk = info?.sourceDir,
            sdcard = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull(),
            ime = runCatching {
                android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            }.getOrNull(),
        )
    }

    /** True when Play would hand [game] to RetroArch (its route, unless a standalone override wins). */
    private fun launchesRetroArch(game: GameEntity): Boolean {
        if (PlatformRegistry.byFolder[game.folder]?.route !is Route.RetroCore) return false
        val override = game.emulatorOverride ?: return true
        return override == RA_PKG
    }

    /** play()'s launch gates without launching: availability, RetroArch's first run, BIOS fetch, prep, archive prep. */
    suspend fun preflight(ctx: Context, game: GameEntity, db: LibraryDb = LibraryDb.get(ctx)): Preflight {
        if (!game.available) return Preflight.Stop(Outcome.Failed("Game files aren't reachable right now (SD card?)."))
        if (launchesRetroArch(game) && retroArchNeedsFirstRun(ctx)) {
            return Preflight.Stop(Outcome.NeedsSetup(UserErrors.retroArchFirstRun()))
        }
        // BIOS auto-fetch: missing firmware (or a never-prepared row)
        // resolves here so Play just works. Fail-open: any error keeps
        // the old gating below.
        val g = runCatching { BiosFetcher.ensureForLaunch(ctx, db, game) }.getOrDefault(game)
        if (g.prepState == PrepPipeline.PREP_NEEDS_BIOS) return Preflight.Stop(Outcome.Blocked("Missing BIOS — open details to see what's needed."))
        if (g.prepState == PrepPipeline.PREP_FAILED || g.prepState == PrepPipeline.PREP_NEEDS_MANUAL) {
            return Preflight.Stop(Outcome.Blocked(g.prepNote?.ifBlank { null } ?: "This game needs a manual fix first."))
        }
        if (needsArchivePrep(g.folder, g.ext, g.prepState)) {
            // A raw disc archive never boots: prep extracts first. No
            // per-emulator exception without a regression test proving
            // the contract (the old PSP zip pass-through had none).
            return Preflight.Stop(Outcome.Blocked("This archive needs preparation before it can boot."))
        }
        return Preflight.Ok(g)
    }

    suspend fun play(
        ctx: Context,
        game: GameEntity,
        fresh: Boolean = false,
        together: TogetherLaunch? = null,
        onCoreFetch: ((read: Long, total: Long) -> Unit)? = null,
    ): Outcome {
        // Shared launch boundary: every surface funnels through here, so
        // availability and known-bad prep gate here — not per-screen.
        if (!game.available) return Outcome.Failed("Game files aren't reachable right now (SD card?).")
        if (!inFlight.add(game.id)) return Outcome.Failed("Launch already in progress.")
        try {
            return when (val gate = preflight(ctx, game)) {
                is Preflight.Stop -> gate.outcome
                is Preflight.Ok -> playInner(ctx, gate.game, onCoreFetch, fresh, together)
            }
        } finally { inFlight.remove(game.id) }
    }

    private suspend fun playInner(
        ctx: Context,
        game: GameEntity,
        onCoreFetch: ((read: Long, total: Long) -> Unit)? = null,
        fresh: Boolean = false,
        together: TogetherLaunch? = null,
    ): Outcome {
        if (together != null) return playTogether(ctx, game, together)

        val platform = PlatformRegistry.byFolder[game.folder]
            ?: return Outcome.Blocked(UserErrors.unknownSystem())
        val emuOverride = game.emulatorOverride?.let { name ->
            Emu.entries.firstOrNull { it.pkg == name }
        }
        // Free RAM for the target: other resident emulators get stopped
        // (bounded wait, never the target itself; see EmuCloser).
        launchTarget(platform.route, emuOverride)?.let { pkg ->
            if (isInstalled(ctx.packageManager, pkg)) EmuCloser.beforeLaunch(ctx, pkg)
        }
        return when (val route = platform.route) {
            // Route reasons are internal notes; users get plain copy.
            is Route.Blocked -> Outcome.Blocked(UserErrors.unsupportedSystem(platform.display))
            is Route.GuidedInstall -> Outcome.NeedsSetup(UserErrors.guidedImport(route.emu.label, game.title))
            is Route.SideloadApp -> installApk(ctx, game)
            is Route.RetroCore -> {
                if (emuOverride != null && emuOverride != Emu.RETROARCH) {
                    return fireStandalone(ctx, game, emuOverride)
                }
                val core = game.coreOverride ?: route.core
                // System-file packs (Dolphin Sys, PPSSPP fonts, blueMSX
                // databases) land before boot. Non-fatal: most games run
                // without them and the System screen keeps flagging it.
                (CoreSystemFiles.ensure(ctx, core, onCoreFetch) as? CoreSystemFiles.Result.Failed)
                    ?.let { Log.w(TAG, "system files for $core: ${it.reason}") }
                val cores = RetroCoreProbe.queryInstalledCores(ctx)
                if (shouldFetchCore(cores, core, isInstalled(ctx.packageManager, RA_PKG))) {
                    // Missing core ANRs RetroArch (it hangs on a
                    // nonexistent LIBRETRO path) — and an unanswered probe
                    // must never boot blind for the same reason. The fetch
                    // path is idempotent (staged-core cache), so a redundant
                    // fetch only costs a reinstall, never a hang.
                    val rom = RomStore.resolveLaunchFile(ctx, game)
                    return when (val r = RetroCoreFetcher.ensureCore(ctx, core, rom, onCoreFetch)) {
                        is RetroCoreFetcher.Result.Fetched -> {
                            // Sideload never boots a ROM: confirmation must
                            // precede the normal managed config/fresh launch.
                            val launched = fireRetroArch(ctx, game, core, fresh)
                            if (launched is Outcome.Launched) launched.copy(coreFetched = true) else launched
                        }
                        is RetroCoreFetcher.Result.Failed -> Outcome.NeedsSetup(r.reason)
                    }
                }
                fireRetroArch(ctx, game, core, fresh)
            }
            is Route.Standalone -> fireStandalone(ctx, game, emuOverride ?: route.emu)
        }
    }

    private suspend fun playTogether(ctx: Context, game: GameEntity, req: TogetherLaunch): Outcome {
        return when (req.mode) {
            TogetherMode.RA_NETPLAY, TogetherMode.GB_LINK -> {
                EmuCloser.beforeLaunch(ctx, RA_PKG)
                if (!isInstalled(ctx.packageManager, RA_PKG)) return Outcome.MissingEmu(Emu.RETROARCH)
                if (retroArchNeedsFirstRun(ctx)) return Outcome.NeedsSetup(TogetherCopy.retroArchFileAccess)
                val core = req.core ?: TogetherSupport.coreFor(game, req.mode)!!
                (CoreSystemFiles.ensure(ctx, core, null) as? CoreSystemFiles.Result.Failed)
                    ?.let { Log.w(TAG, "system files for $core: ${it.reason}") }
                // Never boot RetroArch on a core neither the probe nor the ledger confirms.
                val cores = RetroCoreProbe.queryInstalledCores(ctx)
                if (!TogetherSupport.coreConfirmed(core, cores) { InstalledCoreLedger.forApp(ctx).verified(core) != null }) {
                    return Outcome.NeedsSetup(TogetherCopy.needsCore(com.gamebex.client.data.CoreNames.display(core)))
                }
                // The session config itself turns save-state resume off; fresh = false
                // here only picks nothing, because cfgOverride replaces the managed config.
                launchRetroArchSession(
                    stop = { SessionStop.stop(ctx, RA_PKG) },
                    writeConfig = {
                        TogetherRaConfig.write(
                            @Suppress("DEPRECATION") Environment.getExternalStorageDirectory(),
                            req.role, req.mode, req.hostAddress, req.playerName,
                        )
                    },
                    fire = { cfg -> fireRetroArch(ctx, game, core, fresh = false, cfgOverride = cfg) },
                )
            }
            TogetherMode.PSP_ADHOC -> {
                EmuCloser.beforeLaunch(ctx, Emu.PPSSPP.pkg)
                if (!isInstalled(ctx.packageManager, Emu.PPSSPP.pkg)) return Outcome.MissingEmu(Emu.PPSSPP)
                when (val result = PpssppNetConfig.forApp(ctx).apply(req.role, req.hostAddress, req.playerName)) {
                    is PpssppNetConfig.ApplyResult.Failed -> Outcome.NeedsSetup(result.text)
                    is PpssppNetConfig.ApplyResult.Applied -> fireStandalone(ctx, game, Emu.PPSSPP, req)
                }
            }
            TogetherMode.STREAM -> if (req.role == Role.HOST) playStreamHost(ctx, game, req) else Outcome.Blocked(TogetherCopy.streamUnavailable)
        }
    }

    /**
     * The Stream host plays as usual. On RetroArch it boots with the
     * session config that reserves player 2 ([TogetherLaunch.p2Config]), after a
     * restart so RetroArch reads it; other emulators take Play's own route.
     */
    private suspend fun playStreamHost(ctx: Context, game: GameEntity, req: TogetherLaunch): Outcome {
        val cfg = req.p2Config ?: return playInner(ctx, game, null, fresh = false, together = null)
        EmuCloser.beforeLaunch(ctx, RA_PKG)
        if (!isInstalled(ctx.packageManager, RA_PKG)) return Outcome.MissingEmu(Emu.RETROARCH)
        if (retroArchNeedsFirstRun(ctx)) return Outcome.NeedsSetup(TogetherCopy.retroArchFileAccess)
        val core = StreamEmus.raCore(game) ?: return Outcome.Blocked(TogetherCopy.notSupported)
        (CoreSystemFiles.ensure(ctx, core, null) as? CoreSystemFiles.Result.Failed)
            ?.let { Log.w(TAG, "system files for $core: ${it.reason}") }
        val cores = RetroCoreProbe.queryInstalledCores(ctx)
        if (!TogetherSupport.coreConfirmed(core, cores) { InstalledCoreLedger.forApp(ctx).verified(core) != null }) {
            return Outcome.NeedsSetup(TogetherCopy.needsCore(com.gamebex.client.data.CoreNames.display(core)))
        }
        return launchRetroArchSession(
            stop = { SessionStop.stop(ctx, RA_PKG) },
            writeConfig = { cfg },
            fire = { file -> fireRetroArch(ctx, game, core, fresh = false, cfgOverride = file) },
        )
    }

    /**
     * A session launch into a RetroArch that's already running leaves it on a
     * black screen (CLEAR_TASK alone doesn't restart it), so RetroArch must be
     * force-stopped first; when that fails, nothing is written and nothing fires.
     */
    internal suspend fun launchRetroArchSession(
        stop: suspend () -> Boolean,
        writeConfig: () -> java.io.File?,
        fire: (java.io.File) -> Outcome,
    ): Outcome {
        if (TogetherCaps.RA_RESTART_NEEDS_SHIZUKU && !stop()) return Outcome.NeedsSetup(TogetherCopy.shizukuRequiredRa)
        val cfg = writeConfig() ?: return Outcome.Failed(TogetherCopy.configUnwritable)
        return fire(cfg)
    }

    /** OUYA: the ROM IS the app — hand it to the system package installer. */
    private fun installApk(ctx: Context, game: GameEntity): Outcome {
        val file = RomStore.resolveLaunchFile(ctx, game)
        if (file == null || !file.isFile) return Outcome.Failed("APK is no longer available.")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(RomProvider.uriFor(game.id), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            Log.i(TAG, "installing OUYA apk=${game.relPath}")
            Outcome.Launched("Package installer", ctx.packageName)
        } catch (e: ActivityNotFoundException) {
            Outcome.Failed("No package installer found.")
        } catch (e: SecurityException) {
            Outcome.Failed("The installer blocked this file.")
        }
    }

    private fun fireRetroArch(
        ctx: Context,
        game: GameEntity,
        core: String,
        fresh: Boolean,
        cfgOverride: java.io.File? = null,
    ): Outcome {
        if (!isInstalled(ctx.packageManager, RA_PKG)) return Outcome.MissingEmu(Emu.RETROARCH)
        val file = RomStore.resolveLaunchFile(ctx, game)
            ?: return Outcome.Failed("ROM file is no longer available.")
        if (!file.isFile) return Outcome.Failed("ROM file is no longer available.")
        val coreSo = "/data/data/$RA_PKG/cores/${core}_libretro_android.so"
        // Managed frontend config (fills height, smooth, overlay auto-hide).
        // When shared storage is unreachable the extra is omitted and RA
        // boots on its own config — never the cross-app Android/data path,
        // which Android 13+ blocks (verified on-device).
        // Fresh launches use a sibling file with auto-load off, so the
        // normal managed config is never left in fresh mode.
        val cfg = cfgOverride?.absolutePath ?: GraphicsTuner.writeRetroArchConfig(resume = !fresh)?.absolutePath
        if (cfg == null && fresh) {
            // Without our file RA uses its own auto-load setting: refuse
            // rather than silently resuming when the user asked not to.
            Log.w(TAG, "fresh RA config unwritable; refusing fresh launch")
            return Outcome.Failed(UserErrors.freshUnavailable())
        }
        if (cfg == null) Log.w(TAG, "managed RA config unwritable; launching without CONFIGFILE")
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(RA_PKG, RA_ACTIVITY)
            retroArchExtras(file.absolutePath, coreSo, cfg, raPaths(ctx)).forEach { (key, value) -> putExtra(key, value) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (cfgOverride != null) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return try {
            ctx.startActivity(intent)
            Log.i(TAG, "launched RetroArch core=$core fresh=$fresh rom=${game.relPath} cfg=${cfg ?: "none"}")
            Outcome.Launched("RetroArch (${com.gamebex.client.data.CoreNames.display(core)})", RA_PKG)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "RetroArch activity missing", e)
            Outcome.Failed("RetroArch is installed but refused the launch.")
        } catch (e: SecurityException) {
            Log.w(TAG, "RetroArch launch blocked", e)
            Outcome.Failed("RetroArch blocked the launch.")
        }
    }

    /**
     * Direct boot, the ES-DE contract: exported EmulationActivity +
     * `bootPath` string extra (device-verified on v2.2n-4248). NetherSX2
     * holds no storage permission, so the path is our RomProvider URI with
     * a read grant; the trailing file name keeps the extension visible for
     * its format sniffing. Any failure falls back to opening the app so
     * the user can pick the game from its list.
     */
    private fun fireNether(ctx: Context, game: GameEntity): Outcome {
        val emu = Emu.NETHER
        val file = RomStore.resolveLaunchFile(ctx, game)
        if (file != null && file.isFile) {
            val uri = RomProvider.uriFor(game.id, file.name)
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(emu.pkg, "${emu.pkg}.EmulationActivity")
                putExtra("bootPath", uri.toString())
                // The extra carries no grant by itself: ClipData does, and
                // the explicit grant outlives this intent for save/resume.
                clipData = android.content.ClipData.newRawUri(game.title, uri)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            try {
                ctx.grantUriPermission(emu.pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(intent)
                Log.i(TAG, "direct-booted NetherSX2 rom=${game.relPath}")
                return if (guidedOnce(ctx, "nether_bios")) {
                    Outcome.NeedsSetup(
                        "NetherSX2 one-time setup: if it asks for a BIOS, " +
                            "Settings → BIOS Directory → GameBEX/bios/ps2.",
                    )
                } else {
                    Outcome.Launched("NetherSX2", emu.pkg)
                }
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "NetherSX2 direct boot unavailable; opening app", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "NetherSX2 direct boot blocked; opening app", e)
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(emu.pkg, "${emu.pkg}.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            Log.i(TAG, "opened NetherSX2 rom=${game.relPath}")
            if (guidedOnce(ctx, "nether_setup")) {
                Outcome.NeedsSetup(
                    "NetherSX2 one-time setup: ADD GAME DIRECTORY → ps2 folder, " +
                        "then Settings → BIOS Directory → GameBEX/bios/ps2. Then tap the game.",
                )
            } else {
                Outcome.Launched("NetherSX2 (pick the game inside it)", emu.pkg)
            }
        } catch (e: ActivityNotFoundException) {
            Outcome.Failed("NetherSX2 is installed but refused the launch.")
        } catch (e: SecurityException) {
            Outcome.Failed("NetherSX2 blocked the launch.")
        }
    }

    private suspend fun fireStandalone(
        ctx: Context,
        game: GameEntity,
        emu: Emu,
        together: TogetherLaunch? = null,
    ): Outcome {
        if (emu == Emu.PPSSPP && together == null && !PpssppNetConfig.forApp(ctx).restorePending()) {
            Log.w(TAG, "PPSSPP restore failed; launching anyway")
        }
        if (!isInstalled(ctx.packageManager, emu.pkg)) return Outcome.MissingEmu(emu)
        // Controller auto-config for this emulator only: a few small file
        // reads (callers are already off the main thread). Pads only.
        val pads = ControllerRegistry.snapshot()
        if (pads.isNotEmpty() && emu in EmuControllerSetup.TARGETS) {
            runCatching { EmuControllerSetup.applyFor(ctx, emu, pads) }
        }
        // Device-verified contracts.
        if (emu == Emu.NETHER) {
            // Bindings can't be written from outside NetherSX2: one-time
            // card per new controller (only while GameBEX is on screen).
            if (pads.isNotEmpty()) ControllerGuide.awaitNetherCard(ctx, pads)
            return fireNether(ctx, game)
        }
        if (emu == Emu.SCUMMVM || emu == Emu.MAME4DROID) {
            // No working VIEW receiver: LAUNCHER-only.
            // ScummVM: SplashActivity not directly exported, address by package.
            // MAME4droid 1.41: VIEW intent native-crashes warm AND cold (file://);
            // its own list already finds our ROMs after the one-time SAF grant.
            // Explicit component: implicit MAIN+setPackage threw ANF on-device
            // despite <queries>; this class is stable/exported.
            val hint = if (emu == Emu.SCUMMVM) "use Add Game inside it" else "pick the game inside it"
            val intent = Intent(Intent.ACTION_MAIN).apply {
                if (emu == Emu.MAME4DROID) {
                    component = ComponentName(emu.pkg, "com.seleuco.mame4droid.MAME4droid")
                } else {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(emu.pkg)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                ctx.startActivity(intent)
                Log.i(TAG, "launched ${emu.label} (main-only) rom=${game.relPath}")
                Outcome.Launched("${emu.label} ($hint)", emu.pkg)
            } catch (e: ActivityNotFoundException) {
                Outcome.Failed("${emu.label} is installed but refused the launch.")
            } catch (e: SecurityException) {
                Outcome.Failed("${emu.label} blocked the launch.")
            }
        }
        if (emu == Emu.J2MELOADER && game.ext.lowercase() !in setOf("jar", "jad")) {
            return Outcome.NeedsSetup("J2ME Loader plays .jar files — unzip \"${game.fileName}\" first, then play the .jar from your Library.")
        }
        if (emu == Emu.VITA3K) {
            return Outcome.NeedsSetup("Import \"${game.title}\" inside ${emu.label}, then play from there.")
        }
        val uri = RomProvider.uriFor(game.id)
        val intent: Intent = when (emu) {
            Emu.PPSSPP -> viewIn(ctx, emu.pkg, uri, null)
            Emu.MELONDS -> Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(emu.pkg, "me.magnum.melonds.ui.emulator.EmulatorActivity")
                data = uri
            }
            Emu.AZAHAR -> viewIn(ctx, emu.pkg, uri, "application/octet-stream")
                ?: return Outcome.Failed("Azahar has no viewer for this file.")
            Emu.DOLPHIN -> Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(emu.pkg, "org.dolphinemu.dolphinemu.ui.main.MainActivity")
                data = uri
            }
            Emu.FLYCAST -> viewIn(ctx, emu.pkg, uri, null)
            Emu.J2MELOADER -> viewIn(ctx, emu.pkg, uri, "application/java-archive")
            // ES-DE contract: VIEW on the exported MainActivity alias. The
            // name-bearing URI lets it sniff iso/chd/cso from the extension.
            Emu.ARMSX2 -> Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(emu.pkg, "${emu.pkg}.MainActivity")
                val file = RomStore.resolveLaunchFile(ctx, game)
                data = if (file != null) RomProvider.uriFor(game.id, file.name) else uri
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            else -> return Outcome.Blocked(UserErrors.noDirectBoot(emu.label))
        } ?: return Outcome.Failed("${emu.label} has no viewer for this file.")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Dolphins older than scoped-storage builds also read raw paths; harmless extra.
        if (emu == Emu.DOLPHIN) {
            RomStore.resolveLaunchFile(ctx, game)?.let {
                intent.putExtra("AutoStartFile", it.absolutePath)
            }
        }
        return try {
            ctx.startActivity(intent)
            Log.i(TAG, "launched ${emu.label} rom=${game.relPath}")
            Outcome.Launched(emu.label, emu.pkg)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "${emu.label} activity missing", e)
            Outcome.Failed("${emu.label} is installed but refused the launch.")
        } catch (e: SecurityException) {
            Log.w(TAG, "${emu.label} launch blocked", e)
            Outcome.Failed("${emu.label} blocked the launch.")
        }
    }

    /** Implicit VIEW resolved to an explicit component inside [pkg]
     *  (avoids choosers and wrong-app matches). */
    private fun viewIn(ctx: Context, pkg: String, uri: Uri, mime: String?): Intent? {
        val probe = Intent(Intent.ACTION_VIEW).apply {
            if (mime != null) setDataAndType(uri, mime) else data = uri
        }
        val found = ctx.packageManager
            .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName == pkg } ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(pkg, found.activityInfo.name)
            if (mime != null) setDataAndType(uri, mime) else data = uri
        }
    }
}
