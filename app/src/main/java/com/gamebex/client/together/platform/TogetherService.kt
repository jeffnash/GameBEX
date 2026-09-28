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

    override fun onCreate() {
        super.onCreate()
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
        super.onDestroy()
    }

    private fun promote(text: String) {
        TogetherPlatformImpl.ensureChannels(this)
        val notification = TogetherPlatformImpl.buildOngoing(this, text)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                TogetherPlatformImpl.NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
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

        fun requestStop() {
            stopRequested = true
        }

        fun clearStopRequest() {
            stopRequested = false
        }
    }
}
