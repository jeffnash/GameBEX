package com.gamebex.client.ui.presentation

import android.content.Context
import android.content.Intent
import android.util.Log
import com.gamebex.client.GameBexApp
import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.Route
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.ui.UserErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared Play for library games (Home, Library): routes through EmuRouter
 * and reports one structured status at a time; null clears it.
 */
object LocalLauncher {
    suspend fun play(
        ctx: Context,
        id: Long,
        fresh: Boolean = false,
        onStatus: (LaunchStatus?) -> Unit,
    ) {
        onStatus(LaunchStatus.progress(id, "Launching…", fresh))
        val status = withContext(Dispatchers.IO) {
            val g = LibraryDb.get(ctx).games().findById(id) ?: return@withContext null
            val platform = PlatformRegistry.byFolder[g.folder]
            val route = platform?.route as? Route.RetroCore
            val altCore = (platform?.route as? Route.Standalone)
                ?.takeIf { g.emulatorOverride == Emu.RETROARCH.pkg }
                ?.let { platform.altRetroCore }
            val core = route?.takeIf {
                g.emulatorOverride == null || g.emulatorOverride == Emu.RETROARCH.pkg
            }?.let { g.coreOverride ?: it.core } ?: altCore?.let { g.coreOverride ?: it }
            val out = try {
                withCoreProgress(id, core, fresh, onStatus) { progress ->
                    EmuRouter.play(ctx, g, fresh, onCoreFetch = progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GameBEXPlay", "quick-play failed", e)
                EmuRouter.Outcome.Failed(UserErrors.launchFailed())
            }
            if (out is EmuRouter.Outcome.Launched) {
                // Counted on confirmed boot at next resume, not here.
                (ctx.applicationContext as GameBexApp).noteLaunch(g.id, out.emuPkg)
            }
            launchStatusFor(id, out, fresh, retroCore = core != null)
        }
        onStatus(status)
    }

    /** Virtual-time seam; callbacks and the watchdog share the same progress contract as Detail. */
    internal suspend fun withCoreProgress(
        id: Long,
        core: String?,
        fresh: Boolean,
        onStatus: (LaunchStatus?) -> Unit,
        launch: suspend ((Long, Long) -> Unit) -> EmuRouter.Outcome,
    ): EmuRouter.Outcome = coroutineScope {
        val lock = Any()
        var progress = core?.let { CoreInstallProgress(it) }
        var watchdog: Job? = null
        var version = 0L
        var finished = false
        fun armWatchdog() {
            watchdog?.cancel()
            val expected = ++version
            watchdog = this.launch {
                delay(8_000L)
                synchronized(lock) {
                    if (!finished && expected == version) {
                        progress = progress?.copy(stalled = true)
                        progress?.let { onStatus(LaunchStatus.coreInstall(id, it, fresh)) }
                    }
                }
            }
        }
        synchronized(lock) {
            progress?.let {
                onStatus(LaunchStatus.coreInstall(id, it, fresh))
                armWatchdog()
            }
        }
        try {
            val outcome = launch { read, total ->
                synchronized(lock) {
                    val old = progress
                    if (!finished && old != null) {
                        val next = if (read < 0 && total < 0) old.copy(installing = true)
                            else old.copy(read = read.coerceAtLeast(0), total = total.coerceAtLeast(0), installing = false)
                        // Repeated callbacks are not evidence of forward progress.
                        if (next.copy(stalled = false) != old.copy(stalled = false)) {
                            progress = next.copy(stalled = false)
                            onStatus(LaunchStatus.coreInstall(id, progress!!, fresh))
                            armWatchdog()
                        }
                    }
                }
            }
            ensureActive()
            outcome
        } finally {
            synchronized(lock) {
                finished = true
                watchdog?.cancel()
            }
        }
    }

    /** Opens the installed runtime, never the APK installer. Null means it opened. */
    fun openRetroArch(ctx: Context): String? = try {
        val intent = ctx.packageManager.getLaunchIntentForPackage(Emu.RETROARCH.pkg)
        if (intent == null) "RetroArch isn't available to open."
        else {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }
    } catch (_: Exception) {
        "RetroArch refused to open."
    }

    /**
     * The one install path every surface shares (Detail's dialog, Home and
     * Library prompts): start the reviewed-build pipeline, then the caller
     * opens System, where progress, Android's confirmation and the
     * unknown-sources fix render. Guided origins (no direct channel) are a
     * no-op in the installer; System shows their store links instead.
     */
    fun beginInstall(ctx: Context, emu: Emu) {
        (ctx.applicationContext as GameBexApp).installer.start(emu.pkg)
    }
}

/** One backend job per quick-play surface, including cancellation cleanup before Retry. */
internal class LocalLaunchJob(
    private val scope: () -> CoroutineScope,
    private val launcher: suspend (Long, Boolean, (LaunchStatus?) -> Unit) -> Unit,
    private val onStatus: (LaunchStatus?) -> Unit,
) {
    private val lock = Any()
    private var job: Job? = null
    private var cancelledJob: Job? = null
    private var status: LaunchStatus? = null
    private var retryCore: CoreInstallProgress? = null

    private fun publish(next: LaunchStatus?) {
        status = next
        onStatus(next)
    }

    fun play(id: Long, fresh: Boolean = false) {
        synchronized(lock) {
            if (job != null || status?.busy == true) return
            val predecessor = cancelledJob
            val progress = retryCore?.takeIf { status?.gameId == id }
            retryCore = null
            publish(progress?.let { LaunchStatus.coreInstall(id, it.copy(stalled = false), fresh) }
                ?: LaunchStatus.progress(id, "Launching…", fresh))
            lateinit var next: Job
            next = scope().launch(start = CoroutineStart.LAZY) {
                var cancelled = false
                try {
                    // EmuRouter's in-flight gate is released only after backend cleanup.
                    predecessor?.join()
                    ensureActive()
                    launcher(id, fresh) { value ->
                        synchronized(lock) {
                            if (job === next && next.isActive) publish(value)
                        }
                    }
                    ensureActive()
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                } catch (e: Exception) {
                    synchronized(lock) {
                        if (job === next) publish(launchStatusFor(id, EmuRouter.Outcome.Failed(UserErrors.launchFailed()), fresh))
                    }
                } finally {
                    synchronized(lock) {
                        if (job === next) {
                            if ((cancelled || next.isCancelled) && status?.busy == true) {
                                retryCore = status?.coreProgress
                                publish(LaunchStatus.cancelled(id, fresh))
                            }
                            job = null
                        }
                    }
                }
            }
            job = next
            next.start()
        }
    }

    fun dismiss() {
        synchronized(lock) {
            val current = status ?: return
            if (current.canCancel) cancel(current)
            else if (!current.busy || job == null) publish(null)
        }
    }

    private fun cancel(current: LaunchStatus) {
        val active = job ?: return
        job = null
        retryCore = current.coreProgress
        cancelledJob = cancelledJob?.takeUnless { it.isCompleted } ?: active
        active.cancel()
        publish(LaunchStatus.cancelled(current.gameId, current.fresh))
    }

    fun retry() {
        synchronized(lock) {
            val current = status ?: return
            if (current.action != LaunchAction.Retry && current.secondaryAction != LaunchAction.Retry) return
            if (current.busy) {
                if (current.coreProgress?.stalled != true) return
                cancel(current)
            }
            play(current.gameId, current.fresh)
        }
    }
}
