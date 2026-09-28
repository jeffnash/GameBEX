package com.gamebex.client.launch

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Trust-on-first-use pins for one core: zip hash + extracted .so hash. */
@Serializable
data class CorePin(val zip: String, val so: String)

@Serializable
private data class CorePinFile(val pins: Map<String, CorePin> = emptyMap())

/**
 * TOFU pin store for RetroArch cores. The Libretro buildbot publishes
 * neither hashes nor immutable builds (nightly `latest/` only), so the
 * first validated fetch of a core pins its SHA-256 hashes and every later
 * fetch/sideload must match. A compromised first fetch goes undetected.
 *
 * File-backed (not prefs) so JVM tests can use a temp dir.
 */
class CorePinStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(core: String): CorePin? = load()[core]

    fun put(core: String, pin: CorePin) {
        // Concurrent core fetches hold separate coreLocks but share this file;
        // without a per-path lock the second put drops the first pin.
        synchronized(lockFor(file)) {
            val all = load().toMutableMap()
            all[core] = pin
            save(all)
        }
    }

    fun load(): Map<String, CorePin> {
        if (!file.isFile) return emptyMap()
        return try {
            json.decodeFromString(CorePinFile.serializer(), file.readText()).pins
        } catch (_: Exception) {
            emptyMap() // corrupt store pins nothing; next fetch re-pins
        }
    }

    private fun save(all: Map<String, CorePin>) {
        // Unique staging name per write: a fixed ".tmp" name lets two
        // writers interleave content and break publication.
        val tmp = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            tmp.writeText(json.encodeToString(CorePinFile.serializer(), CorePinFile(all)))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
            }
        } finally {
            runCatching { tmp.delete() }
        }
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        private fun lockFor(file: File): Any =
            locks.getOrPut(file.absolutePath) { Any() }

        fun inDir(dir: File): CorePinStore {
            dir.mkdirs()
            return CorePinStore(File(dir, "core_pins.json"))
        }
    }
}
