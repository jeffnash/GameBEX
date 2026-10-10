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
import com.gamebex.client.stream.StreamDebug
import com.gamebex.client.together.platform.TogetherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    const val CONSENT_TIMEOUT_MS: Long = 60_000L

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private val consent = StreamConsentLifecycle<Pair<Int, Intent?>>(lock)
    private var captureRequestId: String? = null
    private var projectionCallback: MediaProjection.Callback? = null

    /** Called when the host ends sharing from Android's own controls (not when [stop] ends it). */
    @Volatile var onEnded: (() -> Unit)? = null

    private var bal: BalOverlay? = null

    /** Arms the BAL overlay (idempotent); call at stream start, seconds before the consent proxy. */
    fun armBalOverlay(app: Context) {
        val overlay = synchronized(lock) {
            if (bal == null) bal = BalOverlay(SystemBalWindows(app.applicationContext), main::post)
            bal
        }
        overlay?.arm()
    }

    /** Disarms the BAL overlay (idempotent). */
    fun disarmBalOverlay() {
        synchronized(lock) { bal }?.disarm()
    }

    fun supported(): Boolean = CaptureIdentity.supported(Build.VERSION.SDK_INT)

    /** The shell's encoder surface, or null when it goes away; re-points a running capture. Any thread. */
    fun onSurface(next: Surface?, w: Int, h: Int, allowed: () -> Boolean) {
        var ended: (() -> Unit)? = null
        synchronized(lock) {
            val vd = display
            CaptureIdentity.surfaceChange(
                current = allowed,
                display = vd,
                present = next != null,
                width = w,
                height = h,
                resize = { display, width, height -> display.resize(width, height, dpi) },
                retarget = { display -> display.surface = next },
                storeSurface = {
                    surface = next
                    width = w
                    height = h
                },
                stop = {
                    Log.w(TAG, "app capture surface change failed")
                    ended = onEnded
                    stopLocked()
                },
                previousSurface = surface,
                nextSurface = next,
                previousWidth = width,
                previousHeight = height,
            )
        }
        ended?.invoke()
    }

    /** Asks the host to share the game's app and starts capturing it; false when declined or unavailable. */
    suspend fun start(ctx: Context, allowed: () -> Boolean = { true }): Boolean {
        if (!supported()) return false
        val app = ctx.applicationContext
        val pending = consent.openIfAllowed(allowed) ?: return false
        var started = false
        try {
            val launched = withContext(Dispatchers.Main) {
                consent.runIfCurrent(pending) {
                    if (!allowed()) return@runIfCurrent false
                    app.startActivity(
                        Intent(app, StreamCaptureActivity::class.java)
                            .putExtra(StreamCaptureActivity.EXTRA_REQUEST_ID, pending.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                } ?: false
            }
            if (!launched) return false
            val (code, data) = consent.await(pending, CONSENT_TIMEOUT_MS) ?: (Activity.RESULT_CANCELED to null)
            if (code != Activity.RESULT_OK || data == null) {
                Log.i(TAG, "video: share prompt declined or timed out ($code)")
                return false
            }
            val captured = withContext(Dispatchers.Main) {
                consent.runIfCurrent(pending) {
                    allowed() && begin(app, code, data, pending.id)
                } ?: false
            }
            started = captured
            return captured
        } finally {
            synchronized(lock) {
                consent.cancel(pending)
                if (!started && captureRequestId == pending.id) stopLocked()
            }
            disarmBalOverlay()
        }
    }

    /** StreamCaptureActivity's share-prompt result. */
    internal fun onConsent(requestId: String, activity: StreamCaptureActivity, code: Int, data: Intent?) {
        consent.answer(requestId, activity, code to data)
    }

    internal fun attachConsent(requestId: String, activity: StreamCaptureActivity, launch: () -> Unit) {
        consent.attach(requestId, activity, {
            if (Looper.myLooper() == main.looper) activity.closePrompt() else main.post { activity.closePrompt() }
        }, launch)
    }

    internal fun detachConsent(requestId: String, activity: StreamCaptureActivity, finishing: Boolean) {
        consent.detach(requestId, activity, finishing)
    }

    /** Ends the capture; the stream itself is stopped by the shell. Idempotent. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun begin(app: Context, code: Int, data: Intent, requestId: String): Boolean {
        if (surface == null || width <= 0 || height <= 0) return false
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
            projection = p
            captureRequestId = requestId
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    val ended = synchronized(lock) {
                        var ended: (() -> Unit)? = null
                        CaptureIdentity.stopProjection(projection, p) {
                            ended = onEnded
                            stopLocked()
                        }
                        ended
                    }
                    Log.i(TAG, "video: the host stopped sharing")
                    ended?.invoke()
                }

                override fun onCapturedContentResize(sourceWidth: Int, sourceHeight: Int) {
                    synchronized(lock) {
                        if (!CaptureIdentity.ownsProjection(projection, p)) return
                        Log.i(TAG, "capture source size=${sourceWidth}x$sourceHeight")
                    }
                }
            }
            projectionCallback = callback
            val refresh = CaptureRefreshOptions.sample().request(Build.VERSION.SDK_INT)
            if (refresh.requestedHz > 0) {
                Log.i(TAG, "CAPTURE_HZ requested=${refresh.requestedHz} applied=${refresh.appliedHz} reason=${refresh.reason}")
            }
            display = runCatching {
                p.registerCallback(callback, main)
                p.createVirtualDisplay(NAME, width, height, dpi, 0, surface, null, main)
            }
                .onFailure { Log.w(TAG, "app capture display failed", it) }
                .getOrNull()
            if (display == null) {
                stopLocked()
                return false
            }
            Log.i(TAG, "video: capturing the consented selection at ${width}x$height")
            return true
        }
    }

    private fun stopLocked() {
        disarmBalOverlay()
        consent.stop()
        captureRequestId = null
        val vd = display
        display = null
        val p = projection
        projection = null
        val callback = projectionCallback
        projectionCallback = null
        surface = null
        width = 0
        height = 0
        runCatching { vd?.release() }
        if (p != null) {
            if (callback != null) runCatching { p.unregisterCallback(callback) }
            runCatching { p.stop() }
            TogetherService.shareScreen(false)
        }
    }

    private val dpi: Int get() = android.content.res.Resources.getSystem().displayMetrics.densityDpi

}

internal data class CaptureRefreshRequest(val requestedHz: Int, val appliedHz: Int, val reason: String)

internal data class CaptureRefreshOptions(val hz: Int = 0) {
    fun request(sdk: Int) = CaptureRefreshRequest(
        requestedHz = if (hz == 60) 60 else 0,
        appliedHz = 0,
        reason = when {
            hz != 60 -> "normal"
            sdk < 34 -> "requested_refresh_rate_requires_api34"
            else -> "projection_refresh_api_unavailable"
        },
    )

    companion object {
        fun sample(
            debug: Boolean = com.gamebex.client.BuildConfig.DEBUG,
            prop: (String) -> String? = StreamDebug::prop,
        ): CaptureRefreshOptions {
            if (!debug) return CaptureRefreshOptions()
            return CaptureRefreshOptions(if (prop("capture_hz") == "60") 60 else 0)
        }
    }
}
