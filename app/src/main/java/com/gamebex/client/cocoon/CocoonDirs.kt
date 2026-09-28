package com.gamebex.client.cocoon

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

/** GameBEX/<folder> at the SD-card root when mounted, else internal Downloads.
 *
 * App-specific SD dirs are private per package, so Cocoon could not read them.
 * ROMs go to the public /storage/XXXX-XXXX/GameBEX instead, which needs
 * MANAGE_EXTERNAL_STORAGE ("All files access") here and in Cocoon.
 */
object CocoonDirs {
    private val SD_ROOT = Regex("^/storage/[0-9A-F]{4}-[0-9A-F]{4}")

    private fun sdRoot(context: Context): File? {
        // Derived from getExternalFilesDirs because listing /storage returns null
        // even with all-files access on Android 16:
        // /storage/XXXX-XXXX/Android/data/<pkg>/files -> /storage/XXXX-XXXX
        val dirs = context.getExternalFilesDirs(null) ?: return null
        for (dir in dirs) {
            val match = SD_ROOT.find(dir?.absolutePath ?: continue) ?: continue
            return File(match.value, CocoonMap.ROM_ROOT)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun internalRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        CocoonMap.ROM_ROOT,
    )

    fun usingSd(context: Context): Boolean {
        val sd = sdRoot(context) ?: return false
        return sd.exists() || sd.mkdirs()
    }

    fun dirFor(context: Context, folder: String?): File {
        val root = sdRoot(context) ?: internalRoot()
        val dir = if (folder.isNullOrBlank()) root else File(root, folder)
        if (!dir.exists() && !dir.mkdirs()) {
            // SD present but not writable (all-files grant missing): internal fallback.
            val fb = if (folder.isNullOrBlank()) internalRoot() else File(internalRoot(), folder)
            fb.mkdirs()
            return fb
        }
        return dir
    }

    fun root(context: Context): File = dirFor(context, null)

    /** Creates the root and one folder per registry platform. */
    fun seedAll(context: Context) {
        root(context)
        com.gamebex.client.data.PlatformRegistry.folders.forEach { dirFor(context, it) }
    }

    fun scan(context: Context, file: File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    }
}

object EmulatorCheck {
    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
