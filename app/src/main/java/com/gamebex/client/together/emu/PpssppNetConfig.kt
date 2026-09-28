package com.gamebex.client.together.emu

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.gamebex.client.launch.DirectFiles
import com.gamebex.client.launch.EmuFiles
import com.gamebex.client.launch.IniText
import com.gamebex.client.launch.Privileged
import com.gamebex.client.launch.ShizukuFiles
import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherCopy
import java.io.File
import java.io.IOException

/** Reads and edits PPSSPP's v1.19.3 network configuration, with restore records. */
class PpssppNetConfig(
    private val store: RestoreStore,
    private val fsFor: (path: String) -> EmuFiles?,
    private val stopper: () -> ((String) -> Boolean)?,
    private val sdRoots: () -> List<String>,
    private val internalRoot: String = "/storage/emulated/0",
) {
    data class IniKey(val section: String, val key: String, val value: String)

    sealed interface ApplyResult {
        data object Applied : ApplyResult
        data class Failed(val text: String) : ApplyResult
    }

    fun candidates(
        internalRoot: String,
        sdRoots: List<String>,
        childDirs: (String) -> List<String>,
    ): List<String> {
        val roots = listOf(internalRoot) + sdRoots
        val raw = mutableListOf<String>()
        raw += "$internalRoot/Android/data/org.ppsspp.ppsspp/files/PSP/SYSTEM/ppsspp.ini"
        raw += "$internalRoot/PSP/SYSTEM/ppsspp.ini"
        for (root in sdRoots) raw += "$root/PSP/SYSTEM/ppsspp.ini"
        for (root in roots) {
            childDirs(root).filter { it != "Android" && !it.startsWith(".") }.sorted().forEach { child ->
                raw += "$root/$child/PSP/SYSTEM/ppsspp.ini"
                if (child.equals("PSP", ignoreCase = true)) raw += "$root/$child/SYSTEM/ppsspp.ini"
            }
        }
        return raw.distinct()
    }

    fun wanted(role: Role, hostAddress: String, playerName: String): List<IniKey> = listOf(
        IniKey("Network", "EnableWlan", "True"),
        IniKey("Network", "EnableAdhocServer", if (role == Role.HOST) "True" else "False"),
        IniKey("Network", "proAdhocServer", hostAddress),
        IniKey("Network", "PortOffset", "10000"),
        IniKey("Network", "EnableUPnP", "False"),
        IniKey("SystemParam", "NickName", playerName),
    )

    fun find(): String? {
        val candidates = candidates(internalRoot, sdRoots()) { root ->
            fsFor(root)?.list(root).orEmpty()
        }
        val existing = candidates.filter { path -> runCatching { fsFor(path)?.isFile(path) == true }.getOrDefault(false) }
        return existing.minWithOrNull(compareByDescending<String> { path ->
            runCatching { fsFor(path)?.lastModified(path) ?: 0L }.getOrDefault(0L)
        }.thenBy { existing.indexOf(it) })
    }

    suspend fun apply(role: Role, hostAddress: String, playerName: String): ApplyResult {
        if (!restorePending()) return ApplyResult.Failed(TogetherCopy.ppssppRestoreFailed)
        val stop = stopper() ?: return ApplyResult.Failed(TogetherCopy.shizukuRequired)
        stop("org.ppsspp.ppsspp")
        kotlinx.coroutines.delay(500L)
        val ini = find() ?: return ApplyResult.Failed(TogetherCopy.openOnce("PPSSPP"))
        val backend = fsFor(ini) ?: return ApplyResult.Failed(TogetherCopy.ppssppWriteFailed)
        val wanted = wanted(role, hostAddress, playerName)
        val dir = File(ini).parent ?: return ApplyResult.Failed(TogetherCopy.ppssppWriteFailed)
        val files = (listOf(ini) + listPerGame(backend, dir)).distinct()
        val records = mutableListOf<RestoreFile>()
        return try {
            for (path in files) {
                val text = fsFor(path)!!.readText(path) ?: continue
                val keys = wanted.map { key -> RestoreKey(key.section, key.key, IniText.get(text, key.section, key.key)) }
                val sections = wanted.map { it.section }.distinct().filter { section ->
                    text.lineSequence().none { it.trim() == "[$section]" }
                }
                records += RestoreFile(path, keys, sections)
            }
            store.save("ppsspp", RestoreRecord(records))
            for (record in records) {
                val path = record.path
                val text = fsFor(path)!!.readText(path) ?: continue
                val edited = wanted.fold(text) { value, key -> IniText.set(value, key.section, key.key, key.value) }
                if (!fsFor(path)!!.writeTextAtomic(path, edited)) throw IOException("write failed")
            }
            ApplyResult.Applied
        } catch (_: Exception) {
            restorePending()
            ApplyResult.Failed(TogetherCopy.ppssppWriteFailed)
        }
    }

    suspend fun restorePending(): Boolean {
        val record = store.load("ppsspp") ?: return true
        val stop = stopper() ?: return false
        stop("org.ppsspp.ppsspp")
        kotlinx.coroutines.delay(500L)
        return try {
            for (file in record.files) {
                val fs = fsFor(file.path) ?: return false
                val text = fs.readText(file.path) ?: continue
                var edited = text
                for (key in file.keys) {
                    edited = if (key.value == null) IniText.remove(edited, key.section, key.key)
                    else IniText.set(edited, key.section, key.key, key.value)
                }
                for (section in file.createdSections) edited = IniText.removeEmptySection(edited, section)
                if (!fs.writeTextAtomic(file.path, edited)) return false
            }
            store.clear("ppsspp")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun listPerGame(fs: EmuFiles, dir: String): List<String> =
        fs.list(dir).orEmpty().filter { GAME_INI.matches(it) }.map { "$dir/$it" }

    companion object {
        private val GAME_INI = Regex("^[A-Z]{4}\\d{5}_ppsspp\\.ini$")

        fun forApp(ctx: Context): PpssppNetConfig {
            val app = ctx.applicationContext
            val privileged by lazy { runCatching { ShizukuFiles.privileged(app) }.getOrNull() }
            val files: (String) -> EmuFiles? = { path ->
                if (path.startsWith("/storage/emulated/0/Android/data/")) {
                    (privileged as? Privileged.Ready)?.files
                } else DirectFiles
            }
            val roots = {
                if (Build.VERSION.SDK_INT >= 30) {
                    app.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
                        .filter { it.isRemovable }.mapNotNull { it.directory?.path }
                } else emptyList()
            }
            return PpssppNetConfig(
                RestoreStore(File(app.filesDir, "together")),
                files,
                { ShizukuFiles.forceStopper(app) },
                roots,
            )
        }
    }
}
