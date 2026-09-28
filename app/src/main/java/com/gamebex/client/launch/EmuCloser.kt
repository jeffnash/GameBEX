package com.gamebex.client.launch

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.gamebex.client.data.Emu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Packages that may be force-stopped: only the known emulators in [Emu], never
 * GameBEX itself. The Shizuku service re-checks this before `am force-stop`.
 */
object ForceStopPolicy {
    private val PKG = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")

    val PACKAGES: Set<String> = Emu.entries.map { it.pkg }.toSet()

    fun allowed(pkg: String?, ownPkg: String? = null): String? {
        if (pkg == null || pkg.length > 255 || !PKG.matches(pkg)) return null
        if (pkg == ownPkg || pkg.startsWith("com.gamebex.")) return null
        return pkg.takeIf { it in PACKAGES }
    }
}

/** Backs "Close other emulators before launching" (System → Device → Controls). */
object LaunchPrefs {
    private const val FILE = "launch"
    private const val KEY_CLOSE_OTHERS = "close_other_emus"
    const val DEFAULT_CLOSE_OTHERS = true

    fun closeOthersOrDefault(stored: Boolean?): Boolean = stored ?: DEFAULT_CLOSE_OTHERS

    fun closeOthers(ctx: Context): Boolean = runCatching {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        closeOthersOrDefault(if (p.contains(KEY_CLOSE_OTHERS)) p.getBoolean(KEY_CLOSE_OTHERS, DEFAULT_CLOSE_OTHERS) else null)
    }.getOrDefault(DEFAULT_CLOSE_OTHERS)

    fun setCloseOthers(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLOSE_OTHERS, on).apply()
    }
}

/**
 * Stops every other emulator before one launches: a resident RetroArch
 * (~1 GB RSS) left NetherSX2 with ~200 MB free and swapping on a 3.7 GB
 * phone. Shizuku (shell uid) runs `am force-stop`; without it,
 * [ActivityManager.killBackgroundProcesses] reaps cached processes only.
 * The launch target is never stopped since it may hold a resume state.
 */
object EmuCloser {
    private const val TAG = "GameBEXCloser"
    const val BUDGET_MS = 1_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun targets(target: String, ownPkg: String, known: Collection<String> = ForceStopPolicy.PACKAGES): List<String> =
        known.distinct().filter { it != target && ForceStopPolicy.allowed(it, ownPkg) != null }

    /** Never throws; waits at most [BUDGET_MS] and lets slower stops finish in the background. */
    suspend fun beforeLaunch(ctx: Context, target: String) {
        if (!LaunchPrefs.closeOthers(ctx)) return
        val app = ctx.applicationContext ?: ctx
        val job = scope.launch { runCatching { closeOthers(app, target) }.onFailure { Log.w(TAG, "close failed", it) } }
        if (withTimeoutOrNull(BUDGET_MS) { job.join() } == null) Log.i(TAG, "still closing; launching anyway")
    }

    private suspend fun closeOthers(ctx: Context, target: String) {
        val pm = ctx.packageManager
        val pkgs = targets(target, ctx.packageName).filter { EmuRouter.isInstalled(pm, it) }
        if (pkgs.isEmpty()) return
        // Binds once (only when Shizuku is already running + allowed).
        val stop = ShizukuFiles.forceStopper(ctx)
        val am = ctx.getSystemService(ActivityManager::class.java)
        coroutineScope {
            pkgs.map { pkg ->
                async {
                    val forced = stop?.let { runCatching { it(pkg) }.getOrDefault(false) } == true
                    if (!forced) runCatching { am?.killBackgroundProcesses(pkg) }
                    Log.d(TAG, "$pkg ${if (forced) "force-stopped" else "background-killed"}")
                }
            }.awaitAll()
        }
    }
}
