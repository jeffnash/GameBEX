package com.gamebex.client.together.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gamebex.client.MainActivity
import com.gamebex.client.together.TogetherAlert
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherPlatform
import kotlinx.coroutines.flow.StateFlow

class TogetherPlatformImpl(
    ctx: Context,
    private val prefs: TogetherPrefs,
) : TogetherPlatform {
    private val app = ctx.applicationContext
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    override val deviceId: String get() = prefs.deviceId
    override val foreground: StateFlow<Boolean> get() = TogetherRuntime.foreground

    override fun playerName(): String = prefs.customName ?: prefs.playerName(lanAddress())

    override fun customName(): String? = prefs.customName

    override fun setCustomName(name: String?) = prefs.setCustomName(name)

    override fun lanAddress(): String? = LanAddress.find(app)

    override fun appVersion(): String = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.versionName.orEmpty()
    }.getOrDefault("")

    override fun packageVersion(pkg: String): String? = runCatching {
        app.packageManager.getPackageInfo(pkg, 0).versionName
    }.getOrNull()

    override fun openRetroArch(): Boolean = runCatching {
        val intent = app.packageManager.getLaunchIntentForPackage("com.retroarch.aarch64") ?: return false
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    override fun startService(ongoingText: String) {
        TogetherService.clearStopRequest()
        runCatching {
            ContextCompat.startForegroundService(
                app,
                Intent(app, TogetherService::class.java).putExtra(TogetherService.EXTRA_TEXT, ongoingText),
            )
        }.onFailure { error ->
            runCatching { android.util.Log.w(TAG, "Unable to start Play together service", error) }
        }
    }

    override fun updateService(ongoingText: String) {
        runCatching {
            ensureChannels()
            notifications?.notify(NOTIF_ID, buildOngoing(app, ongoingText))
        }.onFailure { error ->
            runCatching { android.util.Log.w(TAG, "Unable to update Play together notification", error) }
        }
    }

    override fun stopService() {
        if (TogetherService.promoted) {
            runCatching { app.stopService(Intent(app, TogetherService::class.java)) }
        } else {
            TogetherService.requestStop()
        }
        runCatching { notifications?.cancel(NOTIF_ID) }
    }


    override fun raMenuButton(): String = com.gamebex.client.ui.console.glyphLabel(
        com.gamebex.client.ui.console.ControllerGlyph.Y,
        com.gamebex.client.ui.console.ControllerPrefs.layout,
        swap = false,
    )

    override fun alert(alert: TogetherAlert) {
        runCatching {
            ensureChannels()
            val (id, title, text) = when (alert) {
                is TogetherAlert.RoomOpen -> Triple(43, TogetherCopy.alertRoomOpenTitle(alert.hostName), TogetherCopy.alertRoomOpenText(raMenuButton()))
                is TogetherAlert.PeerLeft -> Triple(44, TogetherCopy.peerLeft(alert.name), TogetherCopy.alertTapToOpen)
                is TogetherAlert.LinkLost -> Triple(
                    44,
                    alert.streamGraceS?.let { TogetherCopy.streamLinkLostHost(alert.name, it) } ?: TogetherCopy.linkLost(alert.name),
                    TogetherCopy.alertTapToOpen,
                )
                is TogetherAlert.PeerSilent -> Triple(44, TogetherCopy.streamPeerSilent(alert.name), TogetherCopy.alertTapToOpen)
                is TogetherAlert.TapToStart -> Triple(45, TogetherCopy.alertTapToStartTitle(alert.hostName), TogetherCopy.alertTapToStartText)
            }
            notifications?.notify(
                id,
                NotificationCompat.Builder(app, ALERT_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_share)
                    .setContentIntent(contentIntent())
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build(),
            )
        }.onFailure { error ->
            runCatching { android.util.Log.w(TAG, "Unable to post Play together alert", error) }
        }
    }

    override fun cancelAlerts() {
        runCatching {
            notifications?.cancel(43)
            notifications?.cancel(45)
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = notifications ?: return
        if (manager.getNotificationChannel(ONGOING_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ONGOING_CHANNEL_ID,
                    TogetherCopy.channelOngoing,
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    TogetherCopy.channelAlerts,
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        app,
        CONTENT_REQUEST,
        Intent(app, MainActivity::class.java)
            .setAction(OPEN_ACTION)
            .putExtra(EXTRA_OPEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )


    companion object {
        const val ONGOING_CHANNEL_ID = "gamebex_together"
        const val ALERT_CHANNEL_ID = "gamebex_together_alerts"
        const val NOTIF_ID = 42
        const val OPEN_ACTION = "com.gamebex.client.together.OPEN"
        const val END_ACTION = "com.gamebex.client.together.END"
        const val EXTRA_OPEN = "gamebex.together.open"
        const val CONTENT_REQUEST = 4200
        const val END_REQUEST = 4201
        private const val TAG = "GameBEXTogether"

        internal fun buildOngoing(ctx: Context, text: String): Notification {
            val app = ctx.applicationContext
            val content = PendingIntent.getActivity(
                app,
                CONTENT_REQUEST,
                Intent(app, MainActivity::class.java)
                    .setAction(OPEN_ACTION)
                    .putExtra(EXTRA_OPEN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val end = PendingIntent.getService(
                app,
                END_REQUEST,
                Intent(app, TogetherService::class.java).setAction(END_ACTION),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(app, ONGOING_CHANNEL_ID)
                .setContentTitle(TogetherCopy.ongoingTitle)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(NotificationCompat.Action(0, TogetherCopy.endAction, end))
                .build()
        }

        internal fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(ONGOING_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(ONGOING_CHANNEL_ID, TogetherCopy.channelOngoing, NotificationManager.IMPORTANCE_LOW),
                )
            }
            if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(ALERT_CHANNEL_ID, TogetherCopy.channelAlerts, NotificationManager.IMPORTANCE_HIGH),
                )
            }
        }
    }
}
