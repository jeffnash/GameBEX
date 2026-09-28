package com.gamebex.client.data

import java.io.File
import java.security.MessageDigest

/** BIOS checker over plain java.io so it runs in JVM unit tests. Callers supply the candidate dirs. */
object BiosCheck {
    enum class FileState { OK, MD5_MISMATCH, SIZE_MISMATCH, PRESENT_UNVERIFIED, UNREADABLE, MISSING }

    data class FileResult(val want: BiosTable.BiosFile, val state: FileState, val found: File?)

    data class NeedResult(val need: BiosTable.BiosNeed, val files: List<FileResult>) {
        /** Every file present, or at least one for anyOf needs; unverified copies count. */
        val satisfied: Boolean =
            if (need.anyOf) files.any { it.state == FileState.OK || it.state == FileState.PRESENT_UNVERIFIED }
            else files.all { it.state == FileState.OK || it.state == FileState.PRESENT_UNVERIFIED }
    }

    data class FolderReport(
        val folder: String,
        val needs: List<NeedResult>,
        /** Required files present only where emulators can't read them. */
        val privateOnly: List<BiosTable.BiosFile> = emptyList(),
    ) {
        val ready: Boolean = needs.filter { it.need.required }.all { it.satisfied }
        val missingRequired: List<BiosTable.BiosFile> =
            needs.filter { it.need.required && !it.satisfied }.flatMap { it.files.map(FileResult::want) }
    }

    fun checkFolder(folder: String, dirs: List<File>, privateDirs: List<File> = emptyList()): FolderReport {
        val needs = BiosTable.TABLE[folder].orEmpty()
        val results = needs.map { checkNeed(it, dirs) }
        val priv = if (privateDirs.isEmpty()) emptyList() else {
            val missingNames = results.filter { it.need.required && !it.satisfied }
                .flatMap { it.files }.map { it.want.name }.toSet()
            BiosTable.TABLE[folder].orEmpty().flatMap { it.files }
                .filter { it.name in missingNames && presentAnywhere(it, privateDirs) }
                .distinctBy { it.name }
        }
        return FolderReport(folder, results, priv)
    }

    private fun presentAnywhere(want: BiosTable.BiosFile, dirs: List<File>): Boolean =
        candidates(want, dirs).any()

    fun checkNeed(need: BiosTable.BiosNeed, dirs: List<File>): NeedResult =
        NeedResult(need, need.files.map { checkFile(it, dirs) })

    /** Recursive scan cap: placements nest at most ~3 deep (system/pcsx2/bios). */
    private const val DEEP_MAX_DEPTH = 4

    /**
     * Same-named candidates: exact, then case-insensitive, then punctuation-insensitive
     * (SCPH-39001.bin satisfies scph39001.bin); dir order is priority. A bounded recursive
     * scan covers subdir placements (system/pcsx2/bios) so placement and readiness agree.
     * A corrupt or unreadable copy never shadows a valid one elsewhere.
     */
    private fun candidates(want: BiosTable.BiosFile, dirs: List<File>): List<File> {
        val exact = dirs.map { File(it, want.name) }.filter { it.isFile }
        val listed = dirs.asSequence()
            .flatMap { (it.listFiles() ?: emptyArray()).asSequence() }
            .filter { it.isFile }
            .filter { f -> exact.none { it.absolutePath == f.absolutePath } }
            .toList()
        val ci = listed.filter { f -> f.name.equals(want.name, ignoreCase = true) }
        val norm = normName(want.name)
        val rest = listed.filter { f ->
            ci.none { it.absolutePath == f.absolutePath } && normName(f.name) == norm
        }
        val seen = (exact + ci + rest).mapTo(mutableSetOf()) { it.absolutePath }
        return exact + ci + rest + deepCandidates(want, dirs, seen)
    }

    private fun deepCandidates(
        want: BiosTable.BiosFile,
        roots: List<File>,
        seen: MutableSet<String>,
    ): List<File> {
        val targetNorm = normName(want.name)
        fun tier(name: String): Int = when {
            name == want.name -> 0
            name.equals(want.name, ignoreCase = true) -> 1
            normName(name) == targetNorm -> 2
            else -> -1
        }
        val out = mutableListOf<File>()
        val visitedDirs = mutableSetOf<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > DEEP_MAX_DEPTH) return
            val canon = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!visitedDirs.add(canon)) return
            val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (k in kids) {
                if (k.isFile) {
                    if (tier(k.name) >= 0 && seen.add(k.absolutePath)) out += k
                } else if (k.isDirectory) {
                    walk(k, depth + 1)
                }
            }
        }
        roots.forEach { walk(it, 1) }
        return out.sortedWith(compareBy({ tier(it.name) }, { it.absolutePath }))
    }

    /** Lowercase alphanumerics: punctuation-insensitive filename key. */
    fun normName(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    fun checkFile(want: BiosTable.BiosFile, dirs: List<File>): FileResult {
        val cands = candidates(want, dirs)
        if (cands.isEmpty()) return FileResult(want, FileState.MISSING, null)
        if (want.md5 == null) {
            // Unpinned: size is the only axis; prefer a size-plausible copy.
            cands.firstOrNull { want.sizeBytes == null || it.length() == want.sizeBytes }?.let {
                return FileResult(want, FileState.PRESENT_UNVERIFIED, it)
            }
            return FileResult(want, FileState.SIZE_MISMATCH, cands.first())
        }
        var sizeBad: File? = null
        var unreadable: File? = null
        var mismatch: File? = null
        for (found in cands) {
            if (want.sizeBytes != null && found.length() != want.sizeBytes) {
                if (sizeBad == null) sizeBad = found
                continue
            }
            val actual = runCatching { md5(found) }.getOrNull()
            if (actual == null) {
                if (unreadable == null) unreadable = found
                continue
            }
            if (actual.equals(want.md5, ignoreCase = true)) {
                return FileResult(want, FileState.OK, found)
            }
            if (mismatch == null) mismatch = found
        }
        // No valid copy: report the most actionable failure. Hashing that
        // throws is UNREADABLE — never PRESENT_UNVERIFIED (which satisfies).
        mismatch?.let { return FileResult(want, FileState.MD5_MISMATCH, it) }
        sizeBad?.let { return FileResult(want, FileState.SIZE_MISMATCH, it) }
        unreadable?.let { return FileResult(want, FileState.UNREADABLE, it) }
        return FileResult(want, FileState.MISSING, null)
    }

    fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
