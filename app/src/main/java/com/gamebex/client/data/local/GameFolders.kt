package com.gamebex.client.data.local

import android.content.Context
import com.gamebex.client.data.PlatformAliases
import java.io.File
import java.security.MessageDigest

/**
 * User-added library roots (ES-DE style): each root holds per-system
 * subfolders (`<root>/snes`, `<root>/megadrive`, ...) resolved through
 * [PlatformAliases]. Stored as canonical absolute paths in app prefs.
 *
 * User roots are READ-ONLY to GameBEX: scanning reads them, but sidecars,
 * prep output, and BIOS fetches never write there (see [RomStore.isUserVolume]).
 */
object GameFolders {
    private const val PREFS = "library_roots"
    private const val KEY = "roots"

    /** Volume-id prefix for user roots: `dir:<canonical path>`. */
    const val VOL_PREFIX = "dir:"

    fun volumeIdFor(path: String): String = VOL_PREFIX + path

    sealed interface AddResult {
        data class Added(val path: String) : AddResult
        data class Rejected(val reason: String) : AddResult
    }

    /** Stored roots in insertion order (unfiltered; see [effective]). */
    fun stored(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun save(ctx: Context, roots: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, roots.joinToString("\n")).apply()
    }

    /** Roots the scanner actually walks: overlap with built-ins or earlier roots drops out. */
    fun effective(ctx: Context, builtIns: List<File>): List<String> =
        dedupe(builtIns.map { canonical(it) ?: it.absolutePath }, stored(ctx))

    fun add(ctx: Context, dir: File, builtIns: List<File>): AddResult {
        val path = canonical(dir) ?: return AddResult.Rejected("Folder can't be read.")
        if (!dir.isDirectory) return AddResult.Rejected("Not a folder.")
        val cur = stored(ctx)
        val builtInPaths = builtIns.map { canonical(it) ?: it.absolutePath }
        rejectReason(path, builtInPaths, cur)?.let { return AddResult.Rejected(it) }
        save(ctx, cur + path)
        return AddResult.Added(path)
    }

    fun remove(ctx: Context, path: String) {
        save(ctx, stored(ctx).filter { it != path })
    }

    fun canonical(f: File): String? = runCatching { f.canonicalPath }.getOrNull()

    /** Lexical normalization: absolute, no `.`/`..`, no trailing slash. */
    fun normalize(path: String): String? {
        if (!path.startsWith("/") || path.contains('\n')) return null
        val parts = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** True when [child] equals [parent] or lies beneath it (path-segment aware). */
    fun isWithin(parent: String, child: String): Boolean {
        val p = normalize(parent) ?: return false
        val c = normalize(child) ?: return false
        if (p == c) return true
        val prefix = if (p == "/") "/" else "$p/"
        return c.startsWith(prefix)
    }

    /**
     * True when two roots would scan the same files: equal, or one lies
     * inside a per-system subtree the other walks (first path segment
     * below the outer root resolves to a platform). `/sd/ROMs` and
     * `/sd/ROMs/snes` overlap; `/sd` and `/sd/ROMs` don't (`ROMs` is not a
     * system folder, so `/sd` never walks into it).
     */
    fun overlaps(a: String, b: String): Boolean {
        val na = normalize(a) ?: return false
        val nb = normalize(b) ?: return false
        if (na == nb) return true
        val (outer, inner) = when {
            isWithin(na, nb) -> na to nb
            isWithin(nb, na) -> nb to na
            else -> return false
        }
        val rest = inner.removePrefix(if (outer == "/") "/" else "$outer/")
        val first = rest.substringBefore('/')
        return PlatformAliases.resolve(first) != null
    }

    /**
     * Why [candidate] can't be added, or null when it can. Built-in GameBEX
     * roots are app-owned: nothing at or below them may become a user root.
     */
    fun rejectReason(candidate: String, builtIns: List<String>, existing: List<String>): String? {
        val c = normalize(candidate) ?: return "Not an absolute folder path."
        if (builtIns.any { isWithin(it, c) }) return "Already part of the GameBEX library folder."
        if (builtIns.any { overlaps(it, c) }) return "Overlaps the GameBEX library folder."
        existing.firstOrNull { overlaps(it, c) }?.let { return "Overlaps $it." }
        return null
    }

    /**
     * Effective user roots: normalized, first occurrence wins, anything
     * inside/overlapping a built-in root or an earlier user root dropped.
     */
    fun dedupe(builtIns: List<String>, custom: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (raw in custom) {
            val c = normalize(raw) ?: continue
            if (rejectReason(c, builtIns, out) != null) continue
            out += c
        }
        return out
    }

    /**
     * App-owned prep output dir for a ROM on a user volume:
     * `<prepRoot>/<volume hash>/<rel dir segments>`. Unsafe segments are
     * dropped, so the result always stays inside [prepRoot].
     */
    fun prepDirFor(prepRoot: File, volumeId: String, relPath: String): File {
        val segs = relPath.split('/', '\\').dropLast(1)
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        var dir = File(prepRoot, volumeKey(volumeId))
        segs.forEach { dir = File(dir, it) }
        return dir
    }

    /** Short stable key for a volume id (no path characters). */
    fun volumeKey(volumeId: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(volumeId.toByteArray())
        return md.take(8).joinToString("") { "%02x".format(it) }
    }
}
