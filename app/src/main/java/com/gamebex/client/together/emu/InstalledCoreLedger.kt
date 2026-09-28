package com.gamebex.client.together.emu

import android.content.Context
import com.gamebex.client.launch.RetroCoreFetcher
import com.gamebex.client.together.CoreFile
import com.gamebex.client.together.CoreBuild
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Records the core build GameBEX handed to CoreSideloadActivity. It does not prove RetroArch installed it:
 * RetroArch's own core probe stays authoritative for readiness, and Online Updater changes are not tracked.
 */
class InstalledCoreLedger(private val file: File) {
    @Serializable
    data class Entry(val sha256: String, val size: Long, val path: String, val atMs: Long)

    @Serializable
    private data class Ledger(val cores: Map<String, Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    fun record(core: String, so: File) {
        synchronized(lockFor(file)) {
            val hash = RetroCoreFetcher.sha256Hex(so)
                ?: throw IOException("Couldn't hash installed core: ${so.absolutePath}")
            val all = load().toMutableMap()
            all[core] = Entry(hash, so.length(), so.absolutePath, System.currentTimeMillis())
            save(all)
        }
    }

    fun get(core: String): Entry? = synchronized(lockFor(file)) { load()[core] }

    fun verified(core: String): CoreFile? {
        val entry = get(core) ?: return null
        val candidate = File(entry.path)
        if (!candidate.isFile || candidate.length() != entry.size) return null
        if (RetroCoreFetcher.sha256Hex(candidate) != entry.sha256) return null
        return CoreFile(CoreBuild(core, entry.sha256, entry.size), candidate)
    }

    private fun load(): Map<String, Entry> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString(Ledger.serializer(), file.readText()).cores
        }.getOrDefault(emptyMap())
    }

    private fun save(cores: Map<String, Entry>) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Couldn't create ledger directory: $parent")
        }
        val tmp = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            tmp.writeText(json.encodeToString(Ledger.serializer(), Ledger(cores)))
            if (!tmp.renameTo(file)) {
                throw IOException("Couldn't publish core ledger: $file")
            }
        } finally {
            runCatching { tmp.delete() }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()

        private fun lockFor(file: File): Any = locks.getOrPut(file.absolutePath) { Any() }

        fun forApp(ctx: Context): InstalledCoreLedger =
            InstalledCoreLedger(File(ctx.filesDir, "together/installed_cores.json"))
    }
}
