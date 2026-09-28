package com.gamebex.client.launch

import android.content.Context
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService: runs in a process Shizuku starts as the shell uid,
 * which can read/write /sdcard/Android/data/<pkg> on Android 11+. Only
 * paths passing [ShellPathPolicy] (lexically AND after canonicalization,
 * so a symlink can't escape) are touched; anything else throws
 * [SecurityException], which the binder carries back to the caller.
 */
@Keep
class ShellFilesService() : IShellFiles.Stub() {
    /** Shizuku v13 prefers a (Context) constructor when present. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun readText(path: String?): String? {
        val p = checked(path)
        val f = File(p)
        if (f.isFile && f.length() > MAX_BYTES) throw IllegalStateException("too large: $p")
        return io { DirectFiles.readText(p) }
    }

    override fun writeTextAtomic(path: String?, text: String?): Boolean {
        val p = checked(path)
        val body = text ?: throw IllegalArgumentException("null text")
        return io {
            val target = File(p)
            if (target.isFile) {
                // Overwrite in place: a rename-into-place would make the file
                // shell-owned; keeping the emulator's own inode keeps it the
                // owner of its config. Configs are small, so a torn write is
                // unlikely and the one-time .gamebex-backup covers it.
                target.writeText(body)
                true
            } else {
                // New file: temp beside p (same allowed dir), then rename.
                DirectFiles.writeTextAtomic(p, body)
            }
        }
    }

    override fun exists(path: String?): Boolean = DirectFiles.exists(checked(path))
    override fun mkdirs(path: String?): Boolean = io { DirectFiles.mkdirs(checked(path)) }
    override fun copy(path: String?, dest: String?): Boolean {
        val src = checked(path)
        val d = checked(dest)
        if (File(src).length() > MAX_BYTES) throw IllegalStateException("too large: $src")
        return io { DirectFiles.copy(src, d) }
    }
    override fun isFile(path: String?): Boolean = DirectFiles.isFile(checked(path))
    override fun isDirectory(path: String?): Boolean = DirectFiles.isDirectory(checked(path))
    override fun lastModified(path: String?): Long = File(checked(path)).lastModified()
    override fun listNames(path: String?): MutableList<String>? =
        io { File(checked(path)).list()?.toMutableList() }

    /**
     * Stops one allowlisted emulator ([ForceStopPolicy]); anything else is a
     * [SecurityException]. Argv, no shell: the package can't inject.
     */
    override fun forceStop(pkg: String?): Boolean {
        val p = ForceStopPolicy.allowed(pkg) ?: throw SecurityException("package not allowed")
        val proc = ProcessBuilder("am", "force-stop", p).redirectErrorStream(true).start()
        if (!proc.waitFor(STOP_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.destroy()
            return false
        }
        return proc.exitValue() == 0
    }

    private fun checked(path: String?): String {
        val p = ShellPathPolicy.allowed(path.orEmpty()) ?: throw SecurityException("path not allowed")
        val canonical = runCatching { File(p).canonicalPath }.getOrNull()
        if (canonical == null || ShellPathPolicy.allowed(canonical) == null) {
            throw SecurityException("path escapes allowed dir")
        }
        return p
    }

    /** IOException can't cross the binder; IllegalStateException can. */
    private inline fun <T> io(block: () -> T): T =
        try {
            block()
        } catch (e: java.io.IOException) {
            throw IllegalStateException(e.message ?: "io error")
        }

    private companion object {
        const val MAX_BYTES = 512L * 1024
        const val STOP_TIMEOUT_S = 2L
    }
}
