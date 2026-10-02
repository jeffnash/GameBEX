package com.gamebex.client.stream.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.gamebex.client.together.platform.TogetherService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-only capture on the host (Android 14+). Android's share prompt lets the host pick the game's
 * app, and that single-app MediaProjection renders into the shell's encoder surface, so nothing else
 * on the host's screen (notifications, toasts, system dialogs) reaches the guest.
 *
 * The shell can't do this itself: a single-app capture only comes from a MediaProjection, and making
 * one without the prompt needs MANAGE_MEDIA_PROJECTION, which the shell doesn't hold (checked on the
 * phones' Android 16 services.jar).
 *
 * Android 14 order: consent, then the foreground service with the mediaProjection type, then
 * getMediaProjection, registerCallback, createVirtualDisplay.
 */
object StreamAppCapture {
    private const val TAG = "GameBEXStream"
    private const val NAME = "gamebex-stream"

    /** How long the host has to answer the share prompt before the stream mirrors the display. */
    const val CONSENT_TIMEOUT_MS: Long = 60_000L

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var consent: CompletableDeferred<Pair<Int, Intent?>>? = null
    private var stopping = false

    /** Called when the host ends sharing from Android's own controls (not when [stop] ends it). */
    @Volatile var onEnded: (() -> Unit)? = null

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 34

    /** The shell's encoder surface, or null when it goes away; re-points a running capture. Any thread. */
    fun onSurface(next: Surface?, w: Int, h: Int) {
        synchronized(lock) {
            surface = next
            width = w
            height = h
            val vd = display ?: return
            runCatching {
                if (next != null) vd.resize(w, h, dpi)
                vd.surface = next
            }.onFailure { Log.w(TAG, "app capture surface change failed", it) }
        }
    }

    /** Asks the host to share the game's app and starts capturing it; false when declined or unavailable. */
    suspend fun start(ctx: Context): Boolean {
        if (!supported()) return false
        val app = ctx.applicationContext
        val pending = CompletableDeferred<Pair<Int, Intent?>>()
        synchronized(lock) { consent = pending }
        try {
            withContext(Dispatchers.Main) {
                app.startActivity(Intent(app, StreamCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val (code, data) = withTimeoutOrNull(CONSENT_TIMEOUT_MS) { pending.await() } ?: (Activity.RESULT_CANCELED to null)
            if (code != Activity.RESULT_OK || data == null) {
                Log.i(TAG, "video: share prompt declined or timed out ($code)")
                return false
            }
            return withContext(Dispatchers.Main) { begin(app, code, data) }
        } finally {
            synchronized(lock) { if (consent === pending) consent = null }
        }
    }

    /** StreamCaptureActivity's share-prompt result. */
    internal fun onConsent(code: Int, data: Intent?) {
        synchronized(lock) { consent }?.complete(code to data)
    }

    /** Ends the capture; the stream itself is stopped by the shell. Idempotent. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun begin(app: Context, code: Int, data: Intent): Boolean {
        if (!TogetherService.shareScreen(true)) {
            Log.w(TAG, "video: no foreground service for app capture")
            return false
        }
        val p = runCatching { app.getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }
            .onFailure { Log.w(TAG, "getMediaProjection failed", it) }
            .getOrNull()
        synchronized(lock) {
            if (p == null) {
                TogetherService.shareScreen(false)
                return false
            }
            stopping = false
            projection = p
            p.registerCallback(callback, main)
            display = runCatching { p.createVirtualDisplay(NAME, width, height, dpi, 0, surface, null, main) }
                .onFailure { Log.w(TAG, "app capture display failed", it) }
                .getOrNull()
            if (display == null) {
                stopLocked()
                return false
            }
            Log.i(TAG, "video: capturing the shared app only at ${width}x$height")
            return true
        }
    }

    private fun stopLocked() {
        stopping = true
        runCatching { display?.release() }
        display = null
        val p = projection
        projection = null
        if (p != null) {
            runCatching { p.unregisterCallback(callback) }
            runCatching { p.stop() }
            TogetherService.shareScreen(false)
        }
    }

    private val dpi: Int get() = android.content.res.Resources.getSystem().displayMetrics.densityDpi

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            val byHost = synchronized(lock) {
                val ours = stopping
                stopLocked()
                !ours
            }
            if (byHost) {
                Log.i(TAG, "video: the host stopped sharing")
                onEnded?.invoke()
            }
        }
    }
}
