package com.gamebex.client.sources

import androidx.core.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** One installed source entry. Availability is recomputed on every load. */
@Serializable
data class InstalledSource(
    val id: String,
    val revision: String,
    val enabled: Boolean = true,
    val name: String = "",
    val available: Boolean = true,
    val reason: String? = null,
)

@Serializable
data class SourceStateData(
    val selectedId: String? = null,
    val installed: List<InstalledSource> = emptyList(),
)

/**
 * App-private source files. Takes a root directory (not a Context) so the
 * storage policy is unit-testable on the JVM:
 *
 * root/
 *   state.json
 *   definitions/<sourceId>/<sha256>.json
 *   staging/<random>.json
 */
class SourceStore(root: File) {
    private val root = root
    private val stateFile = AtomicFile(File(root, "state.json"))
    private val definitionsDir = File(root, "definitions")
    private val stagingDir = File(root, "staging")

    /** App-private format: liberal decoding, strict definition parsing stays in the runtime. */
    private val json = Json { ignoreUnknownKeys = true }

    fun loadState(): SourceStateData {
        return try {
            stateFile.openRead().use { stream ->
                val bytes = stream.readBytes()
                if (bytes.isEmpty()) SourceStateData() else json.decodeFromString(SourceStateData.serializer(), bytes.toString(Charsets.UTF_8))
            }
        } catch (_: FileNotFoundException) {
            SourceStateData()
        }
    }

    fun saveState(state: SourceStateData) {
        root.mkdirs()
        val bytes = json.encodeToString(SourceStateData.serializer(), state).toByteArray(Charsets.UTF_8)
        val out = stateFile.startWrite()
        try {
            out.write(bytes)
            stateFile.finishWrite(out)
        } catch (e: java.io.IOException) {
            stateFile.failWrite(out)
            throw e
        }
    }

    /** Writes validated bytes to a fresh staging file. Callers publish or discard it. */
    fun stage(bytes: ByteArray): File {
        stagingDir.mkdirs()
        val staged = File(stagingDir, UUID.randomUUID().toString() + ".json")
        staged.writeBytes(bytes)
        return staged
    }

    /**
     * Atomically publishes a staged file as the immutable definition for
     * (id, revision). Same filesystem, so rename is atomic.
     */
    fun publish(staged: File, id: String, revision: String): File {
        require(idRegex.matches(id)) { "unsafe source id" }
        require(revisionRegex.matches(revision)) { "unsafe revision" }
        val dir = File(definitionsDir, id)
        dir.mkdirs()
        val target = File(dir, "$revision.json")
        if (!staged.renameTo(target)) {
            // Rename across a restart boundary may collide with the already
            // published identical file; only identical bytes may coincide.
            if (!target.isFile || !target.readBytes().contentEquals(staged.readBytes())) {
                throw java.io.IOException("cannot publish staged definition")
            }
            staged.delete()
        }
        return target
    }

    fun readDefinition(id: String, revision: String): ByteArray? {
        if (!idRegex.matches(id) || !revisionRegex.matches(revision)) return null
        val file = File(File(definitionsDir, id), "$revision.json")
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    fun definitionFile(id: String, revision: String): File? {
        if (!idRegex.matches(id) || !revisionRegex.matches(revision)) return null
        return File(File(definitionsDir, id), "$revision.json").takeIf { it.isFile }
    }

    fun listIds(): Set<String> {
        if (!definitionsDir.isDirectory) return emptySet()
        return definitionsDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && idRegex.matches(it.name) }
            .map { it.name }
            .toSet()
    }

    fun listRevisions(id: String): Set<String> {
        if (!idRegex.matches(id)) return emptySet()
        val dir = File(definitionsDir, id)
        if (!dir.isDirectory) return emptySet()
        return dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .map { it.name.removeSuffix(".json") }
            .filter { revisionRegex.matches(it) }
            .toSet()
    }

    fun deleteDefinition(id: String, revision: String): Boolean {
        if (!idRegex.matches(id) || !revisionRegex.matches(revision)) return false
        val deleted = File(File(definitionsDir, id), "$revision.json").delete()
        File(definitionsDir, id).takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
        return deleted
    }

    /** Deletes unreferenced staging files; returns the count removed. */
    fun cleanStaging(): Int {
        if (!stagingDir.isDirectory) return 0
        var removed = 0
        for (file in stagingDir.listFiles().orEmpty()) {
            if (file.isFile && file.delete()) removed++
        }
        return removed
    }

    companion object {
        private val idRegex = Regex("[a-z][a-z0-9._-]*")
        private val revisionRegex = Regex("[0-9a-f]{64}")
    }
}
