package com.gamebex.client.stream.host

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.gamebex.client.launch.ShizukuFiles
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamError
import com.gamebex.client.stream.StreamHost
import com.gamebex.client.stream.StreamJson
import com.gamebex.client.stream.StreamProbe
import com.gamebex.client.stream.StreamStarted
import com.gamebex.client.stream.StreamStatus
import com.gamebex.client.stream.shell.IStreamShell
import com.gamebex.client.stream.shell.StreamShellService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * [StreamHost] over Shizuku: binds [StreamShellService] in its own
 * shell-uid process, keeps it bound while a stream runs, and unbinds with
 * remove=true on [stop], which makes Shizuku call destroy() so the process
 * exits and player 2's pad disappears.
 */
class StreamShellClient private constructor(ctx: Context) : StreamHost {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var remote: IStreamShell? = null
    private var pending: CountDownLatch? = null
    @Volatile private var streaming = false
    @Volatile private var unboundAtMs = 0L

    private val args: Shizuku.UserServiceArgs by lazy {
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        Shizuku.UserServiceArgs(ComponentName(app.packageName, StreamShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("stream")
            .tag("gamebex-stream")
            .debuggable(debuggable)
            .version(StreamCaps.SHELL_SERVICE_VERSION)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) remote = IStreamShell.Stub.asInterface(binder)
            synchronized(lock) {
                pending?.countDown()
                pending = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    override suspend fun probe(): StreamProbe = withContext(Dispatchers.IO) {
        if (ShizukuFiles.state(app) != ShizukuFiles.State.READY) return@withContext StreamProbe()
        val svc = bind() ?: return@withContext StreamProbe()
        val probe = runCatching { StreamJson.json.decodeFromString(StreamProbe.serializer(), svc.probe()) }
            .onFailure { Log.w(TAG, "probe failed", it) }
            .getOrDefault(StreamProbe())
        if (!streaming) unbind()
        probe
    }

    override suspend fun start(config: StreamConfig): StreamStarted = withContext(Dispatchers.IO) {
        if (ShizukuFiles.state(app) != ShizukuFiles.State.READY) {
            return@withContext StreamStarted(ok = false, error = StreamError.SHELL_UNAVAILABLE)
        }
        val svc = bind() ?: return@withContext StreamStarted(ok = false, error = StreamError.SHELL_UNAVAILABLE)
        val json = StreamJson.json.encodeToString(StreamConfig.serializer(), config)
        val started = runCatching { StreamJson.json.decodeFromString(StreamStarted.serializer(), svc.start(json)) }
            .onFailure { Log.w(TAG, "start failed", it) }
            .getOrDefault(StreamStarted(ok = false, error = StreamError.SHELL_UNAVAILABLE))
        streaming = started.ok
        if (!started.ok) unbind()
        started
    }

    override suspend fun status(): StreamStatus? = withContext(Dispatchers.IO) {
        val svc = remote ?: return@withContext null
        runCatching { StreamJson.json.decodeFromString(StreamStatus.serializer(), svc.status()) }
            .onFailure { Log.w(TAG, "status failed", it) }
            .getOrNull()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        streaming = false
        remote?.let { svc -> runCatching { svc.stop() }.onFailure { Log.w(TAG, "stop failed", it) } }
        unbind()
    }

    private fun bind(): IStreamShell? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "StreamShellClient.bind on main thread" }
        remote?.let { if (it.asBinder().pingBinder()) return it }
        remote = null
        // Shizuku keys a service by its tag. Binding again while the process that unbind(remove) just
        // destroyed is still being cleaned up loses the new binder, so let it settle.
        val settle = unboundAtMs + REBIND_SETTLE_MS - SystemClock.elapsedRealtime()
        if (settle > 0) Thread.sleep(settle)
        val latch = synchronized(lock) {
            pending ?: CountDownLatch(1).also { l ->
                pending = l
                main.post {
                    runCatching { Shizuku.bindUserService(args, conn) }.onFailure {
                        Log.w(TAG, "bindUserService failed", it)
                        synchronized(lock) {
                            l.countDown()
                            if (pending === l) pending = null
                        }
                    }
                }
            }
        }
        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "stream service bind timed out")
            synchronized(lock) { if (pending === latch) pending = null }
        }
        return remote
    }

    private fun unbind() {
        remote = null
        unboundAtMs = SystemClock.elapsedRealtime()
        main.post {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
                .onFailure { Log.w(TAG, "unbindUserService failed", it) }
        }
    }

    companion object {
        private const val TAG = "GameBEXStream"
        /** The first bind starts a new app_process; give it longer than ShizukuFiles' 5 s. */
        private const val BIND_TIMEOUT_MS = 8_000L

        /** How long after unbind(remove) the same service tag may be bound again. */
        private const val REBIND_SETTLE_MS = 2_000L

        @Volatile private var instance: StreamShellClient? = null

        fun forApp(ctx: Context): StreamShellClient =
            instance ?: synchronized(this) { instance ?: StreamShellClient(ctx).also { instance = it } }
    }
}
