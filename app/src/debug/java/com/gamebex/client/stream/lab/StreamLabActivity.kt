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
import kotlinx.coroutines.launch

/**
 * Debug builds only: the emulator gate's two ends.
 *
 * - `--es mode pattern`: host content. A bar sweeps across the screen every
 *   second (so the encoder always has motion), a 440 Hz tone plays with
 *   USAGE_GAME (so playback capture has something to take), and key and
 *   stick events from "GameBEX Player 2" are printed while this is in front.
 * - `--es mode watch --es token <32 hex> [--ei media 55441] [--ei input 55442]`:
 *   opens the real viewer on 127.0.0.1 in loopback mode (muted, ignores the
 *   virtual pad), then closes itself. The viewer itself is not exported.
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
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        }
    }

    private fun openViewer(from: Intent) {
        val endpoint = StreamEndpoint(
            host = "127.0.0.1",
            mediaPort = from.getIntExtra("media", StreamCaps.MEDIA_PORT),
            inputPort = from.getIntExtra("input", StreamCaps.INPUT_PORT),
            token = from.getStringExtra("token").orEmpty(),
            width = StreamCaps.LEVELS[0].width,
            height = StreamCaps.LEVELS[0].height,
            audio = true,
            hostName = "lab",
        )
        startActivity(StreamGuestRuntime.intent(this, endpoint, loopback = true))
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
            val started = host.start(com.gamebex.client.stream.StreamConfig(token = token, guestAddress = "127.0.0.1", level = 1))
            say("start ${com.gamebex.client.stream.StreamJson.json.encodeToString(com.gamebex.client.stream.StreamStarted.serializer(), started)}")
            if (!started.ok) return@launch
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
        tone?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        tone = null
    }

    private companion object {
        const val REQUEST_SHIZUKU = 7314
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
