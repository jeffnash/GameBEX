package com.gamebex.client.launch

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Client side of [ShellFilesService]: Shizuku state, lazy bind (with a
 * timeout) and idle unbind. Blocking calls — never on the main thread.
 * Every Shizuku call is guarded: its statics throw when no binder arrived.
 */
object ShizukuFiles {
    private const val TAG = "GameBEXShizuku"
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val IDLE_UNBIND_MS = 30_000L
    /** Bump when IShellFiles/ShellFilesService change so Shizuku restarts it. */
    private const val SERVICE_VERSION = 3

    enum class State { READY, NOT_RUNNING, NOT_GRANTED, OUTDATED, UNAVAILABLE }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var remote: IShellFiles? = null
    private var pending: CountDownLatch? = null
    private var args: Shizuku.UserServiceArgs? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) remote = IShellFiles.Stub.asInterface(binder)
            synchronized(lock) { pending?.countDown(); pending = null }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    private val idleUnbind = Runnable { unbind() }

    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Cheap, no binding. */
    fun state(ctx: Context): State = runCatching {
        when {
            !Shizuku.pingBinder() -> if (installed(ctx)) State.NOT_RUNNING else State.UNAVAILABLE
            Shizuku.isPreV11() -> State.OUTDATED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
            else -> State.NOT_GRANTED
        }
    }.getOrDefault(State.UNAVAILABLE)

    /** Resolves Shizuku for one setup pass; binds only when READY. */
    fun privileged(ctx: Context): Privileged = when (state(ctx)) {
        State.READY -> bind(ctx)?.let { Privileged.Ready(Remote(it)) } ?: Privileged.NoResponse
        State.NOT_RUNNING -> Privileged.NotRunning
        State.NOT_GRANTED -> Privileged.NotGranted
        State.OUTDATED -> Privileged.Outdated
        State.UNAVAILABLE -> Privileged.Unavailable
    }

    /**
     * Null when Shizuku isn't already running and allowed (never prompts).
     * Binds once; each call is policy-checked here and again in the service.
     * Blocking, off main.
     */
    fun forceStopper(ctx: Context): ((String) -> Boolean)? {
        if (state(ctx) != State.READY) return null
        val svc = bind(ctx) ?: return null
        val own = ctx.packageName
        return { pkg ->
            ForceStopPolicy.allowed(pkg, own) != null && runCatching {
                touch()
                svc.forceStop(pkg)
            }.onFailure { Log.w(TAG, "forceStop $pkg failed", it) }.getOrDefault(false)
        }
    }

    private fun installed(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true }.getOrDefault(false)

    private fun bind(ctx: Context): IShellFiles? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "ShizukuFiles.bind on main thread" }
        remote?.let { if (it.asBinder().pingBinder()) return it.also { touch() } }
        remote = null
        val latch = synchronized(lock) {
            pending ?: CountDownLatch(1).also { l ->
                pending = l
                val a = args ?: userServiceArgs(ctx).also { args = it }
                main.post {
                    runCatching { Shizuku.bindUserService(a, conn) }.onFailure {
                        Log.w(TAG, "bindUserService failed", it)
                        synchronized(lock) { l.countDown(); if (pending === l) pending = null }
                    }
                }
            }
        }
        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "UserService bind timed out")
            synchronized(lock) { if (pending === latch) pending = null }
        }
        return remote?.also { touch() }
    }

    private fun userServiceArgs(ctx: Context): Shizuku.UserServiceArgs {
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellFilesService::class.java.name))
            .daemon(false)
            .processNameSuffix("shellfiles")
            .debuggable(debuggable)
            .version(SERVICE_VERSION)
    }

    /** (Re)arms the idle unbind. */
    private fun touch() {
        main.removeCallbacks(idleUnbind)
        main.postDelayed(idleUnbind, IDLE_UNBIND_MS)
    }

    private fun unbind() {
        val a = args ?: return
        remote = null
        runCatching { Shizuku.unbindUserService(a, conn, true) }
            .onFailure { Log.w(TAG, "unbindUserService failed", it) }
    }

    /** [EmuFiles] over the binder; checks the policy locally first. */
    private class Remote(private val svc: IShellFiles) : EmuFiles {
        private inline fun <T> call(path: String, vararg more: String, block: () -> T): T {
            if (ShellPathPolicy.allowed(path) == null || more.any { ShellPathPolicy.allowed(it) == null }) {
                throw IOException("path not allowed for Shizuku: $path")
            }
            touch()
            return try {
                block()
            } catch (e: Exception) {
                // RemoteException, or Security/IllegalState/IllegalArgument from the service.
                throw IOException("Shizuku file op failed: ${e.message}", e)
            }
        }

        override fun isDirectory(path: String) = call(path) { svc.isDirectory(path) }
        override fun isFile(path: String) = call(path) { svc.isFile(path) }
        override fun exists(path: String) = call(path) { svc.exists(path) }
        override fun readText(path: String): String? = call(path) { svc.readText(path) }
        override fun writeTextAtomic(path: String, text: String) = call(path) { svc.writeTextAtomic(path, text) }
        override fun mkdirs(path: String) = call(path) { svc.mkdirs(path) }
        override fun copy(path: String, dest: String) = call(path, dest) { svc.copy(path, dest) }
        override fun lastModified(path: String): Long = call(path) { svc.lastModified(path) }
        override fun list(path: String): List<String>? = call(path) { svc.listNames(path)?.toList() }
    }

    enum class Ask { GRANTED, REQUESTED, NOT_RUNNING, OUTDATED, DENIED_FOR_GOOD, UNAVAILABLE }

    /**
     * Shows Shizuku's permission prompt when possible. Result arrives via a
     * [Shizuku.OnRequestPermissionResultListener] with [requestCode].
     */
    fun requestPermission(ctx: Context, requestCode: Int): Ask = runCatching {
        when {
            !Shizuku.pingBinder() -> if (installed(ctx)) Ask.NOT_RUNNING else Ask.UNAVAILABLE
            Shizuku.isPreV11() -> Ask.OUTDATED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Ask.GRANTED
            // Denied with "don't ask again": Shizuku won't show the prompt.
            Shizuku.shouldShowRequestPermissionRationale() -> Ask.DENIED_FOR_GOOD
            else -> {
                Shizuku.requestPermission(requestCode)
                Ask.REQUESTED
            }
        }
    }.getOrElse {
        Log.w(TAG, "requestPermission failed", it)
        Ask.NOT_RUNNING
    }
}
