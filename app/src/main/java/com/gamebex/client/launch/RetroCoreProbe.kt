package com.gamebex.client.launch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * RetroArch installed-core probe. RetroArch answers the
 * QUERY_INSTALLED_CORES broadcast with INSTALLED_CORES_RESULT + a String[]
 * extra of cores-dir filenames (verified against the installed manifest +
 * receiver bytecode, and live on-device). Explicit package-targeted
 * broadcast: allowed on modern Android with the manifest queries entry.
 */
object RetroCoreProbe {
    const val RA_PKG = "com.retroarch.aarch64"
    const val ACTION_QUERY = "com.retroarch.QUERY_INSTALLED_CORES"
    const val ACTION_RESULT = "com.retroarch.INSTALLED_CORES_RESULT"
    const val EXTRA_CORES = "CORES"

    /** Base core name ("gambatte") from a cores-dir filename, else null. */
    fun coreBaseName(fileName: String): String? {
        val lower = fileName.lowercase()
        val base = when {
            lower.endsWith("_libretro_android.so") ->
                fileName.dropLast("_libretro_android.so".length)
            lower.endsWith("_libretro.so") ->
                fileName.dropLast("_libretro.so".length)
            else -> return null
        }
        return base.takeIf { it.isNotEmpty() }?.lowercase()
    }

    fun isRetroArchInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(RA_PKG, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** RetroArch's version code, or 0 when it isn't installed (auto-hide gate). */
    fun versionCode(ctx: Context): Long = try {
        val info = ctx.packageManager.getPackageInfo(RA_PKG, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) {
        0L
    }

    /**
     * Only an accessible directory listing proves the setup folders absent.
     * File.exists() on another app's inaccessible private directory says
     * false too; that is unknown, not evidence of missing first-run assets.
     */
    internal fun setupPathsMissing(accessibleNames: List<String>?): Boolean =
        accessibleNames != null && ("cores" !in accessibleNames || "assets" !in accessibleNames)

    fun setupPathsMissing(ctx: Context): Boolean = try {
        val dataDir = ctx.packageManager.getApplicationInfo(RA_PKG, 0).dataDir
        dataDir != null && setupPathsMissing(File(dataDir).list()?.toList())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * Query broadcast. INCLUDE_STOPPED is load-bearing: without it a
     * force-stopped RetroArch never receives the query, the probe times
     * out, and the caller would boot blind into the ANR-on-missing-core
     * hang (proven on-device).
     */
    fun queryIntent(): Intent = Intent(ACTION_QUERY)
        .setPackage(RA_PKG)
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

    /**
     * Installed core base names, or null when RetroArch is missing or
     * doesn't answer in time. Caller cancellation always propagates.
     */
    suspend fun queryInstalledCores(ctx: Context, timeoutMs: Long = 8000): Set<String>? {
        return try {
            currentCoroutineContext().ensureActive()
            if (!isRetroArchInstalled(ctx)) return null
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) {
                            if (intent.action != ACTION_RESULT) return
                            val names = intent.getStringArrayExtra(EXTRA_CORES).orEmpty()
                                .mapNotNull(::coreBaseName).toSet()
                            runCatching { ctx.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(names)
                        }
                    }
                    // Must be EXPORTED: the reply comes from another app.
                    // Accepted risk: the result action is unauthenticated
                    // (RetroArch sends it without a permission or shared
                    // identity), so a hostile local app could spoof it.
                    // It can spoof confirmation/skip a fetch, but cannot
                    // substitute bytes: staged hash-pin checks still apply.
                    ContextCompat.registerReceiver(
                        ctx, receiver, IntentFilter(ACTION_RESULT),
                        ContextCompat.RECEIVER_EXPORTED,
                    )
                    cont.invokeOnCancellation {
                        runCatching { ctx.unregisterReceiver(receiver) }
                    }
                    try {
                        ctx.sendBroadcast(queryIntent())
                    } catch (e: Exception) {
                        runCatching { ctx.unregisterReceiver(receiver) }
                        throw e
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
