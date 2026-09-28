package com.gamebex.client.sources

import com.gamebex.source.DefinitionValidator
import com.gamebex.source.RomSource
import com.gamebex.source.SchemaRomSource
import com.gamebex.source.SourceDefinition
import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import com.gamebex.source.SourceHttp
import com.gamebex.source.ValidatedDefinition
import com.gamebex.source.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicLong

data class SourceSelection(val id: String, val revision: String, val generation: Long)

data class InstallPreview(
    val definition: SourceDefinition,
    val revision: String,
    val replacesRevision: String?,
    val addedOrigins: Set<String>,
)

data class RevisionPin(val id: String, val revision: String)

/** Installed-source detail for the Sources UI. Null definition = unreadable row. */
data class SourceDetail(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val revision: String,
    val enabled: Boolean,
    val available: Boolean,
    val reason: String?,
    /** Declared origins grouped by purpose (catalog/download/artwork/assist). */
    val origins: Map<String, List<String>>,
    val capabilities: List<String>,
)

/** Thrown when a preview no longer matches current state; the user reviews again. */
class StaleSourcePreviewException : Exception("Source changed; review again.")

/** Download-engine side of source availability. */
interface SourceEngineHook {
    suspend fun sourceUnavailable(id: String)
    suspend fun activeRevisionPins(): Set<RevisionPin>
}

interface SourceAccess {
    val ready: StateFlow<Boolean>
    val selected: StateFlow<SourceSelection?>
    suspend fun open(id: String, revision: String? = null): RomSource
    fun installedRevision(id: String): String?
    /**
     * Checked transfer surface for an installed+enabled source. A null
     * revision binds the current revision; a stale/missing pin throws
     * UNAVAILABLE so the caller parks instead of fetching.
     */
    suspend fun httpFor(id: String, revision: String?): com.gamebex.source.SourceHttp

    /** Non-suspending availability probe for engine fast paths. */
    fun isAvailable(id: String, revision: String?): Boolean
}

/**
 * Owns validated runtime instances, the selected source, leases, and change
 * notifications. Construction performs no I/O; [initialize] runs on IO.
 * Manifest persistence always precedes engine callbacks, and no registry
 * lock is held while waiting on the engine.
 */
class SourceRegistry(
    private val store: SourceStore,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : SourceAccess {
    /** App wiring: private files dir; construction performs no I/O. */
    constructor(appContext: android.content.Context) : this(
        SourceStore(java.io.File(appContext.filesDir, "sources")),
    )
    private val mutex = Mutex()
    private val generation = AtomicLong(0)
    private var initialized = false

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready

    private val _selected = MutableStateFlow<SourceSelection?>(null)
    override val selected: StateFlow<SourceSelection?> = _selected

    private val _installed = MutableStateFlow<List<InstalledSource>>(emptyList())
    fun list(): StateFlow<List<InstalledSource>> = _installed

    @Volatile
    private var state = SourceStateData()
    private val httpCache = HashMap<Pair<String, String>, SourceHttp>()
    private val sourceCache = HashMap<Pair<String, String>, SchemaRomSource>()
    private val validatedCache = HashMap<Pair<String, String>, ValidatedDefinition>()

    @Volatile
    var engineHook: SourceEngineHook? = null

    suspend fun initialize() {
        withContext(ioDispatcher) {
            mutex.withLock {
                if (!initialized) {
                    val loaded = store.loadState()
                    val checked = loaded.installed.map { checkEntry(it) }
                    state = loaded.copy(installed = checked)
                    store.cleanStaging()
                    _installed.value = checked
                    publishSelectionLocked()
                    initialized = true
                    _ready.value = true
                }
            }
        }
    }

    override suspend fun open(id: String, revision: String?): RomSource {
        ready.first { it }
        return mutex.withLock { openLocked(id, revision) }
    }

    override fun isAvailable(id: String, revision: String?): Boolean {
        if (!ready.value) return false
        val entry = state.installed.firstOrNull { it.id == id } ?: return false
        if (!entry.enabled || !entry.available) return false
        if (revision != null && revision != entry.revision) {
            // Old pins stay valid while their bytes are retained.
            if (store.readDefinition(id, revision) == null) return false
        }
        return true
    }

    override fun installedRevision(id: String): String? =
        state.installed.firstOrNull { it.id == id }?.revision

    /** Policy-checked HTTP for transfers, artwork, and assistance. Same availability rules. */
    override suspend fun httpFor(id: String, revision: String?): SourceHttp {
        ready.first { it }
        return mutex.withLock {
            val key = resolveKeyLocked(id, revision)
            httpCache.getOrPut(key) { buildHttpLocked(key.first, key.second) }
        }
    }

    /**
     * Installed-source detail. Reads the stored definition from disk;
     * call off the main thread.
     */
    fun describe(id: String): SourceDetail? {
        val entry = state.installed.firstOrNull { it.id == id } ?: return null
        val def = runCatching {
            store.readDefinition(entry.id, entry.revision)
                ?.let { DefinitionValidator.parseAndValidate(it).def }
        }.getOrNull()
        return SourceDetail(
            id = entry.id, name = def?.name ?: entry.name,
            author = def?.author ?: "unknown",
            version = def?.version ?: "–",
            revision = entry.revision, enabled = entry.enabled,
            available = entry.available, reason = entry.reason,
            origins = if (def == null) emptyMap() else mapOf(
                "Catalog" to def.origins.catalog.sorted(),
                "Download" to def.origins.download.sorted(),
                "Artwork" to def.origins.artwork.sorted(),
                "Assist" to def.origins.assist.sorted(),
            ),
            capabilities = def?.let { (it.requires + it.operations.keys).sorted() } ?: emptyList(),
        )
    }

    fun preview(bytes: ByteArray): InstallPreview {
        val definition = DefinitionValidator.parseAndValidate(bytes).def
        val revision = sha256Hex(bytes)
        val current = state.installed.firstOrNull { it.id == definition.id }
        val oldOrigins = current?.let { unionOrigins(definitionOf(it)) } ?: emptySet()
        val added = unionOrigins(definition) - oldOrigins
        return InstallPreview(definition, revision, current?.revision, added)
    }

    suspend fun install(bytes: ByteArray, expectedPreviousRevision: String?) {
        val validated = DefinitionValidator.parseAndValidate(bytes)
        val revision = sha256Hex(bytes)
        val id = validated.id
        withContext(ioDispatcher) {
            mutex.withLock {
                val current = state.installed.firstOrNull { it.id == id }
                if (current?.revision != expectedPreviousRevision) {
                    throw StaleSourcePreviewException()
                }
                if (current?.revision == revision) return@withContext // Already installed.
                val staged = store.stage(bytes)
                try {
                    store.publish(staged, id, revision)
                } catch (e: Exception) {
                    runCatching { staged.delete() }
                    throw e
                }
                val enabled = current?.enabled ?: true
                val entry = InstalledSource(id, revision, enabled, validated.def.name, available = true)
                val installed = if (current == null) {
                    state.installed + entry
                } else {
                    state.installed.map { if (it.id == id) entry else it }
                }
                val selectedId = state.selectedId ?: installed.firstOrNull { it.enabled }?.id
                state = state.copy(selectedId = selectedId, installed = installed)
                store.saveState(state)
                evictLocked(id)
                _installed.value = installed
                publishSelectionLocked()
            }
        }
        // Best-effort retention cleanup outside the mutation lock.
        runCatching { collectGarbage() }
    }

    suspend fun select(id: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                val entry = state.installed.firstOrNull { it.id == id }
                    ?: throw SourceException(SourceFailure.UNAVAILABLE, id, null)
                if (!entry.enabled || !entry.available) {
                    throw SourceException(SourceFailure.UNAVAILABLE, id, null)
                }
                if (state.selectedId == id) return@withContext
                state = state.copy(selectedId = id)
                store.saveState(state)
                publishSelectionLocked()
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        withContext(ioDispatcher) {
            mutex.withLock {
                val entry = state.installed.firstOrNull { it.id == id }
                    ?: throw SourceException(SourceFailure.UNAVAILABLE, id, null)
                if (entry.enabled == enabled) return@withContext
                val installed = state.installed.map { if (it.id == id) it.copy(enabled = enabled) else it }
                state = state.copy(installed = installed)
                store.saveState(state)
                if (!enabled) evictLocked(id)
                _installed.value = installed
                publishSelectionLocked()
            }
        }
        if (!enabled) {
            // Manifest first, engine second, lock never held across the call.
            runCatching { engineHook?.sourceUnavailable(id) }
            runCatching { collectGarbage() }
        }
    }

    suspend fun remove(id: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                if (state.installed.none { it.id == id }) return@withContext
                val installed = state.installed.filterNot { it.id == id }
                val selectedId = if (state.selectedId == id) null else state.selectedId
                state = state.copy(selectedId = selectedId, installed = installed)
                store.saveState(state)
                evictLocked(id)
                _installed.value = installed
                publishSelectionLocked()
            }
        }
        runCatching { engineHook?.sourceUnavailable(id) }
        runCatching { collectGarbage() }
    }

    /** Deletes staged files and definition revisions no install or job references. */
    suspend fun collectGarbage() {
        val pins = runCatching { engineHook?.activeRevisionPins().orEmpty() }.getOrDefault(emptySet())
        withContext(ioDispatcher) {
            mutex.withLock {
                store.cleanStaging()
                val live = state.installed.map { RevisionPin(it.id, it.revision) }.toSet() + pins
                for (id in store.listIds()) {
                    for (rev in store.listRevisions(id)) {
                        if (RevisionPin(id, rev) !in live) store.deleteDefinition(id, rev)
                    }
                }
            }
        }
    }

    private fun checkEntry(entry: InstalledSource): InstalledSource {
        val bytes = store.readDefinition(entry.id, entry.revision)
            ?: return entry.copy(available = false, reason = "definition file is missing")
        if (sha256Hex(bytes) != entry.revision) {
            return entry.copy(available = false, reason = "definition file does not match its revision")
        }
        return try {
            val def = DefinitionValidator.parseAndValidate(bytes).def
            entry.copy(available = true, reason = null, name = def.name)
        } catch (e: SourceException) {
            val reason = if (e.failure == SourceFailure.INCOMPATIBLE) {
                "definition is incompatible with this app version"
            } else {
                "definition is invalid"
            }
            entry.copy(available = false, reason = reason)
        }
    }

    private fun publishSelectionLocked() {
        val gen = generation.incrementAndGet()
        val id = state.selectedId
        val entry = state.installed.firstOrNull { it.id == id }
        _selected.value = if (entry != null && entry.available) {
            SourceSelection(entry.id, entry.revision, gen)
        } else {
            null
        }
    }

    private fun resolveKeyLocked(id: String, revision: String?): Pair<String, String> {
        val entry = state.installed.firstOrNull { it.id == id }
        if (entry == null || !entry.enabled || !entry.available) {
            throw SourceException(SourceFailure.UNAVAILABLE, id, null)
        }
        val rev = revision ?: entry.revision
        if (store.readDefinition(id, rev) == null) {
            throw SourceException(SourceFailure.UNAVAILABLE, id, null)
        }
        return id to rev
    }

    private fun openLocked(id: String, revision: String?): SchemaRomSource {
        val key = resolveKeyLocked(id, revision)
        return sourceCache.getOrPut(key) {
            SchemaRomSource(validatedLocked(key.first, key.second), httpForCachedLocked(key), key.second)
        }
    }

    private fun httpForCachedLocked(key: Pair<String, String>): SourceHttp =
        httpCache.getOrPut(key) { buildHttpLocked(key.first, key.second) }

    private fun buildHttpLocked(id: String, revision: String): SourceHttp {
        val validated = validatedLocked(id, revision)
        return SourceHttp(validated, revision, isValid = { isLive(id) })
    }

    private fun validatedLocked(id: String, revision: String): ValidatedDefinition {
        val key = id to revision
        validatedCache[key]?.let { return it }
        val bytes = store.readDefinition(id, revision)
            ?: throw SourceException(SourceFailure.UNAVAILABLE, id, null)
        if (sha256Hex(bytes) != revision) throw SourceException(SourceFailure.UNAVAILABLE, id, null)
        try {
            return DefinitionValidator.parseAndValidate(bytes).also { validatedCache[key] = it }
        } catch (e: SourceException) {
            throw SourceException(SourceFailure.UNAVAILABLE, id, null, cause = e)
        }
    }

    /** Live means installed, enabled, and available; read without locking for the HTTP gate. */
    private fun isLive(id: String): Boolean {
        val entry = _installed.value.firstOrNull { it.id == id } ?: return false
        return entry.enabled && entry.available
    }

    private fun evictLocked(id: String) {
        httpCache.keys.removeAll { it.first == id }
        sourceCache.keys.removeAll { it.first == id }
        validatedCache.keys.removeAll { it.first == id }
    }

    private fun definitionOf(entry: InstalledSource): SourceDefinition? {
        val bytes = store.readDefinition(entry.id, entry.revision) ?: return null
        return runCatching { DefinitionValidator.parseAndValidate(bytes).def }.getOrNull()
    }

    private fun unionOrigins(def: SourceDefinition?): Set<String> {
        if (def == null) return emptySet()
        return (def.origins.catalog + def.origins.download + def.origins.artwork + def.origins.assist)
            .mapNotNull { it.toHttpUrlOrNull()?.let { u -> "https://" + u.host.lowercase() } }
            .toSet()
    }
}
