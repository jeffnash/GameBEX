package com.gamebex.client.stream.shell

import android.view.Surface
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamError
import com.gamebex.client.stream.StreamProbe
import com.gamebex.client.stream.VideoLevel
import com.gamebex.client.stream.proto.PadState

/**
 * Seams inside the shell-uid process. StreamHostSession only sees these
 * interfaces, so JVM tests drive it with fakes.
 */

/** A start-up failure with the [error] the host app turns into copy. */
class StreamFailure(val error: StreamError, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Player 2's virtual controller. */
interface VirtualPad : AutoCloseable {
    /** "uhid" or "hid-tool". */
    val backend: String

    /** Sends one input report; false when the device is gone. */
    fun write(state: PadState): Boolean

    /** Removes the device. Idempotent. */
    override fun close()
}

/** One encoded H.264 access unit. Times are the host's System.nanoTime(). */
class EncodedFrame(
    val seq: Int,
    val key: Boolean,
    val captureNs: Long,
    val encodedNs: Long,
    val data: ByteArray,
    val codecEpoch: Long = 0L,
)

class EncodedFormat(
    val level: VideoLevel,
    val bitrate: Int,
    val csd: ByteArray,
    val codecEpoch: Long = 0L,
)

interface VideoSink {
    /** Annex-B SPS+PPS; sent before the first frame and after each restart. */
    fun onConfig(csd: ByteArray)
    fun onFormat(format: EncodedFormat) = onConfig(format.csd)
    fun onFrame(frame: EncodedFrame)
    /** The source stopped for good (capture or encoder failure). */
    fun onFailure(reason: String)
}

/** What the media channel may ask of the video source when the network struggles. */
interface VideoControl {
    fun requestKeyframe()
    fun setBitrate(bps: Int)
    /** Restarts the encoder at [level]; the next frame is a keyframe preceded by new config. */
    fun changeLevel(level: VideoLevel, bitrate: Int)
}

/** App capture: where the encoder's input surface goes instead of a display mirror. */
fun interface CaptureTarget {
    /** The encoder's current input surface at [width]x[height], or null when it goes away. */
    fun onSurface(surface: Surface?, width: Int, height: Int)
}

/** Display capture plus H.264 encoder. */
interface VideoSource : VideoControl {
    val level: VideoLevel

    /**
     * Starts capture and encoding at [level]; frames go to [sink] from the source's
     * own thread. Throws StreamFailure(DISPLAY_CAPTURE or ENCODER).
     */
    fun start(level: VideoLevel, bitrate: Int, sink: VideoSink)

    fun stop()

    fun captureInto(target: CaptureTarget) {}

}

/**
 * Keeps the latest encoder surface for app capture until the app's listener arrives (the app
 * registers after start() returns), then forwards every later one.
 */
class CaptureRelay : CaptureTarget {
    private var received = false
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var listener: CaptureTarget? = null

    @Synchronized
    override fun onSurface(surface: Surface?, width: Int, height: Int) {
        received = true
        this.surface = surface
        this.width = width
        this.height = height
        listener?.onSurface(surface, width, height)
    }

    @Synchronized
    fun setListener(next: CaptureTarget?) {
        listener = next
        if (next != null && received) next.onSurface(surface, width, height)
    }
}

/** 16-bit PCM captured from the host's game audio. */
class PcmChunk(val seq: Int, val captureNs: Long, val frames: Int, val data: ByteArray)

enum class AudioSendResult { SENT, NO_ROUTE, DROPPED }

fun interface AudioSink {
    fun onPcm(chunk: PcmChunk)
    fun sendPcm(chunk: PcmChunk): AudioSendResult = try {
        onPcm(chunk)
        AudioSendResult.SENT
    } catch (_: java.io.IOException) {
        AudioSendResult.DROPPED
    }
}

interface AudioSource {
    /** False when this phone won't share its game audio; the stream then runs without sound. */
    fun start(sink: AudioSink): Boolean
    val rsoundReady: Boolean get() = false
    val audioMode: String get() = "loopback"
    val fallbackReason: String? get() = null
    val audioFailureKind: String? get() = null
    val guestAudioDrops: Long get() = 0
    fun gameLaunched() {}
    fun flushGuestAudio() {}
    fun stop()
}

/** Health of the media connection, for IStreamShell.status. */
data class MediaStats(
    val guestConnected: Boolean,
    val fps: Int,
    val bitrate: Int,
    val level: Int,
    val framesSent: Long,
    val framesDropped: Long,
    val guestVideoP95Us: Int,
    /** Monotonic time the guest last connected or disconnected. */
    val guestChangedNs: Long,
    /** Set when the video source gave up; the session then stops with StreamError.ENCODER. */
    val failure: String? = null,
    val videoConfigDiscarded: Long = 0,
    val videoRetiredDiscarded: Long = 0,
    val tcpAudioEvictedFrames: Long = 0,
)

/** The guest-facing media connection. */
interface MediaChannel : VideoSink, AudioSink {
    /** The bound TCP port; valid after [open]. */
    val port: Int

    /** Binds the listener. Throws StreamFailure(NETWORK). */
    fun open(preferredPort: Int)

    /** Starts accepting the guest; [control] receives keyframe, bitrate and level requests. */
    fun start(control: VideoControl, level: VideoLevel, bitrate: Int, audio: Boolean)

    var onGuestAttached: (attachId: Long) -> Unit
        get() = {}
        set(value) {}
    var onGuestDetached: (attachId: Long) -> Unit
        get() = {}
        set(value) {}

    fun stats(): MediaStats

    /** Sends BYE, closes the guest and the listener. Idempotent. */
    fun close()
}

/** Builds the real (or fake) parts of one shell session. */
interface ShellComponents {
    fun probe(): StreamProbe

    /** Null when neither pad backend works. */
    fun openPad(): VirtualPad?

    fun videoSource(): VideoSource
    fun videoSource(config: StreamConfig): VideoSource = videoSource()

    /** Null when this Android version can't capture playback. */
    fun audioSource(): AudioSource?
    fun audioSource(config: StreamConfig): AudioSource? = audioSource()

    fun mediaChannel(config: StreamConfig): MediaChannel
}

/** Components for a build or process where nothing can be captured. */
object NoShellComponents : ShellComponents {
    override fun probe(): StreamProbe = StreamProbe()
    override fun openPad(): VirtualPad? = null
    override fun videoSource(): VideoSource = throw StreamFailure(StreamError.DISPLAY_CAPTURE, "no video source in this process")
    override fun audioSource(): AudioSource? = null
    override fun mediaChannel(config: StreamConfig): MediaChannel = throw StreamFailure(StreamError.NETWORK, "no media channel in this process")
}
