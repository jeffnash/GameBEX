package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamError
import com.gamebex.client.stream.StreamStarted
import com.gamebex.client.stream.StreamStatus
import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.proto.PadState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One stream in the shell process: player 2's pad, the
 * media and input listeners, display capture and game audio, started in that
 * order and stopped in reverse. Every exit path funnels into [stop].
 */
class StreamHostSession(
    private val config: StreamConfig,
    private val components: ShellComponents,
    private val clock: () -> Long = System::nanoTime,
    /** False for StreamShellMain, which has no app polling status. */
    private val appWatchdog: Boolean = true,
    private val watchdogTickMs: Long = 1_000L,
) {
    private val stopping = AtomicBoolean(false)
    private val lock = Any()
    private var pad: VirtualPad? = null
    private var media: MediaChannel? = null
    private var input: InputServer? = null
    private var video: VideoSource? = null
    private var audio: AudioSource? = null
    private var watchdog: Thread? = null
    private val capture = CaptureRelay()
    @Volatile private var startedNs = 0L
    @Volatile private var lastPollNs = 0L
    @Volatile private var stopError: StreamError? = null

    /** Called once, from whichever thread stops the session. */
    @Volatile var onStopped: (() -> Unit)? = null

    val stopped: Boolean get() = stopping.get()

    fun start(): StreamStarted = synchronized(lock) {
        if (!StreamToken.isValid(config.token)) return fail(StreamError.BAD_CONFIG, "bad token")
        if (!validGuest(config.guestAddress)) return fail(StreamError.BAD_CONFIG, "bad guest address")
        val level = StreamCaps.LEVELS.getOrNull(config.level) ?: return fail(StreamError.BAD_CONFIG, "bad level")
        val openedPad = components.openPad() ?: return fail(StreamError.UHID, "no pad backend")
        pad = openedPad
        try {
            val channel = components.mediaChannel(config)
            media = channel
            channel.open(config.mediaPort)
            val receiver = InputServer(openedPad, StreamToken.key(config.token), config.guestAddress, clock)
            input = receiver
            receiver.open(config.inputPort)
            val source = components.videoSource()
            video = source
            if (config.appCapture) source.captureInto(capture)
            source.start(level, level.maxBitrate, channel)
            // Audio rides UDP to the guest's input address when it has one (no waiting behind video on TCP),
            // else the media connection.
            val sink = AudioSink { chunk ->
                if (!receiver.sendAudio(chunk)) channel.onPcm(chunk)
            }
            val candidate = if (config.audio) components.audioSource(config) else null
            val sound = candidate?.takeIf {
                if (it.start(sink)) true else {
                    it.stop()
                    false
                }
            }
            audio = sound
            channel.start(source, level, level.maxBitrate, sound != null)
            receiver.start()
            startedNs = clock()
            lastPollNs = startedNs
            startWatchdog()
            StreamStarted(
                ok = true,
                mediaPort = channel.port,
                inputPort = receiver.port,
                width = level.width,
                height = level.height,
                audio = sound != null,
                rsound = sound?.rsoundReady == true,
                padBackend = openedPad.backend,
            )
        } catch (f: StreamFailure) {
            stopInternal(f.error)
            StreamStarted(ok = false, error = f.error, detail = f.message)
        } catch (t: Throwable) {
            stopInternal(StreamError.DISPLAY_CAPTURE)
            StreamStarted(ok = false, error = StreamError.DISPLAY_CAPTURE, detail = t.toString())
        }
    }

    /** Also proves the app is alive. */
    fun status(): StreamStatus {
        lastPollNs = clock()
        if (stopping.get()) return StreamStatus(running = false, error = stopError)
        val m = media?.stats()
        val i = input
        return StreamStatus(
            running = true,
            guestConnected = m?.guestConnected == true,
            fps = m?.fps ?: 0,
            bitrate = m?.bitrate ?: 0,
            level = m?.level ?: 0,
            framesSent = m?.framesSent ?: 0,
            framesDropped = m?.framesDropped ?: 0,
            inputPackets = i?.packets?.get() ?: 0,
            inputRejected = i?.rejected?.get() ?: 0,
            audio = audio != null,
            audioMode = audio?.audioMode ?: "none",
            audioFallback = audio?.fallbackReason,
            audioFailureKind = audio?.audioFailureKind,
            guestAudioDrops = audio?.guestAudioDrops ?: 0,
            audioTimestampOrigin = if (audio?.audioMode == "rsound") "bridge-arrival" else "capture",
            guestVideoP95Us = m?.guestVideoP95Us ?: 0,
        )
    }

    fun gameLaunched() {
        if (!stopping.get()) audio?.gameLaunched()
    }

    fun stop() {
        stopInternal(null)
    }

    /** App capture: the app's listener for encoder surfaces (it registers after [start] returns). */
    fun setCaptureListener(listener: CaptureTarget?) {
        capture.setListener(listener)
    }

    /** App capture was declined or failed on the host: mirror the whole display instead. */
    fun useMirror() {
        if (!stopping.get()) video?.useMirror()
    }

    private fun stopInternal(error: StreamError?) {
        if (!stopping.compareAndSet(false, true)) return
        stopError = error
        runCatching { input?.close() }
        runCatching { audio?.stop() }
        runCatching { video?.stop() }
        runCatching { media?.close() }
        runCatching { pad?.write(PadState.NEUTRAL) }
        runCatching { pad?.close() }
        watchdog?.interrupt()
        runCatching { onStopped?.invoke() }
    }

    private fun startWatchdog() {
        watchdog = Thread({
            try {
                while (!stopping.get()) {
                    Thread.sleep(watchdogTickMs)
                    val now = clock()
                    if (appWatchdog && now - lastPollNs > StreamCaps.APP_SILENT_STOP_MS * 1_000_000L) {
                        stopInternal(null)
                        return@Thread
                    }
                    val m = media?.stats() ?: continue
                    if (m.failure != null) {
                        stopInternal(StreamError.ENCODER)
                        return@Thread
                    }
                    val since = maxOf(startedNs, m.guestChangedNs)
                    if (!m.guestConnected && now - since > StreamCaps.GUEST_ABSENT_STOP_MS * 1_000_000L) {
                        stopInternal(null)
                        return@Thread
                    }
                }
            } catch (_: InterruptedException) {
                // stop() interrupts the sleep.
            }
        }, "gbx-stream-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun fail(error: StreamError, detail: String): StreamStarted {
        stopInternal(error)
        return StreamStarted(ok = false, error = error, detail = detail)
    }

    companion object {
        /** A private LAN IPv4 or loopback: the only addresses a guest can have. */
        fun validGuest(address: String): Boolean {
            val parts = address.split('.')
            if (parts.size != 4) return false
            val o = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.length <= 3 && it in 0..255 } ?: return false }
            return o[0] == 127 || o[0] == 10 || (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
        }
    }
}
