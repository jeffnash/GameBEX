package com.gamebex.client.together.emu

import android.content.Context
import com.gamebex.client.GameBexApp
import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.RomStore
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.launch.GraphicsTuner
import com.gamebex.client.launch.RetroCoreFetcher
import com.gamebex.client.launch.RetroCoreProbe
import com.gamebex.client.launch.ShizukuFiles
import com.gamebex.client.together.CoreBuild
import com.gamebex.client.together.CoreFile
import com.gamebex.client.together.CoreResult
import com.gamebex.client.together.Readiness
import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherCaps
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherLaunch
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherSupport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TogetherEmulatorsImpl(ctx: Context) : TogetherEmulators {
    private val context = ctx.applicationContext

    override suspend fun readiness(
        mode: TogetherMode,
        role: Role,
        game: GameEntity,
        core: String?,
    ): Readiness {
        return when (mode) {
            TogetherMode.RA_NETPLAY, TogetherMode.GB_LINK -> readinessRetroArch(mode, role, game, core)
            TogetherMode.PSP_ADHOC -> readinessPpsspp(game)
        }
    }

    private suspend fun readinessRetroArch(mode: TogetherMode, role: Role, game: GameEntity, core: String?): Readiness {
        if (!EmuRouter.isInstalled(context.packageManager, Emu.RETROARCH.pkg)) {
            return Readiness.NeedsEmulator(Emu.RETROARCH)
        }
        TogetherSupport.raShizukuGate(mode, ShizukuFiles.state(context))?.let { return it }
        val raVersionCode = runCatching {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(Emu.RETROARCH.pkg, 0))
        }.getOrNull()
        TogetherSupport.raNetplayBuildGate(mode, raVersionCode)?.let { return it }
        if (EmuRouter.retroArchNeedsFirstRun(context)) {
            return TogetherSupport.retroArchReadiness(true, null, core, null) { false }
        }
        val preflightStop = when (val preflight = EmuRouter.preflight(context, game)) {
            is EmuRouter.Preflight.Stop -> TogetherCopy.outcomeText(preflight.outcome)
            is EmuRouter.Preflight.Ok -> null
        }
        val probed = if (preflightStop == null) RetroCoreProbe.queryInstalledCores(context) else null
        // A netplay host offers its core build, so GameBEX must have installed it.
        val knownBuildRequired = mode == TogetherMode.RA_NETPLAY && role == Role.HOST
        return TogetherSupport.retroArchReadiness(false, preflightStop, core, probed, knownBuildRequired) {
            core != null && InstalledCoreLedger.forApp(context).verified(core) != null
        }
    }

    private suspend fun readinessPpsspp(game: GameEntity): Readiness {
        if (!EmuRouter.isInstalled(context.packageManager, Emu.PPSSPP.pkg)) {
            return Readiness.NeedsEmulator(Emu.PPSSPP)
        }
        when (ShizukuFiles.state(context)) {
            ShizukuFiles.State.NOT_RUNNING -> return Readiness.NeedsShizuku(false)
            ShizukuFiles.State.NOT_GRANTED -> return Readiness.NeedsShizuku(true)
            ShizukuFiles.State.OUTDATED,
            ShizukuFiles.State.UNAVAILABLE,
            -> return Readiness.Blocked(TogetherCopy.shizukuRequired)
            ShizukuFiles.State.READY -> Unit
        }
        if (PpssppNetConfig.forApp(context).find() == null) {
            return Readiness.NeedsOpenOnce(Emu.PPSSPP)
        }
        return when (val preflight = EmuRouter.preflight(context, game)) {
            is EmuRouter.Preflight.Stop -> Readiness.Blocked(TogetherCopy.outcomeText(preflight.outcome))
            is EmuRouter.Preflight.Ok -> Readiness.Ready
        }
    }

    override suspend fun installedCore(core: String): CoreFile? =
        InstalledCoreLedger.forApp(context).verified(core)

    override suspend fun fetchAndInstallCore(
        core: String,
        game: GameEntity,
        onProgress: (read: Long, total: Long) -> Unit,
    ): CoreResult {
        return withContext(Dispatchers.IO) {
            val staged = when (val result = RetroCoreFetcher.stageCore(context, core, onProgress)) {
                is RetroCoreFetcher.StageResult.Staged -> result.file
                is RetroCoreFetcher.StageResult.Failed -> return@withContext CoreResult.Failed(result.reason)
            }
            val rom = if (TogetherCaps.SIDELOAD_WITHOUT_ROM) {
                null
            } else {
                RomStore.resolveLaunchFile(context, game)
            }
            if (RetroCoreFetcher.sideloadCoreOnly(context, core, staged, rom)) {
                CoreResult.Started
            } else {
                CoreResult.Failed(TogetherCopy.coreInstallBlocked)
            }
        }
    }

    override fun receivedCoreFile(build: CoreBuild): File =
        File(
            GraphicsTuner.togetherDir(),
            "cores/${build.sha256.take(16)}/${build.core}_libretro_android.so",
        )

    override suspend fun hasReceivedCore(build: CoreBuild): Boolean {
        val file = receivedCoreFile(build)
        return file.isFile && file.length() == build.sizeBytes &&
            RetroCoreFetcher.sha256Hex(file) == build.sha256
    }

    override suspend fun installReceivedCore(
        file: File,
        build: CoreBuild,
        game: GameEntity,
    ): CoreResult {
        return withContext(Dispatchers.IO) {
            val valid = file.isFile &&
                file.length() == build.sizeBytes &&
                RetroCoreFetcher.sha256Hex(file) == build.sha256 &&
                RetroCoreFetcher.isUsableCore(file.length(), RetroCoreFetcher.readHeader(file)) &&
                RetroCoreFetcher.readHeader(file, 20)?.let(RetroCoreFetcher::isAarch64Elf) == true
            if (!valid) return@withContext CoreResult.Failed(TogetherCopy.coreCopyFailed)
            val rom = if (TogetherCaps.SIDELOAD_WITHOUT_ROM) {
                null
            } else {
                RomStore.resolveLaunchFile(context, game)
            }
            if (RetroCoreFetcher.sideloadCoreOnly(context, build.core, file, rom)) {
                CoreResult.Started
            } else {
                CoreResult.Failed(TogetherCopy.coreInstallBlocked)
            }
        }
    }

    override suspend fun launch(req: TogetherLaunch): EmuRouter.Outcome {
        val outcome = EmuRouter.play(context, req.game, together = req)
        if (outcome is EmuRouter.Outcome.Launched) {
            (context.applicationContext as GameBexApp).noteLaunch(req.game.id, outcome.emuPkg)
        }
        return outcome
    }

    override suspend fun endSession() {
        PpssppNetConfig.forApp(context).restorePending()
    }
}
