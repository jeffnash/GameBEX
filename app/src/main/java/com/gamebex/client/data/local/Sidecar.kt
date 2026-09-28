package com.gamebex.client.data.local

import com.gamebex.source.DownloadFile
import com.gamebex.source.SourceRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Portable metadata next to each ROM (`<name>.gamebex.json`) so a fresh install can rebuild
 *  title, cover and source offline. Room stays authoritative; on conflict Room wins. */
@Serializable
data class Sidecar(
    val title: String,
    val platformSlug: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val source: String = "USER",
    val downloadedAtMs: Long = 0,
    /** ScreenScraper game id for art rebuilds, 0 = unknown. */
    val ssGameId: Long = 0,
    val schemaVersion: Int = 2,
    val sourceId: String? = null,
    val sourceRef: SourceRef? = null,
    val sourceVariant: DownloadFile? = null,
)

object Sidecars {
    private val json = Json { ignoreUnknownKeys = true }

    fun fileFor(romFile: File): File = File(romFile.parentFile, "${romFile.name}.gamebex.json")

    fun isSidecarName(name: String): Boolean = name.endsWith(".gamebex.json")

    fun read(romFile: File): Sidecar? {
        val file = fileFor(romFile)
        return if (file.isFile) readFile(file) else null
    }

    private fun readFile(f: File): Sidecar? = try {
        json.decodeFromString<Sidecar>(f.readText())
    } catch (_: Exception) {
        null
    }

    /** Atomic write through a sibling temp file; interrupted writes keep prior metadata. */
    fun write(romFile: File, sidecar: Sidecar) {
        try {
            val target = fileFor(romFile)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(Sidecar.serializer(), sidecar))
            if (!tmp.renameTo(target)) {
                runCatching { tmp.delete() }
                target.writeText(json.encodeToString(Sidecar.serializer(), sidecar))
            }
        } catch (_: Exception) {
            // Best effort: library works without sidecars.
        }
    }

    fun delete(romFile: File) {
        runCatching { fileFor(romFile).delete() }
    }
}
