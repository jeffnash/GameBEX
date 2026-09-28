package com.gamebex.client.launch

import android.content.Context
import android.os.Environment
import android.util.Log
import com.gamebex.client.data.Emu
import java.io.File
import java.io.IOException

/**
 * Thin IO around [EmuControllerConfig]: finds each emulator's config, makes
 * a one-time `<file>.gamebex-backup`, writes atomically, and reports one
 * status per emulator. Never throws. Call off the main thread.
 *
 * Android 11+ hides other apps' /sdcard/Android/data from us even with
 * all-files access (see GraphicsTuner). When that happens the emulator dir
 * looks absent; [dataDirHidden] tells that apart from "never opened" so the
 * status is honest ([Status.BLOCKED] vs [Status.OPEN_ONCE]). Hidden or
 * permission-denied dirs go through Shizuku's shell-uid service
 * ([ShizukuFiles]) when it's running and GameBEX is allowed; otherwise the
 * status says exactly which Shizuku step is missing.
 */
object EmuControllerSetup {
    private const val TAG = "GameBEXPadCfg"
    const val BACKUP_SUFFIX = ".gamebex-backup"
    private const val NETHER_TEMPLATE_ASSET = "emucfg/nethersx2_gamebex.ini"

    enum class Status(val label: String) {
        CONFIGURED("✓ configured"),
        ALREADY("✓ already set"),
        ONE_TIME("needs one-time step"),
        NOT_INSTALLED("not installed"),
        OPEN_ONCE("open once, then retry"),
        BLOCKED("Android blocks access"),
        NO_PAD("connect a controller"),
        FAILED("couldn't write"),
        SHIZUKU_START("Start Shizuku to finish setup"),
        SHIZUKU_ALLOW("Allow GameBEX in Shizuku"),
        ;

        /** Setup finishes once Shizuku is running and allows GameBEX. */
        val needsShizuku: Boolean get() = this == SHIZUKU_START || this == SHIZUKU_ALLOW
    }

    data class Result(val emu: Emu, val status: Status, val detail: String = "") {
        val line: String get() = "${emu.label}: ${status.label}" + if (detail.isNotEmpty()) " — $detail" else ""
    }

    /** Emulators with a controller writer, in display order. */
    val TARGETS = listOf(Emu.RETROARCH, Emu.DOLPHIN, Emu.FLYCAST, Emu.NETHER, Emu.VITA3K)

    fun applyAll(ctx: Context, pads: List<ControllerRegistry.Pad> = ControllerRegistry.snapshot()): List<Result> {
        // Resolved (and bound) at most once per pass, and only if a dir needs it.
        val shizuku = lazy { ShizukuFiles.privileged(ctx) }
        return TARGETS.map { applyFor(ctx, it, pads) { shizuku.value } }
    }

    /** One emulator; the pre-launch hook uses this. */
    fun applyFor(
        ctx: Context,
        emu: Emu,
        pads: List<ControllerRegistry.Pad> = ControllerRegistry.snapshot(),
        privileged: () -> Privileged = { ShizukuFiles.privileged(ctx) },
    ): Result =
        runCatching {
            applyUnder(
                emu = emu,
                installed = EmuRouter.isInstalled(ctx.packageManager, emu.pkg),
                base = externalBase(),
                ownPkg = ctx.packageName,
                pads = pads,
                privileged = privileged,
                netherTemplate = { ctx.assets.open(NETHER_TEMPLATE_ASSET).bufferedReader().use { it.readText() } },
            )
        }.getOrElse {
            Log.w(TAG, "${emu.label} setup failed", it)
            Result(emu, Status.FAILED)
        }.also { Log.i(TAG, it.line) }

    @Suppress("DEPRECATION")
    private fun externalBase(): File = Environment.getExternalStorageDirectory()

    // File-level logic (testable against a temp dir).

    /**
     * [direct] is our own java.io; [privileged] is consulted only when the
     * emulator dir is hidden from us or a direct op is denied.
     */
    fun applyUnder(
        emu: Emu,
        installed: Boolean,
        base: File,
        ownPkg: String,
        pads: List<ControllerRegistry.Pad>,
        direct: EmuFiles = DirectFiles,
        privileged: () -> Privileged = { Privileged.Unavailable },
        netherTemplate: () -> String,
    ): Result {
        if (!installed) return Result(emu, Status.NOT_INSTALLED)
        if (emu == Emu.RETROARCH) return retroArch(base)
        val files = File(base, "Android/data/${emu.pkg}/files").path
        val visible = runCatching { direct.isDirectory(files) }.getOrDefault(false)
        if (visible) {
            try {
                return write(emu, direct, files, pads, netherTemplate)
            } catch (_: IOException) {
                // Denied part-way (EACCES): retry the whole emulator via Shizuku.
            } catch (_: SecurityException) {
            }
        } else {
            val hidden = dataDirHidden(runCatching { direct.list(File(base, "Android/data").path) }.getOrNull(), ownPkg)
            if (!hidden) return Result(emu, Status.OPEN_ONCE)
        }
        return when (val p = privileged()) {
            is Privileged.Ready -> try {
                if (!p.files.isDirectory(files)) {
                    Result(emu, Status.OPEN_ONCE)
                } else {
                    write(emu, p.files, files, pads, netherTemplate)
                }
            } catch (_: IOException) {
                Result(emu, Status.FAILED, "via Shizuku")
            } catch (_: SecurityException) {
                Result(emu, Status.FAILED, "via Shizuku")
            }
            else -> statusFor(p).let { (st, detail) -> Result(emu, st, detail) }
        }
    }

    /** Why Shizuku couldn't be used → the status shown to the user. Pure. */
    fun statusFor(p: Privileged): Pair<Status, String> = when (p) {
        Privileged.NotRunning -> Status.SHIZUKU_START to ""
        Privileged.NotGranted -> Status.SHIZUKU_ALLOW to ""
        Privileged.Outdated -> Status.BLOCKED to "update Shizuku to v11+"
        Privileged.NoResponse -> Status.FAILED to "Shizuku didn't respond"
        Privileged.Unavailable -> Status.BLOCKED to ""
        is Privileged.Ready -> Status.CONFIGURED to ""
    }

    /** The per-emulator writers, on whichever backend can reach [files]. */
    private fun write(
        emu: Emu,
        fs: EmuFiles,
        files: String,
        pads: List<ControllerRegistry.Pad>,
        netherTemplate: () -> String,
    ): Result = when (emu) {
        Emu.DOLPHIN -> {
            val ini = "$files/Config/Dolphin.ini"
            if (!fs.isFile(ini)) {
                Result(emu, Status.OPEN_ONCE)
            } else {
                edit(fs, ini) { EmuControllerConfig.dolphinIni(it.orEmpty()) }
                Result(emu, Status.ONE_TIME, "overlay off; map Port 1 once in Dolphin's GameCube Input")
            }
        }
        Emu.FLYCAST -> {
            if (pads.isEmpty()) {
                Result(emu, Status.NO_PAD)
            } else {
                val dir = "$files/mappings".also { fs.mkdirs(it) }
                val wrote = pads.map { pad ->
                    edit(fs, "$dir/${EmuControllerConfig.flycastFileName(pad.name)}") { EmuControllerConfig.flycastMapping(it, pad.axes) }
                }
                Result(emu, if (wrote.any { it }) Status.CONFIGURED else Status.ALREADY, pads.joinToString { it.label })
            }
        }
        Emu.NETHER -> {
            val dir = "$files/inputprofiles".also { fs.mkdirs(it) }
            edit(fs, "$dir/gamebex.ini") { EmuControllerConfig.netherProfile(it, netherTemplate()) }
            Result(emu, Status.ONE_TIME, "touch controls auto-hide; map buttons once per new controller")
        }
        Emu.VITA3K -> {
            val yml = "$files/config.yml"
            if (!fs.isFile(yml)) {
                Result(emu, Status.OPEN_ONCE)
            } else {
                edit(fs, yml) { EmuControllerConfig.vita3kYaml(it.orEmpty()) }
                Result(emu, Status.ALREADY, "reads the controller natively")
            }
        }
        else -> Result(emu, Status.FAILED, "no writer")
    }

    private fun retroArch(base: File): Result {
        // Managed CONFIGFILE: what every GameBEX launch actually uses.
        GraphicsTuner.writeRetroArchConfigUnder(base) ?: return Result(Emu.RETROARCH, Status.FAILED)
        // RA's own shared cfg (used when RA is opened directly), if reachable.
        val own = File(base, "RetroArch/retroarch.cfg")
        if (own.isFile) edit(own) { EmuControllerConfig.retroArchCfg(it.orEmpty()) }
        return Result(Emu.RETROARCH, Status.CONFIGURED)
    }

    /**
     * True when Android/data shows no app but us (or can't be listed): the
     * OS is hiding other apps' dirs, so "missing" isn't "never opened". Pure.
     */
    fun dataDirHidden(names: List<String>?, ownPkg: String): Boolean =
        names == null || names.none { it != ownPkg && !it.startsWith(".") }

    /**
     * Rewrite [path] on [fs] via [transform] (null = file absent). No write
     * when the text is unchanged. Before the first real change, the original
     * is kept as `<file>.gamebex-backup` (never overwritten later). Atomic:
     * temp file in the same dir, then rename. Returns true when bytes
     * changed; throws [IOException] when [fs] can't do it.
     */
    fun edit(fs: EmuFiles, path: String, transform: (String?) -> String): Boolean {
        val old = fs.readText(path)
        val new = transform(old)
        if (new == old) return false
        if (old != null) {
            val backup = path + BACKUP_SUFFIX
            if (!fs.exists(backup) && !fs.copy(path, backup)) throw IOException("backup failed: $backup")
        }
        if (!fs.writeTextAtomic(path, new)) throw IOException("write failed: $path")
        return true
    }

    fun edit(file: File, transform: (String?) -> String): Boolean = edit(DirectFiles, file.path, transform)
}
