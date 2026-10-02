package com.gamebex.client.stream.shell

import android.content.Context
import android.os.Looper
import android.util.Log

/**
 * Process set-up for code running as the shell uid. Under Shizuku the
 * UserService starter has already called ActivityThread.systemMain(); under a
 * bare app_process (StreamShellMain) this does it, as Shizuku's starter does
 * (Shizuku-API server-shared/src/main/java/rikka/shizuku/server/UserService.java).
 */
object ShellRuntime {
    private const val TAG = "GameBEXStream"
    @Volatile private var cached: Context? = null

    /** The system context of this process's ActivityThread, creating the thread when missing. */
    fun systemContext(): Context? = cached ?: synchronized(this) {
        cached ?: create().also { cached = it }
    }

    fun ensureMainLooper() {
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
    }

    private fun create(): Context? = try {
        ensureMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        val thread = current ?: activityThread.getMethod("systemMain").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(thread) as Context
    } catch (t: Throwable) {
        Log.w(TAG, "no system context", t)
        null
    }
}
