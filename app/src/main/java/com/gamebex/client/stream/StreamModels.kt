package com.gamebex.client.stream

import com.gamebex.client.stream.p2.P2SlotMode
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Why a stream could not start or stopped. */
@Serializable
enum class StreamError { SHELL_UNAVAILABLE, OLD_ANDROID, DISPLAY_CAPTURE, ENCODER, UHID, NETWORK, BAD_CONFIG, SHARING_CANCELLED }

/** What the host app asks the shell process to run (IStreamShell.start, as JSON). */
@Serializable
data class StreamConfig(
    /** 32 lower-case hex characters ([StreamToken]). */
    val token: String,
    /** The joined guest's IPv4; media and input are accepted only from it. */
    val guestAddress: String,
    val mediaPort: Int = StreamCaps.MEDIA_PORT,
    val inputPort: Int = StreamCaps.INPUT_PORT,
    /** Index into [StreamCaps.LEVELS] to start at. */
    val level: Int = 1,
    val audio: Boolean = true,
    val appCapture: Boolean = true,
    val rsound: Boolean = false,
    /** Host Stream flags: resolved in-app values; the shell prefers the adb prop on debug builds. */
    val queueDeadlineMs: Int = 0,
    val latencyCc: Boolean = false,
    val inputCs6: Boolean = false,
    val encoderFps: Int = 0,
    val keyframesOnRequest: Boolean = false,
    val crop: Boolean = false,
    val cropAspect: String = "4:3",
    /**
     * Overlay viewport in display px ("vx,vy,vw,vh,dw,dh", the same
     * value the host stamps in its overlay sends): the game centers in the
     * emulator window, not the display. Empty centers on the display.
     */
    val cropViewport: String = "",
    /**
     * P2 slot workaround ([P2SlotMode.wire]): "off", "placeholder" or
     * "gate-realpad". Resolved in-app; unknown values stay off.
     */
    val p2SlotMode: String = P2SlotMode.OFF.wire,
)

/** Result of IStreamShell.start. Ports are the ones really bound (the preferred ones may be taken). */
@Serializable
data class StreamStarted(
    val ok: Boolean,
    val mediaPort: Int = 0,
    val inputPort: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val audio: Boolean = false,
    /** "uhid" or "hid-tool". */
    val padBackend: String? = null,
    val error: StreamError? = null,
    /** Shell-side detail for logs; never shown to people. */
    val detail: String? = null,
    val rsound: Boolean = false,
)

/** IStreamShell.status, polled by the host app every [StreamCaps.STATUS_POLL_MS]. */
@Serializable
data class StreamStatus(
    val running: Boolean,
    val guestConnected: Boolean = false,
    val fps: Int = 0,
    val bitrate: Int = 0,
    val level: Int = 0,
    val framesSent: Long = 0,
    val framesDropped: Long = 0,
    val inputPackets: Long = 0,
    val inputRejected: Long = 0,
    val inputWriteFailures: Long = 0,
    val videoConfigDiscarded: Long = 0,
    val videoRetiredDiscarded: Long = 0,
    val tcpAudioEvictedFrames: Long = 0,
    val audioUdpDropped: Long = 0,
    val audio: Boolean = false,
    val guestVideoP95Us: Int = 0,
    val audioMode: String = "none",
    val audioFallback: String? = null,
    val audioFailureKind: String? = null,
    val guestAudioDrops: Long = 0,
    val audioTimestampOrigin: String = "capture",
    val error: StreamError? = null,
)

/** IStreamShell.probe: what this phone's shell can do, checked before a session. */
@Serializable
data class StreamProbe(
    val shellUid: Int = -1,
    val sdk: Int = 0,
    val uhid: Boolean = false,
    val hidTool: Boolean = false,
    val displayApi: Boolean = false,
    val audioApi: Boolean = false,
    val avcEncoder: Boolean = false,
) {
    val canHost: Boolean get() = sdk >= StreamCaps.MIN_HOST_SDK && (uhid || hidTool) && displayApi && avcEncoder
}

/** Where a guest connects: the dialed host address plus LinkMsg.StreamReady. */
@Serializable
data class StreamEndpoint(
    val host: String,
    val mediaPort: Int,
    val inputPort: Int,
    val token: String,
    val width: Int,
    val height: Int,
    val audio: Boolean,
    val hostName: String,
    val audioTimestampOrigin: String = "capture",
    /** PlatformRegistry folder of the hosted game; "" = unknown (generic pad). */
    val system: String = "",
    /** Actual host emulator backend; "" = unknown (generic pad). */
    val backend: String = "",
)

object StreamJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

/** Per-session secret shared over the link; proves the guest on media and input. */
object StreamToken {
    private val HEX = Regex("^[0-9a-f]{32}$")

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun attachId(random: SecureRandom = SecureRandom()): Long {
        var id: Long
        do { id = random.nextLong() } while (id == 0L)
        return id
    }

    fun isValid(token: String): Boolean = HEX.matches(token)

    fun bytes(token: String): ByteArray {
        require(isValid(token)) { "bad stream token" }
        return ByteArray(16) { i -> token.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** First 8 token bytes as a big-endian long: the key every input packet carries. */
    fun key(token: String): Long {
        val b = bytes(token)
        var k = 0L
        for (i in 0 until 8) k = (k shl 8) or (b[i].toLong() and 0xFF)
        return k
    }
}
