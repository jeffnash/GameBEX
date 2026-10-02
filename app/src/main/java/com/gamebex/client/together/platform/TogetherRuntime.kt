package com.gamebex.client.together.platform

import android.content.Context
import com.gamebex.client.GameBexApp
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.net.RomHash
import com.gamebex.client.data.local.RomStore
import com.gamebex.client.together.emu.TogetherEmulatorsImpl
import com.gamebex.client.together.session.TogetherEngine
import com.gamebex.client.together.net.LiveProbesImpl
import com.gamebex.client.together.proto.SocketTransport
import com.gamebex.client.together.session.TogetherLibraryImpl
import com.gamebex.client.together.TogetherController
import com.gamebex.client.stream.host.StreamShellClient
import com.gamebex.client.stream.p2.StreamPlayer2Impl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TogetherRuntime {
    private val foregroundFlow = MutableStateFlow(false)

    /** True while MainActivity is resumed. */
    val foreground: StateFlow<Boolean> = foregroundFlow.asStateFlow()

    fun setForeground(value: Boolean) {
        foregroundFlow.value = value
    }

    @Volatile private var instance: TogetherController? = null

    fun controller(ctx: Context): TogetherController =
        instance ?: synchronized(this) {
            instance ?: build(ctx.applicationContext).also { instance = it }
        }

    private fun build(app: Context): TogetherController {
        val gbx = app as GameBexApp
        val db = LibraryDb.get(app)
        val prefs = TogetherPrefs(app)
        return TogetherEngine(
            transport = SocketTransport(),
            probes = LiveProbesImpl(),
            emulators = TogetherEmulatorsImpl(app),
            library = TogetherLibraryImpl(
                dao = db.games(),
                downloads = gbx.engine,
                homeDownloads = gbx.homeDownloads,
                sources = gbx.sources.list(),
                fingerprint = { g -> RomStore.resolveLaunchFile(app, g)?.let { RomHash.cached(app, g.id, it) } },
                launchFile = { g -> RomStore.resolveLaunchFile(app, g) },
            ),
            discovery = TogetherNsd(app, prefs.deviceId),
            platform = TogetherPlatformImpl(app, prefs),
            streamHost = StreamShellClient.forApp(app),
            player2 = StreamPlayer2Impl.forApp(app),
        )
    }
}
