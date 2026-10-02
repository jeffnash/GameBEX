package com.gamebex.client.together.platform

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/** Keeps an active Play together link alive while GameBEX is backgrounded. */
class TogetherService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private var text = ""

    override fun onCreate() {
        super.onCreate()
        current = this
        runCatching { promote("") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopRequested && intent?.action != TogetherPlatformImpl.END_ACTION) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            when (intent?.action) {
                null -> promote(intent?.getStringExtra(EXTRA_TEXT).orEmpty())
                TogetherPlatformImpl.END_ACTION -> TogetherRuntime.controller(this).end()
            }
        }.onFailure { error ->
            runCatching { android.util.Log.w(TAG, "Unable to handle Play together service command", error) }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { TogetherRuntime.controller(this).end() }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        promoted = false
        sharing = false
        if (current === this) current = null
        super.onDestroy()
    }

    private fun promote(text: String) {
        this.text = text
        TogetherPlatformImpl.ensureChannels(this)
        val notification = TogetherPlatformImpl.buildOngoing(this, text)
        if (Build.VERSION.SDK_INT >= 29) {
            // Stream's app-only capture needs the mediaProjection type before getMediaProjection (Android 14).
            val projection = if (sharing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            ServiceCompat.startForeground(
                this,
                TogetherPlatformImpl.NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or projection,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(TogetherPlatformImpl.NOTIF_ID, notification)
        }
        promoted = true
        if (stopRequested) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        const val EXTRA_TEXT = "gamebex.together.text"
        private const val TAG = "GameBEXTogether"
        @Volatile internal var promoted = false
        @Volatile internal var stopRequested = false
        @Volatile private var current: TogetherService? = null
        @Volatile private var sharing = false

        /**
         * Adds (or drops) the mediaProjection foreground type for Stream's app-only capture. False when
         * the service isn't running or Android refuses; call on the main thread.
         */
        fun shareScreen(on: Boolean): Boolean {
            val service = current ?: return false
            sharing = on
            return runCatching { service.promote(service.text) }
                .onFailure {
                    sharing = false
                    runCatching { android.util.Log.w(TAG, "Unable to change the screen-sharing service type", it) }
                }
                .isSuccess
        }

        fun requestStop() {
            stopRequested = true
        }

        fun clearStopRequest() {
            stopRequested = false
        }
    }
}
