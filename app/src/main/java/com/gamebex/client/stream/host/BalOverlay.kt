package com.gamebex.client.stream.host

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Window ops behind [BalOverlay]. The framework View/WindowManager types are
 * stubs in JVM unit tests, so production and tests each bring their own.
 */
internal interface BalWindows {
    /** Adds the window; the token remove() needs, or null when refused. */
    fun add(): Any?

    /** Removes a token add() returned. */
    fun remove(token: Any?)
}

/**
 * A 1px overlay that defeats the background-activity-start block on the
 * share consent. The consent proxy must start after the emulator is up
 * (starting it into a booting emulator's task shuffle dies on a stale
 * START), but then GameBEX has no visible activity and a
 * fast emulator's draw BAL-blocks the proxy (Dolphin draws in <86ms).
 * BAL_ALLOW_VISIBLE_WINDOW covers non-app windows too, so one settled
 * overlay window keeps the proxy start allowed. Armed at stream start
 * (seconds before the proxy starts, so the window is settled visible),
 * disarmed when consent ends or the stream stops. Invisible (1px,
 * transparent, untouchable) and never captured (app projection captures
 * the game only). Idempotent; safe on any thread.
 */
internal class BalOverlay(
    private val windows: BalWindows,
    private val post: (Runnable) -> Unit,
) {
    private val lock = Any()
    private var token: Any? = null

    fun arm() {
        post(Runnable {
            synchronized(lock) {
                if (token == null) token = runCatching { windows.add() }.getOrNull()
            }
        })
    }

    fun disarm() {
        post(Runnable {
            synchronized(lock) {
                val current = token
                token = null
                if (current != null) runCatching { windows.remove(current) }
            }
        })
    }

    internal fun armedForTest(): Boolean = synchronized(lock) { token != null }
}

/** [BalWindows] over the real window manager. Untested at unit level (framework stubs); verified on-device. */
internal class SystemBalWindows(private val app: Context) : BalWindows {
    override fun add(): Any? {
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "video: no overlay permission; consent may BAL-block on fast emus")
            return null
        }
        val wm = app.getSystemService(WindowManager::class.java) ?: return null
        val view = View(app)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        return runCatching {
            wm.addView(view, lp)
            Log.i(TAG, "video: BAL overlay armed")
            view
        }.onFailure { Log.w(TAG, "video: BAL overlay add failed", it) }.getOrNull()
    }

    override fun remove(token: Any?) {
        val view = token as? View ?: return
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.removeView(view) }
    }

    private companion object {
        const val TAG = "GameBEXStream"
    }
}
