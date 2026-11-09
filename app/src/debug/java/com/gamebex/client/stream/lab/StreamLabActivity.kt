package com.gamebex.client.stream.lab

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamEndpoint
import com.gamebex.client.stream.guest.StreamGuestRuntime
import com.gamebex.client.ui.GameBexTheme
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * Debug builds only: the emulator gate's two ends.
 *
 * - `--es mode pattern`: host content. A bar sweeps across the screen every
 *   second (so the encoder always has motion), a 440 Hz tone plays with
 *   USAGE_GAME (so playback capture has something to take), and key and
 *   stick events from "GameBEX Player 2" are printed while this is in front.
 * - `--es mode optical [--es guest <ipv4> --es token <32 hex> [--ei seconds 300]]`:
 *   host content for camera timing. A white bar crosses between two green
 *   edge lines once a second, placed by the vsync frame time; filming both
 *   phones in one shot turns the bar gap into host→guest delay. With a guest,
 *   it also streams to that phone through the real host path and flags.
 * - `--es mode watch --es token <32 hex> [--es host <ipv4>] [--ei media 55441] [--ei input 55442]`:
 *   opens the real viewer, then closes itself. On 127.0.0.1 (the default) it
 *   runs in loopback mode (muted, ignores the virtual pad). The viewer itself
 *   is not exported.
 * - `--es mode shizuku --es token <32 hex>`: the host app's real path. Binds the
 *   Stream UserService through Shizuku (StreamShellClient), probes, starts a
 *   stream for 127.0.0.1 at level 1, logs its status every 2 s for 60 s, then
 *   stops it. Every step is logged as a line starting `LAB ` under the GameBEXStream tag.
 */
class StreamLabActivity : ComponentActivity() {
    private var tone: AudioTrack? = null
    private val scope = kotlinx.coroutines.MainScope()
    private val lastPad = mutableStateOf("no input from ${StreamCaps.PAD_NAME} yet")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("mode") == "watch") {
            openViewer(intent)
            finish()
            return
        }
        if (intent.getStringExtra("mode") == "shizuku") {
            runShizukuCheck(intent.getStringExtra("token").orEmpty())
            return
        }
        if (intent.getStringExtra("mode") == "probe") {
            logFrontBufferSupport()
            finish()
            return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent.getStringExtra("mode") == "optical") {
            showOptical(intent)
            if (intent.getBooleanExtra("idle_decoder", false)) openIdleDecoder()
            return
        }
        tone = startTone()
        setContent {
            GameBexTheme {
                var frame by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) {
                    while (true) withFrameNanos { frame = it }
                }
                val phase = (frame / 1_000_000L % 1_000L) / 1_000f
                Box(Modifier.fillMaxSize().background(Color(0xFF101820))) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(80.dp)
                            .offset(x = (phase * 800).dp)
                            .background(Color(0xFFFFC857)),
                    )
                    Column(Modifier.padding(24.dp)) {
                        Text("Stream lab · test pattern · 440 Hz tone", color = Color.White, fontSize = 20.sp)
                        Text("t = ${frame / 1_000_000L} ms", color = Color.White, fontSize = 28.sp)
                        Text(lastPad.value, color = Color(0xFF7CFC00), fontSize = 20.sp)
                    }
                }
            }
        }
    }

    /**
     * `am start` hands a second launch of this component to the instance already in front,
     * so a `watch` sent while the pattern (or the Shizuku check) is showing arrives here.
     * The pattern stays underneath: Back from the viewer returns to it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.getStringExtra("mode")) {
            "watch" -> openViewer(intent)
            "shizuku" -> runShizukuCheck(intent.getStringExtra("token").orEmpty())
            "optical" -> showOptical(intent)
        }
    }

    private fun openViewer(from: Intent) {
        val host = from.getStringExtra("host") ?: "127.0.0.1"
        val endpoint = StreamEndpoint(
            host = host,
            mediaPort = from.getIntExtra("media", StreamCaps.MEDIA_PORT),
            inputPort = from.getIntExtra("input", StreamCaps.INPUT_PORT),
            token = from.getStringExtra("token").orEmpty(),
            width = StreamCaps.LEVELS[0].width,
            height = StreamCaps.LEVELS[0].height,
            audio = true,
            hostName = "lab",
        )
        startActivity(StreamGuestRuntime.intent(this, endpoint, loopback = host == "127.0.0.1"))
    }

    /** `--es mode probe`: logs which front-buffer usages this phone's allocator accepts (`LAB front_buffer ...`). */
    private fun logFrontBufferSupport() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            android.util.Log.i("GameBEXStream", "LAB front_buffer sdk=${android.os.Build.VERSION.SDK_INT} unsupported")
            return
        }
        val front = android.hardware.HardwareBuffer.USAGE_FRONT_BUFFER
        val gpu = android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        val sampled = android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        val overlay = android.hardware.HardwareBuffer.USAGE_COMPOSER_OVERLAY
        listOf(
            "front+gpu+overlay" to (front or gpu or overlay),
            "front+gpu+sampled+overlay" to (front or gpu or sampled or overlay),
            "front+gpu" to (front or gpu),
            "gpu+overlay" to (gpu or overlay),
        ).forEach { (name, usage) ->
            val ok = runCatching {
                android.hardware.HardwareBuffer.isSupported(1604, 720, android.hardware.HardwareBuffer.RGBA_8888, 1, usage)
            }.getOrElse { false }
            android.util.Log.i("GameBEXStream", "LAB front_buffer $name supported=$ok")
        }
    }

    private var idleDecoder: android.media.MediaCodec? = null
    private var idleReader: android.media.ImageReader? = null

    /**
     * `--ez idle_decoder true` with the optical pattern: a started hardware AVC decoder that renders
     * nowhere visible, to tell whether the display pipeline reacts to a live decoder or to a video layer.
     */
    private fun openIdleDecoder() {
        runCatching {
            val reader = android.media.ImageReader.newInstance(1280, 576, android.graphics.ImageFormat.PRIVATE, 2)
            val format = android.media.MediaFormat.createVideoFormat(android.media.MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 576)
            val codec = android.media.MediaCodec.createDecoderByType(android.media.MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, reader.surface, null, 0)
            codec.start()
            idleReader = reader
            idleDecoder = codec
            android.util.Log.i("GameBEXStream", "LAB idle decoder ${codec.name} started")
        }.onFailure { android.util.Log.w("GameBEXStream", "LAB idle decoder failed", it) }
    }

    private var opticalStream: kotlinx.coroutines.Job? = null

    private fun showOptical(from: Intent) {
        setContent {
            var frame by remember { mutableLongStateOf(0L) }
            LaunchedEffect(Unit) {
                while (true) withFrameNanos { frame = it }
            }
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.Black)
                val top = size.height * OPTICAL_BAND_TOP
                val band = Size(OPTICAL_LINE_PX, size.height * (1 - 2 * OPTICAL_BAND_TOP))
                val left = size.width * OPTICAL_MARGIN
                val right = size.width * (1 - OPTICAL_MARGIN)
                drawRect(OPTICAL_GREEN, Offset(left - OPTICAL_LINE_PX, top), band)
                drawRect(OPTICAL_GREEN, Offset(right, top), band)
                val phase = (frame % OPTICAL_SWEEP_NS) / OPTICAL_SWEEP_NS.toFloat()
                drawRect(
                    Color.White,
                    Offset(left + phase * (right - left - OPTICAL_BAR_PX), top),
                    band.copy(width = OPTICAL_BAR_PX, height = band.height * 2 / 3),
                )
                val slowPhase = (frame % (OPTICAL_SWEEP_NS * 8)) / (OPTICAL_SWEEP_NS * 8).toFloat()
                drawRect(
                    Color.White,
                    Offset(left + slowPhase * (right - left - OPTICAL_BAR_PX), top + band.height * 2 / 3),
                    band.copy(width = OPTICAL_BAR_PX, height = band.height / 3),
                )
            }
        }
        val guest = from.getStringExtra("guest") ?: return
        val token = from.getStringExtra("token").orEmpty()
        val seconds = from.getIntExtra("seconds", 300)
        val previous = opticalStream
        opticalStream = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // The previous run's stop must land before this run starts, not after.
            previous?.cancelAndJoin()
            fun say(line: String) = android.util.Log.i("GameBEXStream", "LAB $line")
            val host = com.gamebex.client.stream.host.StreamShellClient.forApp(this@StreamLabActivity)
            val config = com.gamebex.client.together.session.streamConfigForFlags(
                token, guest, rsound = false,
                hostFlag = com.gamebex.client.stream.StreamFlagPrefs(this@StreamLabActivity).lookup(),
                startLevelOverride = com.gamebex.client.stream.StreamDebug.prop("start_level")?.toIntOrNull(),
            )
            say("config ${config.copy(token = "…")}")
            val started = host.start(config)
            say("start ${com.gamebex.client.stream.StreamJson.json.encodeToString(com.gamebex.client.stream.StreamStarted.serializer(), started)}")
            if (!started.ok) return@launch
            val service = Intent(this@StreamLabActivity, com.gamebex.client.together.platform.TogetherService::class.java)
            try {
                // The real host path: the Together foreground service, Android's share prompt
                // (pick the app showing the pattern), then that single-app capture.
                com.gamebex.client.together.platform.TogetherService.clearStopRequest()
                startForegroundService(service.putExtra(com.gamebex.client.together.platform.TogetherService.EXTRA_TEXT, "Stream lab"))
                kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    while (!com.gamebex.client.together.platform.TogetherService.promoted) kotlinx.coroutines.delay(50)
                }
                host.attachCapture()
                say("capture attached")
                repeat(seconds * 1_000 / StreamCaps.STATUS_POLL_MS.toInt()) {
                    kotlinx.coroutines.delay(StreamCaps.STATUS_POLL_MS)
                    val status = host.status()
                    say("status ${status?.let { com.gamebex.client.stream.StreamJson.json.encodeToString(com.gamebex.client.stream.StreamStatus.serializer(), it) }}")
                }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { host.stop() }
                stopService(service)
                say("stopped")
            }
        }
    }

    /** RestrictedApi: the same lint false positive as MainActivity.dispatchKeyEvent. */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.device?.name == StreamCaps.PAD_NAME) {
            lastPad.value = "${StreamCaps.PAD_NAME}: ${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"}"
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.device?.name == StreamCaps.PAD_NAME) {
            lastPad.value = "${StreamCaps.PAD_NAME}: x=%.2f y=%.2f hat=%.0f,%.0f".format(
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            )
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Shows [lines] as they come and logs each one. */
    private fun runShizukuCheck(token: String) {
        val lines = androidx.compose.runtime.mutableStateListOf<String>()
        fun say(line: String) {
            android.util.Log.i("GameBEXStream", "LAB $line")
            runOnUiThread { lines += line }
        }
        setContent {
            GameBexTheme {
                Column(Modifier.fillMaxSize().background(Color(0xFF101820)).padding(24.dp)) {
                    lines.forEach { Text(it, color = Color.White, fontSize = 16.sp) }
                }
            }
        }
        if (com.gamebex.client.launch.ShizukuFiles.state(this) == com.gamebex.client.launch.ShizukuFiles.State.NOT_GRANTED) {
            runCatching { rikka.shizuku.Shizuku.requestPermission(REQUEST_SHIZUKU) }
            say("Allow GameBEX in the Shizuku dialog, then run this again.")
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val host = com.gamebex.client.stream.host.StreamShellClient.forApp(this@StreamLabActivity)
            val probe = host.probe()
            say("probe ${com.gamebex.client.stream.StreamJson.json.encodeToString(com.gamebex.client.stream.StreamProbe.serializer(), probe)}")
            if (!probe.canHost) {
                say("this phone can't host")
                return@launch
            }
            val rsound = intent.getBooleanExtra("rsound", false)
            val started = host.start(com.gamebex.client.stream.StreamConfig(token = token, guestAddress = "127.0.0.1", level = 1, rsound = rsound))
            say("start ${com.gamebex.client.stream.StreamJson.json.encodeToString(com.gamebex.client.stream.StreamStarted.serializer(), started)}")
            if (!started.ok) return@launch
            if (rsound) host.gameLaunched()
            repeat(30) {
                kotlinx.coroutines.delay(StreamCaps.STATUS_POLL_MS)
                val status = host.status()
                say("status ${status?.let { com.gamebex.client.stream.StreamJson.json.encodeToString(com.gamebex.client.stream.StreamStatus.serializer(), it) }}")
            }
            host.stop()
            say("stopped")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        idleDecoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        idleReader?.close()
        tone?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        tone = null
    }

    private companion object {
        const val REQUEST_SHIZUKU = 7314

        /** One sweep a second: delays up to half a second read unambiguously. */
        const val OPTICAL_SWEEP_NS = 1_000_000_000L
        const val OPTICAL_MARGIN = 0.08f
        const val OPTICAL_BAND_TOP = 0.2f
        const val OPTICAL_LINE_PX = 10f
        const val OPTICAL_BAR_PX = 36f
        val OPTICAL_GREEN = Color(0xFF00FF00)
    }

    /** One second of 440 Hz stereo looping forever, so it keeps playing behind the viewer. */
    private fun startTone(): AudioTrack? = runCatching {
        val rate = StreamCaps.AUDIO_RATE
        val pcm = ShortArray(rate * 2)
        for (i in 0 until rate) {
            val v = (sin(2 * PI * 440.0 * i / rate) * 6_000).toInt().toShort()
            pcm[2 * i] = v
            pcm[2 * i + 1] = v
        }
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also {
                it.write(pcm, 0, pcm.size)
                it.setLoopPoints(0, rate, -1)
                it.play()
            }
    }.getOrNull()
}
