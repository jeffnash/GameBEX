package com.gamebex.client.stream.guest

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamEndpoint
import com.gamebex.client.stream.StreamJson
import com.gamebex.client.ui.GameBexTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The guest's viewer: full screen, landscape, screen kept on, and a
 * low-latency Wi-Fi lock held while it is in front. Pad input goes to
 * player 2 before Compose sees it; events from the host's own virtual pad
 * ("GameBEX Player 2", present when both ends run on one emulator) are
 * ignored so input can't loop.
 */
class StreamViewerActivity : ComponentActivity() {
    private var session: StreamViewerSession? = null
    private val scope = MainScope()
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val endpoint = runCatching {
            StreamJson.json.decodeFromString(StreamEndpoint.serializer(), intent.getStringExtra(StreamGuestRuntime.EXTRA_ENDPOINT).orEmpty())
        }.getOrNull()
        if (endpoint == null) {
            Log.w(TAG, "viewer started without an endpoint")
            finish()
            return
        }
        val loopback = intent.getBooleanExtra(StreamGuestRuntime.EXTRA_LOOPBACK, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferFastestRefreshRate()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val s = StreamGuestRuntime.attach(endpoint, loopback)
        session = s
        setContent {
            GameBexTheme {
                val ui by s.ui.collectAsState()
                StreamViewerScreen(
                    ui = ui,
                    onSurface = { surface -> if (surface != null) s.attachSurface(surface) else s.detachSurface() },
                    onLeave = { s.leave() },
                    onToggleStats = { s.toggleStats() },
                    onBack = { finish() },
                )
            }
        }
        scope.launch {
            s.ui.collect { if (it.status == ViewerStatus.ENDED && it.message == null) finish() }
        }
        scope.launch {
            StreamGuestRuntime.closeRequests.drop(1).collect { finish() }
        }
        // The Play together link is separate from the stream: say so the moment it drops.
        scope.launch {
            (application as com.gamebex.client.GameBexApp).together.guest.collect { s.setLinkLost(it?.linkLost == true) }
        }
    }

    @Suppress("DEPRECATION")
    private fun preferFastestRefreshRate() {
        runCatching {
            val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            val current = d?.mode ?: return
            val best = d.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            if (best.modeId != current.modeId || best.refreshRate > current.refreshRate) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
            }
            Log.i(TAG, "viewer display mode ${best.physicalWidth}x${best.physicalHeight}@${best.refreshRate}")
        }.onFailure { Log.w(TAG, "refresh rate", it) }
    }

    /** RestrictedApi: the same lint false positive as MainActivity.dispatchKeyEvent. */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = session ?: return super.dispatchKeyEvent(event)
        if (event.device?.name == StreamCaps.PAD_NAME) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (s.ui.value.status == ViewerStatus.ENDED) finish() else s.leave()
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        if (event.repeatCount > 0) return true
        return s.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.eventTime * 1_000_000L) ||
            super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val s = session ?: return super.dispatchGenericMotionEvent(event)
        val joystick = event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!joystick || event.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(event)
        if (event.device?.name == StreamCaps.PAD_NAME) return true
        val device = event.device
        fun axis(primary: Int, fallback: Int): Float =
            if (device?.getMotionRange(primary, event.source) != null) event.getAxisValue(primary) else event.getAxisValue(fallback)
        val values = floatArrayOf(
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y),
            axis(MotionEvent.AXIS_Z, MotionEvent.AXIS_RX),
            axis(MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY),
            maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
            maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)),
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
        s.onAxes(values, event.eventTime * 1_000_000L)
        return true
    }

    override fun onResume() {
        super.onResume()
        if (wifiLock == null) wifiLock = createWifiLock()
        runCatching { wifiLock?.acquire() }.onFailure { Log.w(TAG, "wifi lock", it) }
    }

    override fun onPause() {
        super.onPause()
        session?.releaseInput()
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
    }

    /**
     * Wi-Fi power saving holds received frames until the next beacon; the
     * low-latency lock (Android 10+) switches it off while the viewer is in front.
     */
    @Suppress("DEPRECATION")
    private fun createWifiLock(): WifiManager.WifiLock? = runCatching {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi.createWifiLock(mode, "GameBEX:stream").apply { setReferenceCounted(false) }
    }.getOrNull()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (isFinishing) session?.let { if (it.ui.value.status != ViewerStatus.ENDED) it.leave() }
        session = null
    }

    private companion object {
        const val TAG = "GameBEXStream"
    }
}
    /**
     * Asks for the display's highest refresh rate at the current resolution while the viewer is in front:
     * at 120 Hz a decoded frame waits at most ~8 ms for the next vsync instead of ~17 ms.
     */
