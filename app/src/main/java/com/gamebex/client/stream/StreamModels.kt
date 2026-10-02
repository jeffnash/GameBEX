package com.gamebex.client.stream

import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Why a stream could not start or stopped. */
@Serializable
enum class StreamError { SHELL_UNAVAILABLE, OLD_ANDROID, DISPLAY_CAPTURE, ENCODER, UHID, NETWORK, BAD_CONFIG }

/** What the host app asks the shell process to run (IStreamShell.start, as JSON). */
@Serializable
data class StreamConfig(
 /** 32 lower-case hex characters ([StreamToken]). */
    val token: String,
 /** The joined guest's IPv4; media and input are accepted only from it. */
    val guestAddress: String,
    val mediaPort: Int = StreamCaps.MEDIA_PORT,
    val inputPort: Int = StreamCaps.INPUT_PORT,
 /** Index into [StreamCaps.LEVELS] to start at: level 1 on these phones (early check V-2). */
    val level: Int = 1,
    val audio: Boolean = true,
 /**
 * Capture only the game's app (Android 14+ single-app MediaProjection, run by the app) instead of
 * mirroring the whole display: the shell hands its encoder surface to the app and waits.
 */
    val appCapture: Boolean = false,
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
    val audio: Boolean = false,
    val guestVideoP95Us: Int = 0,
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
