package com.gamebex.client.stream.p2

import android.content.Context
import android.os.Environment
import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.stream.P2Prep
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamPlayer2
import com.gamebex.client.together.TogetherCopy
import java.io.File

/**
 * Player 2's binding per emulator:
 * - RetroArch: a session config ([StreamRaConfig]); nothing to restore.
 * - Dolphin: INI edits with restore ([DolphinP2Config]).
 * - NetherSX2: its bindings can't be written from outside (EmuRouter's
 *   one-time controller card says the same), so the first Stream shows a
 *   one-time guide; NetherSX2 keeps the port-2 mapping afterwards
 *   ([StreamCaps.NETHER_MAPPING_PERSISTS]).
 * - Anything else: a guide naming the emulator.
 */
class StreamPlayer2Impl(
    private val externalBase: () -> File,
    private val dolphin: DolphinP2Config,
    /** True the first time it is called for [key] on this phone. */
    private val firstTime: (key: String) -> Boolean,
) : StreamPlayer2 {
    override suspend fun prepare(game: GameEntity, emu: Emu): P2Prep = when (emu) {
        Emu.RETROARCH -> StreamRaConfig.write(externalBase())?.let { P2Prep.RetroArchConfig(it) }
            ?: P2Prep.Failed(TogetherCopy.configUnwritable)
        Emu.DOLPHIN -> when (val r = dolphin.apply()) {
            DolphinP2Config.Result.Applied -> P2Prep.Written
            is DolphinP2Config.Result.Failed -> P2Prep.Failed(r.text)
        }
        Emu.NETHER -> if (firstTime("nether") || !StreamCaps.NETHER_MAPPING_PERSISTS) {
            P2Prep.Guide(listOf(TogetherCopy.netherPlayer2))
        } else {
            P2Prep.Written
        }
        else -> P2Prep.Guide(listOf(TogetherCopy.streamPlayer2Other(emu.label)))
    }

    override suspend fun restore(): Boolean = dolphin.restorePending()

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
                    firstTime = { key ->
                        val k = "p2_guided_$key"
                        val first = !prefs.getBoolean(k, false)
                        if (first) prefs.edit().putBoolean(k, true).apply()
                        first
                    },
                ).also { instance = it }
            }
        }
    }
}
