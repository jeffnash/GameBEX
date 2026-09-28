package com.gamebex.client.together.platform

import android.app.NotificationManager
import android.app.ActivityManager
import android.app.Notification
import android.os.SystemClock
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.gamebex.client.together.TogetherAlert
import com.gamebex.client.together.TogetherMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TogetherServiceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS")
        try {
            ui.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        } finally {
            ui.dropShellPermissionIdentity()
        }
    }

    @Test
    fun serviceLifecycle_postsOngoingAndAlertAndStops() {
        grantNotifications()
        val platform = TogetherPlatformImpl(app, TogetherPrefs(app))
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            platform.startService("Waiting for the other phone")
            awaitNotification("ongoing notification ${TogetherPlatformImpl.NOTIF_ID} was not posted") {
                manager.activeNotifications.any { it.id == TogetherPlatformImpl.NOTIF_ID }
            }
            assertEquals(
                TogetherPlatformImpl.ONGOING_CHANNEL_ID,
                manager.activeNotifications.first { it.id == TogetherPlatformImpl.NOTIF_ID }.notification.channelId,
            )
            platform.updateService("Updated text")
            assertEquals(
                "Updated text",
                manager.activeNotifications.first { it.id == TogetherPlatformImpl.NOTIF_ID }
                    .notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            )
            platform.alert(TogetherAlert.TapToStart("Sam"))
            awaitNotification("alert notification 45 was not posted") {
                manager.activeNotifications.any { it.id == 45 }
            }
        } finally {
            platform.stopService()
        }
        awaitNotification("ongoing notification ${TogetherPlatformImpl.NOTIF_ID} was still posted after stop") {
            manager.activeNotifications.none { it.id == TogetherPlatformImpl.NOTIF_ID }
        }
    }

    @Test
    fun cancelAlerts_keepsPeerLeftAlert() {
        grantNotifications()
        val platform = TogetherPlatformImpl(app, TogetherPrefs(app))
        val manager = app.getSystemService(NotificationManager::class.java)
        platform.alert(TogetherAlert.RoomOpen("Sam", TogetherMode.RA_NETPLAY))
        platform.alert(TogetherAlert.PeerLeft("Sam"))
        platform.alert(TogetherAlert.TapToStart("Sam"))
        awaitNotification("alerts 43, 44, and 45 were not posted") {
            val ids = manager.activeNotifications.map { it.id }
            ids.any { it == 43 } && ids.any { it == 44 } && ids.any { it == 45 }
        }
        assertTrue(manager.activeNotifications.any { it.id == 43 })
        assertTrue(manager.activeNotifications.any { it.id == 45 })
        platform.cancelAlerts()
        awaitNotification("alerts 43 and 45 were still posted after cancelAlerts") {
            val ids = manager.activeNotifications.map { it.id }
            ids.none { it == 43 || it == 45 } && ids.any { it == 44 }
        }
        assertTrue(manager.activeNotifications.none { it.id == 43 || it.id == 45 })
        assertTrue(manager.activeNotifications.any { it.id == 44 })
        manager.cancel(44)
    }

    @Test
    fun stopBeforePromote_doesNotCallStopServiceAndSetsStopRequested() {
        val wasPromoted = TogetherService.promoted
        TogetherService.promoted = false
        TogetherService.clearStopRequest()
        var stopCalls = 0
        val recorder = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun stopService(name: Intent?): Boolean {
                stopCalls += 1
                return false
            }
        }
        try {
            TogetherPlatformImpl(recorder, TogetherPrefs(app)).stopService()
            assertEquals(0, stopCalls)
            assertTrue(TogetherService.stopRequested)
        } finally {
            TogetherService.promoted = wasPromoted && togetherServiceRunning()
            TogetherService.clearStopRequest()
        }
    }

    @Test
    fun startThenImmediateStop_doesNotCrashBeforeStartForeground() {
        grantNotifications()
        TogetherService.clearStopRequest()
        TogetherService.promoted = false
        val platform = TogetherPlatformImpl(app, TogetherPrefs(app))
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            platform.startService("race")
            platform.stopService()
            // The service record exists from startForegroundService until the service stops
            // itself, which it does only after startForeground: gone means no pending FGS timeout.
            awaitNotification("TogetherService was still running after an immediate stop", timeoutMs = 10_000) {
                !togetherServiceRunning() && manager.activeNotifications.none { it.id == TogetherPlatformImpl.NOTIF_ID }
            }
            assertTrue(
                "ongoing notification was still posted after an immediate stop",
                manager.activeNotifications.none { it.id == TogetherPlatformImpl.NOTIF_ID },
            )
            assertTrue(!TogetherService.promoted)
        } finally {
            if (togetherServiceRunning()) platform.stopService()
            TogetherService.promoted = false
            TogetherService.clearStopRequest()
        }
    }

    @Test
    fun endAction_doesNotPromoteBlankNotification() {
        grantNotifications()
        val platform = TogetherPlatformImpl(app, TogetherPrefs(app))
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            platform.startService("Keep this text")
            awaitNotification("ongoing notification text was not 'Keep this text'") {
                ongoingText(manager) == "Keep this text"
            }
            assertEquals(
                "Keep this text",
                manager.activeNotifications.first { it.id == TogetherPlatformImpl.NOTIF_ID }
                    .notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            )
            val endSentAt = SystemClock.elapsedRealtime()
            app.startService(
                Intent(app, TogetherService::class.java).setAction(TogetherPlatformImpl.END_ACTION),
            )
            awaitNotification("END action replaced ongoing notification text") {
                val text = ongoingText(manager)
                if (text != null && text != "Keep this text") return@awaitNotification false
                SystemClock.elapsedRealtime() >= endSentAt + 200 && text == "Keep this text"
            }
            assertEquals(
                "Keep this text",
                manager.activeNotifications.first { it.id == TogetherPlatformImpl.NOTIF_ID }
                    .notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            )
        } finally {
            platform.stopService()
        }
    }

    @Test
    fun alert45_postsOnGamebexTogetherAlertsChannel() {
        grantNotifications()
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            TogetherPlatformImpl(app, TogetherPrefs(app)).alert(TogetherAlert.TapToStart("Sam"))
            awaitNotification("alert notification 45 was not posted") {
                manager.activeNotifications.any { it.id == 45 }
            }
            val posted = manager.activeNotifications.first { it.id == 45 }
            assertEquals("gamebex_together_alerts", posted.notification.channelId)
        } finally {
            manager.cancel(45)
        }
    }

    private fun ongoingText(manager: NotificationManager): String? =
        manager.activeNotifications.firstOrNull { it.id == TogetherPlatformImpl.NOTIF_ID }
            ?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun awaitNotification(message: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue(message, condition())
    }

    @Suppress("DEPRECATION")
    private fun togetherServiceRunning(): Boolean {
        val manager = app.getSystemService(ActivityManager::class.java)
        return manager.getRunningServices(Integer.MAX_VALUE).any {
            it.service.className == TogetherService::class.java.name
        }
    }
}
