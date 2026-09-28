package com.gamebex.client.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.gamebex.client.GameBexApp
import com.gamebex.client.MainActivity
import com.gamebex.client.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the OS from stalling transfers while GameBEX is backgrounded; the
 * queue itself survives process death. [DownloadEngine] starts it only while
 * jobs are active. Best-effort: where a foreground start isn't allowed
 * (background restrictions, no notification grant), downloads resume on next launch.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastPushMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { promote() }
        scope.launch {
            val engine = (application as GameBexApp).engine
            engine.jobs.collectLatest { pushProgress(it.values.toList()) }
        }
    }

    override fun onDestroy() {
        promoted = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { promote() }
        return START_NOT_STICKY
    }

    private fun promote() {
        ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotif(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, buildNotif(null))
        }
        promoted = true
        // A stop that arrived before startForeground() is applied here:
        // stopping a startForegroundService() service earlier is a fatal
        // RemoteServiceException.
        if (stopRequested) {
            stopRequested = false
            finish()
        }
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Throttled because notification updates are IPC. */
    private fun pushProgress(snaps: List<DownloadEngine.DlSnapshot>) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPushMs < 1000) return
        lastPushMs = now
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            mgr.notify(NOTIF_ID, buildNotif(snaps))
        }
    }

    private fun buildNotif(snaps: List<DownloadEngine.DlSnapshot>?): android.app.Notification {
        val active = snaps?.filter { it.status == "active" }.orEmpty()
        val read = active.sumOf { it.read }
        val total = active.sumOf { it.total }
        val pct = if (total > 0) ((100 * read) / total).toInt().coerceIn(0, 100) else 0
        val (title, text, determinate) = when {
            snaps == null || active.isEmpty() ->
                Triple(getString(R.string.dl_notif_title), getString(R.string.dl_notif_idle), false)
            total > 0 && active.size == 1 ->
                Triple(active[0].title, getString(R.string.dl_notif_one, active[0].fileName, pct), true)
            total > 0 ->
                Triple(getString(R.string.dl_notif_multi, active.size, pct), getString(R.string.dl_notif_idle), true)
            else -> Triple(getString(R.string.dl_notif_title), getString(R.string.dl_notif_preparing), false)
        }
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_ROUTE, "downloads")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(tap)
            .setProgress(100, pct, !determinate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "gamebex_downloads"
        /** MainActivity intent extra: top-level route to open on tap. */
        const val EXTRA_ROUTE = "gamebex.route"
        private const val NOTIF_ID = 41

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, ctx.getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        @Volatile private var promoted = false
        @Volatile private var stopRequested = false

        /** Idempotent: safe to call on every scheduler pass. */
        fun sync(ctx: Context, active: Boolean) {
            runCatching {
                if (active) {
                    stopRequested = false
                    val intent = Intent(ctx.applicationContext, DownloadService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) {
                        ctx.applicationContext.startForegroundService(intent)
                    } else {
                        ctx.applicationContext.startService(intent)
                    }
                } else if (promoted) {
                    ctx.applicationContext.stopService(Intent(ctx.applicationContext, DownloadService::class.java))
                } else {
                    // Not foreground yet (or not running): defer; promote() stops it.
                    stopRequested = true
                }
            }
        }

    }
}
