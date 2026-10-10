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
import com.gamebex.client.together.HostViewport
import com.gamebex.client.together.HostViewportInfo
import com.gamebex.client.together.TogetherController
import com.gamebex.client.together.defaultCutoutViewport
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
            hostFlag = com.gamebex.client.stream.StreamFlagPrefs(app).lookup(),
            seedOverlayForCore = seed@{ core ->
                val asset = com.gamebex.client.stream.p2.OverlaySeed.assetForCore(core) ?: return@seed null
                val apk = com.gamebex.client.stream.p2.OverlaySeed.findRetroArchApk(app.packageManager) ?: return@seed null
                @Suppress("DEPRECATION")
                com.gamebex.client.stream.p2.OverlaySeed.seed(
                    apk, asset,
                    java.io.File(android.os.Environment.getExternalStorageDirectory(), "RetroArch"),
                )
            },
            hostViewportInfo = {
                runCatching {
                    val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val display = wm.maximumWindowMetrics.bounds
                        // GameBEX runs full-bleed, so its window coords are
                        // display coords; a default-cutout-mode emulator on
                        // the same display letterboxes off any cutout edge.
                        val cutouts = wm.currentWindowMetrics.windowInsets.displayCutout
                            ?.boundingRects.orEmpty()
                            .map { HostViewport(it.left, it.top, it.width(), it.height()) }
                        val vp = defaultCutoutViewport(display.width(), display.height(), cutouts)
                        HostViewportInfo(vp, display.width(), display.height())
                    } else {
                        @Suppress("DEPRECATION")
                        val size = android.graphics.Point()
                        wm.defaultDisplay.getRealSize(size)
                        HostViewportInfo(HostViewport(0, 0, size.x, size.y), size.x, size.y)
                    }
                }.getOrNull() ?: HostViewportInfo.UNKNOWN
            },
            appContext = { app },
            emuOverlayFiles = {
                // Shizuku shell-uid reads other apps' Android/data on 11+;
                // without it the readers still get PM resources/APK art.
                when (val p = com.gamebex.client.launch.ShizukuFiles.privileged(app)) {
                    is com.gamebex.client.launch.Privileged.Ready -> p.files
                    else -> com.gamebex.client.launch.DirectFiles
                }
            },
            hostDensityDpi = { app.resources.displayMetrics.densityDpi },
        )
    }
}
