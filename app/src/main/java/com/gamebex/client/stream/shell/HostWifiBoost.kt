package com.gamebex.client.stream.shell

import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.WorkSource
import android.util.Log

/** Keeps the host's Wi-Fi out of power saving while a Stream runs. */
interface LinkBoost {
    fun start()

    /** Called about once a second while the session runs. */
    fun refresh()
    fun stop()
}

object NoLinkBoost : LinkBoost {
    override fun start() = Unit
    override fun refresh() = Unit
    override fun stop() = Unit
}

/**
 * Wi-Fi low-latency mode for the host. Android only switches it on for a lock
 * whose WorkSource includes a foreground app, and GameBEX sits behind the
 * emulator, so the shell (which holds UPDATE_DEVICE_STATS) holds the lock and
 * attributes it to whatever apps are in front, re-checked every [refresh].
 * Any failure leaves Wi-Fi as it was.
 */
class HostWifiBoost(private val context: Context) : LinkBoost {
    private var lock: WifiManager.WifiLock? = null
    private var blamed: Set<Int> = emptySet()
    private val warnings = HostWifiBoostWarnings { Log.w(TAG, it) }

    @Synchronized override fun start() {
        lock = runCatching {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GameBEX:stream").apply {
                setReferenceCounted(false)
            }
        }.onFailure { Log.w(TAG, "wifi boost unavailable: ${it.message}") }.getOrNull()
        refresh()
    }

    @Synchronized override fun refresh() {
        val l = lock ?: return
        val uids = foregroundUids()
        if (uids.isEmpty() || uids == blamed) return
        runCatching {
            val source = try { workSourceOf(uids) } catch (t: Throwable) {
                warnings.workSourceFailure(t)
                return
            }
            l.setWorkSource(source)
            if (!l.isHeld) l.acquire()
            blamed = uids
            Log.i(TAG, "wifi boost for uids=$uids")
        }.onFailure { Log.w(TAG, "wifi boost refused: ${it.message}") }
    }

    @Synchronized override fun stop() {
        runCatching { lock?.let { if (it.isHeld) it.release() } }
        lock = null
        blamed = emptySet()
    }

    /** The app on top (the emulator while a game runs); every foreground app when that can't be read. */
    private fun foregroundUids(): Set<Int> = topUid()?.let { setOf(it) } ?: runCatching {
        warnings.foregroundFallback()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses.orEmpty()
            .filter { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
            .map { it.uid }
            .filter { it >= FIRST_APP_UID }
            .toSortedSet()
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION")
    private fun topUid(): Int? = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pkg = am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName ?: return@runCatching null
        context.packageManager.getPackageUid(pkg, 0).takeIf { it >= FIRST_APP_UID }
    }.getOrNull()

    companion object {
        private const val TAG = "GameBEXStream"
        private const val FIRST_APP_UID = 10_000

        /** WorkSource(uid) and add(uid) are system APIs; the shell process may call them. */
        internal fun workSourceOf(uids: Set<Int>): WorkSource {
            val ws = WorkSource()
            val add = WorkSource::class.java.getMethod("add", Int::class.javaPrimitiveType)
            uids.forEach { add.invoke(ws, it) }
            return ws
        }
    }
}

internal class HostWifiBoostWarnings(private val log: (String) -> Unit) {
    private var foregroundLogged = false
    private var workSourceLogged = false

    fun foregroundFallback() {
        if (foregroundLogged) return
        foregroundLogged = true
        log("wifi boost top uid unavailable; using all foreground apps")
    }

    fun workSourceFailure(error: Throwable) {
        if (workSourceLogged) return
        workSourceLogged = true
        log("wifi boost WorkSource unavailable: ${error.message}")
    }
}
