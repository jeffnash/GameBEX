package com.gamebex.client.stream.shell

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamError
import com.gamebex.client.stream.StreamJson
import com.gamebex.client.stream.StreamProbe
import com.gamebex.client.stream.StreamStarted
import com.gamebex.client.stream.StreamStatus
import kotlin.system.exitProcess

/**
 * Shizuku UserService for Stream: its own process
 * ("com.gamebex.client:stream"), the shell uid. It is a Shizuku daemon so the
 * RetroArch audio bridge can outlive GameBEX; it exits on its own about a second
 * after it has no session and no retained bridge (after a probe-only bind, a
 * failed start, a stop, or a watchdog stop). At most one [StreamHostSession] runs at a time.
 */
@Keep
class StreamShellService() : IStreamShell.Stub() {
    private var context: Context? = null

    /** Shizuku v13 prefers a (Context) constructor when present. */
    constructor(context: Context) : this() {
        this.context = context
    }

    private val lock = Any()
    private var session: StreamHostSession? = null
    private val components: ShellComponents by lazy {
        ShellWiring.components(context).also { parts ->
            (parts as? RealShellComponents)?.onAudioIdle = { scheduleIdleExit() }
        }
    }

    override fun destroy() {
        stopSession()
        (components as? RealShellComponents)?.closeAudioBridge()
        exitProcess(0)
    }

    override fun probe(): String {
        val probe = try {
            components.probe()
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed", t)
            StreamProbe(shellUid = Process.myUid(), sdk = Build.VERSION.SDK_INT)
        }
        if (synchronized(lock) { session } == null) scheduleIdleExit()
        return StreamJson.json.encodeToString(StreamProbe.serializer(), probe)
    }

    override fun start(configJson: String?): String {
        // This runs inside the app's binder call. Permission checks made while starting capture (the audio
        // policy's MODIFY_AUDIO_ROUTING check uses the calling uid) must see this process's shell uid,
        // not the app's, or audio capture is refused on a real Shizuku start.
        val identity = Binder.clearCallingIdentity()
        val result = try {
            synchronized(lock) {
                session?.stop()
                session = null
                val config = runCatching { StreamJson.json.decodeFromString(StreamConfig.serializer(), configJson.orEmpty()) }.getOrNull()
                if (config == null) {
                    StreamStarted(ok = false, error = StreamError.BAD_CONFIG)
                } else {
                    val next = StreamHostSession(config, components)
                    val started = next.start()
                    if (started.ok) {
                        next.onStopped = { scheduleIdleExit() }
                        session = next
                    }
                    started
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        if (!result.ok) {
            Log.w(TAG, "stream start failed: ${result.error} ${result.detail}")
            scheduleIdleExit()
        }
        return StreamJson.json.encodeToString(StreamStarted.serializer(), result)
    }

    override fun status(): String {
        val status = synchronized(lock) { session?.status() } ?: StreamStatus(running = false)
        return StreamJson.json.encodeToString(StreamStatus.serializer(), status)
    }

    override fun stop() {
        stopSession()
    }

    override fun uid(): Int = Process.myUid()

    override fun setCaptureListener(listener: IStreamCapture?) {
        val target = listener?.let { l ->
            CaptureTarget { surface, width, height ->
                runCatching { l.onSurface(surface, width, height) }.onFailure { Log.w(TAG, "capture listener gone", it) }
            }
        }
        synchronized(lock) { session }?.setCaptureListener(target)
    }

    override fun useMirror() {
        synchronized(lock) { session }?.useMirror()
    }

    override fun gameLaunched() {
        synchronized(lock) { session }?.gameLaunched()
    }

    private fun stopSession() {
        val current = synchronized(lock) { session.also { session = null } }
        current?.stop()
        scheduleIdleExit()
    }

    private fun scheduleIdleExit() {
        Thread({
            Thread.sleep(1_000L)
            synchronized(lock) {
                if (session?.stopped != false && (components as? RealShellComponents)?.retainedAudio != true) {
                    Log.i(TAG, "shell idle: no session or audio bridge")
                    exitProcess(0)
                }
            }
        }, "gbx-shell-idle").apply { isDaemon = true; start() }
    }

    private companion object {
        const val TAG = "GameBEXStream"
    }
}
