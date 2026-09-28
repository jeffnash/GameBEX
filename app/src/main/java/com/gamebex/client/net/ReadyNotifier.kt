package com.gamebex.client.net

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gamebex.client.MainActivity
import com.gamebex.client.R
import java.util.UUID

/**
 * The Play action opens the game's detail and starts the game from the
 * foreground activity, so it needs no background activity start and no new
 * exported component. Skipped silently without the POST_NOTIFICATIONS grant.
 */
object ReadyNotifier {
    const val CHANNEL_ID = "gamebex_ready"
    /** MainActivity extra: autoplay the routed game (honored only with [EXTRA_TOKEN]). */
    const val EXTRA_PLAY = "gamebex.play"
    /**
     * MainActivity is exported (launcher), so any app can send it extras.
     * Autoplay therefore requires this install's private token, which only
     * our own PendingIntents carry.
     */
    const val EXTRA_TOKEN = "gamebex.token"
    private const val TAG = "ready"
    private const val PREFS = "ready_notifier"

    fun post(ctx: Context, gameId: Long, title: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            val mgr = NotificationManagerCompat.from(ctx)
            if (!mgr.areNotificationsEnabled()) return
            ensureChannel(ctx)
            val route = GameRoutes.local(gameId)
            val base = (gameId and 0x3FFFFFFF).toInt() * 2
            val open = PendingIntent.getActivity(
                ctx, base, intentFor(ctx, route, play = false),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val play = PendingIntent.getActivity(
                ctx, base + 1, intentFor(ctx, route, play = true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(ctx.getString(R.string.ready_notif_title, title))
                .setContentText(ctx.getString(R.string.ready_notif_text))
                .setContentIntent(open)
                .addAction(0, ctx.getString(R.string.ready_action_play), play)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            mgr.notify(TAG, notifId(gameId), notif)
        }
    }

    /** Clears a game's ready notification (played or deleted). */
    fun cancel(ctx: Context, gameId: Long) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(TAG, notifId(gameId)) }
    }

    private fun notifId(gameId: Long): Int = (gameId and 0x7FFFFFFF).toInt()

    private fun intentFor(ctx: Context, route: String, play: Boolean): Intent =
        Intent(ctx, MainActivity::class.java)
            // Distinct actions keep the two PendingIntents from aliasing.
            .setAction(if (play) "com.gamebex.client.PLAY_GAME" else "com.gamebex.client.OPEN_GAME")
            .putExtra(DownloadService.EXTRA_ROUTE, route)
            .apply {
                if (play) {
                    putExtra(EXTRA_PLAY, true)
                    putExtra(EXTRA_TOKEN, token(ctx))
                }
            }
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Per-install secret gating notification autoplay. */
    fun token(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("token", null)?.let { return it }
        val t = UUID.randomUUID().toString()
        p.edit().putString("token", t).apply()
        return t
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, ctx.getString(R.string.ready_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
}

/**
 * Top-level routes pass through; `local/<id>` opens a game's detail,
 * autoplaying only when the intent carried this install's private token.
 */
object GameRoutes {
    val HOME_ROUTES = setOf("library", "allgames", "browse", "downloads", "emulators")

    fun local(gameId: Long): String = "local/$gameId"

    sealed interface Target {
        data class Top(val route: String) : Target
        data class Game(val gameId: Long, val autoPlay: Boolean) : Target
    }

    fun parse(
        route: String?,
        play: Boolean = false,
        token: String? = null,
        expectedToken: String? = null,
    ): Target? {
        if (route == null) return null
        if (route in HOME_ROUTES) return Target.Top(route)
        if (!route.startsWith("local/")) return null
        val id = route.removePrefix("local/")
            .takeIf { s -> s.isNotEmpty() && s.length <= 18 && s.all { it in '0'..'9' } }
            ?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val trusted = play && !token.isNullOrEmpty() && token == expectedToken
        return Target.Game(id, autoPlay = trusted)
    }
}
