package com.gamebex.client.install

import android.os.Environment
import java.io.File

/**
 * A copy of a pinned emulator APK in `GameBEX/apks/`, used before downloading. RetroArch's is
 * kept there because the buildbot drops dated nightlies after about two weeks. Only the pinned
 * build counts.
 */
object LocalApks {
    fun dir(): File = File(Environment.getExternalStorageDirectory(), "GameBEX/apks")

    /** Last path segment of [url]; null when blank or unsafe as a file name. */
    fun fileName(url: String): String? {
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        return name.takeIf { it.isNotBlank() && !it.contains("..") && !it.contains('%') && !it.contains('\\') }
    }

    /** The copy of [url] in [dir] when it is the pinned build ([pinnedSha]); null otherwise. */
    fun find(dir: File, url: String, pinnedSha: String, sha256: (File) -> String): File? {
        val file = File(dir, fileName(url) ?: return null)
        if (!file.isFile) return null
        return file.takeIf { sha256(it).equals(pinnedSha, ignoreCase = true) }
    }
}
