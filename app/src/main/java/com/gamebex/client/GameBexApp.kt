package com.gamebex.client

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import com.gamebex.client.net.ArtWorker
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.launch.PlayConfirm
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.net.VolumeReceiver
import com.gamebex.client.sources.SourceImageLoaders
import com.gamebex.client.sources.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class GameBexApp : Application() {
    val sources: SourceRegistry by lazy { SourceRegistry(this) }
    val engine: DownloadEngine by lazy { DownloadEngine(this, sources) }
    /** Deep route requested while running (notification tap): AppNav consumes it. */
    val pendingRoute = MutableStateFlow<String?>(null)
    /** Game id whose detail should start Play once opened (ready-notification action). */
    val pendingAutoPlay = MutableStateFlow<Long?>(null)
    /** Library sort to apply on next open (Free Space → biggest games first). */
    val pendingLibrarySort = MutableStateFlow<com.gamebex.client.ui.LibSort?>(null)
    /** Assigned by MainActivity: in-context notification-permission ask. */
    var requestNotifications: (() -> Unit)? = null
    val art: ArtWorker by lazy {
        ArtWorker(this, engine, LibraryDb.get(this), sources = sources)
    }
    val installer: com.gamebex.client.install.EmuInstaller by lazy {
        com.gamebex.client.install.EmuInstaller(this)
    }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Home services, all lazy over the shared singletons.
    /** Device status: online-ness, free bytes per volume, System ⚠ count. */
    val device: com.gamebex.client.ui.home.model.DeviceStatus by lazy {
        com.gamebex.client.launch.DeviceStatusImpl(this, LibraryDb.get(this))
    }
    /** RetroArch save states + thumbnails (`<sharedRoot>/RetroArch/states`). */
    val saveIndex: com.gamebex.client.ui.home.model.SaveIndex by lazy {
        val states = com.gamebex.client.launch.RetroArchTuner
            .wantedDirs(android.os.Environment.getExternalStorageDirectory().absolutePath)
            .getValue("savestate_directory")
        com.gamebex.client.launch.RetroArchSaveIndex(java.io.File(states))
    }
    /** Site search for the header field (plugin name, page 1, sizes). */
    val siteSearch: com.gamebex.client.ui.home.model.SiteSearch by lazy {
        com.gamebex.client.sources.SiteSearchImpl(sources, device.online, appScope)
    }
    /** Download-again / site downloads / Play when ready. */
    val homeDownloads: com.gamebex.client.ui.home.model.HomeDownloads by lazy {
        com.gamebex.client.net.HomeDownloadsImpl(engine, sources, LibraryDb.get(this))
    }
    /** Play together; inert until TogetherRuntime.build is wired. */
    val together: com.gamebex.client.together.TogetherController by lazy {
        com.gamebex.client.together.platform.TogetherRuntime.controller(this)
    }

    val imageLoaders: SourceImageLoaders by lazy {
        SourceImageLoaders(this, sources, appScope)
    }

    @Suppress("unused")
    private var volumeReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        com.gamebex.client.ui.console.ControllerPrefs.load(this)
        com.gamebex.client.ui.console.DisplayPrefs.load(this)
        // Seed the ROM folder tree (SD root when mounted) off the main thread.
        Thread { com.gamebex.client.cocoon.CocoonDirs.seedAll(this) }.start()
        installer.reconcileAfterLaunch()
        // Revocation hook: disable/remove parks source-owned downloads.
        sources.engineHook = engine
        // Sources first: the engine must not start source jobs and artwork
        // repair must not run before availability is known. A missing or
        // corrupt plugin never aborts launch or blocks the local library.
        appScope.launch {
            runCatching { sources.initialize() }
            runCatching { engine.reconcileAfterLaunch() }
            runCatching { art.repairQueue() }
        }
        volumeReceiver = VolumeReceiver.register(this)
        // Pad connects → configure every installed emulator for it.
        com.gamebex.client.launch.ControllerRegistry.listen(this) { scheduleControllerSetup() }
        // Shizuku's binder arrives asynchronously (and again after a Shizuku
        // restart): if we're already allowed and a pad is connected, finish
        // the emulator setup Android blocked us from doing directly.
        runCatching {
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
                if (com.gamebex.client.launch.ShizukuFiles.granted()) scheduleControllerSetup(requirePad = true)
            }
        }
    }

    private var padSetupJob: kotlinx.coroutines.Job? = null

    /** Coalesces a pad's burst of sub-device adds into one setup pass. */
    private fun scheduleControllerSetup(requirePad: Boolean = false) {
        if (padSetupJob?.isActive == true) return
        padSetupJob = appScope.launch {
            kotlinx.coroutines.delay(1_500)
            val pads = com.gamebex.client.launch.ControllerRegistry.snapshot()
            if (requirePad && pads.isEmpty()) return@launch
            runCatching { com.gamebex.client.launch.EmuControllerSetup.applyAll(this@GameBexApp, pads) }
        }
    }

    /**
     * Stash a pending launch session. [direct] must be false for
     * launcher-only handoffs (no ROM passed); callers that don't know pass
     * nothing and the router's known launcher-only set is inferred.
     */
    fun noteLaunch(gameId: Long, emuPkg: String, direct: Boolean = PlayConfirm.isDirectBoot(emuPkg)) {
        // Internal handoffs (package installer) are not plays.
        if (emuPkg == packageName) return
        getSharedPreferences("play_confirm", Context.MODE_PRIVATE).edit()
            .putString("pending", PlayConfirm.encode(PlayConfirm.Pending(gameId, emuPkg, System.currentTimeMillis(), direct)))
            .apply()
    }

    /**
     * Called on every activity resume. Closes the pending launch session:
     * a direct handoff that returned counts as a play (a launch session,
     * not verified gameplay — see PlayConfirm). Launcher-only handoffs,
     * stale stamps, and failed launches (never stashed) never count.
     */
    fun confirmPendingPlay() {
        val prefs = getSharedPreferences("play_confirm", Context.MODE_PRIVATE)
        val pending = PlayConfirm.decode(prefs.getString("pending", null))
        if (pending == null) {
            if (prefs.contains("pending")) prefs.edit().remove("pending").apply()
            return
        }
        prefs.edit().remove("pending").apply()
        if (!PlayConfirm.shouldAttributePlay(pending, System.currentTimeMillis())) return
        appScope.launch {
            runCatching { LibraryDb.get(this@GameBexApp).games().recordPlay(pending.gameId, pending.launchedAt) }
        }
    }
}
