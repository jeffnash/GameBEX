package com.gamebex.client.stream.p2

import android.content.Context
import android.os.Environment
import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.ControllerRegistry
import com.gamebex.client.stream.P2Prep
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamFlagPrefs
import com.gamebex.client.stream.StreamFlags
import com.gamebex.client.stream.StreamPlayer2
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherSupport
import java.io.File

/**
 * Player 2's binding per emulator:
 * - RetroArch: a session config ([StreamRaConfig]); nothing to restore.
 * - Dolphin: INI edits with restore ([DolphinP2Config]).
 * - NetherSX2: its bindings can't be written from outside (EmuRouter's
 *   one-time controller card says the same), so the first Stream shows a
 *   one-time guide; NetherSX2 keeps the port-2 mapping afterwards
 *   ([StreamCaps.NETHER_MAPPING_PERSISTS]).
 * - Flycast: no port setting exists (first-use order), so a one-time
 *   guide explains host-on-controller-first.
 * - PPSSPP, melonDS, Azahar, Vita3K, ScummVM, J2ME Loader: no
 *   second-controller concept on one phone, so nothing to set up.
 * - Anything else (ARMSX2, MAME4droid): a guide naming the emulator.
 */
class StreamPlayer2Impl(
    private val externalBase: () -> File,
    private val dolphin: DolphinP2Config,
    private val hostPads: () -> List<ControllerRegistry.Pad> = ControllerRegistry::snapshot,
    private val raExperiments: () -> RaExperimentOptions = { RaExperimentOptions.sample() },
    /** RetroArch's version code (0 = unknown); gates the v3 overlay auto-hide. */
    private val raVersionCode: () -> Long = { 0 },
    /** `overlay_26b` flag; when on, the session config names the seeded per-core overlay. */
    private val overlay26b: () -> Boolean = { false },
    /** Seeds the core's overlay from the RA APK; null = unavailable. */
    private val seedOverlay: (core: String?) -> File? = { null },
    /** True the first time it is called for [key] on this phone. */
    private val firstTime: (key: String) -> Boolean,
) : StreamPlayer2 {
    private var raSnapshot: RaExperimentOptions? = null

    override suspend fun prepare(game: GameEntity, emu: Emu, rsoundReady: Boolean, p2SlotMode: P2SlotMode): P2Prep = when (emu) {
        Emu.RETROARCH -> {
            if (!rsoundReady || raSnapshot == null) raSnapshot = raExperiments()
            val overlayPath = if (overlay26b()) {
                val core = TogetherSupport.coreFor(game, TogetherMode.RA_NETPLAY)
                runCatching { seedOverlay(core)?.absolutePath }.getOrNull()
            } else null
            StreamRaConfig.write(externalBase(), rsoundReady, hasRealHostPad = hostPads().isNotEmpty(),
                experiments = checkNotNull(raSnapshot), retroArchVersionCode = raVersionCode(),
                overlayCfgPath = overlayPath, p2SlotMode = p2SlotMode)
                ?.let { P2Prep.RetroArchConfig(it) } ?: P2Prep.Failed(TogetherCopy.configUnwritable)
        }
        Emu.DOLPHIN -> when (val r = dolphin.apply()) {
            DolphinP2Config.Result.Applied -> P2Prep.Written
            is DolphinP2Config.Result.Failed -> P2Prep.Failed(r.text)
        }
        Emu.NETHER -> if (firstTime("nether") || !StreamCaps.NETHER_MAPPING_PERSISTS) {
            P2Prep.Guide(listOf(TogetherCopy.netherPlayer2))
        } else {
            P2Prep.Written
        }
        Emu.FLYCAST -> if (firstTime("flycast")) {
            P2Prep.Guide(listOf(TogetherCopy.flycastPlayer2))
        } else {
            P2Prep.Written
        }
        Emu.PPSSPP, Emu.MELONDS, Emu.AZAHAR, Emu.VITA3K, Emu.SCUMMVM, Emu.J2MELOADER -> P2Prep.NoSecondPad
        else -> P2Prep.Guide(listOf(TogetherCopy.streamPlayer2Other(emu.label)))
    }

    override suspend fun restore(): Boolean {
        raSnapshot = null
        return dolphin.restorePending()
    }

    companion object {
        private const val PREFS = "stream"

        @Volatile private var instance: StreamPlayer2Impl? = null

        fun forApp(ctx: Context): StreamPlayer2Impl = instance ?: synchronized(this) {
            instance ?: run {
                val app = ctx.applicationContext
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                StreamPlayer2Impl(
                    externalBase = {
                        @Suppress("DEPRECATION")
                        Environment.getExternalStorageDirectory()
                    },
                    dolphin = DolphinP2Config.forApp(app),
                    raExperiments = { RaExperimentOptions.sample(flags = StreamFlagPrefs(app).lookup()) },
                    raVersionCode = { com.gamebex.client.launch.RetroCoreProbe.versionCode(app) },
                    firstTime = { key ->
                        val k = "p2_guided_$key"
                        val first = !prefs.getBoolean(k, false)
                        if (first) prefs.edit().putBoolean(k, true).apply()
                        first
                    },
                    overlay26b = { StreamFlags.toggleOn("overlay_26b", StreamFlagPrefs(app).lookup()) },
                    seedOverlay = seed@{ core ->
                        val asset = OverlaySeed.assetForCore(core) ?: return@seed null
                        val apk = OverlaySeed.findRetroArchApk(app.packageManager) ?: return@seed null
                        @Suppress("DEPRECATION")
                        OverlaySeed.seed(apk, asset, File(Environment.getExternalStorageDirectory(), "RetroArch"))
                    },
                ).also { instance = it }
            }
        }
    }
}
