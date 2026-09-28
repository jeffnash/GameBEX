package com.gamebex.client.launch

import android.content.Context
import android.os.Environment
import android.util.Log
import com.gamebex.client.data.Emu
import com.gamebex.client.launch.EmuControllerSetup.Result
import com.gamebex.client.launch.EmuControllerSetup.Status
import java.io.File
import java.io.IOException

/**
 * Writes the [RecKind.AUTO] rows of [PerfRecommendations]. Only Dolphin has
 * keys GameBEX is sure of:
 *  - Config/Dolphin.ini `[Core] GFXBackend = Vulkan`
 *  - Config/GFX.ini `[Settings] InternalResolution = 1` (LOW tier only)
 * Same IO rules as the controller setup (direct java.io or Shizuku, one-time
 * `.gamebex-backup`, in-place writes via [EmuControllerSetup.edit]); never throws.
 *
 * Not written:
 *  - NetherSX2: global settings live in private app storage. Per-game INIs
 *    (files/gamesettings/`{serial}_{CRC:08X}.ini`, format from libemucore.so)
 *    need the ELF CRC computed exactly like PCSX2, and CHD/CSO images need
 *    decoders, so its rows stay manual.
 *  - RetroArch: the managed CONFIGFILE already carries the CPU-friendly
 *    settings; core options are RA-private.
 */
object PerfTuner {
    private const val TAG = "GameBEXPerf"

    fun dolphinIni(text: String): String = IniText.set(text, "Core", "GFXBackend", "Vulkan")

    fun dolphinGfxIni(text: String?, tier: DeviceTier): String? =
        if (tier == DeviceTier.LOW) IniText.set(text.orEmpty(), "Settings", "InternalResolution", "1") else text

    /** AUTO keys already at their recommended value in the given texts. */
    fun dolphinApplied(dolphinIni: String?, gfxIni: String?, tier: DeviceTier): Set<String> = buildSet {
        if (dolphinIni != null && IniText.get(dolphinIni, "Core", "GFXBackend") == "Vulkan") {
            add(PerfRecommendations.DOLPHIN_BACKEND)
        }
        if (tier == DeviceTier.LOW && gfxIni != null && IniText.get(gfxIni, "Settings", "InternalResolution") == "1") {
            add(PerfRecommendations.DOLPHIN_RES)
        }
    }

    fun apply(ctx: Context, emu: Emu, tier: DeviceTier): Result = runCatching {
        applyUnder(
            emu, tier, EmuRouter.isInstalled(ctx.packageManager, emu.pkg), externalBase(), ctx.packageName,
            privileged = { ShizukuFiles.privileged(ctx) },
        )
    }.getOrElse {
        Log.w(TAG, "${emu.label} tune failed", it)
        Result(emu, Status.FAILED)
    }.also { Log.i(TAG, it.line) }

    /** Keys already applied, or null when the config can't be read. Uses Shizuku only if already allowed. */
    fun inspect(ctx: Context, emu: Emu, tier: DeviceTier): Set<String>? = runCatching {
        if (!EmuRouter.isInstalled(ctx.packageManager, emu.pkg)) return null
        inspectUnder(
            emu, tier, externalBase(), ctx.packageName,
            privileged = {
                if (ShizukuFiles.state(ctx) == ShizukuFiles.State.READY) ShizukuFiles.privileged(ctx) else Privileged.Unavailable
            },
        )
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun externalBase(): File = Environment.getExternalStorageDirectory()

    fun applyUnder(
        emu: Emu,
        tier: DeviceTier,
        installed: Boolean,
        base: File,
        ownPkg: String,
        direct: EmuFiles = DirectFiles,
        privileged: () -> Privileged = { Privileged.Unavailable },
    ): Result {
        if (emu != Emu.DOLPHIN) return Result(emu, Status.ONE_TIME, "set these inside ${emu.label}")
        if (!installed) return Result(emu, Status.NOT_INSTALLED)
        val r = reach(emu, base, ownPkg, direct, privileged)
        if (r is Reach.Denied) return r.result
        r as Reach.Ok
        tryWrite(r, tier)?.let { return it }
        if (r.fs !== direct) return Result(emu, Status.FAILED, r.via)
        // Readable but not writable directly (EACCES part-way): Shizuku.
        return when (val p = privileged()) {
            is Privileged.Ready -> tryWrite(Reach.Ok(p.files, r.files, "via Shizuku"), tier)
                ?: Result(emu, Status.FAILED, "via Shizuku")
            else -> EmuControllerSetup.statusFor(p).let { (st, d) -> Result(emu, st, d) }
        }
    }

    private fun tryWrite(r: Reach.Ok, tier: DeviceTier): Result? = try {
        writeDolphin(r.fs, r.files, tier)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    fun inspectUnder(
        emu: Emu,
        tier: DeviceTier,
        base: File,
        ownPkg: String,
        direct: EmuFiles = DirectFiles,
        privileged: () -> Privileged = { Privileged.Unavailable },
    ): Set<String>? {
        if (emu != Emu.DOLPHIN) return emptySet()
        val r = reach(emu, base, ownPkg, direct, privileged) as? Reach.Ok ?: return null
        return try {
            dolphinApplied(r.fs.readText("${r.files}/Config/Dolphin.ini"), r.fs.readText("${r.files}/Config/GFX.ini"), tier)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun writeDolphin(fs: EmuFiles, files: String, tier: DeviceTier): Result {
        val ini = "$files/Config/Dolphin.ini"
        // No Dolphin.ini yet = never opened: its first run would overwrite us.
        if (!fs.isFile(ini)) return Result(Emu.DOLPHIN, Status.OPEN_ONCE)
        var changed = EmuControllerSetup.edit(fs, ini) { dolphinIni(it.orEmpty()) }
        if (tier == DeviceTier.LOW) {
            val gfx = "$files/Config/GFX.ini"
            changed = EmuControllerSetup.edit(fs, gfx) { dolphinGfxIni(it, tier).orEmpty() } || changed
        }
        return Result(
            Emu.DOLPHIN,
            if (changed) Status.CONFIGURED else Status.ALREADY,
            if (changed) "applies next time Dolphin starts" else "",
        )
    }

    private sealed interface Reach {
        data class Ok(val fs: EmuFiles, val files: String, val via: String) : Reach
        data class Denied(val result: Result) : Reach
    }

    /** Which backend can see [emu]'s files dir (the controller setup's rules). */
    private fun reach(
        emu: Emu,
        base: File,
        ownPkg: String,
        direct: EmuFiles,
        privileged: () -> Privileged,
    ): Reach {
        val files = File(base, "Android/data/${emu.pkg}/files").path
        val visible = runCatching { direct.isDirectory(files) }.getOrDefault(false)
        if (visible) {
            val readable = runCatching { direct.readText("$files/Config/Dolphin.ini"); true }.getOrDefault(false)
            if (readable) return Reach.Ok(direct, files, "")
        } else {
            val hidden = EmuControllerSetup.dataDirHidden(runCatching { direct.list(File(base, "Android/data").path) }.getOrNull(), ownPkg)
            if (!hidden) return Reach.Denied(Result(emu, Status.OPEN_ONCE))
        }
        return when (val p = privileged()) {
            is Privileged.Ready -> {
                val ok = runCatching { p.files.isDirectory(files) }.getOrDefault(false)
                if (ok) Reach.Ok(p.files, files, "via Shizuku") else Reach.Denied(Result(emu, Status.OPEN_ONCE))
            }
            else -> EmuControllerSetup.statusFor(p).let { (st, d) -> Reach.Denied(Result(emu, st, d)) }
        }
    }
}
