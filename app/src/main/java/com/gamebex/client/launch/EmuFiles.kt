package com.gamebex.client.launch

import com.gamebex.client.data.Emu
import java.io.File
import java.io.IOException

/**
 * The file ops the controller writers need, by absolute path. Two backends:
 * [DirectFiles] (our own uid, java.io) and the Shizuku shell-uid service
 * ([ShizukuFiles]) for other apps' Android/data on Android 11+.
 *
 * Contract: [readText] returns null when the path is not a regular file and
 * throws [IOException] when it is but can't be read; writes throw
 * [IOException] (or return false) on failure. Callers treat any
 * IOException/SecurityException as "this backend can't do it".
 */
interface EmuFiles {
    fun isDirectory(path: String): Boolean
    fun isFile(path: String): Boolean
    fun exists(path: String): Boolean
    fun readText(path: String): String?
    fun writeTextAtomic(path: String, text: String): Boolean
    fun mkdirs(path: String): Boolean
    /** Copy [path] to [dest]; false when [dest] already exists. */
    fun copy(path: String, dest: String): Boolean
    fun lastModified(path: String): Long = 0L
    /** Directory entry names, or null when unlistable (direct backend only). */
    fun list(path: String): List<String>? = null
}

/** Plain java.io under our own uid. Also the implementation the shell service runs. */
object DirectFiles : EmuFiles {
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun isFile(path: String) = File(path).isFile
    override fun exists(path: String) = File(path).exists()
    override fun list(path: String): List<String>? = File(path).list()?.toList()
    override fun lastModified(path: String) = File(path).lastModified()

    override fun readText(path: String): String? {
        val f = File(path)
        return if (f.isFile) f.readText() else null
    }

    override fun writeTextAtomic(path: String, text: String): Boolean {
        val file = File(path)
        val dir = file.parentFile ?: throw IOException("no parent: $path")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdirs failed: $dir")
        val tmp = File(dir, ".${file.name}.gamebex-tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // Some FUSE mounts refuse rename-over; fall back to in-place.
            try {
                file.writeText(text)
            } finally {
                tmp.delete()
            }
        }
        return true
    }

    override fun mkdirs(path: String): Boolean {
        val f = File(path)
        return f.isDirectory || f.mkdirs()
    }

    override fun copy(path: String, dest: String): Boolean {
        val d = File(dest)
        if (d.exists()) return false
        File(path).copyTo(d)
        return true
    }
}

/**
 * What the shell service may touch: files strictly inside
 * `/storage/emulated/0/Android/data/<emulator pkg>/` for the emulators in
 * [Emu]. Lexical and strict: no `.`/`..`/empty segments, no control chars,
 * no package dir itself. The service re-checks the canonical path (symlinks).
 */
object ShellPathPolicy {
    const val DATA_ROOT = "/storage/emulated/0/Android/data/"
    private const val MAX_PATH = 1024

    val PACKAGES: Set<String> = Emu.entries.map { it.pkg }.toSet()

    /** [path] when allowed, else null. Pure. */
    fun allowed(path: String): String? {
        if (path.length > MAX_PATH || !path.startsWith(DATA_ROOT)) return null
        if (path.any { it.isISOControl() || it == '\\' }) return null
        val segs = path.substring(DATA_ROOT.length).split('/')
        if (segs.size < 2) return null
        if (segs.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (segs[0] !in PACKAGES) return null
        return path
    }

    fun require(path: String): String =
        allowed(path) ?: throw IllegalArgumentException("path not allowed: $path")
}

/** Shizuku availability as seen by the writers (resolved lazily, once per pass). */
sealed interface Privileged {
    data class Ready(val files: EmuFiles) : Privileged
    /** Shizuku installed (or Sui) but its server isn't running. */
    data object NotRunning : Privileged
    /** Running, but GameBEX isn't allowed yet. */
    data object NotGranted : Privileged
    /** Server older than v11: no UserService. */
    data object Outdated : Privileged
    /** Granted, but the service didn't bind in time. */
    data object NoResponse : Privileged
    /** No Shizuku on this device. */
    data object Unavailable : Privileged
}
