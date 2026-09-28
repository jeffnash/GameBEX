package com.gamebex.client.together.emu

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RestoreRecord(val files: List<RestoreFile>)

@Serializable
data class RestoreFile(
    val path: String,
    val keys: List<RestoreKey>,
    val createdSections: List<String> = emptyList(),
)

@Serializable
data class RestoreKey(val section: String, val key: String, val value: String?)

/** Durable, per-session originals for emulator settings that GameBEX edits. */
class RestoreStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(name: String, rec: RestoreRecord) {
        synchronized(lockFor(file(name))) {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("Couldn't create restore directory: $dir")
            }
            val target = file(name)
            val tmp = File.createTempFile("${target.name}.", ".tmp", dir)
            try {
                tmp.writeText(json.encodeToString(RestoreRecord.serializer(), rec))
                if (!tmp.renameTo(target)) {
                    throw IOException("Couldn't publish restore record: $target")
                }
            } finally {
                runCatching { tmp.delete() }
            }
        }
    }

    fun load(name: String): RestoreRecord? {
        val target = file(name)
        if (!target.isFile) return null
        return try {
            json.decodeFromString(RestoreRecord.serializer(), target.readText())
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Corrupt restore record: ${target.absolutePath}", e) }
            runCatching { target.delete() }
            null
        }
    }

    fun clear(name: String) {
        runCatching { file(name).delete() }
    }

    private fun file(name: String): File = File(dir, "restore-$name.json")

    private companion object {
        const val TAG = "GameBEXTogether"
        val locks = ConcurrentHashMap<String, Any>()

        fun lockFor(file: File): Any = locks.getOrPut(file.absolutePath) { Any() }
    }
}
