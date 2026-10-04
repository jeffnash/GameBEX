package com.gamebex.client.stream.guest

import android.content.Context
import android.net.wifi.WifiManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gamebex.client.launch.ControllerRegistry
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamDebug
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
    private var options = ViewerOptions()
    private var physicalControllerConnected by mutableStateOf(false)
    private var controllerObserver: ControllerRegistry.PadObserver? = null
    private var oldDisplayMode = 0
    private var oldRefreshRate = 0f
    private var displayManager: DisplayManager? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (viewerDisplay()?.displayId == id) logActualDisplay()
        }
    }

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
        options = savedInstanceState?.getBundle(VIEWER_OPTIONS)?.let { ViewerOptions.restore(it) }
            ?: ViewerOptions.sample(StreamDebug::flag, StreamDebug::int)
        oldDisplayMode = window.attributes.preferredDisplayModeId
        oldRefreshRate = window.attributes.preferredRefreshRate
        window.decorView.post { configureUnbufferedInput() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        configureDisplay()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val s = StreamGuestRuntime.attach(endpoint, loopback)
        session = s
        controllerObserver = ControllerRegistry.observe(this) { pads ->
            physicalControllerConnected = updateViewerControllerPresence(
                physicalControllerConnected, pads.isNotEmpty(), s::releaseTouchInput,
            )
        }
        if (StreamDebug.prop("tdls") == "1") setTdls(endpoint.host, true)
        setContent {
            GameBexTheme {
                val ui by s.ui.collectAsState()
                StreamViewerScreen(
                    ui = ui,
                    onSurface = { surface ->
                        if (surface != null) {
                            voteFrameRate(surface)
                            s.attachSurface(surface)
                        } else s.detachSurface()
                    },
                    onLeave = { s.leave() },
                    onToggleStats = { s.toggleStats() },
                    onBack = { finish() },
                    onTouchKey = s::onTouchKey,
                    onTouchAxes = s::onTouchAxes,
                    onReleaseTouchInput = s::releaseTouchInput,
                    hudEnabled = !options.hudOff,
                    touchPadHidden = options.touchPadHidden,
                    unbufferedInput = options.unbufferedInput,
                    fixedSurface = options.fixedSurface,
                    surfaceOpaque = options.surfaceOpaque,
                    physicalControllerConnected = physicalControllerConnected,
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
    private fun viewerDisplay() = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay

    /**
     * Asks for the display's highest refresh rate at the current resolution while the viewer is in front:
     * at 120 Hz a decoded frame waits at most ~8 ms for the next vsync instead of ~17 ms.
     */
    private fun configureDisplay() {
        runCatching {
            val d = viewerDisplay() ?: return
            val current = d.mode
            val modes = d.supportedModes.map { ViewerDisplayMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
            Log.i(TAG, "DISPLAY_MODES ${modes.joinToString(prefix = "[", postfix = "]") { "{\"id\":${it.id},\"width\":${it.width},\"height\":${it.height},\"hz\":${it.hz}}" }}")
            val chosen = selectViewerDisplayMode(modes, current.physicalWidth, current.physicalHeight, options.displayMode)
            if (options.displayMode > 0 && chosen == null) {
                Log.w(TAG, "DISPLAY_UNSUPPORTED id=${options.displayMode}")
            } else {
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = chosen?.id ?: 0
                    if (options.displayMode != 0) preferredRefreshRate = 0f
                }
            }
            displayManager = getSystemService(DisplayManager::class.java)
            displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            logActualDisplay()
        }.onFailure { Log.w(TAG, "refresh rate", it) }
    }

    private fun logActualDisplay() {
        val mode = viewerDisplay()?.mode ?: return
        Log.i(TAG, "DISPLAY_ACTUAL id=${mode.modeId} width=${mode.physicalWidth} height=${mode.physicalHeight} hz=${mode.refreshRate}")
    }

    private fun voteFrameRate(surface: Surface) {
        if (Build.VERSION.SDK_INT >= 30 && options.frameRateVote > 0) {
            runCatching { surface.setFrameRate(options.frameRateVote.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
                .onFailure { Log.w(TAG, "frame rate vote", it) }
        }
    }

    private fun configureUnbufferedInput() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.requestUnbufferedDispatch(unbufferedJoystickSources(Build.VERSION.SDK_INT, options.unbufferedInput))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle(VIEWER_OPTIONS, options.save())
        super.onSaveInstanceState(outState)
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
        return s.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, touchSampleNs(event.eventTime, System.nanoTime(), SystemClock.uptimeMillis())) ||
            super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val s = session ?: return super.dispatchGenericMotionEvent(event)
        val joystick = event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!joystick || event.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(event)
        if (event.device?.name == StreamCaps.PAD_NAME) return true
        if (options.unbufferedInput) {
            Log.d(TAG, "INPUT_DISPATCH history=${event.historySize} source=${event.source} action=${event.action} delayNs=${System.nanoTime() - touchSampleNs(event.eventTime, System.nanoTime(), SystemClock.uptimeMillis())}")
        }
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
        s.onAxes(values, touchSampleNs(event.eventTime, System.nanoTime(), SystemClock.uptimeMillis()))
        return true
    }

    override fun onResume() {
        super.onResume()
        controllerObserver?.rescan()
        configureUnbufferedInput()
        logActualDisplay()
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

    /** StreamDebug "tdls": a direct Wi-Fi link to the host while both stay on the AP. */
    private fun setTdls(host: String, on: Boolean) {
        tdlsHost = if (on) host else null
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val peer = java.net.InetAddress.getByName(host)
            if (Build.VERSION.SDK_INT >= 34) {
                wifi.setTdlsEnabled(peer, on, mainExecutor) { ok ->
                    runCatching { Log.i(TAG, "tdls $host on=$on accepted=$ok supported=${wifi.isTdlsSupported}") }
                }
                wifi.getNumberOfEnabledTdlsSessions(mainExecutor) { n -> runCatching { Log.i(TAG, "tdls sessions=$n") } }
            } else {
                @Suppress("DEPRECATION") wifi.setTdlsEnabled(peer, on)
                Log.i(TAG, "tdls $host on=$on supported=${wifi.isTdlsSupported}")
            }
        }.onFailure { Log.w(TAG, "tdls failed", it) }
    }

    private var tdlsHost: String? = null

    override fun onDestroy() {
        controllerObserver?.close()
        controllerObserver = null
        tdlsHost?.let { setTdls(it, false) }
        displayManager?.unregisterDisplayListener(displayListener)
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = oldDisplayMode
            preferredRefreshRate = oldRefreshRate
        }
        if (Build.VERSION.SDK_INT >= 30) window.decorView.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_NONE)
        super.onDestroy()
        scope.cancel()
        if (isFinishing) session?.let { if (it.ui.value.status != ViewerStatus.ENDED) it.leave() }
        session = null
    }

    private companion object {
        const val TAG = "GameBEXStream"
        const val VIEWER_OPTIONS = "stream_viewer_options"
    }
}

internal fun updateViewerControllerPresence(previous: Boolean, connected: Boolean, releaseTouchInput: () -> Unit): Boolean {
    if (previous != connected) releaseTouchInput()
    return connected
}

internal data class ViewerDisplayMode(val id: Int, val width: Int, val height: Int, val hz: Float)

internal fun selectViewerDisplayMode(modes: List<ViewerDisplayMode>, width: Int, height: Int, requested: Int): ViewerDisplayMode? {
    val matching = modes.filter { it.width == width && it.height == height }
    return when {
        requested == 0 -> matching.maxByOrNull { it.hz }
        requested > 0 -> matching.singleOrNull { it.id == requested }
        else -> null
    }
}

internal fun unbufferedJoystickSources(sdk: Int, enabled: Boolean): Int =
    if (sdk >= 30 && enabled) InputDevice.SOURCE_CLASS_JOYSTICK else InputDevice.SOURCE_CLASS_NONE

internal data class ViewerOptions(
    val unbufferedInput: Boolean = false,
    val displayMode: Int = 0,
    val frameRateVote: Int = 0,
    val hudOff: Boolean = false,
    val touchPadHidden: Boolean = false,
    val fixedSurface: Boolean = false,
    val surfaceOpaque: Boolean = false,
) {
    fun save() = Bundle().apply {
        putBoolean("unbuffered_input", unbufferedInput)
        putInt("display_mode", displayMode)
        putInt("frame_rate_vote", frameRateVote)
        putBoolean("hud_off", hudOff)
        putBoolean("touch_pad_hidden", touchPadHidden)
        putBoolean("fixed_surface", fixedSurface)
        putBoolean("surface_opaque", surfaceOpaque)
    }

    companion object {
        fun sample(flag: (String, Boolean) -> Boolean, int: (String, Int, IntRange) -> Int) = ViewerOptions(
            unbufferedInput = flag("unbuffered_input", false),
            displayMode = int("display_mode", 0, -1..Int.MAX_VALUE),
            frameRateVote = int("frame_rate_vote", 0, 0..120),
            hudOff = flag("hud_off", false),
            touchPadHidden = flag("touch_pad_hidden", false),
            fixedSurface = flag("fixed_surface", false),
            surfaceOpaque = flag("surface_opaque", false),
        )

        fun restore(bundle: Bundle) = ViewerOptions(
            unbufferedInput = bundle.getBoolean("unbuffered_input"),
            displayMode = bundle.getInt("display_mode"),
            frameRateVote = bundle.getInt("frame_rate_vote"),
            hudOff = bundle.getBoolean("hud_off"),
            touchPadHidden = bundle.getBoolean("touch_pad_hidden"),
            fixedSurface = bundle.getBoolean("fixed_surface"),
            surfaceOpaque = bundle.getBoolean("surface_opaque"),
        )
    }
}
